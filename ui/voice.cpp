// Push-to-talk voice chat, all on the phone:
//   hold volume-down  -> the mic records (tinyalsa, PCM device 0 = MultiMedia1)
//   release           -> whisper-cli turns it into text (Whisper tiny.en)
//                     -> the next question in the conversation with llama serve (streamed)
//                     -> each finished sentence is spoken by espeak-ng and
//                        played on the speaker while the rest is generated,
//                        when the sound is on (volume-up; off after boot)
// The conversation goes on from question to question until its next exchange
// might not fit in the server's context window (the model's own context length,
// at most 32K: see llm-serve); then a new one starts. A new model starts one too.
// Every exchange is appended to /tmp/www/conversations.jsonl, which busybox
// httpd serves on :8081 for the PC app.
//
// The voice pack (/tmp/voice: whisper-cli, espeak-ng + data, the Whisper
// model, tinymix) comes from the PC over USB; etc/llm-audio.sh then boots the
// ADSP and sets the mixer defaults. Rules for Qualcomm's DSP PCM driver
// (msm-pcm-q6-v2), learned the hard way:
//   - read/write one period per call, and never let ALSA stop the stream on an
//     over/underrun (stop_threshold = INT_MAX): it does not recover from that
//   - stop a capture before switching its route off, or it wedges
//   - play only 48 kHz, and never record and play at the same time
// Routes are the shell scripts in /etc/audio, generated from Android's
// mixer_paths.xml by tools/mixer-path.py.
#include "voice.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <climits>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include <tinyalsa/asoundlib.h>

static const char* kQuestionWav = "/tmp/voice/question.wav";
static const char* kLog = "/tmp/www/conversations.jsonl";
static const char* kSystem =
    "You are a voice assistant running on an old phone. Answer in one to three short "
    "sentences of plain spoken English. No lists, no markdown, no emoji.";
static const unsigned kRecRate = 48000, kPlayRate = 48000;
static const unsigned kRecPeriod = 1024, kRecPeriods = 4;
static const unsigned kPlayPeriod = 1920, kPlayPeriods = 4;
static const int kMaxTokens = 200;  // per answer

// The conversation so far. Only the turn thread touches it.
struct Exchange {
  std::string question, answer;
};
static std::vector<Exchange> history;
static std::string conv_id, conv_model;  // conv_model: /tmp/model.name when it started
static int conv_ctx = 0;                 // the server's context window, tokens
static int conv_tokens = 0;              // what the conversation fills of it

static std::mutex mu;
static VoiceView view;  // guarded by mu
static std::atomic<bool> recording{false}, cancel{false}, busy{false};
static std::atomic<bool> sound{false};  // speak the answers; muting stops the one being spoken
static std::thread rec_thread;

static void set_phase(const std::string& p) {
  std::lock_guard<std::mutex> l(mu);
  view.phase = p;
}

static bool exists(const char* p) {
  struct stat st;
  return stat(p, &st) == 0;
}

static long long now_ms() {
  timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}

// Runs a program (no shell) and returns its stdout.
static std::string run(const std::vector<std::string>& argv, int* status = nullptr) {
  int fd[2];
  if (pipe(fd) != 0) return "";
  pid_t pid = fork();
  if (pid == 0) {
    dup2(fd[1], 1);
    int null = open("/dev/null", O_WRONLY);
    dup2(null, 2);
    close(fd[0]);
    std::vector<char*> a;
    for (const auto& s : argv) a.push_back(const_cast<char*>(s.c_str()));
    a.push_back(nullptr);
    execv(a[0], a.data());
    _exit(127);
  }
  close(fd[1]);
  std::string out;
  char buf[65536];
  ssize_t n;
  while ((n = read(fd[0], buf, sizeof(buf))) > 0) out.append(buf, n);
  close(fd[0]);
  int st = 0;
  waitpid(pid, &st, 0);
  if (status) *status = st;
  return out;
}

static void route(const char* script) {
  std::string cmd = std::string("PATH=/tmp/voice/bin:$PATH sh /etc/audio/") + script + " >/dev/null 2>&1";
  (void)!system(cmd.c_str());
}

// ---------------------------------------------------------------- text

static void put_utf8(std::string& o, unsigned cp) {
  if (cp < 0x80) {
    o += char(cp);
  } else if (cp < 0x800) {
    o += char(0xC0 | (cp >> 6));
    o += char(0x80 | (cp & 0x3F));
  } else if (cp < 0x10000) {
    o += char(0xE0 | (cp >> 12));
    o += char(0x80 | ((cp >> 6) & 0x3F));
    o += char(0x80 | (cp & 0x3F));
  } else {
    o += char(0xF0 | (cp >> 18));
    o += char(0x80 | ((cp >> 12) & 0x3F));
    o += char(0x80 | ((cp >> 6) & 0x3F));
    o += char(0x80 | (cp & 0x3F));
  }
}

static std::string json_escape(const std::string& s) {
  std::string o;
  for (unsigned char c : s) {
    if (c == '"') o += "\\\"";
    else if (c == '\\') o += "\\\\";
    else if (c == '\n') o += "\\n";
    else if (c < 0x20) { char b[8]; snprintf(b, sizeof(b), "\\u%04x", c); o += b; }
    else o += char(c);
  }
  return o;
}

// The JSON string value after "key": in line (first match), unescaped to UTF-8.
static bool json_string(const std::string& line, const char* key, std::string* out) {
  std::string k = std::string("\"") + key + "\":\"";
  size_t i = line.find(k);
  if (i == std::string::npos) return false;
  out->clear();
  for (i += k.size(); i < line.size() && line[i] != '"'; ++i) {
    if (line[i] != '\\' || i + 1 >= line.size()) { *out += line[i]; continue; }
    char e = line[++i];
    if (e == 'n') *out += '\n';
    else if (e == 't') *out += ' ';
    else if (e == 'r') {}
    else if (e == 'u' && i + 4 < line.size()) {
      unsigned cp = strtoul(line.substr(i + 1, 4).c_str(), nullptr, 16);
      i += 4;
      if (cp >= 0xD800 && cp < 0xDC00 && i + 6 < line.size() && line[i + 1] == '\\' && line[i + 2] == 'u') {
        unsigned lo = strtoul(line.substr(i + 3, 4).c_str(), nullptr, 16);
        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
        i += 6;
      }
      put_utf8(*out, cp);
    } else *out += e;  // \" \\ \/
  }
  return true;
}

// The number after "key": in s, or -1.
static long json_number(const std::string& s, const char* key) {
  std::string k = std::string("\"") + key + "\":";
  size_t i = s.find(k);
  return i == std::string::npos ? -1 : strtol(s.c_str() + i + k.size(), nullptr, 10);
}

static std::string trim(std::string s) {
  size_t a = s.find_first_not_of(" \t\r\n");
  if (a == std::string::npos) return "";
  size_t b = s.find_last_not_of(" \t\r\n");
  return s.substr(a, b - a + 1);
}

// What espeak should say: no markdown marks, no emoji.
static std::string speakable(const std::string& s) {
  std::string o;
  for (size_t i = 0; i < s.size(); ++i) {
    unsigned char c = s[i];
    if (c == '*' || c == '#' || c == '`' || c == '_' || c == '|' || c == '>') continue;
    if (c >= 0xF0) { i += 3; continue; }  // 4-byte UTF-8: emoji
    o += char(c);
  }
  return trim(o);
}

// ---------------------------------------------------------------- WAV

static void wav_header(int fd, unsigned rate, unsigned channels, unsigned data_bytes) {
  unsigned char h[44];
  auto u32 = [&](int at, unsigned v) { for (int i = 0; i < 4; ++i) h[at + i] = (v >> (8 * i)) & 0xFF; };
  auto u16 = [&](int at, unsigned v) { h[at] = v & 0xFF; h[at + 1] = (v >> 8) & 0xFF; };
  memcpy(h, "RIFF", 4); u32(4, 36 + data_bytes); memcpy(h + 8, "WAVEfmt ", 8);
  u32(16, 16); u16(20, 1); u16(22, channels); u32(24, rate); u32(28, rate * channels * 2);
  u16(32, channels * 2); u16(34, 16); memcpy(h + 36, "data", 4); u32(40, data_bytes);
  lseek(fd, 0, SEEK_SET);
  (void)!write(fd, h, 44);
}

// espeak-ng's WAV (mono s16, usually 22050 Hz) -> 48 kHz stereo s16 frames.
static std::vector<int16_t> to_48k_stereo(const std::string& wav) {
  std::vector<int16_t> out;
  if (wav.size() < 44 || wav.compare(0, 4, "RIFF") != 0) return out;
  unsigned rate = (unsigned char)wav[24] | (unsigned char)wav[25] << 8 | (unsigned char)wav[26] << 16;
  size_t data = wav.find("data", 12);
  if (data == std::string::npos || !rate) return out;
  const int16_t* in = reinterpret_cast<const int16_t*>(wav.data() + data + 8);
  size_t n = (wav.size() - data - 8) / 2;
  size_t m = n * kPlayRate / rate;
  out.resize(m * 2);
  for (size_t j = 0; j < m; ++j) {  // linear interpolation
    double pos = double(j) * rate / kPlayRate;
    size_t i = size_t(pos);
    double f = pos - i;
    double a = in[std::min(i, n - 1)], b = in[std::min(i + 1, n - 1)];
    int16_t v = int16_t(a + (b - a) * f);
    out[2 * j] = out[2 * j + 1] = v;
  }
  return out;
}

// ---------------------------------------------------------------- recording

static void record() {
  route("route-mic.sh");
  pcm_config cfg = {};
  cfg.channels = 1;
  cfg.rate = kRecRate;
  cfg.period_size = kRecPeriod;
  cfg.period_count = kRecPeriods;
  cfg.format = PCM_FORMAT_S16_LE;
  cfg.stop_threshold = INT_MAX;
  pcm* p = pcm_open(0, 0, PCM_IN, &cfg);
  int fd = open(kQuestionWav, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  unsigned bytes = 0;
  if (p && pcm_is_ready(p) && fd >= 0) {
    wav_header(fd, kRecRate, 1, 0);
    std::vector<int16_t> buf(kRecPeriod);
    while (recording && bytes < kRecRate * 2 * 30) {  // at most 30 s
      int n = pcm_readi(p, buf.data(), kRecPeriod);
      if (n <= 0) break;
      (void)!write(fd, buf.data(), n * 2);
      bytes += n * 2;
    }
    wav_header(fd, kRecRate, 1, bytes);
  }
  if (fd >= 0) close(fd);
  if (p) pcm_close(p);  // the capture stops before its route goes off
  route("route-mic-off.sh");
}

// ---------------------------------------------------------------- speaking

// sentences -> [tts thread: espeak-ng] -> audio -> [player thread: the DSP]
// The player never waits for espeak: it plays silence until audio is ready.
static std::mutex play_mu;
static std::condition_variable play_cv;
static std::deque<std::string> sentences;          // waiting for espeak
static std::deque<std::vector<int16_t>> audio_q;   // 48 kHz stereo, waiting to play
static bool text_done = false;                     // the model has finished
static bool tts_done = false;                      // ...and espeak too

static void tts_thread() {
  while (true) {
    std::string s;
    {
      std::unique_lock<std::mutex> l(play_mu);
      play_cv.wait(l, [] { return !sentences.empty() || text_done || cancel || !sound; });
      if (cancel || !sound || (sentences.empty() && text_done)) break;
      s = sentences.front();
      sentences.pop_front();
    }
    auto a = to_48k_stereo(run({"/tmp/voice/bin/espeak-ng", "--path=/tmp/voice", "-v", "en-us", "-s", "165",
                                "--stdout", s}));
    std::lock_guard<std::mutex> l(play_mu);
    if (!a.empty()) audio_q.push_back(std::move(a));
  }
  std::lock_guard<std::mutex> l(play_mu);
  tts_done = true;
}

static void player_thread() {
  sched_param sp = {};
  sp.sched_priority = 10;  // the model keeps all six cores busy meanwhile
  pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp);
  route("route-speaker.sh");
  pcm_config cfg = {};
  cfg.channels = 2;
  cfg.rate = kPlayRate;
  cfg.period_size = kPlayPeriod;
  cfg.period_count = kPlayPeriods;
  cfg.format = PCM_FORMAT_S16_LE;
  cfg.start_threshold = kPlayPeriod;
  cfg.stop_threshold = INT_MAX;
  pcm* p = pcm_open(0, 0, PCM_OUT, &cfg);
  std::vector<int16_t> silence(kPlayPeriod * 2, 0), audio;
  size_t pos = 0;
  bool ok = p && pcm_is_ready(p);
  while (ok && !cancel && sound) {
    if (pos >= audio.size()) {
      std::lock_guard<std::mutex> l(play_mu);
      if (!audio_q.empty()) {
        audio = std::move(audio_q.front());
        audio_q.pop_front();
        pos = 0;
      } else if (tts_done) {
        break;
      }
    }
    // keep the DSP fed: the current sentence, or silence while espeak works
    std::vector<int16_t> chunk(silence);
    size_t take = pos < audio.size() ? std::min(audio.size() - pos, chunk.size()) : 0;
    if (take) std::copy(audio.begin() + pos, audio.begin() + pos + take, chunk.begin());
    pos += take;
    if (pcm_writei(p, chunk.data(), kPlayPeriod) < 0) break;
  }
  for (int i = 0; ok && i < 6; ++i)  // let the tail play out
    if (pcm_writei(p, silence.data(), kPlayPeriod) < 0) break;
  if (p) pcm_close(p);
  route("route-speaker-off.sh");
}

static void queue_sentence(const std::string& s) {
  if (!sound) return;
  std::string t = speakable(s);
  if (t.empty()) return;
  std::lock_guard<std::mutex> l(play_mu);
  sentences.push_back(t);
  play_cv.notify_all();
}

// ---------------------------------------------------------------- the model

static int connect_server() {
  int s = socket(AF_INET, SOCK_STREAM, 0);
  sockaddr_in a = {};
  a.sin_family = AF_INET;
  a.sin_port = htons(8080);
  a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (s < 0 || connect(s, (sockaddr*)&a, sizeof(a)) != 0) {
    if (s >= 0) close(s);
    return -1;
  }
  return s;
}

// The server's context window (GET /props), in tokens; 0 if it does not answer.
static int server_ctx() {
  int s = connect_server();
  if (s < 0) return 0;
  std::string req = "GET /props HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", raw;
  if (send(s, req.data(), req.size(), MSG_NOSIGNAL) == (ssize_t)req.size()) {
    char buf[4096];
    ssize_t n;
    while ((n = recv(s, buf, sizeof(buf), 0)) > 0) raw.append(buf, n);
  }
  close(s);
  return std::max(0L, json_number(raw, "n_ctx"));
}

// Streams the answer into view.answer, handing each finished sentence to the
// speaker. *tokens: the context the conversation fills afterwards, as the
// server counted it (-1 if it did not say). Returns false if no model answers.
static bool ask(const std::string& question, std::string* model, int* tokens) {
  *tokens = -1;
  int s = connect_server();
  if (s < 0) return false;
  std::string body = "{\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":" +
                     std::to_string(kMaxTokens) + ",\"temperature\":0.7,\"messages\":[" +
                     "{\"role\":\"system\",\"content\":\"" + json_escape(kSystem) + "\"},";
  for (const auto& e : history)
    body += "{\"role\":\"user\",\"content\":\"" + json_escape(e.question) + "\"}," +
            "{\"role\":\"assistant\",\"content\":\"" + json_escape(e.answer) + "\"},";
  body += "{\"role\":\"user\",\"content\":\"" + json_escape(question) + "\"}]}";
  std::string req = "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    "Content-Length: " + std::to_string(body.size()) + "\r\nConnection: close\r\n\r\n" + body;
  if (send(s, req.data(), req.size(), MSG_NOSIGNAL) != (ssize_t)req.size()) { close(s); return false; }
  std::string raw, sse, pending;  // pending: text of the sentence being written
  bool in_body = false, chunked = false, any = false;
  size_t chunk_left = 0;
  char buf[4096];
  ssize_t n;
  while (!cancel) {
    pollfd p = {s, POLLIN, 0};
    if (poll(&p, 1, 200) == 0) continue;
    if ((n = recv(s, buf, sizeof(buf), 0)) <= 0) break;
    raw.append(buf, n);
    if (!in_body) {
      size_t e = raw.find("\r\n\r\n");
      if (e == std::string::npos) continue;
      if (raw.compare(9, 3, "200") != 0) break;
      chunked = raw.substr(0, e).find("chunked") != std::string::npos;
      raw.erase(0, e + 4);
      in_body = true;
    }
    if (!chunked) { sse += raw; raw.clear(); }
    while (chunked && !raw.empty()) {  // undo the chunking first: a chunk can end mid-line
      if (chunk_left == 0) {
        size_t e = raw.find("\r\n");
        if (e == std::string::npos) break;
        if (e == 0) { raw.erase(0, 2); continue; }
        chunk_left = strtoul(raw.c_str(), nullptr, 16);
        raw.erase(0, e + 2);
        if (chunk_left == 0) break;
      }
      size_t take = std::min(chunk_left, raw.size());
      sse.append(raw, 0, take);
      raw.erase(0, take);
      chunk_left -= take;
    }
    size_t e;
    while ((e = sse.find('\n')) != std::string::npos) {
      std::string line = sse.substr(0, e);
      sse.erase(0, e + 1);
      if (line.compare(0, 6, "data: ") != 0 || line.compare(0, 12, "data: [DONE]") == 0) continue;
      std::string m, piece;
      if (model->empty() && json_string(line, "model", &m)) *model = m;
      long used = json_number(line, "total_tokens");  // the last chunk: usage
      if (used >= 0) *tokens = int(used);
      size_t d = line.find("\"delta\":");
      if (d == std::string::npos || !json_string(line.substr(d), "content", &piece)) continue;
      any = true;
      {
        std::lock_guard<std::mutex> l(mu);
        view.answer += piece;
        if (view.phase == "thinking...") view.phase = "answering...";
      }
      // speak each sentence as soon as it is complete
      for (char c : piece) {
        pending += c;
        size_t k = pending.size();
        if (c == '\n' || ((c == ' ') && k >= 2 && strchr(".!?:;", pending[k - 2]) && k > 12)) {
          queue_sentence(pending);
          pending.clear();
        }
      }
    }
  }
  close(s);
  queue_sentence(pending);
  return any || cancel;
}

// ---------------------------------------------------------------- log

// A unique id: this boot, the time since it, a counter.
static std::string new_id() {
  static int counter = 0;
  std::string boot = trim(run({"/bin/cat", "/proc/sys/kernel/random/boot_id"})).substr(0, 8);
  char id[64];
  snprintf(id, sizeof(id), "%s-%lld-%d", boot.c_str(), now_ms(), ++counter);
  return id;
}

static void log_exchange(const std::string& q, const std::string& a, const std::string& model) {
  std::string id = new_id();
  std::string line = std::string("{\"id\":\"") + id + "\",\"conversation\":\"" + conv_id + "\",\"turn\":" +
                     std::to_string(history.size()) + ",\"model\":\"" + json_escape(model) + "\",\"question\":\"" +
                     json_escape(q) + "\",\"answer\":\"" + json_escape(a) + "\"}\n";
  mkdir("/tmp/www", 0755);
  int fd = open(kLog, O_WRONLY | O_CREAT | O_APPEND, 0644);
  if (fd >= 0) {
    (void)!write(fd, line.data(), line.size());
    close(fd);
  }
}

// ---------------------------------------------------------------- the turn

static void turn() {
  set_phase("transcribing...");
  std::string text = trim(run({"/tmp/voice/bin/whisper-cli", "-m", "/tmp/voice/whisper.bin", "-f", kQuestionWav,
                               "-t", "6", "-l", "en", "-nt", "-np"}));
  // Whisper's notes for non-speech: [BLANK_AUDIO], (static), [Music]...
  std::string words;
  int depth = 0;
  for (char c : text) {
    if (c == '[' || c == '(') ++depth;
    else if ((c == ']' || c == ')') && depth) --depth;
    else if (!depth) words += c;
  }
  words = trim(words);
  if (words.empty() || cancel) {
    set_phase(cancel ? "" : "didn't catch that");
    busy = false;
    return;
  }
  // Keep the conversation going if this exchange fits in the context window
  // (with a tenth to spare: the question's token count is a guess); else start anew.
  std::string model_now = trim(run({"/bin/cat", "/tmp/model.name"}));
  int ctx = server_ctx();
  bool full = conv_tokens + int(words.size()) / 3 + 32 + kMaxTokens > ctx - ctx / 10;
  if (history.empty() || full || model_now != conv_model || ctx != conv_ctx) {
    history.clear();
    conv_id = new_id();
    conv_model = model_now;
    conv_ctx = ctx;
    conv_tokens = 0;
  }
  {
    std::lock_guard<std::mutex> l(mu);
    view.question = words;
    view.answer.clear();
    view.phase = "thinking...";
    view.turn = int(history.size()) + 1;
    view.ctx_used = conv_tokens;
    view.ctx_max = ctx;
    view.new_after_full = full && conv_tokens > 0;
  }
  {
    std::lock_guard<std::mutex> l(play_mu);
    sentences.clear();
    audio_q.clear();
    text_done = tts_done = false;
  }
  bool speak = sound;
  std::thread tts, speaker;
  if (speak) tts = std::thread(tts_thread);
  if (speak) speaker = std::thread([] {
    {  // the speaker route goes on once the first sentence is spoken
      std::unique_lock<std::mutex> l(play_mu);
      while (audio_q.empty() && !tts_done && !cancel && sound) {
        l.unlock();
        usleep(20000);
        l.lock();
      }
      if (audio_q.empty() || !sound) return;
    }
    set_phase("speaking...");
    player_thread();
  });
  std::string model;
  int tokens;
  bool ok = ask(words, &model, &tokens);
  if (!ok && !history.empty() && !cancel) {  // the server refused it (context overflow?): once more, anew
    history.clear();
    conv_id = new_id();
    conv_tokens = 0;
    {
      std::lock_guard<std::mutex> l(mu);
      view.turn = 1;
      view.ctx_used = 0;
    }
    ok = ask(words, &model, &tokens);
  }
  {
    std::lock_guard<std::mutex> l(play_mu);
    text_done = true;
    play_cv.notify_all();
  }
  if (speak) {
    tts.join();
    speaker.join();
  }
  std::string answer;
  {
    std::lock_guard<std::mutex> l(mu);
    answer = view.answer;
    view.phase = !ok ? "no model loaded" : cancel ? "stopped" : "";
  }
  if (ok && !answer.empty()) {
    history.push_back({words, answer});
    // stopped before the server counted: about 3 characters a token
    conv_tokens = tokens >= 0 ? tokens : conv_tokens + int(words.size() + answer.size()) / 3 + 32;
    std::lock_guard<std::mutex> l(mu);
    view.ctx_used = conv_tokens;
  }
  if (ok && !answer.empty()) log_exchange(words, answer, model);
  busy = false;
}

// ---------------------------------------------------------------- API

void voice_init() {
  signal(SIGPIPE, SIG_IGN);
  mkdir("/tmp/www", 0755);
}

void voice_press() {
  if (!exists("/run/audio-ready")) return;
  if (busy) {  // a press during an answer stops it
    cancel = true;
    return;
  }
  if (recording) return;
  cancel = false;
  busy = true;
  recording = true;
  set_phase("listening...");
  rec_thread = std::thread(record);
}

void voice_release() {
  if (!recording) return;
  recording = false;
  if (rec_thread.joinable()) rec_thread.join();
  struct stat st;
  if (stat(kQuestionWav, &st) != 0 || st.st_size < 44 + kRecRate * 2 / 2) {  // under half a second
    set_phase("hold volume-down while you speak");
    busy = false;
    return;
  }
  std::thread(turn).detach();
}

void voice_set_sound(bool on) {
  sound = on;
  std::lock_guard<std::mutex> l(play_mu);
  play_cv.notify_all();  // muted: the answer being spoken stops
}

VoiceView voice_view() {
  std::lock_guard<std::mutex> l(mu);
  VoiceView v = view;
  v.available = exists("/run/audio-ready");
  v.busy = busy;
  v.sound = sound;
  return v;
}

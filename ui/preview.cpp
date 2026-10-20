// Host preview: builds the real llm-ui drawing code with a fake framebuffer
// and renders one screen to a PPM, to check the layout before booting.
//   make -C ui ../out/ui/preview && out/ui/preview out/ui/preview.ppm [model] [state]
//   state: ready (default), loading, receiving, none
#define main llm_ui_main
#include "main.cpp"
#undef main

#include "minui/graphics_fbdev.h"

static const char* g_out = "preview.ppm";

MinuiBackendFbdev::MinuiBackendFbdev() : gr_draw(nullptr), fb_fd(-1) {}
void MinuiBackendFbdev::Blank(bool) {}
void MinuiBackendFbdev::SetDisplayedFramebuffer(unsigned) {}
GRSurface* MinuiBackendFbdev::Init() {
  static GRSurface s = {1080, 1920, 1080 * 4, 4, nullptr};
  s.data = static_cast<unsigned char*>(calloc(1, 1080 * 1920 * 4));
  gr_draw = &s;
  return gr_draw;
}
GRSurface* MinuiBackendFbdev::Flip() {
  FILE* f = fopen(g_out, "wb");
  fprintf(f, "P6 1080 1920 255\n");
  for (int i = 0; i < 1080 * 1920; ++i) fwrite(gr_draw->data + i * 4, 1, 3, f);
  fclose(f);
  return gr_draw;
}
MinuiBackendFbdev::~MinuiBackendFbdev() {}

// voice.cpp (tinyalsa) is not linked on the host either: a fixed exchange.
static VoiceView g_voice;
void voice_init() {}
void voice_press() {}
void voice_release() {}
void voice_set_sound(bool) {}
VoiceView voice_view() { return g_voice; }

// events.cpp is not linked on the host; the preview never reads input.
int ev_init(ev_callback, bool) { return 0; }
int ev_get_input(int, uint32_t, input_event*) { return -1; }
int ev_wait(int) { return -1; }
void ev_dispatch() {}

int main(int argc, char** argv) {
  if (argc > 1) g_out = argv[1];
  gr_init();
  ui_init();
  std::string state = argc > 3 ? argv[3] : "ready";
  Screen s;
  s.model = argc > 2 ? argv[2] : "qwen2.5-coder:0.5b";
  s.url = "http://172.16.42.1:8080";
  s.status = state == "ready" ? "ready" : state == "loading" ? "loading model..." :
             state == "receiving" ? "receiving model... 212 MB" : "waiting for a model";
  s.color = state == "ready" ? 2 : state == "none" ? 0 : 1;
  s.battery = 97;
  s.cpu = 87;
  s.mem_used = 63;
  s.charging = state == "receiving";
  if (state == "none") {
    s.model = "";
    s.hint = "load one from the Nexus 5X LLM app on the PC";
  }
  g_voice.available = state != "novoice";
  if (state == "ready") {
    g_voice.question = "What is the tallest mountain in Japan?";
    g_voice.answer = "The tallest mountain in Japan is Mount Fuji, at 3,776 meters. It is an active "
                     "volcano about 100 kilometers southwest of Tokyo.";
    g_voice.turn = 3;
    g_voice.ctx_used = 412;
    g_voice.ctx_max = 32768;
  } else if (state == "speaking") {
    g_voice.phase = "speaking...";
    g_voice.sound = true;
    g_voice.question = "Tell me a fun fact about octopuses.";
    g_voice.answer = "An octopus has three hearts and blue blood.";
    s.status = "ready";
    s.color = 2;
  }
  s.voice = voice_view();
  draw(s);
  return 0;
}

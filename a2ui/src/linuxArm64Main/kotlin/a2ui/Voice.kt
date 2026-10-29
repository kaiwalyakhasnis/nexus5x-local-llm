package a2ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.F_OK
import platform.posix.X_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.system
import ui.log

private const val KEY_VOLUMEUP = 115
private const val DIR = "/tmp/voice"
private const val WAV = "$DIR/question.wav"
private const val TEXT = "$DIR/question.txt"
private const val DONE = "$DIR/question.done"
private const val TOOLS = "PATH=$DIR/bin:\$PATH"

/**
 * Push-to-talk: hold VOLUME UP and speak, let go, and Whisper's text goes
 * to [onText]. It uses the voice pack of nexus5x-local-llm (/tmp/voice:
 * tinycap, whisper-cli and Whisper tiny.en, sent from the PC) and that
 * project's mixer scripts (/etc/audio). The tools run as their own
 * processes, so the screen keeps drawing while Whisper works (about 5 s).
 */
internal class Voice(private val scope: CoroutineScope, private val onText: (String) -> Unit) {
    /** What the voice is doing, for the status line; empty when idle. */
    var state by mutableStateOf("")
        private set
    private var recording = false
    private var stopping = false  // until the capture is stopped and transcribed
    private var since = 0L
    private var job: Job? = null

    private val available get() = access("$DIR/bin/whisper-cli", X_OK) == 0 && access("$DIR/bin/tinycap", X_OK) == 0

    /** For Device.keyFilter: takes VOLUME UP (press, repeat and release); other keys go on. */
    fun key(code: Int, value: Int): Boolean {
        if (code != KEY_VOLUMEUP) return false
        when (value) {
            1 -> start()
            0 -> stop()
        }
        return true
    }

    private fun start() {
        if (recording || stopping) return
        if (!available) {
            state = "No voice pack: load it from the Nexus 5X LLM app"
            return
        }
        job?.cancel()
        recording = true
        since = clockMs()
        state = "Listening... let go of VOL+ to ask"
        // The mic's route, then the capture (48 kHz mono, as llm-ui records), in the background.
        sh("rm -f $WAV $TEXT $DONE; ($TOOLS sh /etc/audio/route-mic.sh >/dev/null 2>&1; " +
            "exec $DIR/bin/tinycap $WAV -D 0 -d 0 -c 1 -r 48000 -b 16 -p 1024 -n 4 >/dev/null 2>&1) &")
    }

    private fun stop() {
        if (!recording) return
        recording = false
        stopping = true
        val short = clockMs() - since < 600
        // In the background, as one script: wait for the capture to have started (the route
        // takes a moment), stop it (SIGINT: tinycap finishes the file), make sure it is gone,
        // and only then switch the route off (the DSP driver wedges otherwise); then Whisper.
        val cap = "pidof tinycap >/dev/null"
        sh("(for i in $(seq 75); do $cap && break; usleep 40000; done; kill -INT $(pidof tinycap) 2>/dev/null; " +
            "for i in $(seq 50); do $cap || break; usleep 40000; done; kill -9 $(pidof tinycap) 2>/dev/null; " +
            "$TOOLS sh /etc/audio/route-mic-off.sh >/dev/null 2>&1; " +
            (if (short) "" else "$DIR/bin/whisper-cli -m $DIR/whisper.bin -f $WAV -t 6 -l en -nt -np > $TEXT 2>/dev/null; ") +
            "touch $DONE) &")
        if (!short) state = "Transcribing..."
        job = scope.launch {
            while (access(DONE, F_OK) != 0) delay(200)
            stopping = false
            if (short) { state = ""; return@launch }
            val words = spoken(readFile(TEXT) ?: "")
            log("a2ui: heard: $words")
            if (words.isEmpty()) {
                state = "Didn't catch that"
                delay(2500)
                state = ""
            } else {
                state = ""
                onText(words)
            }
        }
    }

    /** Whisper's text without its notes for what isn't speech: [BLANK_AUDIO], (wind), ... */
    private fun spoken(text: String): String {
        val out = StringBuilder()
        var depth = 0
        for (c in text) when {
            c == '[' || c == '(' -> depth++
            (c == ']' || c == ')') && depth > 0 -> depth--
            depth == 0 -> out.append(if (c == '\n') ' ' else c)
        }
        return out.toString().trim().replace(Regex("\\s+"), " ")
    }
}

private fun sh(command: String) {
    system(command)
}

private val start = kotlin.time.TimeSource.Monotonic.markNow()
private fun clockMs() = start.elapsedNow().inWholeMilliseconds

private fun readFile(path: String): String? {
    val f = fopen(path, "r") ?: return null
    try {
        val out = StringBuilder()
        val buf = ByteArray(4096)
        while (true) {
            val n = buf.usePinned { fread(it.addressOf(0), 1u, buf.size.convert(), f) }.toInt()
            if (n <= 0) break
            out.append(buf.decodeToString(0, n))
        }
        return out.toString()
    } finally {
        fclose(f)
    }
}

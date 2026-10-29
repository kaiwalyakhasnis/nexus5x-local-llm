# nexus5x-local-llm

Small language models (Gemma 3, Qwen 2.5, Llama 3.2, SmolLM...) running on a
2015 **Nexus 5X** (bullhead) with **no Android at all**: just a trimmed Linux
kernel, BusyBox and llama.cpp. The phone does the inference on its CPU, with
the model in its 2 GB of RAM. You chat with it from a browser on the PC, or
from a mobile on the same Wi-Fi, or **talk to the phone itself**: hold
VOLUME DOWN, ask, and it answers on its screen and, if you want, out loud,
remembering the conversation. Booted with `fastboot boot` only: nothing is
flashed, and the phone's Android is untouched.

**Answers as screens** (optional): the model can also answer with a screen, text, cards and the
next questions to ask, drawn as Material 3 components through [A2UI](a2ui/README.md). Here
Gemma 3 1B is asked what system calls are; the answer is read with the volume key, then one of the
suggested questions is pressed:

<p align="center"><img src="docs/media/a2ui-chat.gif" width="360" alt="The A2UI screen: 'what are system calls in linux kernel' asked, Gemma 3 1B writes a titled answer with paragraphs and a card for open(), VOLUME DOWN scrolls through it and focuses the suggested questions, POWER asks 'What are some common system call examples?', and the next answer appears with its own suggestions"></p>

<sub>Captured from the phone's framebuffer. While the model writes, the GIF runs 16 times faster (marked 16x):
each answer took about 3 minutes on the phone; the key presses are real time. The answers are the model's,
unedited, glitches included (the stray `")},{` after the first paragraph, which the app now removes, and the
cut-off `open(` example).</sub>

## More

- [Chat and voice](docs/chat-and-voice.md): the web chat on the PC and on a mobile, the PC app,
  talking to the phone, and what its screen shows
- [Answers as screens (A2UI)](a2ui/README.md): how the screens are made, building and running them
- [Models and speed](docs/models.md): which models fit, how fast they are, context length, heat
- [How it works](docs/how-it-works.md): why, how little runs on the phone, how it is built
- [Building from source](docs/building.md): the boot image, the voice pack, command-line tools, debugging

## Try it on your Nexus 5X

You need:

- a **Nexus 5X with an unlocked bootloader** (*OEM unlocking* in Developer
  options, then `fastboot flashing unlock`, done once; it wipes the phone).
  Whatever Android is installed stays as it is.
- a **Linux PC** with `fastboot`, Python 3 and [Ollama](https://ollama.com)
  (only used as the model downloader), and a USB cable.

Steps:

1. **Get the boot image**: download the zip from
   [Releases](https://github.com/kaiwalyakhasnis/nexus5x-local-llm/releases/latest)
   (it holds `nexus5x-llm-boot.img`), or build it (see [Building from source](docs/building.md)).
2. **Boot it** (RAM only, nothing is flashed):

   ```sh
   adb reboot bootloader          # or hold power + volume down
   fastboot boot nexus5x-llm-boot.img
   ```

   A few seconds later the phone shows *waiting for a model*, and a new USB
   network interface comes up on the PC.
3. **Start the PC app**: clone this repo, then

   ```sh
   python3 manager/nexus5x-llm.py --install   # once: adds "Nexus 5X LLM" to the app menu
   python3 manager/nexus5x-llm.py             # or start it directly; opens http://localhost:8765
   ```

   Click **Download & load** next to a model (`gemma3:1b` is a good first
   one), and wait for *ready*: a 1 GB model takes about a minute over USB.
4. **Chat**: click **Open chat** (http://172.16.42.1:8080). For these small
   models, open Settings → Tools in the chat and switch the built-in tools
   off; otherwise the page sends tool definitions with every message and a
   0.5B model answers with a made-up tool call.
5. **Talk to it**: under *Voice on the phone*, click **Download voice pack**
   once (53 MB, from the release; or build it). From then on the app sends
   it to the phone after every boot. Then **hold VOLUME DOWN**, ask your
   question, and let go: the answer appears on the phone's screen. The
   sound is off after boot; **VOLUME UP** turns it on, and the phone speaks
   its answers. Press VOLUME DOWN during an answer to stop it.
6. **On a mobile**: tick *Share the chat on Wi-Fi* in the app and scan the
   QR code. Anyone on that network can use the chat while it is on.
7. **Back to Android**: hold power for ~10 s, or
   `scripts/phone.py llm-reboot`. Nothing on the phone changed.

The model and the voice pack live in RAM, so after a reboot load the model
again (one click; the voice pack goes by itself). Any GGUF file works too,
without Ollama: `scripts/send-model.sh model.gguf`.

## Licenses and credits

- Linux kernel 3.10 (GPL-2.0): [LineageOS/android_kernel_lge_bullhead](https://github.com/LineageOS/android_kernel_lge_bullhead)
  `lineage-15.1` + [`patches/`](patches/)
- [llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0 and its web UI (MIT)
- BusyBox 1.36.1 (GPL-2.0), Ubuntu's `busybox-static` build ([source](https://busybox.net/downloads/busybox-1.36.1.tar.bz2))
- minui (Apache-2.0), from AOSP via [nexus5x-hike-buddy](https://github.com/kaiwalyakhasnis/nexus5x-hike-buddy)
- [tinyalsa](https://github.com/tinyalsa/tinyalsa) v2.0.0 (BSD-3-Clause) +
  [`patches/tinyalsa/`](patches/tinyalsa/), in `llm-ui` and the voice pack
- The mixer scripts in [`rootfs/etc/audio/`](rootfs/etc/audio/) are generated from
  LineageOS/AOSP `device/lge/bullhead/mixer_paths.xml` (Apache-2.0)
- Voice pack (a separate download): [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
  v1.9.5 (MIT) with OpenAI's Whisper tiny.en model (MIT), and
  [eSpeak NG](https://github.com/espeak-ng/espeak-ng) 1.52.0 (GPL-3.0-or-later);
  their licenses and source links are inside the pack
- The logos in [`ui/assets/`](ui/assets/) belong to Google (Gemma), Alibaba
  (Qwen), Meta, Hugging Face, DeepSeek and Ollama; they are shown only to
  identify which family a model comes from.
- Models are downloaded by you through Ollama, under each model's own license.
- The optional A2UI screen ([`a2ui/`](a2ui/), not in the boot image) is built with
  [embedded-compose](https://github.com/kaiwalyakhasnis/embedded-compose) and
  [embedded-compose-a2ui](https://github.com/kaiwalyakhasnis/embedded-compose-a2ui), and draws
  its text in Roboto (Apache-2.0, from AOSP)

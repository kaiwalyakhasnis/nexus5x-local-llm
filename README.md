# nexus5x-local-llm

Small language models (Gemma 3, Qwen 2.5, Llama 3.2, SmolLM...) running on a
2015 **Nexus 5X** (bullhead) with **no Android at all**: just a trimmed Linux
kernel, BusyBox and llama.cpp. The phone does the inference on its CPU, with
the model in its 2 GB of RAM; you chat with it from a browser on the PC, or
from a mobile on the same Wi-Fi. Booted with `fastboot boot` only: nothing is
flashed, and the phone's Android is untouched.

<p align="center"><img src="docs/media/nexus-screen.png" width="720" alt="The Nexus 5X screen: waiting for a model, receiving one over USB, then Google Gemma 3 1b ready"></p>

<sub>The phone's own screen (captured from its framebuffer): waiting for a model, receiving one over USB, ready.</sub>

| Chat in the PC browser (Qwen2.5 Coder 0.5B, real time) | Chat from a mobile over Wi-Fi (Gemma 3 1B, real time) |
|---|---|
| <img src="docs/media/pc-chat.gif" alt="Asking for a Python Fibonacci function in the web chat; the answer streams at about 9.5 tokens/s"> | <p align="center"><img src="docs/media/mobile-chat.gif" width="260" alt="The same web chat on a phone-sized screen, answered by Gemma 3 1B at about 5 tokens/s"></p> |

**Loading a model** from the PC app (Ollama models on the PC → the phone, over USB):

<p align="center"><img src="docs/media/pc-app.gif" width="760" alt="The Nexus 5X LLM app: Load on phone, the upload progress, then the phone reports ready"></p>

<sub>Recorded from headless Chromium driving the real pages, at real speed (full-quality MP4s:
[PC app](docs/media/pc-app.mp4), [PC chat](docs/media/pc-chat.mp4), [mobile chat](docs/media/mobile-chat.mp4)).
The mobile view is Chromium's phone emulation (390×844) opening the chat through the Wi-Fi share.</sub>

## Why

A Nexus 5X has a Snapdragon 808 (2× Cortex-A57 + 4× Cortex-A53, ARMv8.0,
no dot-product instructions) and 2 GB of RAM: about the floor of what still
runs a modern language model. Under Android, a big part of that RAM and CPU
belongs to the OS. Here **nothing else runs**, so what you measure is the
hardware and the model:

- **Try small models on old or low-end hardware**: how fast a 0.5B or 1B
  model really is on a 2015 phone CPU, which ones fit in 2 GB, what
  quantization costs, how heat limits a long answer.
- **Pick a model for an offline/edge device** before building one.
- **Give an old phone a job**: a private, offline chatbot on the desk.

## How little runs on the phone

There is **no Android**: no Zygote, no ART/Java, no `system_server`, no
SurfaceFlinger, no Binder (not even compiled in), no SELinux, no vold, no
`/system` or `/data`. The boot chain is:

```
bootloader ─▶ Linux 3.10 kernel ─▶ BusyBox init ─▶ 4 small services
                (trimmed)            (PID 1)        llmd · llama serve · llm-ui · telnetd
```

Measured on the phone with Gemma 3 1B loaded:

| | |
|---|---|
| **Processes** | 7: `init`, `udhcpd`, `llmd` (+ its `nc` waiting for the next model), `llama serve`, `llm-ui`, `telnetd` |
| **Files** | the whole system is a 13 MB boot image; its RAM disk holds `/bin` with 9 files: `llama` (17 MB, llama.cpp), `llm-ui` (4 MB, the screen), `busybox` (2 MB), 2 tiny C tools and 4 shell scripts |
| **Storage** | none: every file system is in RAM (`rootfs`, `tmpfs`, `proc`, `sysfs`, `devtmpfs`). The kernel has **no eMMC driver**, so it cannot read or write the phone's storage at all |
| **RAM** | 1.83 GB total; everything except the model uses about **150 MB**, the rest is for the model and its cache |
| **Kernel** | LineageOS 15.1's bullhead kernel with 810 of its 1282 built-in options: no modem, radios, GPU, camera, audio, touch, sensors, storage, filesystems, netfilter or IPv6 |
| **Network** | only USB (RNDIS gadget): the phone is `172.16.42.1`, the PC gets `172.16.42.2` by DHCP |

What stays in the kernel is what keeps the SoC safe and the phone usable:
power and regulators, cpufreq and the HMP scheduler, **thermal protection**
(msm_thermal, battery current limiting), charger and fuel gauge, the
watchdog, the display (the bootloader hands over a running splash screen),
the buttons, and the USB gadget.

```
PC (Linux)                                   Nexus 5X (172.16.42.1)
Nexus 5X LLM app ─── model file, :9000 ───▶  llmd ─▶ /tmp/model.gguf (RAM)
   (Ollama models)                            │
browser ─────────── web chat, :8080 ──────▶  llama serve (llama.cpp web UI + OpenAI-style API)
mobile ── Wi-Fi ──▶ PC :8080 ── forwarded ─▶  ″
                                             llm-ui ─▶ screen: model logo, name, state, URL
```

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
   (it holds `nexus5x-llm-boot.img`), or build it (see *Build from source*).
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
5. **On a mobile**: tick *Share the chat on Wi-Fi* in the app and scan the
   QR code. Anyone on that network can use the chat while it is on.
6. **Back to Android**: hold power for ~10 s, or
   `scripts/phone.py llm-reboot`. Nothing on the phone changed.

The model lives in RAM, so after a reboot load it again (one click). Any
GGUF file works too, without Ollama: `scripts/send-model.sh model.gguf`.

## Models and speed

Measured on the phone, answers of 200-400 tokens, 6 threads (all cores):

| Model (Ollama name) | File | Generation | Notes |
|---|---|---|---|
| Qwen2.5 Coder 0.5B (`qwen2.5-coder:0.5b`) | 398 MB | ~9.5 tok/s, ~8 sustained | good for short code snippets |
| Qwen2.5 0.5B (`qwen2.5:0.5b`) | 398 MB | 10.3 tok/s (llama-bench) | general chat |
| Google Gemma 3 1B (`gemma3:1b`) | 806 MB | ~5.3 tok/s, prompt 11 tok/s | best quality that fits |

Models up to about **1.25 GB** fit (the app greys out bigger ones):
`gemma3:270m`, `qwen3:0.6b`, `qwen2.5-coder:1.5b`, `llama3.2:1b` in Q4_K_M,
`smollm2:360m` in Q8_0... Gemma 3n and Gemma 4 (3.5 GB and up) don't.

**Heat**: under full load the A57 cores reach their 100 °C limit within a
second or two, and the stock thermal driver holds them at 768 MHz (instead of
1824) until they cool; the A53s stay at full speed. The numbers above are
with this protection on, and it stays on: the Nexus 5X is known for
heat-related failures. A bigger model doesn't make it hotter, only slower.

## The phone's screen

`llm-ui` ([`ui/`](ui/), minui on the framebuffer) shows the model's logo and
name, its state (waiting / receiving / loading / ready / failed) and the chat
URL; POWER turns the screen off and on. The name comes from the model file
itself ([`tools/gguf-name.c`](tools/gguf-name.c) reads the GGUF metadata),
the logo from the name: Gemma, Qwen, Llama (Meta), SmolLM (Hugging Face),
DeepSeek, else Ollama's llama. When a new model starts arriving, `llmd` frees
the old one first: two don't fit in RAM.

## How it is built

- **Kernel**: LineageOS's bullhead kernel (`lineage-15.1`, 3.10) plus the
  four commits in [`patches/`](patches/): the `dtc` fix for modern host
  compilers, and three fixes that let the smaller config build (gadget MIDI
  without ALSA, the LG backlight hook without MDSS, a missing include in
  dw8768). [`config/local-llm.config`](config/local-llm.config) is merged
  onto `lineageos_bullhead_defconfig`; its comments say what each section
  removes and why some things stay.
- **Ramdisk** ([`rootfs/`](rootfs/)): BusyBox `init` runs
  [`etc/rc`](rootfs/etc/rc), [`etc/llm-usb.sh`](rootfs/etc/llm-usb.sh) (RNDIS
  + DHCP) and [`etc/llm-cpu.sh`](rootfs/etc/llm-cpu.sh) (the CPU, scheduler,
  idle and DDR bandwidth setup from Android's `init.bullhead.power.sh`), then
  keeps [`llmd`](rootfs/bin/llmd), `llm-ui` and `telnetd` running.
- **llama.cpp** v0.7.0: static aarch64 build with `-mcpu=cortex-a57`, CPU
  backend only, the unified `llama` binary with the web UI embedded
  ([`scripts/build-llama.sh`](scripts/build-llama.sh)).
- **Boot image**: the ramdisk is LZMA-compressed so the image stays at
  13.4 MB; at 16 MB the bootloader no longer starts it.
- **PC app** ([`manager/`](manager/)): one Python file (standard library
  only) and one HTML page.

## Build from source

Needs a Linux PC with `g++-aarch64-linux-gnu`, `gcc`, `make`, `git`, `curl`,
`cpio`, `lzma`, `python3` with `python3-pil` and `python3-venv`. No Android
or LineageOS tree is needed.

```sh
scripts/fetch-sources.sh    # kernel + patches, llama.cpp, BusyBox, cmake, kernel GCC 4.9
scripts/build-kernel.sh     # out/kobj/arch/arm64/boot/Image.gz-dtb
scripts/build-llama.sh      # out/llama/bin/llama
scripts/build-bootimg.sh    # out/boot.img
fastboot boot out/boot.img
```

The kernel needs GCC 4.9 (current GCCs can't build 3.10): `fetch-sources.sh`
takes AOSP's. With a LineageOS 15.1 tree, `LINEAGE=/path` in an untracked
`local.env` uses that one instead.

Command line tools, for scripting and debugging:

```sh
scripts/send-model.sh qwen2.5:0.5b      # Ollama model name, or a .gguf path
scripts/chat.py                         # chat in the terminal; prints tok/s
scripts/phone.py 'llm bench'            # llama-bench on the phone (telnet)
scripts/phone.py 'echo THREADS=4 > /tmp/llm.conf; llm-serve'   # change settings
scripts/push.sh f /bin/f 755            # copy a file into the running phone (RAM)
scripts/screencap.py screen.png         # the phone's screen
scripts/phone.py 'llm-reboot bootloader'                       # back to fastboot
```

QEMU (userspace only, with the 3.10 QEMU kernel from `~/bullhead-qemu`):
`scripts/run-qemu.sh model.gguf`.

## Debugging boot hangs

pstore does not survive any reboot on bullhead, and without a UART there is
no log. `scripts/probe-boot.sh <kobj>` boots a kernel with a tiny ramdisk
whose init reboots into fastboot: ending in fastboot means userspace was
reached; an apps watchdog bark ends in Android instead.
`scripts/bisect-config.sh` builds fragments from sections or single options
of `config/local-llm.config`. This is how the networking hang was found
(see the comment in that section).

## Licenses and credits

- Linux kernel 3.10 (GPL-2.0): [LineageOS/android_kernel_lge_bullhead](https://github.com/LineageOS/android_kernel_lge_bullhead)
  `lineage-15.1` + [`patches/`](patches/)
- [llama.cpp](https://github.com/ggml-org/llama.cpp) v0.7.0 and its web UI (MIT)
- BusyBox 1.36.1 (GPL-2.0), Ubuntu's `busybox-static` build ([source](https://busybox.net/downloads/busybox-1.36.1.tar.bz2))
- minui (Apache-2.0), from AOSP via [nexus5x-hike-buddy](https://github.com/kaiwalyakhasnis/nexus5x-hike-buddy)
- The logos in [`ui/assets/`](ui/assets/) belong to Google (Gemma), Alibaba
  (Qwen), Meta, Hugging Face, DeepSeek and Ollama; they are shown only to
  identify which family a model comes from.
- Models are downloaded by you through Ollama, under each model's own license.

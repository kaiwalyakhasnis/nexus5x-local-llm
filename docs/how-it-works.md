# How it works

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
- **Give an old phone a job**: a private, offline chatbot or voice
  assistant on the desk. Nothing you say or type leaves the phone and the PC.

## How little runs on the phone

There is **no Android**: no Zygote, no ART/Java, no `system_server`, no
SurfaceFlinger, no Binder (not even compiled in), no SELinux, no vold, no
`/system` or `/data`. The boot chain is:

```
bootloader ─▶ Linux 3.10 kernel ─▶ BusyBox init ─▶ 5 small services
                (trimmed)            (PID 1)        llmd · llama serve · llm-ui · httpd · telnetd
```

Measured on the phone with Gemma 3 1B and the voice pack loaded:

| | |
|---|---|
| **Processes** | 8: `init`, `udhcpd`, `llmd` (+ its `nc` waiting for the next upload), `llama serve`, `llm-ui`, `httpd`, `telnetd`. Voice chat starts `whisper-cli` and `espeak-ng` for a moment per question |
| **Files** | the whole system is a 14.4 MB boot image; its RAM disk holds `/bin` with 9 files: `llama` (17 MB, llama.cpp), `llm-ui` (4 MB, the screen and voice chat), `busybox` (2 MB), 2 tiny C tools and 4 shell scripts. The voice pack (53 MB) comes over USB after boot, into RAM |
| **Storage** | every file system is in RAM (`rootfs`, `tmpfs`, `proc`, `sysfs`, `devtmpfs`). The only exception: for audio, the phone's own `vendor` partition is mounted **read-only** (ext4 `ro,noload`, so not even a journal replay writes) to read the audio DSP's firmware. Nothing else on the storage is opened |
| **RAM** | 1.83 GB total. With Gemma 3 1B at 32K context: the model file 806 MB and the voice pack 53 MB (both in `tmpfs`), llama.cpp's cache and buffers 251 MB, **everything else about 50 MB**; ~750 MB stay free |
| **Kernel** | LineageOS 15.1's bullhead kernel with 876 of its 1282 built-in options: no modem, radios, GPU, camera, touch, sensors, netfilter or IPv6 |
| **Network** | only USB (RNDIS gadget): the phone is `172.16.42.1`, the PC gets `172.16.42.2` by DHCP |

What stays in the kernel is what keeps the SoC safe and the phone usable:
power and regulators, cpufreq and the HMP scheduler, **thermal protection**
(msm_thermal, battery current limiting), charger and fuel gauge, the
watchdog, the display (the bootloader hands over a running splash screen),
the buttons, the USB gadget, and the audio path (ADSP, codec, eMMC/ext4 to
read its firmware).

```
PC (Linux)                                   Nexus 5X (172.16.42.1)
Nexus 5X LLM app ─ model / voice pack, :9000 ─▶ llmd ─▶ /tmp/model.gguf, /tmp/voice (RAM)
   (Ollama models)                            │
browser ─────────── web chat, :8080 ──────▶  llama serve (llama.cpp web UI + OpenAI-style API)
mobile ── Wi-Fi ──▶ PC :8080 ── forwarded ─▶  ″
                                              ▲ questions from the phone's mic
PC app ◀── voice state, conversations, :8081  llm-ui ─▶ screen: model, state, voice chat
                                                     ─▶ mic → Whisper; answers → eSpeak NG → speaker
```

## How it is built

- **Kernel**: LineageOS's bullhead kernel (`lineage-15.1`, 3.10) plus the
  four commits in [`patches/`](../patches/): the `dtc` fix for modern host
  compilers, and three fixes that let the smaller config build (gadget MIDI
  without ALSA, the LG backlight hook without MDSS, a missing include in
  dw8768). [`config/local-llm.config`](../config/local-llm.config) is merged
  onto `lineageos_bullhead_defconfig`, then
  [`config/audio.config`](../config/audio.config) puts the audio path back;
  their comments say what each section removes or restores, and why.
  `FRAG=config/local-llm.config` builds the kernel without audio.
- **Ramdisk** ([`rootfs/`](../rootfs/)): BusyBox `init` runs
  [`etc/rc`](../rootfs/etc/rc), [`etc/llm-usb.sh`](../rootfs/etc/llm-usb.sh) (RNDIS
  + DHCP) and [`etc/llm-cpu.sh`](../rootfs/etc/llm-cpu.sh) (the CPU, scheduler,
  idle and DDR bandwidth setup from Android's `init.bullhead.power.sh`), then
  keeps [`llmd`](../rootfs/bin/llmd), `llm-ui`, `httpd` and `telnetd` running.
- **llama.cpp** v0.7.0: static aarch64 build with `-mcpu=cortex-a57`, CPU
  backend only, the unified `llama` binary with the web UI embedded
  ([`scripts/build-llama.sh`](../scripts/build-llama.sh)).
- **Voice pack** ([`scripts/build-voice.sh`](../scripts/build-voice.sh)):
  static aarch64 builds of whisper.cpp v1.9.5, eSpeak NG 1.52.0 and
  tinyalsa v2.0.0, the Whisper tiny.en model and eSpeak's English data. It
  doesn't fit in the boot image, so it travels like a model: `llmd`
  recognizes the tar and unpacks it into RAM. `llm-ui` itself links
  tinyalsa.
- **Boot image**: the ramdisk is LZMA-compressed so the image stays at
  14.4 MB; at 16 MB the bootloader no longer starts it.
- **PC app** ([`manager/`](../manager/)): one Python file (standard library
  only) and one HTML page.

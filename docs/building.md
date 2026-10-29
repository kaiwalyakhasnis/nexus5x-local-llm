# Building from source

Needs a Linux PC with `g++-aarch64-linux-gnu`, `gcc`, `g++`, `make`, `git`,
`curl`, `cpio`, `lzma`, `python3` with `python3-pil` and `python3-venv`. No
Android or LineageOS tree is needed.

```sh
scripts/fetch-sources.sh    # kernel + patches, llama.cpp, whisper.cpp, eSpeak NG, tinyalsa, BusyBox, cmake, GCC 4.9
scripts/build-kernel.sh     # out/kobj/arch/arm64/boot/Image.gz-dtb
scripts/build-llama.sh      # out/llama/bin/llama
scripts/build-bootimg.sh    # out/boot.img
scripts/build-voice.sh      # out/voice-pack.tar (the PC app finds it there)
fastboot boot out/boot.img
```

The kernel needs GCC 4.9 (current GCCs can't build 3.10): `fetch-sources.sh`
takes AOSP's. With a LineageOS 15.1 tree, `LINEAGE=/path` in an untracked
`local.env` uses that one instead.

Command line tools, for scripting and debugging:

```sh
scripts/send-model.sh qwen2.5:0.5b      # Ollama model name, or a .gguf path
nc -N 172.16.42.1 9000 < out/voice-pack.tar   # the voice pack, without the app
scripts/chat.py                         # chat in the terminal; prints tok/s
scripts/phone.py 'llm bench'            # llama-bench on the phone (telnet)
scripts/phone.py 'echo CTX=8192 > /tmp/llm.conf; llm-serve'    # change settings
scripts/phone.py 'echo "sound on" > /tmp/ptt'                  # what VOLUME UP does ("down"/"up": VOLUME DOWN)
scripts/push.sh f /bin/f 755            # copy a file into the running phone (RAM)
scripts/screencap.py screen.png         # the phone's screen
scripts/phone.py 'llm-reboot bootloader'                       # back to fastboot
```

`make -C ui ../out/ui/preview` builds a PC version of the screen that
renders to an image, for working on `llm-ui` without the phone.

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

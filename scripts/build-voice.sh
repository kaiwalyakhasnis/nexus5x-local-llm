#!/bin/sh
# Build the voice pack, out/voice-pack.tar: what the phone needs for voice chat
# and does not fit in the boot image. The PC app sends it like a model; llmd
# unpacks it into /tmp/voice (RAM).
#   bin/whisper-cli          speech -> text (whisper.cpp, static aarch64)
#   whisper.bin              the Whisper model: tiny.en, q8_0 (44 MB)
#   bin/espeak-ng            text -> speech (static aarch64)
#   espeak-ng-data/          its English voice data (built on the PC)
#   bin/tinymix, tinycap, tinyplay   ALSA tools (patches/tinyalsa applied)
#   licenses/, README.txt    the licenses, and where the sources are
# Sources come from scripts/fetch-sources.sh.
set -eu
P=$(cd "$(dirname "$0")/.." && pwd)
cd "$P"
JOBS=${JOBS:-4}
export PATH="$P/out/venv/bin:$PATH"
STAGE=out/voice-stage
rm -rf $STAGE && mkdir -p $STAGE/bin
CROSS="-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc
	-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ -DCMAKE_EXE_LINKER_FLAGS=-static"

# tinyalsa
T=out/src/tinyalsa
for u in tinymix tinycap tinyplay; do
	aarch64-linux-gnu-gcc -O2 -static -s -I$T/include -o $STAGE/bin/$u $T/utils/$u.c \
		$T/src/pcm.c $T/src/pcm_hw.c $T/src/pcm_plugin.c $T/src/snd_card_plugin.c \
		$T/src/mixer.c $T/src/mixer_hw.c $T/src/mixer_plugin.c $T/src/limits.c -ldl 2>/dev/null
done

# whisper.cpp
cmake -S out/src/whisper.cpp -B out/whisper-build -G Ninja -DCMAKE_BUILD_TYPE=Release $CROSS \
	-DCMAKE_C_FLAGS=-mcpu=cortex-a57 -DCMAKE_CXX_FLAGS=-mcpu=cortex-a57 \
	-DBUILD_SHARED_LIBS=OFF -DGGML_STATIC=ON -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
	-DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_SDL2=OFF > out/whisper-build.log
cmake --build out/whisper-build -j "$JOBS" --target whisper-cli >> out/whisper-build.log
aarch64-linux-gnu-strip -o $STAGE/bin/whisper-cli out/whisper-build/bin/whisper-cli
M=ggml-tiny.en-q8_0.bin
mkdir -p out/voice-models
[ -f out/voice-models/$M ] ||
	curl -fL -o out/voice-models/$M https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$M
cp out/voice-models/$M $STAGE/whisper.bin

# espeak-ng: the voice data is compiled by a PC build, the phone gets the program
E="-DUSE_MBROLA=OFF -DUSE_LIBSONIC=OFF -DUSE_LIBPCAUDIO=OFF -DUSE_ASYNC=OFF -DESPEAK_BUILD_MANPAGES=OFF"
cmake -S out/src/espeak-ng -B out/espeak-host -G Ninja -DCMAKE_BUILD_TYPE=Release $E > out/espeak-host.log
cmake --build out/espeak-host -j "$JOBS" >> out/espeak-host.log
cmake -S out/src/espeak-ng -B out/espeak-arm -G Ninja -DCMAKE_BUILD_TYPE=Release $CROSS $E > out/espeak-arm.log
cmake --build out/espeak-arm -j "$JOBS" --target espeak-ng-bin >> out/espeak-arm.log
aarch64-linux-gnu-strip -o $STAGE/bin/espeak-ng out/espeak-arm/src/espeak-ng
mkdir -p $STAGE/espeak-ng-data
(cd out/espeak-host/espeak-ng-data && cp -r phondata phondata-manifest phonindex phontab intonations \
	en_dict lang voices "$P/$STAGE/espeak-ng-data/")

# licenses, and where the sources are
mkdir -p $STAGE/licenses
cp out/src/whisper.cpp/LICENSE $STAGE/licenses/whisper.cpp-MIT
cp out/src/espeak-ng/COPYING $STAGE/licenses/espeak-ng-GPL-3.0
cp out/src/tinyalsa/NOTICE $STAGE/licenses/tinyalsa-BSD-3-Clause
cat > $STAGE/README.txt <<'EOT'
Voice pack for nexus5x-local-llm (https://github.com/kaiwalyakhasnis/nexus5x-local-llm).
The PC app sends it to the phone after each boot; the phone unpacks it into RAM.
Built by scripts/build-voice.sh (static aarch64) from:
  bin/whisper-cli, whisper.bin  whisper.cpp v1.9.5 (MIT), model ggml-tiny.en-q8_0 (OpenAI Whisper, MIT)
                                https://github.com/ggml-org/whisper.cpp/tree/v1.9.5
  bin/espeak-ng, espeak-ng-data eSpeak NG 1.52.0 (GPL-3.0-or-later)
                                https://github.com/espeak-ng/espeak-ng/tree/1.52.0
  bin/tinymix, tinycap, tinyplay  tinyalsa v2.0.0 (BSD-3-Clause) + the repo's patches/tinyalsa
                                https://github.com/tinyalsa/tinyalsa/tree/v2.0.0
EOT
tar -C $STAGE -cf out/voice-pack.tar .
ls -la out/voice-pack.tar

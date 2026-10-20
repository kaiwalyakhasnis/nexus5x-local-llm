# llm-ui: static aarch64 build with the stock Linux cross compiler and glibc
# (apt install g++-aarch64-linux-gnu). Only plain Linux interfaces are used.
# The logos are baked into a generated header (python3-pil). The voice chat
# uses tinyalsa (out/src/tinyalsa from scripts/fetch-sources.sh, with
# patches/tinyalsa applied).
CROSS    ?= aarch64-linux-gnu-
OUT      ?= ../out/ui
TINYALSA ?= ../out/src/tinyalsa
CXX      := $(CROSS)g++
CC       := $(CROSS)gcc

CXXFLAGS := -O2 -std=c++17 -Wall -Iminui/include -Iminui -I$(OUT)/gen -I$(TINYALSA)/include -DOVERSCAN_PERCENT=0
CFLAGS   := -O2 -I$(TINYALSA)/include
LDFLAGS  := -static -s -pthread

SRCS := main.cpp voice.cpp minui/graphics.cpp minui/graphics_fbdev.cpp minui/events.cpp
TSRCS := pcm.c pcm_hw.c pcm_plugin.c snd_card_plugin.c mixer.c mixer_hw.c mixer_plugin.c limits.c
OBJS := $(SRCS:%.cpp=$(OUT)/%.o) $(TSRCS:%.c=$(OUT)/tinyalsa/%.o)
LOGOS := $(OUT)/gen/logos.h

$(OUT)/llm-ui: $(OBJS)
	$(CXX) $(LDFLAGS) -o $@ $(OBJS) -ldl

$(OUT)/main.o: $(LOGOS)

$(LOGOS): logo2h.py $(wildcard assets/*.png)
	@mkdir -p $(dir $@)
	python3 logo2h.py $@ $(foreach f,$(wildcard assets/*.png),$(basename $(notdir $(f)))=$(f))

$(OUT)/%.o: %.cpp $(wildcard *.h minui/*.h minui/include/minui/*.h)
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c -o $@ $<

$(OUT)/tinyalsa/%.o: $(TINYALSA)/src/%.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c -o $@ $<

# Host preview: renders the screen to a PPM (see preview.cpp).
$(OUT)/preview: preview.cpp main.cpp minui/graphics.cpp minui/graphics_fbdev.h $(LOGOS)
	@mkdir -p $(OUT)
	g++ -O1 -std=c++17 -Iminui/include -Iminui -I$(OUT)/gen -DOVERSCAN_PERCENT=0 -o $@ preview.cpp minui/graphics.cpp -pthread

clean:
	rm -rf $(OUT)
.PHONY: clean

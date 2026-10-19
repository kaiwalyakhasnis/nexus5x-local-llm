# llm-ui: static aarch64 build with the stock Linux cross compiler and glibc
# (apt install g++-aarch64-linux-gnu). Only plain Linux interfaces are used.
# The logos are baked into a generated header (python3-pil).
CROSS    ?= aarch64-linux-gnu-
OUT      ?= ../out/ui
CXX      := $(CROSS)g++

CXXFLAGS := -O2 -std=c++17 -Wall -Iminui/include -Iminui -I$(OUT)/gen -DOVERSCAN_PERCENT=0
LDFLAGS  := -static -s -pthread

SRCS := main.cpp minui/graphics.cpp minui/graphics_fbdev.cpp minui/events.cpp
OBJS := $(SRCS:%.cpp=$(OUT)/%.o)
LOGOS := $(OUT)/gen/logos.h

$(OUT)/llm-ui: $(OBJS)
	$(CXX) $(LDFLAGS) -o $@ $(OBJS)

$(OUT)/main.o: $(LOGOS)

$(LOGOS): logo2h.py $(wildcard assets/*.png)
	@mkdir -p $(dir $@)
	python3 logo2h.py $@ $(foreach f,$(wildcard assets/*.png),$(basename $(notdir $(f)))=$(f))

$(OUT)/%.o: %.cpp $(wildcard *.h minui/*.h minui/include/minui/*.h)
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c -o $@ $<

# Host preview: renders the screen to a PPM (see preview.cpp).
$(OUT)/preview: preview.cpp main.cpp minui/graphics.cpp minui/graphics_fbdev.h $(LOGOS)
	@mkdir -p $(OUT)
	g++ -O1 -std=c++17 -Iminui/include -Iminui -I$(OUT)/gen -DOVERSCAN_PERCENT=0 -o $@ preview.cpp minui/graphics.cpp -pthread

clean:
	rm -rf $(OUT)
.PHONY: clean

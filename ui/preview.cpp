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
  if (state == "none") {
    s.model = "";
    s.hint = "load one from the Nexus 5X LLM app on the PC";
  }
  draw(s);
  return 0;
}

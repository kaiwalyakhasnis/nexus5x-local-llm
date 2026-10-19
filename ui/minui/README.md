# hike-buddy/minui/ — graphics and input library

A copy of `bootable/recovery/minui` from the LineageOS 15.1 tree: the same
tiny library Android recovery uses to draw on the framebuffer and read
input devices. Vendored so hike-buddy builds without the Android build
system.

| File | What it does |
|---|---|
| `graphics.cpp` / `graphics.h` | Drawing: colour, fill, blit, text, flip. |
| `graphics_fbdev.*` | fbdev backend (`/dev/fb0`), the only backend kept. |
| `events.cpp` | Input: opens `/dev/input/event*`, epoll loop, callbacks. |
| `font_10x18.h` | The compiled-in 10x18 ASCII font (no font files on the phone). |
| `include/minui/minui.h` | Public API. |

## Changes from upstream (marked `// hike-buddy:` in the code)

- fbdev only (no ADF/DRM backends), compiled-in font only, no PNG loading.
- `gr_text_scaled()`: integer-scaled text, the 10x18 font is tiny at 1080p.
- `gr_alloc_like_fb()` / `gr_set_target()`: draw into off-screen surfaces
  (page slide animation, cached map render).
- `EVIOCSSUSPENDBLOCK` + `EPOLLWAKEUP` on input fds, so a key press can't be
  lost to suspend.
- Touchscreens are skipped unless `ev_init(cb, true)` asks for them.
- Bug fix: upstream `std::move`d the callback into the first device, leaving
  it empty for the others.
- Opens `/dev/fb0` (devtmpfs) before `/dev/graphics/fb0` (Android's ueventd).

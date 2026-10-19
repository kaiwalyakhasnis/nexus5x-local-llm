/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _MINUI_H_
#define _MINUI_H_

#include <stdint.h>
#include <sys/types.h>

#include <functional>
#include <string>

//
// Graphics.
//

struct GRSurface {
    int width;
    int height;
    int row_bytes;
    int pixel_bytes;
    unsigned char* data;
};

struct GRFont {
    GRSurface* texture;
    int char_width;
    int char_height;
};

int gr_init();
void gr_exit();

int gr_fb_width();
int gr_fb_height();

void gr_flip();
void gr_fb_blank(bool blank);

void gr_clear();  // clear entire surface to current color
void gr_color(unsigned char r, unsigned char g, unsigned char b, unsigned char a);
void gr_fill(int x1, int y1, int x2, int y2);

void gr_texticon(int x, int y, GRSurface* icon);

const GRFont* gr_sys_font();
const GRFont* gr_menu_font();
void gr_text(const GRFont* font, int x, int y, const char *s, bool bold);
void gr_text_scaled(const GRFont* font, int x, int y, const char *s, int scale);
int gr_measure(const GRFont* font, const char *s);
void gr_font_size(const GRFont* font, int *x, int *y);

void gr_blit(GRSurface* source, int sx, int sy, int w, int h, int dx, int dy);

// hike-buddy: draw into an off-screen surface instead of the framebuffer.
// gr_set_target() returns the previous target; restore it before gr_flip().
GRSurface* gr_alloc_like_fb();
GRSurface* gr_set_target(GRSurface* s);
unsigned int gr_get_width(GRSurface* surface);
unsigned int gr_get_height(GRSurface* surface);

//
// Input events.
//

struct input_event;

using ev_callback = std::function<int(int fd, uint32_t epevents)>;
using ev_set_key_callback = std::function<int(int code, int value)>;

int ev_init(ev_callback input_cb, bool allow_touch_inputs = false);
void ev_exit();
int ev_add_fd(int fd, ev_callback cb);
void ev_iterate_available_keys(const std::function<void(int)>& f);
void ev_iterate_touch_inputs(const std::function<void(int)>& touch_device_detected,
                             const std::function<void(int)>& key_detected);
int ev_sync_key_state(const ev_set_key_callback& set_key_cb);

// 'timeout' has the same semantics as poll(2).
//    0 : don't block
//  < 0 : block forever
//  > 0 : block for 'timeout' milliseconds
int ev_wait(int timeout);

int ev_get_input(int fd, uint32_t epevents, input_event* ev);
void ev_dispatch();
int ev_get_epollfd();

#endif

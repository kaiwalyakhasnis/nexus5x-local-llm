#!/usr/bin/env python3
"""Save what the phone's screen shows as a PNG (reads /dev/fb0 over a one-shot nc).
Usage: scripts/screencap.py [out.png]       (needs python3-pil)"""
import os, socket, subprocess, sys, time
from PIL import Image

HOST = os.environ.get("PHONE", "172.16.42.1")
P = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else "screen.png"


def phone(cmd):
    return subprocess.run([os.path.join(P, "phone.py"), cmd], capture_output=True, text=True).stdout


info = phone("cat /sys/class/graphics/fb0/stride /sys/class/graphics/fb0/virtual_size "
             "/sys/class/graphics/fb0/pan")
nums = [l for l in info.splitlines() if l and (l[0].isdigit())]
stride = int(nums[0])
vw, vh = map(int, nums[1].split(","))
pan_y = int(nums[2].split(",")[1]) if len(nums) > 2 else 0
W, H = vw, 1920 if vh >= 3840 else vh

phone("setsid sh -c 'nc -l -p 9002 < /dev/fb0' </dev/null >/dev/null 2>&1 & true")
time.sleep(1)
s = socket.create_connection((HOST, 9002), timeout=10)
data = bytearray()
while True:
    d = s.recv(1 << 20)
    if not d:
        break
    data += d
s.close()
frame = bytes(data[pan_y * stride:(pan_y + H) * stride])
img = Image.frombytes("RGBX", (stride // 4, H), frame).convert("RGB").crop((0, 0, W, H))
img.save(out)
print(f"{out}: {W}x{H} (pan y={pan_y})")

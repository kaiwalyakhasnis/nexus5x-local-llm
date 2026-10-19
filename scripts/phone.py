#!/usr/bin/env python3
"""Run a shell command on the phone over telnet (172.16.42.1) and print its output.
Usage: scripts/phone.py 'dmesg | tail'     T=60 scripts/phone.py 'llm bench'  (timeout, s)"""
import os, socket, sys, time

HOST = os.environ.get("PHONE", "172.16.42.1")
cmd = " ".join(sys.argv[1:]) or "uname -a"
END = "__LLM_END__"
s = socket.create_connection((HOST, 23), timeout=10)
# the echoed command line reads __LLM''_END__, so only the real marker matches END
s.sendall(f"stty -echo; {cmd}; echo __LLM''_END__\n".encode())
buf = b""
deadline = time.time() + float(os.environ.get("T", "20"))
while time.time() < deadline and END.encode() not in buf:
    try:
        d = s.recv(65536)
    except socket.timeout:
        continue
    if not d:
        break
    while b"\xff" in d:  # drop telnet option negotiation (IAC x y)
        i = d.index(b"\xff")
        d = d[:i] + d[i + 3:]
    buf += d
s.close()
out = buf.decode(errors="replace").replace("\r", "").split(END, 1)[0]
print(out.split("_END__", 1)[-1].lstrip("\n"), end="")

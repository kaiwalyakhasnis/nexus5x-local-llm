#!/usr/bin/env python3
"""Chat with the phone's llama-server (OpenAI-style /v1/chat/completions), streaming.
Usage: scripts/chat.py                 interactive
       scripts/chat.py 'one question'  single answer, then speed stats"""
import json, os, sys, time, urllib.request

URL = f"http://{os.environ.get('PHONE', '172.16.42.1')}:{os.environ.get('PORT', '8080')}/v1/chat/completions"


def ask(history):
    body = json.dumps({"messages": history, "stream": True, "temperature": 0.7,
                       "max_tokens": int(os.environ.get("MAX", "256"))}).encode()
    req = urllib.request.Request(URL, body, {"Content-Type": "application/json"})
    text, t0, first, n = "", time.time(), None, 0
    with urllib.request.urlopen(req, timeout=600) as r:
        for line in r:
            line = line.decode().strip()
            if not line.startswith("data: ") or line == "data: [DONE]":
                continue
            d = json.loads(line[6:])
            delta = d["choices"][0]["delta"].get("content") or ""
            if delta:
                first = first or time.time()
                n += 1
                text += delta
                print(delta, end="", flush=True)
    t1 = time.time()
    if first:
        rate = (n - 1) / (t1 - first) if n > 1 and t1 > first else 0
        print(f"\n[{n} tokens, first after {first - t0:.1f}s, {rate:.1f} tok/s]")
    return text


history = []
if len(sys.argv) > 1:
    ask([{"role": "user", "content": " ".join(sys.argv[1:])}])
    sys.exit()
while True:
    try:
        q = input("you> ")
    except EOFError:
        break
    if not q.strip():
        continue
    history.append({"role": "user", "content": q})
    print("phone> ", end="")
    history.append({"role": "assistant", "content": ask(history)})

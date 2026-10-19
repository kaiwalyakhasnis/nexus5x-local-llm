#!/usr/bin/env python3
"""Nexus 5X LLM: the PC-side app for the phone (a small local web page).

  - shows what the phone is running and opens its web chat
  - lists the Ollama models on this PC and loads one onto the phone (port 9000)
  - downloads more models through Ollama (starts `ollama serve` if needed)
  - shares the chat with the Wi-Fi network (this PC's port 8080 -> phone), with a QR code

Start it from the app menu ("Nexus 5X LLM", see --install) or run it directly;
it opens http://localhost:8765 in the browser. Python standard library only.
  nexus5x-llm.py [--no-browser]     run (a second start just opens the page)
  nexus5x-llm.py --install          add it to the desktop's app menu
"""
import glob, json, os, shutil, socket, struct, subprocess, sys, threading, time
import urllib.error, urllib.request, webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "ui", "assets")  # the same logos the phone shows
PHONE = os.environ.get("PHONE", "172.16.42.1")
APP_PORT = int(os.environ.get("APP_PORT", "8765"))
SHARE_PORT = 8080
OLLAMA = "http://127.0.0.1:11434"
OLLAMA_BIN = shutil.which("ollama") or os.path.expanduser("~/.local/ollama/bin/ollama")
MODELS = os.environ.get("OLLAMA_MODELS", os.path.expanduser("~/.ollama/models"))
CONFIG = os.path.expanduser("~/.config/nexus5x-llm.json")
# The phone has 1.8 GB of RAM for the model file (tmpfs), its cache and the system.
MAX_MODEL = 1_250_000_000

# Known to fit; sizes as in the Ollama library.
SUGGESTED = [
    ("gemma3:270m", 292, "Google Gemma 3, tiny and fast; weak at general chat"),
    ("gemma3:1b", 806, "Google Gemma 3, best small Google chat model"),
    ("qwen2.5-coder:0.5b", 398, "Qwen coder, fast code snippets"),
    ("qwen2.5-coder:1.5b", 986, "Qwen coder, better code, slower"),
    ("qwen3:0.6b", 523, "Qwen 3, thinks before answering (/no_think to skip)"),
    ("llama3.2:1b-instruct-q4_K_M", 808, "Meta Llama 3.2, general chat"),
    ("smollm2:360m-instruct-q8_0", 386, "Hugging Face SmolLM2, tiny general chat"),
]

lock = threading.Lock()
state = {
    "phone": {"online": False, "server": "offline", "model": ""},
    "upload": None,   # {name, display, sent, total, phase: sending|loading|ready|error, error}
    "pull": None,     # {name, status, completed, total, phase: pulling|done|error, error, load}
    "share": {"on": False, "urls": [], "error": ""},
}


def load_config():
    try:
        return json.load(open(CONFIG))
    except (OSError, ValueError):
        return {}


def save_config(**kw):
    c = load_config()
    c.update(kw)
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    json.dump(c, open(CONFIG, "w"))


# ------------------------------------------------------------------ models on this PC

def gguf_display_name(path):
    """Same rule as tools/gguf-name.c on the phone, so names match."""
    want = {"general.name": "", "general.basename": "", "general.size_label": ""}
    sizes = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
    try:
        with open(path, "rb") as f:
            if f.read(4) != b"GGUF" or struct.unpack("<I", f.read(4))[0] < 2:
                return "model"
            _, nkv = struct.unpack("<QQ", f.read(16))

            def rstr():
                n, = struct.unpack("<Q", f.read(8))
                return f.read(n).decode("utf-8", "replace")

            for _ in range(nkv):
                k = rstr()
                t, = struct.unpack("<I", f.read(4))
                if t == 8:
                    v = rstr()
                    if k in want:
                        want[k] = v
                elif t == 9:
                    at, n = struct.unpack("<IQ", f.read(12))
                    if at == 8:
                        for _ in range(n):
                            f.seek(struct.unpack("<Q", f.read(8))[0], 1)
                    else:
                        f.seek(sizes[at] * n, 1)
                else:
                    f.seek(sizes[t], 1)
                if all(want.values()):
                    break
    except (OSError, KeyError, struct.error):
        return "model"
    name, base, size = want["general.name"], want["general.basename"], want["general.size_label"]
    if base and (not name or len(name) > 28):
        return base + (" " + size if size else "")
    return name or "model"


_names = {}  # blob path -> display name


def local_models():
    out = []
    root = os.path.join(MODELS, "manifests")
    for man in glob.glob(os.path.join(root, "*", "*", "*", "*")):
        host, ns, model, tag = man[len(root) + 1:].split(os.sep)
        try:
            layers = json.load(open(man))["layers"]
            layer = [l for l in layers if l["mediaType"].endswith(".model")][0]
        except (OSError, ValueError, KeyError, IndexError):
            continue
        blob = os.path.join(MODELS, "blobs", layer["digest"].replace(":", "-"))
        if not os.path.exists(blob):
            continue
        if blob not in _names:
            _names[blob] = gguf_display_name(blob)
        name = model + ":" + tag if (host, ns) == ("registry.ollama.ai", "library") else f"{ns}/{model}:{tag}"
        out.append({"name": name, "display": _names[blob], "size": layer["size"], "blob": blob,
                    "fits": layer["size"] <= MAX_MODEL,
                    "vision": any(l["mediaType"].endswith(".projector") for l in layers)})
    return sorted(out, key=lambda m: m["name"])


# ------------------------------------------------------------------ the phone

def http_get(url, timeout=1.5):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.status, r.read()


def poll_phone():
    while True:
        p = {"online": False, "server": "offline", "model": ""}
        try:
            code, body = http_get(f"http://{PHONE}:8080/health")
            p.update(online=True, server="ready" if code == 200 else "loading")
            _, body = http_get(f"http://{PHONE}:8080/v1/models")
            p["model"] = json.loads(body)["data"][0]["id"]
        except urllib.error.HTTPError as e:  # 503 while the model loads
            p.update(online=True, server="loading" if e.code == 503 else "error")
        except (urllib.error.URLError, OSError, ValueError, KeyError, IndexError) as e:
            reason = getattr(e, "reason", e)
            if isinstance(reason, ConnectionRefusedError):  # phone is there, no server: no model
                p.update(online=True, server="none")
        with lock:
            state["phone"] = p
        time.sleep(1.5)


def upload(m):
    up = {"name": m["name"], "display": m["display"], "sent": 0, "total": m["size"], "phase": "sending", "error": ""}
    with lock:
        state["upload"] = up
    try:
        with socket.create_connection((PHONE, 9000), timeout=10) as s, open(m["blob"], "rb") as f:
            while True:
                d = f.read(1 << 20)
                if not d:
                    break
                s.sendall(d)
                up["sent"] += len(d)
            s.shutdown(socket.SHUT_WR)
            s.settimeout(30)
            while s.recv(4096):  # the phone closes once it has everything
                pass
        up["phase"] = "loading"
        deadline = time.time() + 240
        while time.time() < deadline:
            time.sleep(1.5)
            with lock:
                p = dict(state["phone"])
            if p["server"] == "ready" and p["model"] == m["display"]:
                up["phase"] = "ready"
                return
        up.update(phase="error", error="the phone did not finish loading it (too big, or not supported)")
    except OSError as e:
        up.update(phase="error", error=f"could not send to the phone: {e}")


# ------------------------------------------------------------------ Ollama downloads

def ollama_up():
    try:
        http_get(OLLAMA + "/api/version", 1)
        return True
    except (urllib.error.URLError, OSError):
        return False


def pull(name, then_load):
    pl = {"name": name, "status": "starting", "completed": 0, "total": 0, "phase": "pulling", "error": "",
          "load": then_load}
    with lock:
        state["pull"] = pl
    try:
        if not ollama_up():
            pl["status"] = "starting ollama"
            subprocess.Popen([OLLAMA_BIN, "serve"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
            for _ in range(40):
                time.sleep(0.5)
                if ollama_up():
                    break
        req = urllib.request.Request(OLLAMA + "/api/pull", json.dumps({"model": name}).encode(),
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            for line in r:
                ev = json.loads(line)
                if "error" in ev:
                    raise RuntimeError(ev["error"])
                pl["status"] = ev.get("status", "")
                if "total" in ev:
                    pl["total"], pl["completed"] = ev["total"], ev.get("completed", 0)
        pl["phase"] = "done"
    except (OSError, ValueError, RuntimeError) as e:
        pl.update(phase="error", error=str(e))
        return
    if then_load:
        m = next((m for m in local_models() if m["name"] in (name, name + ":latest")), None)
        if m and m["fits"]:
            upload(m)


# ------------------------------------------------------------------ Wi-Fi sharing

share_sock = None


def lan_addresses():
    out = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        dev, addr = line.split()[1], line.split()[3].split("/")[0]
        # skip loopback, containers and the USB link to the phone
        if dev != "lo" and not dev.startswith(("docker", "br-", "veth")) and not addr.startswith("172.16.42."):
            yield addr


def pipe(src, dst):
    try:
        while True:
            d = src.recv(65536)
            if not d:
                break
            dst.sendall(d)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def forward(client):
    try:
        phone = socket.create_connection((PHONE, 8080), timeout=5)
    except OSError:
        client.close()
        return
    phone.settimeout(None)
    t = threading.Thread(target=pipe, args=(phone, client), daemon=True)
    t.start()
    pipe(client, phone)
    t.join()
    client.close()
    phone.close()


def share(on):
    global share_sock
    with lock:
        if share_sock:
            share_sock.close()
            share_sock = None
        state["share"] = {"on": False, "urls": [], "error": ""}
        if not on:
            return
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("0.0.0.0", SHARE_PORT))
        except OSError as e:
            state["share"]["error"] = f"port {SHARE_PORT} is busy on this PC ({e.strerror})"
            return
        s.listen(64)
        share_sock = s
        state["share"] = {"on": True, "urls": [f"http://{a}:{SHARE_PORT}" for a in lan_addresses()], "error": ""}

    def accept_loop(s):
        while True:
            try:
                c, _ = s.accept()
            except OSError:
                return  # closed: sharing turned off
            threading.Thread(target=forward, args=(c,), daemon=True).start()

    threading.Thread(target=accept_loop, args=(s,), daemon=True).start()


# ------------------------------------------------------------------ web page

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self.send(200, open(os.path.join(HERE, "index.html"), "rb").read(), "text/html; charset=utf-8")
        elif self.path.startswith("/logo/"):
            f = os.path.join(ASSETS, os.path.basename(self.path[6:]) + ".png")
            if os.path.exists(f):
                self.send(200, open(f, "rb").read(), "image/png")
            else:
                self.send(404, {"error": "no such logo"})
        elif self.path == "/api/state":
            models = [{k: v for k, v in m.items() if k != "blob"} for m in local_models()]
            have = {m["name"] for m in models}
            with lock:
                s = json.loads(json.dumps(state))
            s.update(models=models, phone_ip=PHONE, max_model=MAX_MODEL,
                     suggested=[{"name": n, "size": mb * 1_000_000, "about": a}
                                for n, mb, a in SUGGESTED if n not in have])
            self.send(200, s)
        else:
            self.send(404, {"error": "not found"})

    def do_POST(self):
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError:
            return self.send(400, {"error": "bad request"})
        busy = lambda key, phases: state[key] and state[key]["phase"] in phases
        if self.path == "/api/load":
            m = next((m for m in local_models() if m["name"] == req.get("name")), None)
            if not m:
                return self.send(404, {"error": "no such model on this PC"})
            if not m["fits"]:
                return self.send(400, {"error": "too big for the phone"})
            if busy("upload", ("sending", "loading")):
                return self.send(409, {"error": "already loading a model"})
            threading.Thread(target=upload, args=(m,), daemon=True).start()
        elif self.path == "/api/pull":
            name = (req.get("name") or "").strip()
            if not name or busy("pull", ("pulling",)):
                return self.send(409, {"error": "a download is already running"})
            threading.Thread(target=pull, args=(name, bool(req.get("load"))), daemon=True).start()
        elif self.path == "/api/share":
            share(bool(req.get("on")))
            save_config(share=bool(req.get("on")))
        elif self.path == "/api/quit":
            self.send(200, {"ok": True})
            threading.Thread(target=lambda: (time.sleep(0.3), os._exit(0))).start()
            return
        else:
            return self.send(404, {"error": "not found"})
        self.send(200, {"ok": True})


def install():
    d = os.path.expanduser("~/.local/share/applications")
    os.makedirs(d, exist_ok=True)
    f = os.path.join(d, "nexus5x-llm.desktop")
    with open(f, "w") as o:
        o.write("[Desktop Entry]\nType=Application\nName=Nexus 5X LLM\n"
                "Comment=Load models onto the Nexus 5X and chat with them\n"
                f"Exec=python3 {os.path.abspath(__file__)}\n"
                f"Icon={os.path.abspath(os.path.join(ASSETS, 'ollama.png'))}\n"
                "Terminal=false\nCategories=Utility;Development;\n")
    print("added to the app menu:", f)


def main():
    if "--install" in sys.argv:
        return install()
    url = f"http://localhost:{APP_PORT}"
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", APP_PORT), Handler)
    except OSError:  # already running: just show it
        webbrowser.open(url)
        return
    threading.Thread(target=poll_phone, daemon=True).start()
    if load_config().get("share"):
        share(True)
    print(f"Nexus 5X LLM: {url}", flush=True)
    if "--no-browser" not in sys.argv:
        threading.Thread(target=lambda: (time.sleep(0.5), webbrowser.open(url)), daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
A stand-in ComfyUI: the HTTP API (/prompt, /history, /view, /queue, /interrupt,
/free, /upload/image, /system_stats) and the /ws progress socket, sending the
same message types ComfyUI does (status, execution_start, executing, progress,
executed, execution_interrupted). Steps take STEP_SECONDS each.
    python3 fake_comfy.py <port> [steps] [step_seconds]
Records every request in FAKE_COMFY_LOG (JSON lines).
"""
import base64, hashlib, json, os, socket, struct, sys, threading, time, uuid, zlib

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8188
STEPS = int(sys.argv[2]) if len(sys.argv) > 2 else 8
STEP_SECONDS = float(sys.argv[3]) if len(sys.argv) > 3 else 0.15
LOG = os.environ.get("FAKE_COMFY_LOG", "fake_comfy.jsonl")
CLIENTS = {}          # client_id -> socket
HISTORY = {}
INTERRUPT = threading.Event()
RUNNING = set()
LOCK = threading.Lock()


def log(rec):
    with LOCK, open(LOG, "a") as f:
        f.write(json.dumps(rec) + "\n")


def png(w=64, h=64):
    rows = b"".join(b"\x00" + b"".join(bytes([(x * 3) % 256, (y * 3) % 256, 200]) for x in range(w)) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def ws_send(sock, obj):
    data = json.dumps(obj).encode()
    n = len(data)
    head = bytes([0x81]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n))
    try:
        sock.sendall(head + data)
    except OSError:
        pass


def run_prompt(pid, client_id, prompt):
    sock = CLIENTS.get(client_id)
    send = (lambda o: ws_send(sock, o)) if sock else (lambda o: None)
    INTERRUPT.clear()
    RUNNING.add(pid)
    send({"type": "status", "data": {"status": {"exec_info": {"queue_remaining": 1}}}})
    send({"type": "execution_start", "data": {"prompt_id": pid}})
    sampler = next((k for k, v in prompt.items() if v.get("class_type", "").startswith("KSampler")), None)
    for nid, node in prompt.items():
        send({"type": "executing", "data": {"node": nid, "prompt_id": pid}})
        if nid == sampler:
            for i in range(1, STEPS + 1):
                if INTERRUPT.is_set():
                    send({"type": "execution_interrupted", "data": {"prompt_id": pid}})
                    log({"interrupted": pid})
                    RUNNING.discard(pid)
                    return
                time.sleep(STEP_SECONDS)
                send({"type": "progress", "data": {"value": i, "max": STEPS, "prompt_id": pid, "node": nid}})
    RUNNING.discard(pid)
    HISTORY[pid] = {"outputs": {"9": {"images": [{"filename": f"{pid}.png", "subfolder": "", "type": "output"}]}}}
    send({"type": "executing", "data": {"node": None, "prompt_id": pid}})


def handle(conn):
    try:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = conn.recv(65536)
            if not chunk:
                return
            data += chunk
        head, _, rest = data.partition(b"\r\n\r\n")
        lines = head.decode(errors="replace").split("\r\n")
        method, path, _ = lines[0].split(" ", 2)
        hdrs = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
        if hdrs.get("upgrade", "").lower() == "websocket":
            key = hdrs["sec-websocket-key"]
            acc = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            conn.sendall(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {acc}\r\n\r\n".encode())
            cid = path.split("clientId=")[-1]
            CLIENTS[cid] = conn
            ws_send(conn, {"type": "status", "data": {"status": {"exec_info": {"queue_remaining": 0}}, "sid": cid}})
            while True:
                if not conn.recv(1024):
                    return
        length = int(hdrs.get("content-length", "0"))
        while len(rest) < length:
            rest += conn.recv(65536)
        body = rest[:length]
        log({"method": method, "path": path.split("?")[0], "bytes": len(body)})
        status, out, ctype = 200, b"{}", "application/json"
        if path.startswith("/system_stats"):
            out = json.dumps({"system": {"comfyui_version": "fake-0.3"}, "devices": []}).encode()
        elif path.startswith("/prompt") and method == "POST":
            req = json.loads(body)
            pid = uuid.uuid4().hex
            log({"prompt": req["prompt"]})
            threading.Thread(target=run_prompt, args=(pid, req.get("client_id"), req["prompt"]), daemon=True).start()
            out = json.dumps({"prompt_id": pid, "number": 1}).encode()
        elif path.startswith("/history/"):
            pid = path.split("/history/")[1]
            out = json.dumps({pid: HISTORY[pid]} if pid in HISTORY else {}).encode()
        elif path.startswith("/view"):
            out, ctype = png(), "image/png"
        elif path.startswith("/interrupt"):
            INTERRUPT.set()
        elif path.startswith("/queue"):
            out = json.dumps({"queue_running": [[1, p, {}, {}, []] for p in RUNNING], "queue_pending": []}).encode()
        elif path.startswith("/upload/image"):
            out = json.dumps({"name": "uploaded.png", "subfolder": "", "type": "input"}).encode()
        conn.sendall(f"HTTP/1.1 {status} OK\r\nContent-Type: {ctype}\r\nContent-Length: {len(out)}\r\nConnection: close\r\n\r\n".encode() + out)
    except Exception:
        pass
    finally:
        try:
            if conn not in CLIENTS.values():
                conn.close()
        except Exception:
            pass


def main():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", PORT))
    s.listen(50)
    print("fake comfy ready", flush=True)
    while True:
        c, _ = s.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()

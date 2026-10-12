"""
Image generation and editing through Glen's own ComfyUI (Qwen-Image).

No node names are guessed. Gemma uses the workflow Glen exported from his
working ComfyUI ("Workflow → Export (API)"), saved as:
    workflows/image_generate.json   text → image
    workflows/image_edit.json       image + instruction → image   (optional)
and finds the places to put the prompt, size, seed and source image by
following that workflow's own links: the text-encoder node feeding the
sampler's "positive" input gets the prompt, the latent-image node feeding the
sampler gets the size, the sampler gets the seed, a LoadImage node gets the
source picture. If something cannot be identified unambiguously, the job
fails with a clear message instead of guessing; workflows/<name>.map.json can
then say exactly which node/input to use: {"prompt": ["6", "text"], ...}.

Progress is ComfyUI's own: queue position, model loading ("execution_start" /
"executing"), the sampler's step counter ("progress" value/max) and the save
node finishing. The percentage is never invented.

GPU: before a job, Gemma asks Ollama to release the chat model if ComfyUI and
the chat model cannot both fit (GEMMA_IMAGE_FREE_LLM=auto|always|never); after
the job she asks ComfyUI to free its memory, so ordinary chat gets the GPU
back. Each transition is in Diagnostics.
"""
from __future__ import annotations

import base64
import copy
import hashlib
import json
import os
import random
import socket
import struct
import subprocess
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode, urlparse

import requests

from .. import artifacts, jobs, models, observe, store
from ..tools import Tool, ToolFailure, register
from . import Plugin, add

WORDS = ("image", "picture", "photo", "draw", "paint", "generate", "illustration", "render", "art", "make me a", "edit this",
         "remove the", "make the sky", "change this", "logo", "wallpaper", "poster", "sketch")
COMFY = os.environ.get("GEMMA_COMFY_URL", "http://127.0.0.1:8188").rstrip("/")
TEXT_ENCODERS = ("CLIPTextEncode", "TextEncodeQwenImageEdit", "TextEncodeQwenImageEditPlus", "CLIPTextEncodeSDXL", "CLIPTextEncodeFlux")
LATENTS = ("EmptyLatentImage", "EmptySD3LatentImage", "EmptyHunyuanLatentVideo", "EmptyQwenImageLatent")
SAMPLERS = ("KSampler", "KSamplerAdvanced", "SamplerCustom", "SamplerCustomAdvanced")


def _wf_dir() -> Path:
    p = store.app_dir() / "workflows"
    p.mkdir(exist_ok=True)
    return p


# ------------------------------------------------------------- tiny websocket client (RFC 6455, text frames)

class WebSocket:
    def __init__(self, url: str, timeout: float = 30):
        u = urlparse(url)
        self.sock = socket.create_connection((u.hostname, u.port or 80), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        path = u.path + ("?" + u.query if u.query else "")
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {u.hostname}:{u.port or 80}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("websocket handshake failed")
            head += chunk
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise ConnectionError("websocket refused: " + head.split(b"\r\n", 1)[0].decode(errors="replace"))
        self.buf = head.split(b"\r\n\r\n", 1)[1]

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("websocket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        """The next text message (binary preview frames are skipped). None on close."""
        while True:
            b1, b2 = self._read(2)
            op, ln = b1 & 0x0F, b2 & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", self._read(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if b2 & 0x80 else None
            data = self._read(ln)
            if mask:
                data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if op == 8:
                return None
            if op == 9:          # ping -> pong
                self._send(10, data)
                continue
            if op == 1:
                return data.decode("utf-8", "replace")

    def _send(self, op, data: bytes):
        mask = os.urandom(4)
        head = bytes([0x80 | op])
        n = len(data)
        head += bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n)
        self.sock.sendall(head + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(data)))

    def close(self):
        try:
            self._send(8, b"")
            self.sock.close()
        except Exception:
            pass


# ------------------------------------------------------------- workflow handling

def load_workflow(kind: str) -> tuple[dict, dict]:
    path = _wf_dir() / f"image_{kind}.json"
    if not path.exists():
        raise ToolFailure("not_connected", f"No ComfyUI workflow for image {kind} yet. In ComfyUI, open your working Qwen {kind} "
                                           f"workflow, choose Workflow → Export (API), and save it as {path}.")
    wf = json.loads(path.read_text())
    if "nodes" in wf and "links" in wf:
        raise ToolFailure("invalid_request", f"{path.name} is the editor format. Use Workflow → Export (API) instead.")
    mp = {}
    mpath = path.with_suffix(".map.json")
    if mpath.exists():
        mp = json.loads(mpath.read_text())
    return wf, mp


def _link(value):
    return value if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str) else None


def detect(wf: dict, mp: dict) -> dict:
    """Find where prompt/size/seed/image go by following the workflow's own links."""
    found = {k: tuple(v) for k, v in mp.items() if isinstance(v, list) and len(v) == 2}
    samplers = [nid for nid, n in wf.items() if n.get("class_type") in SAMPLERS]
    if len(samplers) != 1 and "seed" not in found:
        raise ToolFailure("invalid_request", f"The workflow has {len(samplers)} sampler nodes; add workflows/*.map.json to say which to use.")
    if samplers:
        s = wf[samplers[0]]
        inputs = s.get("inputs", {})
        if "seed" not in found:
            for name in ("seed", "noise_seed"):
                if name in inputs and not _link(inputs[name]):
                    found["seed"] = (samplers[0], name)
        if "steps" not in found and "steps" in inputs and not _link(inputs["steps"]):
            found["steps"] = (samplers[0], "steps")

        def trace(start, want_types, field_names):
            seen, frontier = set(), [start]
            while frontier:
                nid = frontier.pop()
                if nid in seen or nid not in wf:
                    continue
                seen.add(nid)
                node = wf[nid]
                if node.get("class_type") in want_types:
                    for f in field_names:
                        if f in node.get("inputs", {}) and not _link(node["inputs"][f]):
                            return nid, f
                for v in node.get("inputs", {}).values():
                    l = _link(v)
                    if l:
                        frontier.append(l[0])
            return None
        if "prompt" not in found and _link(inputs.get("positive")):
            hit = trace(_link(inputs["positive"])[0], TEXT_ENCODERS, ("text", "prompt", "text_g"))
            if hit:
                found["prompt"] = hit
        if "negative" not in found and _link(inputs.get("negative")):
            hit = trace(_link(inputs["negative"])[0], TEXT_ENCODERS, ("text", "prompt", "text_g"))
            if hit and hit != found.get("prompt"):
                found["negative"] = hit
        if "width" not in found and _link(inputs.get("latent_image")):
            hit = trace(_link(inputs["latent_image"])[0], LATENTS, ("width",))
            if hit:
                found["width"], found["height"] = hit, (hit[0], "height")
    if "image" not in found:
        loads = [nid for nid, n in wf.items() if n.get("class_type") == "LoadImage"]
        if len(loads) == 1:
            found["image"] = (loads[0], "image")
    if "prompt" not in found:
        encs = [nid for nid, n in wf.items() if n.get("class_type") in TEXT_ENCODERS]
        if len(encs) == 1:
            key = next((k for k in ("prompt", "text") if k in wf[encs[0]].get("inputs", {})), None)
            if key:
                found["prompt"] = (encs[0], key)
    if "prompt" not in found:
        raise ToolFailure("invalid_request", "Couldn't identify the prompt box in the workflow; add a .map.json naming it.")
    return found


def fill(wf: dict, places: dict, values: dict) -> dict:
    out = copy.deepcopy(wf)
    for key, value in values.items():
        if value is None or key not in places:
            continue
        nid, field = places[key]
        out[nid]["inputs"][field] = value
    return out


# ------------------------------------------------------------- ComfyUI client

def comfy(method, path, **kw):
    try:
        r = requests.request(method, COMFY + path, timeout=kw.pop("timeout", 30), **kw)
    except requests.RequestException:
        raise ToolFailure("unavailable", f"ComfyUI isn't reachable at {COMFY}. Is it running?")
    if r.status_code >= 400:
        raise ToolFailure("upstream_error", f"ComfyUI error {r.status_code}: {r.text[:400]}")
    return r


def vram() -> dict:
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=5).stdout.strip().split(",")
        return {"used_mb": int(out[0]), "total_mb": int(out[1])}
    except Exception:
        return {}


def _free_llm_if_needed(ctx):
    policy = os.environ.get("GEMMA_IMAGE_FREE_LLM", "auto").lower()
    before = vram()
    loaded = models.loaded()
    if policy == "never" or not loaded:
        return []
    need = policy == "always" or (before and before.get("total_mb", 0) - before.get("used_mb", 0) < 12000)
    released = []
    if need:
        for m in loaded:
            if models.unload(m["name"]):
                released.append(m["name"])
    observe.event("IMAGE", "GPU before image job", vram=before, ollama_models=[m["name"] for m in loaded], released=released,
                  vram_after_release=vram() if released else None)
    return released


def run_job(ctx: jobs.JobContext) -> dict:
    p = ctx.params
    kind = p.get("kind", "generate")
    wf, mp = load_workflow(kind)
    places = detect(wf, mp)
    values = {"prompt": p.get("prompt"), "negative": p.get("negative"), "width": p.get("width"), "height": p.get("height"),
              "seed": p.get("seed"), "steps": p.get("steps")}
    if kind == "edit":
        src = artifacts.read(p["source_artifact"]) if p.get("source_artifact") else None
        if src is None:
            raise RuntimeError("The source picture for the edit could not be found.")
        up = comfy("POST", "/upload/image", files={"image": (f"gemma-{ctx.id}.png", src)}, data={"overwrite": "true"})
        values["image"] = up.json().get("name")
        if "image" not in places:
            raise RuntimeError("The edit workflow has no single LoadImage node for the source picture.")
    graph = fill(wf, places, values)
    ctx.progress(0.0, "Queued", phase="queued")
    released = _free_llm_if_needed(ctx)
    client_id = uuid.uuid4().hex
    ws = None
    try:
        ws = WebSocket(COMFY.replace("http", "ws", 1) + f"/ws?clientId={client_id}")
    except Exception as e:
        observe.warn("IMAGE", "ComfyUI progress channel unavailable; percentage will be unknown", error=str(e))
    pid = comfy("POST", "/prompt", json={"prompt": graph, "client_id": client_id}).json().get("prompt_id")
    if not pid:
        raise RuntimeError("ComfyUI did not accept the workflow.")
    with store.lock(), store.db() as con:
        con.execute("UPDATE jobs SET params=? WHERE id=?", (store.jdump(dict(p, comfy_prompt_id=pid)), ctx.id))
    observe.event("IMAGE", "image job submitted to ComfyUI", prompt_id=pid, kind=kind, places={k: list(v) for k, v in places.items()})
    started = time.time()
    done = False
    try:
        while not done:
            if ctx.cancelled:
                _cancel_comfy(pid)
                raise jobs.Cancelled()
            if ws is None:
                time.sleep(1.0)
                h = comfy("GET", f"/history/{pid}").json()
                done = pid in h
                if not done:
                    ctx.progress(None, "Generating…", phase="generating")
                continue
            ws.sock.settimeout(2.0)
            try:
                msg = ws.recv()
            except socket.timeout:
                continue
            if msg is None:
                ws = None
                continue
            ev = json.loads(msg)
            t, d = ev.get("type"), ev.get("data") or {}
            if d.get("prompt_id") not in (None, pid):
                continue
            if t == "status":
                q = ((d.get("status") or {}).get("exec_info") or {}).get("queue_remaining")
                if q:
                    ctx.progress(0.0, f"Queued ({q} ahead)" if q > 1 else "Queued", phase="queued")
            elif t == "execution_start":
                ctx.progress(0.0, "Loading model…", phase="loading")
            elif t == "executing":
                node = d.get("node")
                if node is None:
                    done = True
                else:
                    cls = graph.get(node, {}).get("class_type", "")
                    if cls in SAMPLERS:
                        ctx.progress(0.0, "Generating… 0%", phase="generating")
                    elif "Save" in cls or "VAEDecode" in cls:
                        ctx.progress(0.99, "Finalising…", phase="finalising")
                    elif "Loader" in cls:
                        ctx.progress(0.0, "Loading model…", phase="loading")
            elif t == "progress":
                v, m = d.get("value", 0), max(1, d.get("max", 1))
                ctx.progress(v / m * 0.98, f"Generating… {round(v / m * 100)}%", phase="generating", step=v, steps=m)
            elif t == "execution_error":
                raise RuntimeError("ComfyUI reported an error: " + str(d.get("exception_message") or d)[:400])
            elif t == "execution_interrupted":
                raise jobs.Cancelled()
    finally:
        if ws:
            ws.close()
    ctx.progress(0.99, "Finalising…", phase="finalising")
    hist = comfy("GET", f"/history/{pid}").json().get(pid, {})
    images = [img for out in (hist.get("outputs") or {}).values() for img in out.get("images", []) if img.get("type") == "output"] or \
             [img for out in (hist.get("outputs") or {}).values() for img in out.get("images", [])]
    if not images:
        raise RuntimeError("ComfyUI finished but produced no image.")
    first = images[0]
    data = comfy("GET", "/view?" + urlencode({"filename": first["filename"], "subfolder": first.get("subfolder", ""),
                                              "type": first.get("type", "output")}), timeout=60).content
    seconds = round(time.time() - started, 1)
    art = artifacts.save(data, f"gemma-{ctx.id}.png", created_by="image_service", conversation_id=ctx.row.get("conversation_id"),
                         kind="image", parent_id=p.get("source_artifact"), job_id=ctx.id, description=p.get("prompt", "")[:300],
                         provenance={"prompt": p.get("prompt"), "negative": p.get("negative"), "width": values.get("width"),
                                     "height": values.get("height"), "seed": values.get("seed"), "steps": values.get("steps"),
                                     "workflow": f"image_{kind}.json",
                                     "workflow_sha": hashlib.sha256(json.dumps(wf, sort_keys=True).encode()).hexdigest()[:12],
                                     "comfy_prompt_id": pid, "source_artifact": p.get("source_artifact"), "seconds": seconds})
    try:
        comfy("POST", "/free", json={"unload_models": True, "free_memory": True})
        observe.event("IMAGE", "ComfyUI asked to release GPU memory", vram=vram(), reloaded_chat_model_on_next_message=bool(released))
    except Exception:
        pass
    store.usage("image_seconds", seconds, "s", ctx.id)
    try:
        from .. import hooks
        hooks.emit("image.completed", artifact=art)
    except Exception:
        pass
    with store.db() as con:
        row = con.execute("SELECT message_id FROM jobs WHERE id=?", (ctx.id,)).fetchone()
    if row and row["message_id"] and ctx.row.get("conversation_id"):
        artifacts.attach_to_message([art["id"]], row["message_id"], ctx.row["conversation_id"])
    return {"artifact_id": art["id"], "seconds": seconds, "seed": values.get("seed"), "width": values.get("width"), "height": values.get("height")}


def _cancel_comfy(pid):
    try:
        q = comfy("GET", "/queue").json()
        running = [x[1] for x in q.get("queue_running", [])]
        if pid in running:
            comfy("POST", "/interrupt")
        else:
            comfy("POST", "/queue", json={"delete": [pid]})
        observe.event("IMAGE", "image job cancelled in ComfyUI", prompt_id=pid, was_running=pid in running)
    except Exception as e:
        observe.warn("IMAGE", "could not cancel in ComfyUI", error=str(e))


def _size(a):
    w, h = int(a.get("width") or 1328), int(a.get("height") or 1328)
    aspect = str(a.get("aspect") or "")
    presets = {"square": (1328, 1328), "landscape": (1664, 928), "portrait": (928, 1664), "16:9": (1664, 928), "9:16": (928, 1664),
               "4:3": (1472, 1104), "3:4": (1104, 1472)}
    if aspect in presets:
        w, h = presets[aspect]
    return max(256, min(2048, w // 16 * 16)), max(256, min(2048, h // 16 * 16))


def generate(a, c):
    if not str(a.get("prompt") or "").strip():
        raise ToolFailure("invalid_request", "describe the picture")
    load_workflow("generate")
    w, h = _size(a)
    params = {"kind": "generate", "prompt": a["prompt"], "negative": a.get("negative") or "", "width": w, "height": h,
              "seed": int(a["seed"]) if str(a.get("seed", "")).isdigit() else random.randint(1, 2 ** 31 - 1),
              "steps": int(a["steps"]) if str(a.get("steps", "")).isdigit() else None}
    job = jobs.submit("image", "Generating an image", params, conversation_id=c.get("cid"),
                      dedupe_key=f"img:{c.get('request_id')}:{hashlib.sha1(json.dumps(params, sort_keys=True).encode()).hexdigest()[:12]}")
    c.setdefault("jobs", []).append(job["id"])
    return {"job_id": job["id"], "note": "The picture is being made; Glen sees a progress card that turns into the image. "
                                         "Say a short line about what you asked for; don't describe the finished picture."}


def edit(a, c):
    src = a.get("source_artifact")
    if not src:
        imgs = c.get("image_artifacts") or []
        src = imgs[-1] if imgs else None
    if not src or not artifacts.get(src):
        raise ToolFailure("invalid_request", "Which picture? Give the artifact id of the image to edit.")
    load_workflow("edit")
    params = {"kind": "edit", "prompt": a.get("instruction") or a.get("prompt"), "source_artifact": src,
              "seed": random.randint(1, 2 ** 31 - 1)}
    job = jobs.submit("image", "Editing an image", params, conversation_id=c.get("cid"),
                      dedupe_key=f"imgedit:{c.get('request_id')}:{src}:{hash(params['prompt'])}")
    c.setdefault("jobs", []).append(job["id"])
    return {"job_id": job["id"], "note": "The edit is being made; Glen sees a progress card that becomes the new picture."}


class ImagesPlugin(Plugin):
    id, name, version, auth = "images", "Image generation (ComfyUI)", "1.0", "local"
    description = "Make and edit pictures with your own ComfyUI and Qwen-Image workflow, with real progress."
    permissions = ("send prompts and pictures to the ComfyUI running on Vision", "may briefly release the chat model from the GPU while a picture is made")

    def connect_info(self):
        return {"how": f"Start ComfyUI (default {COMFY}, or set GEMMA_COMFY_URL). In ComfyUI open your working Qwen-Image workflow, "
                       f"choose Workflow → Export (API) and save it as {_wf_dir() / 'image_generate.json'} "
                       f"(and the edit workflow as image_edit.json)."}

    def health(self):
        try:
            stats = comfy("GET", "/system_stats", timeout=3).json()
        except ToolFailure as f:
            return {"status": "unavailable", "message": str(f)}
        has = {k: (_wf_dir() / f"image_{k}.json").exists() for k in ("generate", "edit")}
        if not has["generate"]:
            return {"status": "not_connected", "message": "ComfyUI is running; the Qwen workflow still needs exporting.", "workflows": has}
        return {"status": "ok", "message": "Ready", "workflows": has, "comfy": (stats.get("system") or {}).get("comfyui_version")}

    def register(self):
        register(Tool("generate_image", self.id,
                      "Make a picture from a description with Glen's ComfyUI (Qwen-Image). Write a rich, specific prompt. Runs in the "
                      "background with a progress card.",
                      {"prompt": {"type": "string"}, "negative": {"type": "string"}, "aspect": {"type": "string",
                       "enum": ["square", "landscape", "portrait", "16:9", "9:16", "4:3", "3:4"]}, "width": {"type": "integer"},
                       "height": {"type": "integer"}, "seed": {"type": "integer"}}, generate, ("prompt",), keywords=WORDS, label="Starting the picture"))
        register(Tool("edit_image", self.id, "Edit a picture (one Glen sent or one you made) by instruction, e.g. 'remove the car in the background'.",
                      {"instruction": {"type": "string"}, "source_artifact": {"type": "string", "description": "artifact id of the picture"}},
                      edit, ("instruction",), keywords=WORDS, label="Starting the edit"))


jobs.register("image", run_job, slot="gpu", title="Image")
add(ImagesPlugin())

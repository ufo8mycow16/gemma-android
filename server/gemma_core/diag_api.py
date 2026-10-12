"""HTTP side of Diagnostics: the live window and exports read the same events as the log files."""
from __future__ import annotations

import json
import queue
import time

from flask import Response, jsonify, request

from . import observe


def _filters():
    a = request.args
    cats = [c.strip().upper() for c in (a.get("cats") or "").split(",") if c.strip() and c.strip().upper() != "ALL"]
    levels = [lv.strip().upper() for lv in (a.get("levels") or "").split(",") if lv.strip()]
    return dict(rid=a.get("rid") or None, cid=a.get("cid") or None, jid=a.get("jid") or None, doc=a.get("doc") or None,
                cats=cats or None, levels=levels or None, text=a.get("q") or None)


def register(app):
    @app.get("/api/v1/diagnostics/events")
    def diag_events():
        after = request.args.get("after", type=int) or 0
        limit = min(5000, request.args.get("limit", type=int) or 1000)
        return jsonify({"events": observe.recent(after, limit, **_filters()), "build": observe.build_info()})

    @app.get("/api/v1/diagnostics/stream")
    def diag_stream():
        f = _filters()
        after = request.args.get("after", type=int) or 0

        def gen():
            q = observe.subscribe()
            try:
                for rec in observe.recent(after, 2000, **f):
                    yield json.dumps(rec, ensure_ascii=False) + "\n"
                last_ping = time.time()
                while True:
                    try:
                        rec = q.get(timeout=10)
                    except queue.Empty:
                        yield '{"ping":1}\n'
                        last_ping = time.time()
                        continue
                    if _match(rec, f):
                        yield json.dumps(rec, ensure_ascii=False) + "\n"
                    if time.time() - last_ping > 10:
                        yield '{"ping":1}\n'
                        last_ping = time.time()
            finally:
                observe.unsubscribe(q)
        return Response(gen(), mimetype="application/x-ndjson", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.post("/api/v1/diagnostics/clear")
    def diag_clear():
        observe.clear()
        observe.event("SYSTEM", "live diagnostics view cleared (log files kept)")
        return jsonify({"ok": True})

    @app.get("/api/v1/diagnostics/requests")
    def diag_requests():
        rows = observe.recent(0, 8000)
        by = {}
        for r in rows:
            rid = r.get("rid")
            if not rid:
                continue
            item = by.setdefault(rid, {"rid": rid, "cid": r.get("cid"), "first": r["ts"], "last": r["ts"], "events": 0,
                                       "errors": 0, "warnings": 0, "route": None, "status": None, "ms": None, "summary": ""})
            item["last"] = r["ts"]
            item["events"] += 1
            item["errors"] += r["level"] == "ERROR"
            item["warnings"] += r["level"] == "WARNING"
            d = r.get("data") or {}
            if r["msg"] == "route chosen":
                item["route"] = d.get("intent")
            if r["msg"] == "turn finished":
                item["status"], item["ms"] = d.get("status"), d.get("ms")
            if r["msg"] == "turn received":
                item["summary"] = f"{d.get('chars')} chars" + (f", {len(d.get('documents') or [])} file(s)" if d.get("documents") else "")
        return jsonify({"requests": sorted(by.values(), key=lambda x: x["first"], reverse=True)[:200]})

    @app.get("/api/v1/diagnostics/export")
    def diag_export():
        rows = observe.recent(0, 8000, **_filters())
        body = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
        name = "gemma-diagnostics-" + time.strftime("%Y%m%d-%H%M%S") + ".jsonl"
        return Response(body, mimetype="application/x-ndjson", headers={"Content-Disposition": f'attachment; filename="{name}"'})

    @app.get("/api/v1/build")
    def build():
        return jsonify(observe.build_info())


def _match(rec, f) -> bool:
    if f["rid"] and rec.get("rid") != f["rid"]:
        return False
    if f["cid"] and str(rec.get("cid")) != str(f["cid"]):
        return False
    if f["jid"] and rec.get("jid") != f["jid"]:
        return False
    if f["doc"] and str(rec.get("doc")) != str(f["doc"]):
        return False
    if f["cats"] and rec["cat"] not in f["cats"]:
        return False
    if f["levels"] and rec["level"] not in f["levels"]:
        return False
    if f["text"] and f["text"].lower() not in json.dumps(rec, ensure_ascii=False).lower():
        return False
    return True

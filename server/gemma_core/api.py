"""HTTP endpoints for the platform layer. All under /api/v1 and behind the access key (app.require_access_key)."""
from __future__ import annotations

import json
import queue
import time

from flask import Response, jsonify, request

from . import (approvals, artifacts, export, jobs, notify, observe, plugins, projects, search, secrets, skills, store, tools,
               workspace)


def _err(message, code, status):
    return jsonify({"error": message, "code": code}), status


def register(app):
    # ---------------------------------------------------------------- jobs
    @app.get("/api/v1/jobs")
    def v1_jobs():
        cid = request.args.get("conversation_id", type=int)
        states = [s for s in (request.args.get("state") or "").split(",") if s]
        return jsonify({"jobs": jobs.listing(conversation_id=cid, states=states or None, limit=request.args.get("limit", 50, type=int))})

    @app.get("/api/v1/jobs/<jid>")
    def v1_job(jid):
        j = jobs.get(jid)
        return jsonify(j) if j else _err("unknown job", "not_found", 404)

    @app.get("/api/v1/jobs/<jid>/events")
    def v1_job_events(jid):
        return jsonify({"events": jobs.events(jid, request.args.get("after", 0, type=int))})

    @app.post("/api/v1/jobs/<jid>/cancel")
    def v1_job_cancel(jid):
        return jsonify({"cancelled": jobs.cancel(jid), "job": jobs.get(jid)})

    @app.get("/api/v1/jobs/stream")
    def v1_job_stream():
        """NDJSON: the current state of watched jobs whenever one changes (ids=a,b,c, or all active)."""
        ids = [i for i in (request.args.get("ids") or "").split(",") if i]
        q: queue.Queue = queue.Queue()
        jobs.on_change(lambda jid: q.put(jid))

        def gen():
            sent = {}
            watch = ids or [j["id"] for j in jobs.listing(states=["queued", "running"])]
            for jid in watch:
                j = jobs.get(jid)
                if j:
                    sent[jid] = (j["state"], j["progress"], j["progress_text"])
                    yield json.dumps({"type": "job", "job": j}) + "\n"
            last = time.time()
            while True:
                try:
                    jid = q.get(timeout=10)
                except queue.Empty:
                    yield '{"type":"ping"}\n'
                    continue
                if ids and jid not in ids:
                    continue
                j = jobs.get(jid)
                if not j:
                    continue
                key = (j["state"], j["progress"], j["progress_text"])
                if sent.get(jid) == key:
                    continue
                sent[jid] = key
                yield json.dumps({"type": "job", "job": j}) + "\n"
                if ids and all((jobs.get(i) or {}).get("state") in jobs.TERMINAL for i in ids):
                    return
                if time.time() - last > 10:
                    last = time.time()
                    yield '{"type":"ping"}\n'
        return Response(gen(), mimetype="application/x-ndjson", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    # ---------------------------------------------------------------- artifacts
    @app.get("/api/v1/artifacts")
    def v1_artifacts():
        return jsonify({"artifacts": artifacts.listing(request.args.get("conversation_id", type=int), limit=request.args.get("limit", 100, type=int))})

    @app.get("/api/v1/artifacts/<aid>")
    def v1_artifact(aid):
        a = artifacts.get(aid)
        return jsonify(a) if a else _err("unknown file", "not_found", 404)

    @app.get("/api/v1/artifacts/<aid>/content")
    def v1_artifact_content(aid):
        meta = artifacts.get(aid)
        path = artifacts.path_of(aid)
        if not meta or not path:
            return _err("unknown file", "not_found", 404)
        return Response(path.read_bytes(), mimetype=meta["mime"],
                        headers=artifacts.serve_headers(meta, download=request.args.get("download") == "1"))

    @app.get("/api/v1/artifacts/<aid>/preview")
    def v1_artifact_preview(aid):
        p = artifacts.preview(aid)
        return jsonify(p) if p else _err("unknown file", "not_found", 404)

    @app.delete("/api/v1/artifacts/<aid>")
    def v1_artifact_delete(aid):
        return jsonify({"deleted": artifacts.delete(aid)})

    @app.post("/api/v1/artifacts")
    def v1_artifact_upload():
        """Raw upload: body = file bytes, X-Filename header. For files Glen gives Gemma (code, ZIPs, any type)."""
        name = request.headers.get("X-Filename") or "upload"
        data = request.get_data(cache=False)
        if not data:
            return _err("empty upload", "validation_error", 400)
        if len(data) > artifacts.MAX_ARTIFACT_BYTES:
            return _err("too large", "attachment_rejected", 413)
        a = artifacts.save(data, name, created_by="user", conversation_id=request.args.get("conversation_id", type=int),
                           provenance={"source": "upload", "client": request.headers.get("X-Client", "")})
        return jsonify(a), 201

    # ---------------------------------------------------------------- approvals
    @app.get("/api/v1/approvals")
    def v1_approvals():
        return jsonify({"approvals": approvals.listing(request.args.get("conversation_id", type=int), request.args.get("state"))})

    @app.post("/api/v1/approvals/<pid>/approve")
    def v1_approve(pid):
        body = request.get_json(silent=True) or {}
        try:
            return jsonify(approvals.decide(pid, True, always=bool(body.get("always"))))
        except LookupError:
            return _err("unknown approval", "not_found", 404)

    @app.post("/api/v1/approvals/<pid>/deny")
    def v1_deny(pid):
        try:
            return jsonify(approvals.decide(pid, False))
        except LookupError:
            return _err("unknown approval", "not_found", 404)

    # ---------------------------------------------------------------- plugins, tools, privacy
    @app.get("/api/v1/plugins")
    def v1_plugins():
        return jsonify({"plugins": plugins.listing(fresh=request.args.get("fresh") == "1")})

    @app.post("/api/v1/plugins/<pid>/enabled")
    def v1_plugin_enabled(pid):
        if pid not in plugins.REGISTRY:
            return _err("unknown plugin", "not_found", 404)
        plugins.set_enabled(pid, bool((request.get_json(silent=True) or {}).get("enabled")))
        return jsonify(plugins.describe(pid, fresh=True))

    @app.post("/api/v1/plugins/<pid>/settings")
    def v1_plugin_settings(pid):
        if pid not in plugins.REGISTRY:
            return _err("unknown plugin", "not_found", 404)
        p = plugins.REGISTRY[pid]
        body = request.get_json(silent=True) or {}
        schema = p.settings_schema()
        plain = {}
        for k, v in body.items():
            if k not in schema:
                continue
            if schema[k].get("type") == "secret":
                secrets.put(pid, k, (str(v).strip() or None) if v is not None else None)
            else:
                plain[k] = v
        if plain:
            p.save_settings(plain)
        plugins.setup()
        observe.event("SECURITY", "plugin settings changed", plugin=pid, fields=list(body))
        return jsonify(plugins.describe(pid, fresh=True))

    @app.post("/api/v1/plugins/<pid>/disconnect")
    def v1_plugin_disconnect(pid):
        if pid not in plugins.REGISTRY:
            return _err("unknown plugin", "not_found", 404)
        secrets.forget(pid)
        if pid == "google" and store.S.get("google_disconnect"):
            store.S["google_disconnect"]()
        plugins.setup()
        observe.event("SECURITY", "plugin disconnected", plugin=pid)
        return jsonify(plugins.describe(pid, fresh=True))

    @app.get("/api/v1/tools")
    def v1_tools():
        return jsonify({"tools": tools.catalogue(), "policies": tools.policies()})

    @app.post("/api/v1/tools/policy")
    def v1_tool_policy():
        b = request.get_json(silent=True) or {}
        try:
            tools.set_policy(str(b.get("plugin")), str(b.get("tool")), str(b.get("policy")))
        except ValueError as e:
            return _err(str(e), "validation_error", 400)
        return jsonify({"policies": tools.policies()})

    @app.get("/api/v1/privacy")
    def v1_privacy():
        with store.db() as con:
            counts = {t: con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("conversations", "messages", "memories", "attachments", "artifacts", "jobs")}
        return jsonify({"plugins": [{"id": p["id"], "name": p["name"], "enabled": p["enabled"], "status": p["health"]["status"],
                                     "message": p["health"].get("message"), "permissions": p["permissions"]} for p in plugins.listing()],
                        "policies": tools.policies(), "data": counts,
                        "notes": ["Credentials are kept in files only your user can read and are never shown to the model.",
                                  "Diagnostics never contain message text in full, passwords, tokens or keys."]})

    # ---------------------------------------------------------------- workspaces (review UI)
    @app.get("/api/v1/workspaces/<wid>")
    def v1_workspace(wid):
        try:
            return jsonify(dict(workspace.info(wid), files_list=workspace.list_files(wid), changes=workspace.changes(wid)))
        except LookupError:
            return _err("unknown workspace", "not_found", 404)

    @app.get("/api/v1/workspaces/<wid>/diff")
    def v1_workspace_diff(wid):
        try:
            return jsonify(workspace.diff(wid, 200000))
        except LookupError:
            return _err("unknown workspace", "not_found", 404)

    @app.post("/api/v1/workspaces/<wid>/accept")
    def v1_workspace_accept(wid):
        return jsonify(workspace.accept(wid))

    @app.post("/api/v1/workspaces/<wid>/revert")
    def v1_workspace_revert(wid):
        return jsonify(workspace.revert(wid, (request.get_json(silent=True) or {}).get("path")))

    @app.post("/api/v1/workspaces/<wid>/package")
    def v1_workspace_package(wid):
        b = request.get_json(silent=True) or {}
        return jsonify(workspace.package(wid, b.get("filename") or "project.zip", b.get("conversation_id")))

    # ---------------------------------------------------------------- notifications, search, projects, skills, usage, export
    @app.get("/api/v1/notifications")
    def v1_notifications():
        return jsonify(notify.listing(request.args.get("after", type=int)))

    @app.get("/api/v1/activity")
    def v1_activity():
        return jsonify({"notifications": notify.recent(), "jobs": jobs.listing(limit=20),
                        "approvals": approvals.listing(state="pending")})

    @app.post("/api/v1/notifications/read")
    def v1_notifications_read():
        notify.mark_read([int(i) for i in (request.get_json(silent=True) or {}).get("ids", []) if str(i).isdigit()])
        return jsonify({"ok": True})

    @app.get("/api/v1/search")
    def v1_search():
        inc = [x for x in (request.args.get("include") or "").split(",") if x]
        return jsonify(search.run(request.args.get("q", ""), include=inc))

    @app.get("/api/v1/projects")
    def v1_projects():
        return jsonify({"projects": projects.listing()})

    @app.post("/api/v1/projects")
    def v1_project_create():
        b = request.get_json(silent=True) or {}
        return jsonify(projects.create(str(b.get("name") or "Project"), str(b.get("instructions") or ""))), 201

    @app.get("/api/v1/projects/<int:pid>")
    def v1_project(pid):
        p = projects.get(pid)
        return jsonify(p) if p else _err("unknown project", "not_found", 404)

    @app.put("/api/v1/projects/<int:pid>")
    def v1_project_update(pid):
        b = request.get_json(silent=True) or {}
        return jsonify(projects.update(pid, b.get("name"), b.get("instructions"), b.get("archived")))

    @app.put("/api/v1/conversations/<int:cid>/project")
    def v1_conversation_project(cid):
        b = request.get_json(silent=True) or {}
        return jsonify(projects.assign(b.get("project_id"), cid))

    @app.get("/api/v1/skills")
    def v1_skills():
        return jsonify({"skills": skills.listing()})

    @app.get("/api/v1/usage")
    def v1_usage():
        return jsonify({"usage": store.usage_summary(days=request.args.get("days", 30, type=int))})

    @app.get("/api/v1/export")
    def v1_export():
        data = export.export_all()
        name = "gemma-export-" + time.strftime("%Y%m%d-%H%M") + ".zip"
        return Response(data, mimetype="application/zip", headers={"Content-Disposition": f'attachment; filename="{name}"'})

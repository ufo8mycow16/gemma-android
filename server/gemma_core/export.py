"""
Export and backup.

export_all(): one ZIP with everything a person owns in Gemma: conversations
(JSON, every message), Projects, memories, documents (the original files),
files Gemma made, schedules, tool policies, plugin settings without secrets.

full_backup(): a consistent copy of gemma.db (SQLite online backup) plus the
upload, artifact and workflow folders, into backups/, so the database is not a
single point of failure. restore_into(): unpack a backup into a NEW folder,
never over the running one.
"""
from __future__ import annotations

import io
import json
import os
import sqlite3
import time
import zipfile
from pathlib import Path

from . import observe, store


def _rows(con, sql, args=()):
    return [dict(r) for r in con.execute(sql, args)]


def export_all(user_id: int = 1) -> bytes:
    base = store.app_dir()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z, store.db() as con:
        convs = _rows(con, "SELECT * FROM conversations WHERE coalesce(user_id,1)=? AND deleted_at IS NULL", (user_id,))
        for c in convs:
            c["messages"] = _rows(con, "SELECT id, role, content, created_at FROM messages WHERE conversation_id=? ORDER BY id", (c["id"],))
            z.writestr(f"conversations/{c['id']:05d}.json", json.dumps(c, ensure_ascii=False, indent=1))
        z.writestr("projects.json", json.dumps(_rows(con, "SELECT * FROM projects WHERE user_id=?", (user_id,)), ensure_ascii=False, indent=1))
        z.writestr("memories.json", json.dumps(_rows(con, "SELECT * FROM memories WHERE active=1"), ensure_ascii=False, indent=1))
        z.writestr("schedules.json", json.dumps(_rows(con, "SELECT * FROM schedules WHERE user_id=?", (user_id,)), ensure_ascii=False, indent=1))
        z.writestr("tool_policies.json", json.dumps(_rows(con, "SELECT * FROM tool_policies WHERE user_id=?", (user_id,)), indent=1))
        z.writestr("plugin_settings.json", json.dumps(_rows(con, "SELECT plugin, enabled, settings FROM plugin_state WHERE user_id=?", (user_id,)), indent=1))
        for a in _rows(con, "SELECT id, filename, original_name FROM attachments WHERE original_name IS NOT NULL"):
            p = base / "uploads" / a["filename"]
            if p.exists():
                z.write(p, f"documents/{a['id']}-{a['original_name']}")
        for a in _rows(con, "SELECT * FROM artifacts WHERE user_id=? AND deleted_at IS NULL", (user_id,)):
            p = base / "artifacts" / a["path"]
            if p.exists():
                z.write(p, f"files/{a['id']}-{a['filename']}")
        z.writestr("README.txt", "Gemma export, " + time.strftime("%Y-%m-%d %H:%M") + ". Conversations are JSON; documents are the "
                   "original files you sent; files/ are the files Gemma made.\n")
    observe.event("API", "export created", bytes=buf.tell())
    return buf.getvalue()


def full_backup(db_path: Path) -> Path:
    base = store.app_dir()
    folder = base / "backups"
    folder.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    target = folder / f"gemma-full-{stamp}.zip"
    tmp_db = folder / f".gemma-{stamp}.db"
    src = sqlite3.connect(db_path)
    dst = sqlite3.connect(tmp_db)
    with dst:
        src.backup(dst)
    src.close()
    dst.close()
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(tmp_db, "gemma.db")
        for sub in ("uploads", "artifacts", "workflows", "skills"):
            d = base / sub
            if d.exists():
                for p in d.rglob("*"):
                    if p.is_file() and not p.is_symlink():
                        z.write(p, f"{sub}/{p.relative_to(d)}")
        for name in ("models.json", "mcp.json", "hooks.json"):
            if (base / name).exists():
                z.write(base / name, name)
    tmp_db.unlink()
    fulls = sorted(folder.glob("gemma-full-*.zip"))
    for old in fulls[:-5]:
        old.unlink()
    observe.event("SYSTEM", "full backup written", path=str(target), bytes=target.stat().st_size)
    return target


def restore_into(backup_zip: Path, new_folder: Path) -> Path:
    if new_folder.exists() and any(new_folder.iterdir()):
        raise ValueError("Restore into an empty folder; the running Gemma is never overwritten.")
    from .artifacts import safe_unzip
    safe_unzip(backup_zip.read_bytes(), new_folder)
    return new_folder

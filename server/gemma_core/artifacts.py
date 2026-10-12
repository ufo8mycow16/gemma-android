"""
Artifacts: real files Gemma (or Claude Code, a build tool, the image service)
produces, stored once and shown the same on web and Android.

Any file type can be stored; the extension is not whitelisted. What is
restricted is how a file is *served*: only plainly safe types are shown inline,
HTML and SVG are only ever previewed as source text, and everything else is a
download. Filenames are sanitised; archives are unpacked with checks against
path traversal, links, absurd sizes and file counts.
"""
from __future__ import annotations

import hashlib
import io
import mimetypes
import os
import re
import shutil
import stat
import uuid
import zipfile
from pathlib import Path, PurePosixPath

from . import observe, store

MAX_ARTIFACT_BYTES = 200 * 1024 * 1024
MAX_UNZIP_BYTES = 500 * 1024 * 1024
MAX_UNZIP_FILES = 20000
TEXT_PREVIEW_CHARS = 200_000
INLINE_SAFE = {"image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain", "audio/wav",
               "audio/mpeg", "audio/mp4", "video/mp4"}
TEXT_EXT = {".py", ".gd", ".gdshader", ".tscn", ".tres", ".godot", ".cfg", ".txt", ".md", ".json", ".csv", ".tsv", ".yaml",
            ".yml", ".toml", ".xml", ".html", ".htm", ".css", ".js", ".ts", ".tsx", ".jsx", ".sh", ".bash", ".zsh", ".sql",
            ".ini", ".log", ".kt", ".kts", ".java", ".c", ".h", ".cpp", ".hpp", ".cs", ".rs", ".go", ".rb", ".php", ".lua",
            ".gradle", ".properties", ".env.example", ".gitignore", ".svg", ".bat", ".ps1", ".r", ".swift", ".dart"}
LANGUAGE = {".py": "python", ".gd": "gdscript", ".gdshader": "glsl", ".sh": "bash", ".bash": "bash", ".json": "json",
            ".sql": "sql", ".html": "html", ".htm": "html", ".css": "css", ".js": "javascript", ".ts": "typescript",
            ".yaml": "yaml", ".yml": "yaml", ".toml": "toml", ".xml": "xml", ".md": "markdown", ".kt": "kotlin",
            ".java": "java", ".c": "c", ".cpp": "cpp", ".cs": "csharp", ".rs": "rust", ".go": "go", ".tscn": "ini",
            ".tres": "ini", ".godot": "ini", ".cfg": "ini", ".ini": "ini", ".csv": "csv", ".svg": "xml"}
EXTRA_MIME = {".gd": "text/x-gdscript", ".md": "text/markdown", ".py": "text/x-python", ".tscn": "text/plain",
              ".tres": "text/plain", ".godot": "text/plain", ".toml": "text/plain", ".yaml": "text/yaml", ".yml": "text/yaml",
              ".kt": "text/plain", ".gdshader": "text/plain"}


def root() -> Path:
    p = store.app_dir() / "artifacts"
    p.mkdir(exist_ok=True)
    return p


def safe_filename(name: str, default: str = "file") -> str:
    name = PurePosixPath(str(name or "").replace("\\", "/")).name
    name = re.sub(r"[\x00-\x1f<>:\"|?*]", "_", name).strip(" .")
    if name.lower() in ("", ".", ".."):
        name = default
    stem, dot, ext = name.rpartition(".")
    if dot and len(ext) <= 12:
        return (stem[:150] or default) + "." + ext
    return name[:160]


def mime_for(name: str) -> str:
    ext = Path(name).suffix.lower()
    return EXTRA_MIME.get(ext) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def is_text(name: str, mime: str = "") -> bool:
    return Path(name).suffix.lower() in TEXT_EXT or mime.startswith("text/") or mime in ("application/json", "application/xml")


def save(data: bytes, filename: str, *, created_by: str, conversation_id=None, message_id=None, kind: str | None = None,
         provenance: dict | None = None, parent_id=None, job_id=None, description: str = "", user_id: int = 1) -> dict:
    if len(data) > MAX_ARTIFACT_BYTES:
        raise ValueError("That file is larger than the 200 MB artifact limit.")
    filename = safe_filename(filename)
    mime = mime_for(filename)
    aid = "a" + uuid.uuid4().hex[:15]
    folder = root() / aid[1:3] / aid
    folder.mkdir(parents=True, exist_ok=True)
    (folder / filename).write_bytes(data)
    sha = hashlib.sha256(data).hexdigest()
    kind = kind or ("image" if mime.startswith("image/") else "archive" if filename.lower().endswith(".zip") else "file")
    rel = str((folder / filename).relative_to(root()))
    with store.lock(), store.db() as con:
        con.execute(
            "INSERT INTO artifacts(id,user_id,conversation_id,message_id,kind,filename,mime,size,sha256,path,created_by,provenance,"
            "parent_id,job_id,request_id,description,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (aid, user_id, conversation_id, message_id, kind, filename, mime, len(data), sha, rel, created_by,
             store.jdump(provenance or {}), parent_id, job_id, observe.request_id.get(), description[:500], store.now()))
    store.usage("artifact_bytes", len(data), "bytes", aid, user_id)
    observe.event("ARTIFACT", f"artifact created: {filename}", artifact=aid, size=len(data), mime=mime, created_by=created_by,
                  parent=parent_id, sha=sha[:12])
    try:
        from . import hooks
        hooks.emit("artifact.created", artifact=get(aid))
    except Exception:
        pass
    return get(aid)


def get(aid: str, user_id: int = 1) -> dict | None:
    with store.db() as con:
        r = con.execute("SELECT * FROM artifacts WHERE id=? AND user_id=? AND deleted_at IS NULL", (aid, user_id)).fetchone()
    return public(dict(r)) if r else None


def path_of(aid: str, user_id: int = 1) -> Path | None:
    with store.db() as con:
        r = con.execute("SELECT path FROM artifacts WHERE id=? AND user_id=? AND deleted_at IS NULL", (aid, user_id)).fetchone()
    if not r:
        return None
    p = (root() / r["path"]).resolve()
    if root().resolve() not in p.parents:
        return None
    return p if p.exists() else None


def read(aid: str) -> bytes | None:
    p = path_of(aid)
    return p.read_bytes() if p else None


def public(r: dict) -> dict:
    ext = Path(r["filename"]).suffix.lower()
    return {
        "id": r["id"], "kind": r["kind"], "filename": r["filename"], "mime": r["mime"], "size": r["size"],
        "sha256": r["sha256"], "created_by": r["created_by"], "created_at": r["created_at"],
        "conversation_id": r["conversation_id"], "message_id": r["message_id"], "parent_id": r["parent_id"],
        "job_id": r["job_id"], "description": r["description"], "provenance": store.jload(r["provenance"], {}),
        "language": LANGUAGE.get(ext), "text": is_text(r["filename"], r["mime"]),
        "url": f"/api/v1/artifacts/{r['id']}/content", "preview_url": f"/api/v1/artifacts/{r['id']}/preview",
    }


def listing(conversation_id=None, user_id: int = 1, limit: int = 100, message_id=None) -> list[dict]:
    q, args = "SELECT * FROM artifacts WHERE user_id=? AND deleted_at IS NULL", [user_id]
    if conversation_id is not None:
        q += " AND conversation_id=?"
        args.append(conversation_id)
    if message_id is not None:
        q += " AND message_id=?"
        args.append(message_id)
    q += " ORDER BY created_at DESC LIMIT ?"
    args.append(limit)
    with store.db() as con:
        return [public(dict(r)) for r in con.execute(q, args)]


def by_messages(message_ids: list[int]) -> dict:
    if not message_ids:
        return {}
    with store.db() as con:
        rows = con.execute("SELECT * FROM artifacts WHERE deleted_at IS NULL AND message_id IN (%s) ORDER BY created_at" %
                           ",".join("?" * len(message_ids)), message_ids).fetchall()
    out: dict = {}
    for r in rows:
        out.setdefault(r["message_id"], []).append(public(dict(r)))
    return out


def attach_to_message(artifact_ids: list[str], message_id: int, conversation_id: int):
    if not artifact_ids:
        return
    with store.lock(), store.db() as con:
        con.execute("UPDATE artifacts SET message_id=?, conversation_id=COALESCE(conversation_id, ?) WHERE id IN (%s) AND message_id IS NULL"
                    % ",".join("?" * len(artifact_ids)), [message_id, conversation_id, *artifact_ids])


def delete(aid: str) -> bool:
    with store.lock(), store.db() as con:
        n = con.execute("UPDATE artifacts SET deleted_at=? WHERE id=? AND deleted_at IS NULL", (store.now(), aid)).rowcount
    return bool(n)


def preview(aid: str) -> dict | None:
    meta = get(aid)
    if not meta:
        return None
    if meta["text"]:
        data = read(aid) or b""
        text = data[:TEXT_PREVIEW_CHARS * 2].decode("utf-8", errors="replace")[:TEXT_PREVIEW_CHARS]
        return dict(meta, content=text, truncated=len(data) > len(text.encode("utf-8")))
    if meta["filename"].lower().endswith(".zip"):
        try:
            with zipfile.ZipFile(io.BytesIO(read(aid) or b"")) as z:
                entries = [{"path": i.filename, "size": i.file_size} for i in z.infolist()[:2000]]
            return dict(meta, entries=entries)
        except Exception:
            return dict(meta, entries=[])
    return meta


def serve_headers(meta: dict, download: bool) -> dict:
    """Never let a stored file run as a page on Gemma's origin."""
    inline = (not download) and meta["mime"] in INLINE_SAFE
    disposition = "inline" if inline else "attachment"
    quoted = re.sub(r'["\\\r\n]', "_", meta["filename"])
    return {
        "Content-Disposition": f'{disposition}; filename="{quoted}"',
        "X-Content-Type-Options": "nosniff",
        "Content-Security-Policy": "default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; sandbox",
        "Cache-Control": "private, max-age=3600",
    }


def make_zip(files: dict[str, bytes]) -> bytes:
    """files: relative path -> bytes. Directory structure is kept; unsafe paths are refused."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, data in sorted(files.items()):
            clean = clean_relpath(rel)
            if clean is None:
                raise ValueError(f"unsafe path in archive: {rel!r}")
            z.writestr(clean, data)
    return buf.getvalue()


def clean_relpath(rel: str) -> str | None:
    rel = str(rel or "").replace("\\", "/").strip()
    if not rel or rel.startswith("/") or re.match(r"^[A-Za-z]:", rel):
        return None
    parts = [p for p in rel.split("/") if p not in ("", ".")]
    if not parts or any(p == ".." for p in parts) or any("\x00" in p for p in parts):
        return None
    return "/".join(parts)


def safe_unzip(data: bytes, dest: Path) -> list[str]:
    """Unpack into dest. Refuses traversal, absolute paths, links, device files, and archives that are
    absurdly large or numerous (zip bombs). Returns the written relative paths."""
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    written = []
    total = 0
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        infos = z.infolist()
        if len(infos) > MAX_UNZIP_FILES:
            raise ValueError("That archive has too many files.")
        for info in infos:
            rel = clean_relpath(info.filename)
            if rel is None:
                raise ValueError(f"The archive contains an unsafe path: {info.filename!r}")
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode) or (mode and not (stat.S_ISREG(mode) or stat.S_ISDIR(mode))):
                raise ValueError(f"The archive contains a link or special file: {info.filename!r}")
            total += info.file_size
            if total > MAX_UNZIP_BYTES:
                raise ValueError("That archive would unpack to more than 500 MB.")
            target = (dest / rel).resolve()
            if dest not in target.parents and target != dest:
                raise ValueError(f"The archive tries to write outside its folder: {info.filename!r}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, 1024 * 1024)
            os.chmod(target, 0o644)
            written.append(rel)
    return written


def folder_to_zip(folder: Path, exclude=(".git", "__pycache__", ".godot", ".import", "node_modules", ".venv", "venv")) -> bytes:
    files = {}
    for p in sorted(folder.rglob("*")):
        if p.is_symlink() or not p.is_file():
            continue
        rel = p.relative_to(folder)
        if any(part in exclude for part in rel.parts):
            continue
        files[str(rel).replace(os.sep, "/")] = p.read_bytes()
    return make_zip(files)

"""
Local Files: Glen's own files on Vision, by name or by what is inside them.

Read access covers the roots in settings (default: the home folder). Writing is
only possible inside roots marked "write", and always asks first. Never listed,
searched or read: hidden files and folders, anything that looks like a key,
token, password or credential, Gemma's own data, virtual environments and
model weights (by name only). Paths are resolved to their real location and
must stay inside a root, so links and ".." cannot reach anything else.
(Search code first written for Gemma's mobile API in the previous pass.)
"""
from __future__ import annotations

import os
import re
import time

from .. import store


def _plugin_roots():
    try:
        from . import REGISTRY
        return REGISTRY["local_files"].settings().get("roots")
    except Exception:
        return None


def _env_seconds(name, default):
    try:
        value = float(os.environ.get(name, "").strip() or default)
    except ValueError:
        value = default
    return min(120.0, max(0.2, value)) if value else default


FILE_SEARCH_SECONDS = _env_seconds("GEMMA_FILE_SEARCH_SECONDS", 6.0)
FILE_CONTENT_MAX_BYTES = 2 * 1024 * 1024
FILE_DOCUMENT_MAX_BYTES = 30 * 1024 * 1024
FILE_TEXT_CHARS = 20000
FILE_TOOL_CHARS = 8000
FILE_TOOL_RESULTS = 12
FILES_BLOCK_WEB = "Not after reading Glen's files in the same turn; ask him first."
NEEDS_GO_AHEAD_FILES = (
    "Not done. Glen's files were read during this turn, and the app does not let "
    "anything be saved, scheduled or changed in the same turn as file contents. "
    "Tell Glen what you would do; when he confirms in his next message, do it then."
)

_FILE_SECRET_NAMES = ("gemma_token.txt", "google_token.json", "google_credentials.json", "wallet.dat")
_FILE_SECRET_PATTERNS = (
    "*.pem", "*.key", "*.p12", "*.pfx", "*.kdbx", "*.keystore", "*.jks", "id_rsa*", "id_ed25519*",
    "*.gpg", "*.asc", "*password*", "*passwd*", "*secret*", "*credential*", "*token*",
)
_FILE_OWN_PATTERNS = ("gemma.db*", "gemma-*.db", "voice_diagnostics.log*")
_FILE_SKIP_DIRS = ("node_modules", "__pycache__", "site-packages", "snap", "trash", "lost+found", "ollama")
# Model weights and the like: their names may be listed, their contents are never read.
_FILE_NAME_ONLY = (".safetensors", ".gguf", ".bin", ".pt", ".ckpt")
_FILE_TEXT_EXTENSIONS = frozenset((
    ".txt .text .md .markdown .rst .log .csv .tsv .json .jsonl .yaml .yml .xml .html .htm .ini .cfg .conf "
    ".toml .properties .tex .srt .vtt .org .rtf .py .js .ts .jsx .tsx .java .kt .kts .c .h .cpp .hpp .cc .cs "
    ".go .rs .rb .php .sh .bash .zsh .bat .ps1 .sql .css .scss .lua .gd .gradle .cmake .diff .patch .nfo"
).split())


def _name_matches(name, patterns):
    import fnmatch
    low = name.lower()
    return any(fnmatch.fnmatchcase(low, p) for p in patterns)


def file_roots():
    """The folders Gemma may look in, as real absolute paths. GEMMA_FILE_ROOTS
    (separated by ':' on Linux) or, by default, the home folder of whoever is
    running the app."""
    raw = os.environ.get("GEMMA_FILE_ROOTS", "").strip()
    configured = [r.get("path") for r in (_plugin_roots() or []) if r.get("path")]
    wanted = configured or ([p for p in raw.split(os.pathsep) if p.strip()] if raw else [os.path.expanduser("~")])
    roots = []
    for p in wanted:
        try:
            real = os.path.realpath(os.path.expanduser(p.strip()))
        except Exception:
            continue
        if os.path.isdir(real) and real not in roots:
            roots.append(real)
    return roots


def _own_data_dirs():
    """Gemma's own folders: pictures Glen has sent her, and database backups."""
    base = store.app_dir()
    return [os.path.realpath(str(base / p)) for p in ("uploads", "backups", "artifacts", "workspaces", "logs")]


def _excluded_name(name, is_dir):
    """Rules that depend only on the name of one file or folder."""
    low = name.lower()
    if not name or name.startswith("."):
        return True
    if low in _FILE_SECRET_NAMES or _name_matches(name, _FILE_SECRET_PATTERNS) or _name_matches(name, _FILE_OWN_PATTERNS):
        return True
    if is_dir and low in _FILE_SKIP_DIRS:
        return True
    return False


def _excluded_dir(real_dir):
    """Rules that need a look at the folder itself."""
    if real_dir in _own_data_dirs():
        return True
    try:
        return os.path.isfile(os.path.join(real_dir, "pyvenv.cfg"))
    except OSError:
        return True


def _inside(real, root):
    return real == root or real.startswith(root.rstrip(os.sep) + os.sep)


def allowed_file(path):
    """
    The real path of a regular file Gemma may read, or None. The answer is the
    same None whether the file is missing, excluded or outside the roots.
    """
    import stat as _stat
    try:
        path = str(path or "")
        if not path or "\x00" in path or not os.path.isabs(path):
            return None
        real = os.path.realpath(path)
        if not _stat.S_ISREG(os.stat(real).st_mode):
            return None
    except (OSError, ValueError):
        return None
    for root in file_roots():
        if not _inside(real, root) or real == root:
            continue
        parts = os.path.relpath(real, root).split(os.sep)
        folder, ok = root, True
        for i, part in enumerate(parts):
            last = i == len(parts) - 1
            if part in ("", ".", "..") or _excluded_name(part, not last):
                ok = False
                break
            if not last:
                folder = os.path.join(folder, part)
                if _excluded_dir(folder):
                    ok = False
                    break
        if ok and not _excluded_dir(root):
            return real
    return None


def _tilde(path):
    home = os.path.realpath(os.path.expanduser("~"))
    if path == home:
        return "~"
    if home not in ("", os.sep) and path.startswith(home.rstrip(os.sep) + os.sep):
        return "~" + path[len(home.rstrip(os.sep)):]
    return path


def _looks_binary(sample):
    return b"\x00" in sample[:4096]


def _docx_text(real, limit):
    import zipfile
    from html import unescape
    with zipfile.ZipFile(real) as z:
        info = z.getinfo("word/document.xml")
        if info.file_size > FILE_DOCUMENT_MAX_BYTES:
            raise ValueError("document too large")
        with z.open(info) as f:
            xml = f.read(FILE_DOCUMENT_MAX_BYTES).decode("utf-8", "replace")
    xml = re.sub(r"</w:p>|<w:br\s*/>|<w:cr\s*/>", "\n", xml)
    xml = re.sub(r"<w:tab\s*/>", "\t", xml)
    return unescape(re.sub(r"<[^>]+>", "", xml))[:limit + 1]


def _pdf_text(real, limit, deadline=None):
    import pypdf                                   # optional; absent means PDFs are skipped
    reader = pypdf.PdfReader(real)
    out, size = [], 0
    for page in reader.pages[:200]:
        if deadline is not None and time.time() > deadline:
            break
        piece = page.extract_text() or ""
        out.append(piece)
        size += len(piece)
        if size > limit:
            break
    return "\n".join(out)[:limit + 1]


def file_text(real, limit, deadline=None, content_search=False):
    """
    (text, truncated) for a file already passed by allowed_file, or None when
    it is not something Gemma can read as text. `content_search` applies the
    stricter rules used when looking inside many files.
    """
    name = os.path.basename(real)
    ext = os.path.splitext(name)[1].lower()
    if ext in _FILE_NAME_ONLY:
        return None
    cut_short = False
    try:
        size = os.path.getsize(real)
        if ext in (".pdf", ".docx"):
            if size > FILE_DOCUMENT_MAX_BYTES or (content_search and size > FILE_CONTENT_MAX_BYTES * 4):
                return None
            text = _pdf_text(real, limit, deadline) if ext == ".pdf" else _docx_text(real, limit)
        else:
            if content_search and (ext not in _FILE_TEXT_EXTENSIONS or size > FILE_CONTENT_MAX_BYTES):
                return None
            with open(real, "rb") as f:
                data = f.read(FILE_CONTENT_MAX_BYTES if content_search else limit * 4 + 4096)
            if _looks_binary(data):
                return None
            text = data.decode("utf-8", "replace")
            cut_short = size > len(data)               # more of the file was not read
    except Exception:
        return None
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if not text.strip() and ext in (".pdf", ".docx"):
        return None
    return text[:limit], len(text) > limit or cut_short


def _snippet(text, low, words_wanted, phrase, width=200):
    at = low.find(phrase) if phrase else -1
    if at < 0:
        at = min([p for p in (low.find(w) for w in words_wanted) if p >= 0] or [0])
    start = max(0, at - width // 3)
    piece = " ".join(text[start:start + width].split())
    return ("…" if start else "") + piece + ("…" if start + width < len(text) else "")


def search_files(query, mode="both", limit=40, budget=None):
    """
    Look for Glen's files by name, by what is in them, or both. Read-only.
    Stops when the time budget runs out and says so with truncated=True.
    """
    started = time.time()
    budget = FILE_SEARCH_SECONDS if budget is None else budget
    deadline = started + budget
    mode = mode if mode in ("name", "content", "both") else "both"
    wanted = [w for w in str(query or "").lower().split() if w][:12]
    phrase = " ".join(wanted)
    roots = file_roots()
    out = {"results": [], "truncated": False, "took_seconds": 0.0, "roots": [_tilde(r) for r in roots]}
    if len(phrase) < 2:
        return out

    by_name, candidates, seen = [], [], set()
    truncated = False
    stack = [(r, r) for r in reversed(roots) if not _excluded_dir(r)]
    while stack and not truncated:
        folder, root = stack.pop()
        if folder in seen:
            continue
        seen.add(folder)
        try:
            entries = list(os.scandir(folder))
        except OSError:
            continue
        subfolders = []
        for entry in entries:
            if time.time() > deadline:
                truncated = True
                break
            name = entry.name
            try:
                if entry.is_dir(follow_symlinks=False):
                    # Excluded folders are pruned here, before anything in them is looked at.
                    if not _excluded_name(name, True) and not _excluded_dir(entry.path):
                        subfolders.append(entry.path)
                    continue
                if _excluded_name(name, False):
                    continue
                path = entry.path
                if entry.is_symlink():
                    # A link is only ever followed to a file that is itself allowed.
                    if not allowed_file(path):
                        continue
                elif not entry.is_file(follow_symlinks=False):
                    continue
                rel = os.path.relpath(path, root).lower()
                if mode != "content" and all(w in rel for w in wanted):
                    by_name.append(path)
                elif mode != "name":
                    ext = os.path.splitext(name)[1].lower()
                    if ext in _FILE_TEXT_EXTENSIONS or ext in (".pdf", ".docx"):
                        candidates.append(path)
            except OSError:
                continue
        for sub in sorted(subfolders, reverse=True):
            stack.append((sub, root))

    def describe(path, match, snippet=""):
        try:
            st = os.stat(path)
        except OSError:
            return None
        item = {"path": path, "name": os.path.basename(path), "folder": _tilde(os.path.dirname(path)),
                "size": st.st_size,
                "modified": datetime.fromtimestamp(st.st_mtime, timezone.utc).isoformat(timespec="seconds"),
                "match": match}
        if match == "content":
            item["snippet"] = snippet
        return item, st.st_mtime

    found = []
    for path in by_name[:5000]:
        d = describe(path, "name")
        if d:
            found.append((0, -d[1], d[0]))

    if candidates and mode != "name":
        dated = []
        for path in candidates:
            try:
                dated.append((os.stat(path).st_mtime, path))
            except OSError:
                pass
        dated.sort(reverse=True)                      # newest first, so they are reached before time runs out
        hits = 0
        for _, path in dated:
            if time.time() > deadline:
                truncated = True
                break
            if hits >= limit:
                break
            real = allowed_file(path)
            got = file_text(real, FILE_CONTENT_MAX_BYTES, deadline, content_search=True) if real else None
            if not got:
                continue
            low = got[0].lower()
            if all(w in low for w in wanted):
                d = describe(path, "content", _snippet(got[0], low, wanted, phrase))
                if d:
                    found.append((1, -d[1], d[0]))
                    hits += 1

    found.sort(key=lambda x: (x[0], x[1], x[2]["path"]))
    out["results"] = [item for _, _, item in found[:limit]]
    out["truncated"] = truncated
    out["took_seconds"] = round(time.time() - started, 2)
    return out



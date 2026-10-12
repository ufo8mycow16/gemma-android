"""
Structure-aware document index (index version 2).

Audit of the v9 pipeline (Tiffany's), and what this replaces:

  * Headings were guessed by a regex on every line ("^Episode 3\\b.*"), so prose
    such as "Episode 3 will reveal..." became the stored heading of a chunk. v9
    worked around this at query time by re-scanning chunk text and scoring
    heading-like lines with document-specific words ("synopsis", "hud",
    "design bible"...). Here the cause is fixed at ingestion: DOCX heading styles
    are read from the file, and the fallback pattern only accepts a line that
    *is* a heading (short, standalone, the words after the number are a title,
    not a sentence).
  * DOCX tables were appended after all paragraphs, out of reading order. Here
    the body is read in order.
  * A chunk was labelled with the heading in force when it was *flushed*, so the
    text just before a new heading carried the new heading (preceding-boundary
    contamination), and chunks ran straight across section boundaries. Here
    chunks never cross a section boundary.
  * The 600-character overlap was copied from the end of one chunk into the next
    even across sections (next-section leakage, duplicated evidence). Here there
    is no cross-section overlap; inside a section the last paragraph is carried
    over only when it is short.
  * Chunks had no source positions. Here every section and chunk keeps its
    character range in the version text, and each version records the SHA-256 of
    the original file, so a cached index can never be paired with other bytes.
  * Structural questions are answered from the section table: the genuine
    "Episode 3" section is the longest section with that heading (a table of
    contents entry or a passing mention has almost no body), and the evidence is
    exactly that section's text, in order, ending where Episode 4 begins.
  * Chunk size is now ~800 tokens (3,200 characters, cut at paragraph
    boundaries, never above 4,800): small enough to rank precisely, large enough
    to carry a scene. Sizes are measured in estimated tokens (chars / 4).

What is kept: the original files, the extracted text, v9's chunks and its
completed hierarchy (section coverage and whole-document overview). The
hierarchy is built from v9's chunks and stays valid as a derived summary of the
same document, so a finished 34-section synthesis is never regenerated.
"""
from __future__ import annotations

import hashlib
import io
import json
import math
import re
import threading
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

from . import observe
from .routing import number_token

INDEX_VERSION = 2
TARGET_CHARS = 3200
MAX_CHARS = 4800
CARRY_CHARS = 500
STRUCTURAL_BUDGET = 60000        # characters of original text for one structural answer (~15k tokens)
HYBRID_BUDGET = 24000

_CTX: dict = {}

_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
_STRUCT_KINDS = ("episode", "chapter", "act", "scene", "part", "section", "prologue", "epilogue", "interlude",
                 "intermission", "appendix", "book")
_STRUCT_LEVEL = {"book": 0, "part": 1, "episode": 1, "chapter": 2, "act": 2, "section": 3, "scene": 3,
                 "prologue": 1, "epilogue": 1, "interlude": 2, "intermission": 2, "appendix": 1}
_HEAD_RE = re.compile(
    r"^(?P<kind>episode|chapter|act|scene|part|section|book|appendix)\s+(?P<num>\d{1,3}|[ivxlc]{1,6}|one|two|three|four|five|six|"
    r"seven|eight|nine|ten|eleven|twelve)\b(?P<tail>.*)$", re.I)
_NAMED_RE = re.compile(r"^(?P<kind>prologue|epilogue|interlude|intermission)\b(?P<tail>.*)$", re.I)
_MINOR = {"a", "an", "the", "of", "and", "or", "in", "on", "at", "to", "for", "with", "from", "by", "vs", "&"}


def init(*, db, lock, upload_dir: Path, embed, now, text_hash, embed_model: str = "doc"):
    _CTX.update(db=db, lock=lock, upload_dir=upload_dir, embed=embed, now=now, text_hash=text_hash, embed_model=embed_model)
    with lock, db() as con:
        con.executescript("""
        CREATE TABLE IF NOT EXISTS doc_versions(
          id INTEGER PRIMARY KEY AUTOINCREMENT, attachment_id INTEGER NOT NULL, sha256 TEXT NOT NULL,
          index_version INTEGER NOT NULL, text TEXT NOT NULL, chars INTEGER NOT NULL,
          heading_source TEXT, sections INTEGER NOT NULL DEFAULT 0, chunks INTEGER NOT NULL DEFAULT 0,
          embedded INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL,
          UNIQUE(attachment_id, sha256, index_version)
        );
        CREATE TABLE IF NOT EXISTS doc_sections(
          id INTEGER PRIMARY KEY AUTOINCREMENT, version_id INTEGER NOT NULL, attachment_id INTEGER NOT NULL,
          ordinal INTEGER NOT NULL, level INTEGER NOT NULL, kind TEXT, number INTEGER, title TEXT NOT NULL,
          path TEXT NOT NULL, parent_ordinal INTEGER, start_char INTEGER NOT NULL, end_char INTEGER NOT NULL,
          body_chars INTEGER NOT NULL, source TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS doc_sections_lookup ON doc_sections(attachment_id, kind, number);
        CREATE TABLE IF NOT EXISTS doc_chunks(
          id INTEGER PRIMARY KEY AUTOINCREMENT, version_id INTEGER NOT NULL, attachment_id INTEGER NOT NULL,
          section_ordinal INTEGER, ordinal INTEGER NOT NULL, start_char INTEGER NOT NULL, end_char INTEGER NOT NULL,
          heading_path TEXT NOT NULL, text TEXT NOT NULL, tokens INTEGER NOT NULL, carried INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS doc_chunks_by_doc ON doc_chunks(attachment_id, ordinal);
        CREATE TABLE IF NOT EXISTS doc_section_summaries(
          version_id INTEGER NOT NULL, section_ordinal INTEGER NOT NULL, summary TEXT NOT NULL,
          mode TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(version_id, section_ordinal)
        );
        -- Which document is the authority for which part of a story ("the Episode 1 master
        -- is authoritative for Episode 1; the complete script for Episode 2 onward").
        CREATE TABLE IF NOT EXISTS doc_authority(
          id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, number_from INTEGER NOT NULL,
          number_to INTEGER NOT NULL, attachment_id INTEGER NOT NULL, note TEXT, created_at TEXT NOT NULL
        );
        """)


# ---------------------------------------------------------------- extraction

@dataclass
class Block:
    kind: str                 # "heading", "para", "table", "page"
    text: str
    level: int | None = None  # heading level from the document's own styles
    source: str = ""          # "style", "pattern", "markdown", "page"


def _docx_style_levels(z: zipfile.ZipFile) -> dict:
    """style id -> heading level, from the document's styles (names like 'Heading 2', 'Title', or an outline level)."""
    levels = {}
    try:
        root = ET.fromstring(z.read("word/styles.xml"))
    except Exception:
        return levels
    for st in root.iter(_W + "style"):
        sid = st.get(_W + "styleId") or ""
        name_el = st.find(_W + "name")
        name = (name_el.get(_W + "val") if name_el is not None else "") or sid
        low = name.lower()
        outline = st.find(f"{_W}pPr/{_W}outlineLvl")
        if low == "title":
            levels[sid] = 0
        elif re.match(r"^heading\s*(\d)$", low):
            levels[sid] = int(re.match(r"^heading\s*(\d)$", low).group(1))
        elif outline is not None and (outline.get(_W + "val") or "").isdigit() and int(outline.get(_W + "val")) < 9:
            levels[sid] = int(outline.get(_W + "val")) + 1
    return levels


def _docx_blocks(data: bytes) -> list[Block]:
    """Paragraphs and tables in reading order, with heading levels taken from the file's own styles."""
    z = zipfile.ZipFile(io.BytesIO(data))
    levels = _docx_style_levels(z)
    root = ET.fromstring(z.read("word/document.xml"))
    body = root.find(_W + "body")
    out: list[Block] = []

    def para_text(p) -> str:
        parts = []
        for node in p.iter():
            tag = node.tag
            if tag == _W + "t" and node.text:
                parts.append(node.text)
            elif tag == _W + "tab":
                parts.append("\t")
            elif tag in (_W + "br", _W + "cr"):
                parts.append("\n")
        return "".join(parts)

    for el in list(body) if body is not None else []:
        if el.tag == _W + "p":
            text = " ".join(para_text(el).replace("\n", " \n ").split(" ")).strip()
            text = re.sub(r"[ \t]+", " ", text).strip()
            if not text:
                continue
            level = None
            ppr = el.find(_W + "pPr")
            if ppr is not None:
                st = ppr.find(_W + "pStyle")
                if st is not None and st.get(_W + "val") in levels:
                    level = levels[st.get(_W + "val")]
                ol = ppr.find(_W + "outlineLvl")
                if level is None and ol is not None and (ol.get(_W + "val") or "").isdigit() and int(ol.get(_W + "val")) < 9:
                    level = int(ol.get(_W + "val")) + 1
            if level is not None and len(text) <= 200:
                out.append(Block("heading", text, level, "style"))
            else:
                out.append(Block("para", text))
        elif el.tag == _W + "tbl":
            rows = []
            for tr in el.iter(_W + "tr"):
                cells = [" ".join(para_text(tc).split()) for tc in tr.iter(_W + "tc")]
                if any(cells):
                    rows.append(" | ".join(cells))
            if rows:
                out.append(Block("table", "\n".join(rows)))
    return out


def _pdf_blocks(data: bytes) -> list[Block]:
    from pypdf import PdfReader
    reader = PdfReader(io.BytesIO(data))
    out: list[Block] = []
    for i, page in enumerate(reader.pages, 1):
        text = (page.extract_text() or "").strip()
        if not text:
            continue
        out.append(Block("page", f"[Page {i}]", None, "page"))
        for para in re.split(r"\n\s*\n", text):
            for line in para.split("\n"):
                line = " ".join(line.split())
                if line:
                    out.append(Block("para", line))
    return out


def _text_blocks(text: str) -> list[Block]:
    out = []
    for line in str(text).replace("\r\n", "\n").split("\n"):
        line = line.rstrip()
        if not line.strip():
            continue
        m = re.match(r"^(#{1,6})\s+(.+?)\s*#*$", line)
        if m:
            out.append(Block("heading", m.group(2).strip(), len(m.group(1)), "markdown"))
        else:
            out.append(Block("para", " ".join(line.split())))
    return out


def extract_blocks(mime: str, data: bytes) -> list[Block]:
    if mime == "application/pdf":
        return _pdf_blocks(data)
    if mime.endswith("wordprocessingml.document"):
        return _docx_blocks(data)
    return _text_blocks(data.decode("utf-8-sig", errors="replace"))


# ------------------------------------------------------------ heading logic

def _title_like(tail: str) -> bool:
    """Is the text after 'Episode 3' a title (or nothing) rather than the rest of a sentence?"""
    t = tail.strip()
    if not t:
        return True
    if t[0] in ":-–—.|)(,[":
        return True
    words = re.findall(r"[A-Za-z0-9'’]+", t)
    if not words or len(words) > 10:
        return False
    capitalised = sum(1 for w in words if w[0].isupper() or w[0].isdigit() or w.lower() in _MINOR)
    return capitalised == len(words) and not t.rstrip().endswith((".", "?", "!")) or t.isupper()


def structural_heading(text: str):
    """(kind, number or None, title) if this line *is* a structural heading, else None."""
    s = " ".join(str(text or "").split()).strip().strip("*_#").strip()
    if not s or len(s) > 120:
        return None
    m = _HEAD_RE.match(s)
    if m:
        if not _title_like(m.group("tail")):
            return None
        return m.group("kind").lower(), number_token(m.group("num")) or _roman_big(m.group("num")), s
    m = _NAMED_RE.match(s)
    if m and _title_like(m.group("tail")):
        return m.group("kind").lower(), None, s
    return None


def _roman_big(raw: str):
    vals = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100}
    s = str(raw).lower()
    if not s or any(ch not in vals for ch in s):
        return None
    total = 0
    for i, ch in enumerate(s):
        v = vals[ch]
        total += -v if i + 1 < len(s) and vals[s[i + 1]] > v else v
    return total or None


def _generic_heading(text: str) -> bool:
    """An unstyled line that looks like a heading: short, all capitals, no sentence ending."""
    s = text.strip()
    words = s.split()
    return 1 <= len(words) <= 8 and len(s) <= 70 and s.isupper() and any(c.isalpha() for c in s) and not s.endswith((".", ",", ";"))


@dataclass
class Section:
    ordinal: int
    level: int
    kind: str | None
    number: int | None
    title: str
    start: int
    end: int = 0
    body_chars: int = 0
    parent: int | None = None
    source: str = ""
    path: str = ""


@dataclass
class Index:
    text: str
    sections: list[Section] = field(default_factory=list)
    chunks: list[dict] = field(default_factory=list)
    heading_source: str = "none"


def build_index(blocks: list[Block]) -> Index:
    """Canonical text, the section tree and section-bounded chunks, all with character positions."""
    styled = any(b.kind == "heading" and b.source == "style" for b in blocks)
    pieces: list[str] = []
    pos = 0
    marks = []          # (start, end, block, heading tuple or None)
    for b in blocks:
        head = None
        if b.kind == "heading":
            st = structural_heading(b.text)
            head = (st[0], st[1], b.text, b.level if b.level is not None else _STRUCT_LEVEL.get(st[0], 2), b.source) if st else \
                (None, None, b.text, b.level if b.level is not None else 2, b.source)
        elif b.kind == "para" and not styled:
            st = structural_heading(b.text)
            if st:
                head = (st[0], st[1], b.text, _STRUCT_LEVEL.get(st[0], 2), "pattern")
            elif _generic_heading(b.text):
                head = (None, None, b.text, 4, "pattern")
        elif b.kind == "para" and styled:
            # Even in a styled document, an unstyled line that is plainly "Episode 4" is still a boundary.
            st = structural_heading(b.text)
            if st and st[1] is not None:
                head = (st[0], st[1], b.text, _STRUCT_LEVEL.get(st[0], 2), "pattern")
        start = pos
        pieces.append(b.text)
        pos += len(b.text)
        marks.append((start, pos, b, head))
        pieces.append("\n")
        pos += 1
    text = "".join(pieces).rstrip("\n")

    idx = Index(text=text, heading_source="style" if styled else ("pattern" if any(m[3] for m in marks) else "none"))
    root = Section(0, -1, None, None, "(start)", 0, source="document")
    sections = [root]
    stack = [root]
    for start, end, b, head in marks:
        if head is None:
            continue
        kind, number, title, level, source = head
        while len(stack) > 1 and stack[-1].level >= level:
            stack.pop()
        sec = Section(len(sections), level, kind, number, title, start, parent=stack[-1].ordinal, source=source)
        sections.append(sec)
        stack.append(sec)
    # Each section runs until the next heading at the same or a higher level.
    for i, sec in enumerate(sections):
        sec.end = len(text)
        for later in sections[i + 1:]:
            if later.level <= sec.level:
                sec.end = later.start
                break
        # Own body: until the very next heading of any level.
        nxt = next((s.start for s in sections[i + 1:]), len(text))
        sec.body_chars = max(0, min(sec.end, nxt) - sec.start - len(sec.title))
    by_ord = {s.ordinal: s for s in sections}
    for sec in sections:
        names = []
        cur = sec
        while cur is not None and cur.ordinal != 0:
            names.append(cur.title[:80])
            cur = by_ord.get(cur.parent) if cur.parent is not None else None
        sec.path = " › ".join(reversed(names)) or "(start)"
    idx.sections = sections

    # Chunks: walk the leaf spans (from one heading to the next) and cut each at paragraph boundaries.
    boundaries = sorted({s.start for s in sections[1:]} | {0, len(text)})
    owner = {}
    for s in sections[1:]:
        owner[s.start] = s          # when headings share a start, the deepest one owns the text
    chunks = []
    for a, b in zip(boundaries, boundaries[1:]):
        if b <= a:
            continue
        sec = owner.get(a, root)
        paras = [(m.start(), m.end()) for m in re.finditer(r"[^\n]+", text[a:b])]
        cur_start = None
        cur_end = None
        carried = False
        for ps, pe in paras:
            ps += a
            pe += a
            if cur_start is None:
                cur_start, cur_end = ps, pe
                continue
            if pe - cur_start > TARGET_CHARS and cur_end - cur_start >= TARGET_CHARS // 3:
                chunks.append((sec, cur_start, cur_end, carried))
                last_para = text.rfind("\n", cur_start, cur_end)
                last_para = cur_start if last_para < 0 else last_para + 1
                if cur_end - last_para <= CARRY_CHARS and last_para > cur_start:
                    cur_start, carried = last_para, True
                else:
                    cur_start, carried = ps, False
            cur_end = pe
            while cur_end - cur_start > MAX_CHARS:          # one enormous paragraph
                cut = text.rfind(". ", cur_start + TARGET_CHARS // 2, cur_start + MAX_CHARS)
                cut = cut + 1 if cut > 0 else cur_start + MAX_CHARS
                chunks.append((sec, cur_start, cut, carried))
                cur_start, carried = cut, False
        if cur_start is not None and cur_end > cur_start:
            chunks.append((sec, cur_start, cur_end, carried))
    for i, (sec, s, e, carried) in enumerate(chunks):
        body = text[s:e].strip()
        if not body:
            continue
        idx.chunks.append({"ordinal": i, "section": sec.ordinal, "start": s, "end": e, "path": sec.path,
                           "text": body, "tokens": max(1, len(body) // 4), "carried": carried})
    return idx


# ----------------------------------------------------------------- storage

def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_for(attachment_id: int):
    db = _CTX["db"]
    with db() as con:
        return con.execute("SELECT * FROM doc_versions WHERE attachment_id=? AND index_version=? ORDER BY id DESC LIMIT 1",
                           (attachment_id, INDEX_VERSION)).fetchone()


def ensure_index(attachment_id: int, data: bytes | None = None, progress=None):
    """Index (or re-index) one document from its original bytes. Cheap: no model calls.
    Returns the doc_versions row. Embeddings are added in the background."""
    db, lock = _CTX["db"], _CTX["lock"]
    progress = progress or (lambda *a, **k: None)
    with db() as con:
        att = con.execute("SELECT id, filename, mime, original_name, extracted_text FROM attachments WHERE id=?", (attachment_id,)).fetchone()
    if att is None:
        return None
    if data is None:
        path = _CTX["upload_dir"] / att["filename"]
        data = path.read_bytes() if path.exists() else (att["extracted_text"] or "").encode("utf-8")
        mime = att["mime"] if path.exists() else "text/plain"
    else:
        mime = att["mime"]
    sha = _sha(data)
    with db() as con:
        existing = con.execute("SELECT * FROM doc_versions WHERE attachment_id=? AND sha256=? AND index_version=?",
                               (attachment_id, sha, INDEX_VERSION)).fetchone()
    if existing:
        observe.debug("DOCUMENT", "index cache hit", doc=attachment_id, version=existing["id"], sha=sha[:12])
        return existing
    with observe.span("DOCUMENT", "document indexed", doc=attachment_id, name=att["original_name"]) as info:
        progress("reading", f"Reading {att['original_name'] or 'document'}…", doc_stage="reading")
        try:
            blocks = extract_blocks(mime, data)
        except Exception as e:
            observe.warn("DOCUMENT", "structured extraction failed; indexing the stored text instead", doc=attachment_id, error=str(e))
            blocks = _text_blocks(att["extracted_text"] or "")
        progress("chunking", f"Chunking {att['original_name'] or 'document'}…", doc_stage="chunking")
        idx = build_index(blocks)
        with lock, db() as con:
            vid = con.execute(
                "INSERT INTO doc_versions(attachment_id,sha256,index_version,text,chars,heading_source,sections,chunks,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?)",
                (attachment_id, sha, INDEX_VERSION, idx.text, len(idx.text), idx.heading_source,
                 len(idx.sections) - 1, len(idx.chunks), _CTX["now"]())).lastrowid
            for s in idx.sections:
                con.execute(
                    "INSERT INTO doc_sections(version_id,attachment_id,ordinal,level,kind,number,title,path,parent_ordinal,"
                    "start_char,end_char,body_chars,source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (vid, attachment_id, s.ordinal, s.level, s.kind, s.number, s.title, s.path, s.parent,
                     s.start, s.end, s.body_chars, s.source))
            for c in idx.chunks:
                con.execute(
                    "INSERT INTO doc_chunks(version_id,attachment_id,section_ordinal,ordinal,start_char,end_char,heading_path,text,tokens,carried) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (vid, attachment_id, c["section"], c["ordinal"], c["start"], c["end"], c["path"], c["text"], c["tokens"], int(c["carried"])))
        info.update(version=vid, sha=sha[:12], blocks=len(blocks), headings_from=idx.heading_source,
                    sections=len(idx.sections) - 1, chunks=len(idx.chunks), chars=len(idx.text),
                    structural=[f"{s.kind} {s.number}" for s in idx.sections if s.kind and s.number][:40],
                    carried_overlaps=sum(1 for c in idx.chunks if c["carried"]))
    progress("indexing", f"Indexing {att['original_name'] or 'document'}…", doc_stage="indexing")
    threading.Thread(target=observe.run_in_context(_embed_version, vid), daemon=True, name=f"embed-doc-{attachment_id}").start()
    return version_for(attachment_id)


def _embed_version(version_id: int):
    db, lock, embed = _CTX["db"], _CTX["lock"], _CTX["embed"]
    with db() as con:
        rows = con.execute("SELECT id, heading_path, text FROM doc_chunks WHERE version_id=? ORDER BY ordinal", (version_id,)).fetchall()
    done = 0
    for i in range(0, len(rows), 24):
        batch = rows[i:i + 24]
        texts = [f"{r['heading_path']}\n{r['text']}" for r in batch]
        vectors = embed(texts, "document")
        if vectors is None:
            observe.event("DOCUMENT", "embeddings unavailable; word matching will be used", version=version_id, embedded=done)
            return
        with lock, db() as con:
            for r, t, v in zip(batch, texts, vectors):
                con.execute("INSERT OR REPLACE INTO embeddings(kind,ref_id,model,text_hash,vector) VALUES(?,?,?,?,?)",
                            ("doc_chunk", r["id"], _CTX.get("embed_model", "doc"), _CTX["text_hash"](t), v.tobytes()))
        done += len(batch)
    with lock, db() as con:
        con.execute("UPDATE doc_versions SET embedded=1 WHERE id=?", (version_id,))
    observe.event("DOCUMENT", "embeddings stored", version=version_id, chunks=done)


# --------------------------------------------------------------- authority

def set_authority(kind: str, number_from: int, number_to: int, attachment_id: int, note: str = "") -> int:
    db, lock = _CTX["db"], _CTX["lock"]
    with lock, db() as con:
        con.execute("DELETE FROM doc_authority WHERE kind=? AND number_from=? AND number_to=?", (kind, number_from, number_to))
        rid = con.execute("INSERT INTO doc_authority(kind,number_from,number_to,attachment_id,note,created_at) VALUES(?,?,?,?,?,?)",
                          (kind, number_from, number_to, attachment_id, note, _CTX["now"]())).lastrowid
    observe.event("DOCUMENT", "authority rule set", kind=kind, range=[number_from, number_to], doc=attachment_id)
    return rid


def authority_for(kind: str, number: int):
    db = _CTX["db"]
    with db() as con:
        row = con.execute("SELECT attachment_id FROM doc_authority WHERE kind=? AND ? BETWEEN number_from AND number_to "
                          "ORDER BY (number_to - number_from), id DESC LIMIT 1", (kind, number)).fetchone()
    return int(row["attachment_id"]) if row else None


def authorities() -> list[dict]:
    db = _CTX["db"]
    with db() as con:
        return [dict(r) for r in con.execute("SELECT * FROM doc_authority ORDER BY kind, number_from")]


# ---------------------------------------------------------------- retrieval

def find_sections(attachment_ids, kind: str, number: int) -> list[dict]:
    """Every genuine heading for kind+number in these documents, best (longest body) first."""
    if not attachment_ids:
        return []
    db = _CTX["db"]
    out = []
    with db() as con:
        for aid in attachment_ids:
            v = con.execute("SELECT id FROM doc_versions WHERE attachment_id=? AND index_version=? ORDER BY id DESC LIMIT 1",
                            (aid, INDEX_VERSION)).fetchone()
            if not v:
                continue
            for s in con.execute("SELECT * FROM doc_sections WHERE version_id=? AND kind=? AND number=?", (v["id"], kind, number)):
                out.append(dict(s, span_chars=s["end_char"] - s["start_char"]))
    out.sort(key=lambda s: (s["span_chars"], s["body_chars"]), reverse=True)
    return out


def section_text(version_id: int, start: int, end: int) -> str:
    db = _CTX["db"]
    with db() as con:
        row = con.execute("SELECT substr(text, ?, ?) AS t FROM doc_versions WHERE id=?", (start + 1, end - start, version_id)).fetchone()
    return row["t"] if row else ""


def structural_evidence(attachment_ids, kind: str, number: int, budget: int = STRUCTURAL_BUDGET, summarise=None, progress=None):
    """The original text of one structural section, exactly from its heading to where the next peer begins.
    Returns dict(found, attachment_id, section, text, truncated, reason, candidates)."""
    candidates = find_sections(attachment_ids, kind, number)
    result = {"found": False, "target": f"{kind} {number}", "candidates": [
        {"doc": c["attachment_id"], "title": c["title"], "chars": c["span_chars"], "source": c["source"]} for c in candidates[:6]]}
    if not candidates:
        result["reason"] = "no genuine heading for this section in the indexed documents"
        observe.event("RETRIEVAL", "structural target not located", target=result["target"], docs=list(attachment_ids))
        return result
    best = candidates[0]
    text = section_text(best["version_id"], best["start_char"], best["end_char"]).strip()
    db = _CTX["db"]
    with db() as con:
        nxt = con.execute("SELECT title FROM doc_sections WHERE version_id=? AND start_char>=? ORDER BY start_char LIMIT 1",
                          (best["version_id"], best["end_char"])).fetchone()
    result.update(found=True, attachment_id=best["attachment_id"], version_id=best["version_id"], section=best["title"],
                  path=best["path"], start=best["start_char"], end=best["end_char"], stop_boundary=nxt["title"] if nxt else "(end of document)",
                  truncated=False, mode="original")
    if len(text) > budget:
        summary = None
        if summarise is not None:
            summary = section_summary(best, summarise, progress)
        if summary:
            result.update(text=summary, mode="section_summary_of_original", truncated=False,
                          original_chars=len(text))
        else:
            cut = text.rfind("\n", 0, budget)
            result.update(text=text[:cut if cut > budget // 2 else budget], truncated=True, original_chars=len(text))
    else:
        result["text"] = text
    observe.event("RETRIEVAL", "structural section selected", doc=best["attachment_id"], target=result["target"],
                  heading=best["title"], heading_source=best["source"], range=[best["start_char"], best["end_char"]],
                  stop_boundary=result["stop_boundary"], evidence_chars=len(result.get("text") or ""), mode=result["mode"],
                  rejected=[{"title": c["title"], "chars": c["span_chars"]} for c in candidates[1:5]])
    return result


def section_summary(section_row: dict, summarise, progress=None) -> str | None:
    """A faithful summary of one section too long to give whole, built from its original chunks and cached."""
    db, lock = _CTX["db"], _CTX["lock"]
    vid, ordn = section_row["version_id"], section_row["ordinal"]
    with db() as con:
        cached = con.execute("SELECT summary FROM doc_section_summaries WHERE version_id=? AND section_ordinal=?", (vid, ordn)).fetchone()
        if cached:
            observe.event("DOCUMENT", "section summary cache hit", section=section_row["title"])
            return cached["summary"]
        rows = con.execute("SELECT text FROM doc_chunks WHERE version_id=? AND start_char>=? AND end_char<=? ORDER BY ordinal",
                           (vid, section_row["start_char"], section_row["end_char"])).fetchall()
    parts, group, size = [], [], 0
    for r in rows:
        if group and size + len(r["text"]) > 20000:
            parts.append("\n\n".join(group))
            group, size = [], 0
        group.append(r["text"])
        size += len(r["text"])
    if group:
        parts.append("\n\n".join(group))
    notes = []
    for i, part in enumerate(parts, 1):
        if progress:
            progress("analysing", f"Reading {section_row['title']} closely — part {i}/{len(parts)}", done=i - 1, total=len(parts))
        try:
            notes.append(summarise(f"Section: {section_row['title']} (part {i} of {len(parts)})\n\n{part}\n\n"
                                   "Write a dense, faithful account of everything that happens in this part, in order. "
                                   "Keep every named character, place and event. Add nothing that is not in the text."))
        except Exception as e:
            observe.warn("DOCUMENT", "section summary part failed", section=section_row["title"], part=i, error=str(e))
            return None
    summary = "\n\n".join(notes)
    with lock, db() as con:
        con.execute("INSERT OR REPLACE INTO doc_section_summaries(version_id,section_ordinal,summary,mode,created_at) VALUES(?,?,?,?,?)",
                    (vid, ordn, summary, "generated", _CTX["now"]()))
    return summary


_WORD = re.compile(r"[a-z0-9']{2,}")
_STOP = set("the a an and or of to in on at for with from by is are was were be been it its this that these those what which who "
            "how why when where do does did can could would should will i you he she they we me my your his her their our about "
            "tell please into than then there here just also so as if not no yes".split())


def _terms(text: str) -> list[str]:
    return [w for w in _WORD.findall(text.lower()) if w not in _STOP]


def hybrid_evidence(attachment_ids, query: str, semantic=None, budget: int = HYBRID_BUDGET, limit: int = 8):
    """Ranked original passages for a narrower question: words, meaning and section titles, then
    neighbouring passages from the same section only."""
    db = _CTX["db"]
    rows = []
    with db() as con:
        for aid in attachment_ids:
            v = con.execute("SELECT id FROM doc_versions WHERE attachment_id=? AND index_version=? ORDER BY id DESC LIMIT 1",
                            (aid, INDEX_VERSION)).fetchone()
            if v:
                rows += [dict(r) for r in con.execute("SELECT * FROM doc_chunks WHERE version_id=? ORDER BY ordinal", (v["id"],))]
    if not rows:
        return {"chunks": [], "reason": "no indexed text"}
    q = _terms(query)
    if not q:
        return {"chunks": [], "reason": "no searchable words in the question"}
    n = len(rows)
    df = {}
    docs_terms = []
    for r in rows:
        ts = _terms(r["text"])
        docs_terms.append(ts)
        for t in set(ts):
            df[t] = df.get(t, 0) + 1
    avg = sum(len(t) for t in docs_terms) / max(1, n)
    meaning = semantic(query) if semantic else {}
    scored = []
    for r, ts in zip(rows, docs_terms):
        tf = {}
        for t in ts:
            tf[t] = tf.get(t, 0) + 1
        bm25 = 0.0
        for t in set(q):
            if t in tf:
                idf = math.log(1 + (n - df[t] + 0.5) / (df[t] + 0.5))
                bm25 += idf * tf[t] * 2.2 / (tf[t] + 1.2 * (0.25 + 0.75 * len(ts) / max(1.0, avg)))
        head = len(set(q) & set(_terms(r["heading_path"]))) * 1.5
        sem = max(0.0, meaning.get(r["id"], 0.0)) * 6.0
        scored.append((bm25 + head + sem, bm25, head, sem, r))
    scored.sort(key=lambda x: x[0], reverse=True)
    seeds = [x for x in scored[:limit] if x[0] > 0.5]
    picked = {}
    by_key = {(r["attachment_id"], r["ordinal"]): r for r in rows}
    for score, *_rest, r in seeds:
        for j in (r["ordinal"] - 1, r["ordinal"], r["ordinal"] + 1):
            nb = by_key.get((r["attachment_id"], j))
            if nb and nb["section_ordinal"] == r["section_ordinal"]:      # never leak into the next section
                picked.setdefault((nb["attachment_id"], j), nb)
    chosen, used = [], 0
    for key in sorted(picked, key=lambda k: (k[0], k[1])):
        c = picked[key]
        if chosen and used + len(c["text"]) > budget:
            continue
        chosen.append(c)
        used += len(c["text"])
    observe.event("RETRIEVAL", "hybrid passages selected", query_terms=q[:12], candidates=len(rows),
                  top=[{"chunk": x[4]["ordinal"], "path": x[4]["heading_path"][:60], "score": round(x[0], 2), "lexical": round(x[1], 2),
                        "heading": x[2], "semantic": round(x[3], 2)} for x in scored[:6]],
                  selected=[c["ordinal"] for c in chosen], evidence_chars=used)
    return {"chunks": chosen, "reason": "" if chosen else "nothing in the indexed text matched the question"}


def outline(attachment_id: int, limit: int = 80) -> list[dict]:
    db = _CTX["db"]
    v = version_for(attachment_id)
    if not v:
        return []
    with db() as con:
        return [dict(r) for r in con.execute(
            "SELECT ordinal, level, kind, number, title, body_chars, end_char - start_char AS span FROM doc_sections "
            "WHERE version_id=? AND ordinal>0 AND level<=3 ORDER BY ordinal LIMIT ?", (v["id"], limit))]


def documents_with_index() -> list[dict]:
    db = _CTX["db"]
    with db() as con:
        return [dict(r) for r in con.execute(
            "SELECT a.id, a.original_name, a.mime, a.analysis_status, v.sections, v.chunks, v.chars, v.heading_source, v.embedded, v.created_at "
            "FROM attachments a LEFT JOIN doc_versions v ON v.attachment_id=a.id AND v.index_version=? "
            "WHERE a.mime IN ('application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','text/plain') "
            "ORDER BY a.id DESC", (INDEX_VERSION,))]

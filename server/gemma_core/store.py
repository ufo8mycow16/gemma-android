"""
Shared persistence for the platform layer (jobs, artifacts, approvals, plugins,
projects, schedules, usage). One database (gemma.db), the same connection helper
and lock as app.py, and only CREATE TABLE IF NOT EXISTS / ADD COLUMN migrations,
so an existing database is never rebuilt or wiped.

Multi-user foundation: every new table carries user_id. Gemma is single-user
today (user 1, "Glen"); nothing here assumes there will only ever be one.
"""
from __future__ import annotations

import json
import threading
from pathlib import Path

from . import observe

S: dict = {}

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, email TEXT, created_at TEXT NOT NULL,
  plan TEXT NOT NULL DEFAULT 'personal', status TEXT NOT NULL DEFAULT 'active',
  storage_quota_bytes INTEGER, deleted_at TEXT
);
CREATE TABLE IF NOT EXISTS usage_events(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL DEFAULT 1, kind TEXT NOT NULL,
  amount REAL NOT NULL, unit TEXT NOT NULL, ref TEXT, created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS usage_by_kind ON usage_events(user_id, kind, created_at);
CREATE TABLE IF NOT EXISTS jobs(
  id TEXT PRIMARY KEY, user_id INTEGER NOT NULL DEFAULT 1, kind TEXT NOT NULL, title TEXT NOT NULL,
  state TEXT NOT NULL, progress REAL, progress_text TEXT, conversation_id INTEGER, request_id TEXT,
  parent_id TEXT, params TEXT NOT NULL, result TEXT, error TEXT, dedupe_key TEXT UNIQUE,
  cancel_requested INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
  notify INTEGER NOT NULL DEFAULT 1, message_id INTEGER, report_back INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS jobs_by_state ON jobs(state, created_at);
CREATE TABLE IF NOT EXISTS job_events(
  id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL, ts TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, data TEXT
);
CREATE INDEX IF NOT EXISTS job_events_by_job ON job_events(job_id, id);
CREATE TABLE IF NOT EXISTS artifacts(
  id TEXT PRIMARY KEY, user_id INTEGER NOT NULL DEFAULT 1, conversation_id INTEGER, message_id INTEGER,
  kind TEXT NOT NULL, filename TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
  path TEXT NOT NULL, created_by TEXT NOT NULL, provenance TEXT NOT NULL, parent_id TEXT, job_id TEXT,
  request_id TEXT, description TEXT, created_at TEXT NOT NULL, deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS artifacts_by_conv ON artifacts(conversation_id, created_at);
CREATE TABLE IF NOT EXISTS approvals(
  id TEXT PRIMARY KEY, user_id INTEGER NOT NULL DEFAULT 1, conversation_id INTEGER, message_id INTEGER,
  request_id TEXT, plugin TEXT NOT NULL, tool TEXT NOT NULL, title TEXT NOT NULL, preview TEXT NOT NULL,
  arguments TEXT NOT NULL, state TEXT NOT NULL, result TEXT, idempotency_key TEXT UNIQUE,
  created_at TEXT NOT NULL, decided_at TEXT, executed_at TEXT, expires_at TEXT
);
CREATE TABLE IF NOT EXISTS tool_policies(
  user_id INTEGER NOT NULL DEFAULT 1, plugin TEXT NOT NULL, tool TEXT NOT NULL, policy TEXT NOT NULL,
  updated_at TEXT NOT NULL, PRIMARY KEY(user_id, plugin, tool)
);
CREATE TABLE IF NOT EXISTS plugin_state(
  user_id INTEGER NOT NULL DEFAULT 1, plugin TEXT NOT NULL, enabled INTEGER NOT NULL, settings TEXT NOT NULL DEFAULT '{}',
  updated_at TEXT NOT NULL, PRIMARY KEY(user_id, plugin)
);
CREATE TABLE IF NOT EXISTS mutation_log(
  idempotency_key TEXT PRIMARY KEY, user_id INTEGER NOT NULL DEFAULT 1, plugin TEXT NOT NULL, tool TEXT NOT NULL,
  result TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS projects(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL DEFAULT 1, name TEXT NOT NULL,
  instructions TEXT NOT NULL DEFAULT '', workspace TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
  archived_at TEXT
);
CREATE TABLE IF NOT EXISTS schedules(
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL DEFAULT 1, conversation_id INTEGER,
  title TEXT NOT NULL, kind TEXT NOT NULL, spec TEXT NOT NULL, prompt TEXT NOT NULL, condition TEXT,
  state TEXT NOT NULL DEFAULT 'active', last_run_at TEXT, next_run_at TEXT, last_value TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS workspaces(
  id TEXT PRIMARY KEY, user_id INTEGER NOT NULL DEFAULT 1, conversation_id INTEGER, project_id INTEGER,
  name TEXT NOT NULL, path TEXT NOT NULL, source TEXT, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS workspace_changes(
  id INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id TEXT NOT NULL, path TEXT NOT NULL, actor TEXT NOT NULL,
  action TEXT NOT NULL, before_sha TEXT, after_sha TEXT, request_id TEXT, job_id TEXT, note TEXT, created_at TEXT NOT NULL
);
"""

ADD_COLUMNS = (
    ("conversations", "user_id", "INTEGER DEFAULT 1"),
    ("conversations", "project_id", "INTEGER"),
    ("conversations", "deleted_at", "TEXT"),
    ("notifications", "title", "TEXT"),
    ("notifications", "link", "TEXT"),
    ("notifications", "level", "TEXT"),
    ("notifications", "read_at", "TEXT"),
    ("notifications", "user_id", "INTEGER DEFAULT 1"),
    ("memories", "user_id", "INTEGER DEFAULT 1"),
    ("attachments", "project_id", "INTEGER"),
    ("jobs", "message_id", "INTEGER"),
    ("jobs", "report_back", "INTEGER NOT NULL DEFAULT 0"),
)


def init(*, db, lock, now, app_dir: Path):
    S.update(db=db, lock=lock, now=now, app_dir=app_dir)
    with lock, db() as con:
        con.executescript(SCHEMA)
        for table, col, decl in ADD_COLUMNS:
            exists = con.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()
            if not exists:
                continue
            cols = {r["name"] for r in con.execute(f"PRAGMA table_info({table})")}
            if col not in cols:
                con.execute(f"ALTER TABLE {table} ADD COLUMN {col} {decl}")
        if not con.execute("SELECT 1 FROM users WHERE id=1").fetchone():
            con.execute("INSERT INTO users(id,name,created_at) VALUES(1,'Glen',?)", (now(),))


def db():
    return S["db"]()


def lock():
    return S["lock"]


def now():
    return S["now"]()


def app_dir() -> Path:
    return S["app_dir"]


def usage(kind: str, amount: float, unit: str, ref: str | None = None, user_id: int = 1) -> None:
    """Internal accounting (model calls, image jobs, storage, task time). Never shown as a meter."""
    try:
        with S["lock"], db() as con:
            con.execute("INSERT INTO usage_events(user_id,kind,amount,unit,ref,created_at) VALUES(?,?,?,?,?,?)",
                        (user_id, kind, float(amount), unit, ref, now()))
    except Exception:
        pass


def usage_summary(user_id: int = 1, days: int = 30) -> list[dict]:
    with db() as con:
        return [dict(r) for r in con.execute(
            "SELECT kind, unit, COUNT(*) AS events, SUM(amount) AS total FROM usage_events "
            "WHERE user_id=? AND created_at >= datetime('now', ?) GROUP BY kind, unit ORDER BY kind",
            (user_id, f"-{int(days)} days"))]


def jdump(value) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def jload(text, default=None):
    try:
        return json.loads(text) if text else default
    except Exception:
        return default


_local = threading.local()

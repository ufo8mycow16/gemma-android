"""
Skills: packaged know-how Gemma loads only when a message needs it.

A skill is a folder under skills/ with SKILL.md:

    ---
    name: godot-development
    description: Working on Godot projects and GDScript ...
    triggers: godot, gdscript, .gd, scene, node, signal, autoload
    tools: workspace, local_git, claude_code
    ---
    (instructions, examples, templates...)

Only the one-line metadata is kept in memory for routing; the body is read
from disk when a message matches (progressive loading), so ordinary chat never
carries any skill text.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from . import observe

MAX_SKILL_CHARS = 9000
MAX_PER_TURN = 2


@dataclass
class Skill:
    name: str
    description: str
    triggers: tuple
    tools: tuple
    path: Path


SKILLS: dict[str, Skill] = {}


def _front(text: str):
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
    if not m:
        return {}, text
    meta = {}
    for line in m.group(1).splitlines():
        k, _, v = line.partition(":")
        meta[k.strip().lower()] = v.strip()
    return meta, m.group(2)


def load(folder: Path):
    SKILLS.clear()
    if not folder.exists():
        return
    for skill_md in sorted(folder.glob("*/SKILL.md")):
        try:
            meta, _ = _front(skill_md.read_text(encoding="utf-8"))
        except Exception:
            continue
        name = meta.get("name") or skill_md.parent.name
        SKILLS[name] = Skill(name, meta.get("description", ""), tuple(t.strip().lower() for t in meta.get("triggers", "").split(",") if t.strip()),
                             tuple(t.strip() for t in meta.get("tools", "").split(",") if t.strip()), skill_md)
    observe.event("SYSTEM", "skills available", skills=list(SKILLS))


def match(text: str) -> list[Skill]:
    q = " " + " ".join(str(text or "").lower().split()) + " "
    scored = []
    for s in SKILLS.values():
        hits = sum(1 for t in s.triggers if (t.startswith(".") and t in q) or re.search(rf"\b{re.escape(t)}\b", q))
        if hits:
            scored.append((hits, s))
    scored.sort(key=lambda x: -x[0])
    return [s for _, s in scored[:MAX_PER_TURN]]


def body(skill: Skill) -> str:
    _, text = _front(skill.path.read_text(encoding="utf-8"))
    files = [p.name for p in skill.path.parent.iterdir() if p.name != "SKILL.md"]
    extra = f"\n(Supporting files in this skill: {', '.join(sorted(files))})" if files else ""
    return text.strip()[:MAX_SKILL_CHARS] + extra


def block_for(text: str) -> tuple[str, list[str]]:
    chosen = match(text)
    if not chosen:
        return "", []
    parts = [f'<skill name="{s.name}">\n{body(s)}\n</skill>' for s in chosen]
    observe.event("CONTEXT", "skills loaded for this turn", skills=[s.name for s in chosen], chars=sum(len(p) for p in parts))
    return ("\n\nSkills (working know-how for this request; follow it, but it is not your personality):\n" + "\n\n".join(parts)), [s.name for s in chosen]


def listing() -> list[dict]:
    return [{"name": s.name, "description": s.description, "triggers": list(s.triggers), "tools": list(s.tools)} for s in SKILLS.values()]

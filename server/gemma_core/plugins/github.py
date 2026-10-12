"""
GitHub: repositories, code, branches, commits, issues, pull requests, CI and
releases, read live from GitHub (never from a local checkout). Creating issues,
comments, branches, commits and pull requests always asks first.

Connect with a personal access token (Settings → Plugins → GitHub), or let it
use the GitHub CLI's login on Vision (`gh auth login`) if that is set up.
"""
from __future__ import annotations

import base64
import shutil
import subprocess
import time

import requests

from .. import observe, secrets
from ..tools import Tool, ToolFailure, register
from . import Plugin, add

API = "https://api.github.com"
WORDS = ("github", "repo", "repository", "pull request", "pr ", "prs", "issue", "issues", "commit", "branch", "release", "releases",
         "workflow", "ci", "actions", "build failing", "code search", "upstream")
_TOKEN_CACHE = {"t": None, "at": 0.0}


def token():
    t = secrets.get("github", "token")
    if t:
        return t
    if _TOKEN_CACHE["t"] and time.time() - _TOKEN_CACHE["at"] < 600:
        return _TOKEN_CACHE["t"]
    if shutil.which("gh"):
        try:
            p = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=10)
            if p.returncode == 0 and p.stdout.strip():
                _TOKEN_CACHE.update(t=p.stdout.strip(), at=time.time())
                return _TOKEN_CACHE["t"]
        except Exception:
            pass
    return None


def gh(method, path, params=None, body=None, accept="application/vnd.github+json", raw=False):
    t = token()
    if not t:
        raise ToolFailure("not_connected", "GitHub isn't connected. Add a token in Gemma's settings (Plugins → GitHub).")
    try:
        r = requests.request(method, API + path, params=params, json=body, timeout=30,
                             headers={"Authorization": f"Bearer {t}", "Accept": accept, "X-GitHub-Api-Version": "2022-11-28",
                                      "User-Agent": "Gemma-assistant"})
    except requests.RequestException as e:
        raise ToolFailure("network_error", f"Couldn't reach GitHub ({type(e).__name__}).")
    observe.debug("PLUGIN", f"github {method} {path}", status=r.status_code, remaining=r.headers.get("X-RateLimit-Remaining"))
    if r.status_code == 401:
        raise ToolFailure("auth_expired", "GitHub didn't accept the token; it may have expired.")
    if r.status_code == 403 and r.headers.get("X-RateLimit-Remaining") == "0":
        raise ToolFailure("rate_limited", "GitHub's rate limit is used up for now.")
    if r.status_code == 403:
        raise ToolFailure("permission_denied", "The GitHub token doesn't allow that.")
    if r.status_code == 404:
        raise ToolFailure("not_found", "GitHub says that doesn't exist (or the token can't see it).")
    if r.status_code == 422:
        raise ToolFailure("invalid_request", "GitHub refused that: " + r.text[:300])
    if r.status_code >= 400:
        raise ToolFailure("upstream_error", f"GitHub error {r.status_code}: {r.text[:300]}")
    if raw:
        return r.text
    return r.json() if r.text else {}


def _repo(args):
    full = str(args.get("repo") or "").strip().strip("/")
    if full.count("/") != 1:
        raise ToolFailure("invalid_request", "repo must be owner/name")
    return full


def _slim_issue(i):
    return {"number": i["number"], "title": i["title"], "state": i["state"], "user": (i.get("user") or {}).get("login"),
            "labels": [l["name"] for l in i.get("labels", [])], "comments": i.get("comments"), "updated": i.get("updated_at"),
            "url": i.get("html_url"), "is_pr": "pull_request" in i}


def repos(a, c):
    data = gh("GET", "/user/repos", {"per_page": 50, "sort": "updated"})
    return {"repos": [{"repo": r["full_name"], "private": r["private"], "default_branch": r["default_branch"],
                       "updated": r["pushed_at"], "description": r["description"]} for r in data]}


def repo_info(a, c):
    r = gh("GET", f"/repos/{_repo(a)}")
    return {k: r.get(k) for k in ("full_name", "description", "default_branch", "private", "open_issues_count", "stargazers_count",
                                  "pushed_at", "language", "html_url")}


def list_dir(a, c):
    path = str(a.get("path") or "").strip("/")
    data = gh("GET", f"/repos/{_repo(a)}/contents/{path}", {"ref": a.get("ref")} if a.get("ref") else None)
    if isinstance(data, dict):
        return {"file": data.get("path"), "size": data.get("size"), "note": "That is a file; use github_read_file."}
    return {"path": path or "/", "items": [{"name": d["name"], "type": d["type"], "size": d.get("size")} for d in data]}


def read_file(a, c):
    path = str(a.get("path") or "").strip("/")
    data = gh("GET", f"/repos/{_repo(a)}/contents/{path}", {"ref": a.get("ref")} if a.get("ref") else None)
    if isinstance(data, list):
        raise ToolFailure("invalid_request", "That is a folder.")
    text = base64.b64decode(data.get("content") or "").decode("utf-8", "replace")
    c["tainted"] = True
    return {"path": data["path"], "sha": data["sha"], "size": data["size"], "content": text[:30000], "truncated": len(text) > 30000}


def code_search(a, c):
    q = str(a.get("query") or "")
    if a.get("repo"):
        q += f" repo:{_repo(a)}"
    data = gh("GET", "/search/code", {"q": q, "per_page": 20}, accept="application/vnd.github.text-match+json")
    c["tainted"] = True
    return {"total": data.get("total_count"), "results": [{"repo": i["repository"]["full_name"], "path": i["path"],
            "matches": [m.get("fragment", "")[:300] for m in i.get("text_matches", [])[:2]]} for i in data.get("items", [])]}


def branches(a, c):
    return {"branches": [b["name"] for b in gh("GET", f"/repos/{_repo(a)}/branches", {"per_page": 100})]}


def commits(a, c):
    params = {"per_page": min(50, int(a.get("count") or 15))}
    if a.get("branch"):
        params["sha"] = a["branch"]
    if a.get("path"):
        params["path"] = a["path"]
    data = gh("GET", f"/repos/{_repo(a)}/commits", params)
    return {"commits": [{"sha": x["sha"][:10], "author": (x["commit"]["author"] or {}).get("name"), "date": x["commit"]["author"]["date"],
                         "message": x["commit"]["message"].split("\n")[0]} for x in data]}


def compare(a, c):
    data = gh("GET", f"/repos/{_repo(a)}/compare/{a.get('base')}...{a.get('head')}")
    return {"status": data.get("status"), "ahead_by": data.get("ahead_by"), "behind_by": data.get("behind_by"),
            "commits": [x["commit"]["message"].split("\n")[0] for x in data.get("commits", [])[:30]],
            "files": [{"file": f["filename"], "status": f["status"], "+": f["additions"], "-": f["deletions"]} for f in data.get("files", [])[:80]]}


def issues(a, c):
    data = gh("GET", f"/repos/{_repo(a)}/issues", {"state": a.get("state") or "open", "per_page": 30})
    c["tainted"] = True
    return {"issues": [_slim_issue(i) for i in data]}


def issue(a, c):
    n = int(a.get("number"))
    i = gh("GET", f"/repos/{_repo(a)}/issues/{n}")
    comments = gh("GET", f"/repos/{_repo(a)}/issues/{n}/comments", {"per_page": 30})
    c["tainted"] = True
    return dict(_slim_issue(i), body=(i.get("body") or "")[:8000],
                comments=[{"user": x["user"]["login"], "body": x["body"][:2000], "at": x["created_at"]} for x in comments])


def pulls(a, c):
    data = gh("GET", f"/repos/{_repo(a)}/pulls", {"state": a.get("state") or "open", "per_page": 30})
    return {"pulls": [{"number": p["number"], "title": p["title"], "user": p["user"]["login"], "head": p["head"]["ref"],
                       "base": p["base"]["ref"], "draft": p.get("draft"), "updated": p["updated_at"], "url": p["html_url"]} for p in data]}


def pull(a, c):
    n = int(a.get("number"))
    p = gh("GET", f"/repos/{_repo(a)}/pulls/{n}")
    files = gh("GET", f"/repos/{_repo(a)}/pulls/{n}/files", {"per_page": 100})
    diff = gh("GET", f"/repos/{_repo(a)}/pulls/{n}", accept="application/vnd.github.diff", raw=True)
    c["tainted"] = True
    return {"number": n, "title": p["title"], "state": p["state"], "merged": p.get("merged"), "body": (p.get("body") or "")[:6000],
            "head": p["head"]["ref"], "base": p["base"]["ref"], "files": [{"file": f["filename"], "+": f["additions"], "-": f["deletions"]} for f in files],
            "diff": diff[:40000], "diff_truncated": len(diff) > 40000}


def runs(a, c):
    data = gh("GET", f"/repos/{_repo(a)}/actions/runs", {"per_page": 10, **({"branch": a["branch"]} if a.get("branch") else {})})
    return {"runs": [{"id": r["id"], "workflow": r["name"], "branch": r["head_branch"], "status": r["status"], "conclusion": r["conclusion"],
                      "commit": r["head_sha"][:10], "started": r["run_started_at"], "url": r["html_url"]} for r in data.get("workflow_runs", [])]}


def releases(a, c):
    data = gh("GET", f"/repos/{_repo(a)}/releases", {"per_page": 10})
    return {"releases": [{"tag": r["tag_name"], "name": r["name"], "published": r["published_at"], "prerelease": r["prerelease"],
                          "assets": [x["name"] for x in r.get("assets", [])]} for r in data]}


# ---- changes (all ask first)

def create_issue(a, c):
    i = gh("POST", f"/repos/{_repo(a)}/issues", body={"title": a["title"], "body": a.get("body", "")})
    return {"created": i["number"], "url": i["html_url"]}


def comment(a, c):
    x = gh("POST", f"/repos/{_repo(a)}/issues/{int(a['number'])}/comments", body={"body": a["body"]})
    return {"commented": x["html_url"]}


def create_branch(a, c):
    repo = _repo(a)
    base = a.get("from") or gh("GET", f"/repos/{repo}")["default_branch"]
    sha = gh("GET", f"/repos/{repo}/git/ref/heads/{base}")["object"]["sha"]
    gh("POST", f"/repos/{repo}/git/refs", body={"ref": f"refs/heads/{a['name']}", "sha": sha})
    return {"branch": a["name"], "from": base}


def put_file(a, c):
    repo, path = _repo(a), str(a["path"]).strip("/")
    body = {"message": a["message"], "content": base64.b64encode(str(a["content"]).encode()).decode(), "branch": a["branch"]}
    try:
        cur = gh("GET", f"/repos/{repo}/contents/{path}", {"ref": a["branch"]})
        body["sha"] = cur["sha"]
    except ToolFailure as f:
        if f.status != "not_found":
            raise
    r = gh("PUT", f"/repos/{repo}/contents/{path}", body=body)
    return {"committed": r["commit"]["sha"][:10], "path": path, "branch": a["branch"]}


def create_pr(a, c):
    p = gh("POST", f"/repos/{_repo(a)}/pulls", body={"title": a["title"], "head": a["head"], "base": a.get("base") or "main", "body": a.get("body", "")})
    return {"created": p["number"], "url": p["html_url"]}


def review_pr(a, c):
    r = gh("POST", f"/repos/{_repo(a)}/pulls/{int(a['number'])}/reviews", body={"body": a["body"], "event": "COMMENT"})
    return {"reviewed": r.get("html_url")}


def _pv(title, *fields):
    return lambda a, c: {"title": title.format(**{k: a.get(k, "") for k in ("repo", "number", "title", "name", "path", "branch", "head", "base")}),
                         "fields": [[f, str(a.get(f.lower(), ""))[:300]] for f in fields],
                         "body": str(a.get("body") or a.get("content") or "")[:2000]}


class GitHubPlugin(Plugin):
    id, name, version, auth = "github", "GitHub", "1.0", "token"
    description = "Live GitHub: repositories, code, branches, commits, issues, pull requests, CI runs and releases."
    permissions = ("read repositories the token can see", "create issues, comments, branches, commits and pull requests: always asks first")

    def settings_schema(self):
        return {"token": {"type": "secret", "label": "Personal access token (fine-grained, the repositories you choose)"}}

    def connect_info(self):
        return {"how": "Create a fine-grained token at github.com/settings/tokens (Contents, Issues, Pull requests, Actions: read; "
                       "add write if you want Gemma to make changes) and paste it here. Or run `gh auth login` on Vision."}

    def health(self):
        if not token():
            return {"status": "not_connected", "message": "GitHub isn't connected yet."}
        try:
            u = gh("GET", "/user")
            return {"status": "ok", "message": f"Connected as {u.get('login')}", "account": u.get("login")}
        except ToolFailure as f:
            return {"status": f.status, "message": str(f)}

    def register(self):
        R = {"repo": {"type": "string", "description": "owner/name"}}
        k = WORDS
        reg = lambda name, desc, params, fn, req=("repo",), **kw: register(Tool(name, self.id, desc, params, fn, req, keywords=k, **kw))
        reg("github_repos", "Glen's GitHub repositories, most recently updated first.", {}, repos, (), label="Checking GitHub")
        reg("github_repo", "Details of one repository.", R, repo_info, label="Checking GitHub")
        reg("github_list_dir", "List a folder in a repository (optional ref).", dict(R, path={"type": "string"}, ref={"type": "string"}), list_dir, label="Browsing GitHub")
        reg("github_read_file", "Read a file from GitHub as it is now (optional ref).", dict(R, path={"type": "string"}, ref={"type": "string"}), read_file, ("repo", "path"), label="Reading from GitHub", reads_untrusted=True)
        reg("github_code_search", "Search code on GitHub (optionally in one repo).", {"query": {"type": "string"}, "repo": {"type": "string"}}, code_search, ("query",), label="Searching GitHub", reads_untrusted=True)
        reg("github_branches", "List branches.", R, branches, label="Checking GitHub")
        reg("github_commits", "Recent commits (optional branch, path).", dict(R, branch={"type": "string"}, path={"type": "string"}, count={"type": "integer"}), commits, label="Reading commits")
        reg("github_compare", "Compare two refs (base...head).", dict(R, base={"type": "string"}, head={"type": "string"}), compare, ("repo", "base", "head"), label="Comparing on GitHub")
        reg("github_issues", "List issues (state open/closed/all).", dict(R, state={"type": "string"}), issues, label="Reading issues", reads_untrusted=True)
        reg("github_issue", "One issue with its comments.", dict(R, number={"type": "integer"}), issue, ("repo", "number"), label="Reading an issue", reads_untrusted=True)
        reg("github_pulls", "List pull requests.", dict(R, state={"type": "string"}), pulls, label="Reading pull requests")
        reg("github_pull", "One pull request with files and diff.", dict(R, number={"type": "integer"}), pull, ("repo", "number"), label="Reading a pull request", reads_untrusted=True)
        reg("github_ci_runs", "Recent GitHub Actions runs (optional branch).", dict(R, branch={"type": "string"}), runs, label="Checking CI")
        reg("github_releases", "Recent releases.", R, releases, label="Checking releases")
        reg("github_create_issue", "Create an issue.", dict(R, title={"type": "string"}, body={"type": "string"}), create_issue, ("repo", "title"),
            mutation=True, risk="medium", label="Create a GitHub issue", preview=_pv("New issue in {repo}: {title}", "Repo", "Title"))
        reg("github_comment", "Comment on an issue or pull request.", dict(R, number={"type": "integer"}, body={"type": "string"}), comment, ("repo", "number", "body"),
            mutation=True, risk="medium", label="Post a GitHub comment", preview=_pv("Comment on {repo}#{number}", "Repo", "Number"))
        reg("github_create_branch", "Create a branch (from another branch, default the main one).", dict(R, name={"type": "string"}, **{"from": {"type": "string"}}),
            create_branch, ("repo", "name"), mutation=True, risk="low", label="Create a GitHub branch", preview=_pv("Create branch {name} in {repo}", "Repo", "Name"))
        reg("github_commit_file", "Create or update one file on a branch, as a commit.", dict(R, path={"type": "string"}, content={"type": "string"},
            message={"type": "string"}, branch={"type": "string"}), put_file, ("repo", "path", "content", "message", "branch"),
            mutation=True, risk="high", label="Commit a file on GitHub", preview=_pv("Commit {path} to {repo} ({branch})", "Repo", "Branch", "Path", "Message"))
        reg("github_create_pr", "Open a pull request.", dict(R, title={"type": "string"}, head={"type": "string"}, base={"type": "string"}, body={"type": "string"}),
            create_pr, ("repo", "title", "head"), mutation=True, risk="medium", label="Open a pull request", preview=_pv("Pull request {head} → {base} in {repo}", "Repo", "Title"))
        reg("github_review_pr", "Post a review comment on a pull request.", dict(R, number={"type": "integer"}, body={"type": "string"}),
            review_pr, ("repo", "number", "body"), mutation=True, risk="medium", label="Review a pull request", preview=_pv("Review on {repo}#{number}", "Repo", "Number"))


add(GitHubPlugin())

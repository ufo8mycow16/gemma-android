/* Gemma web client. Talks only to Gemma's own /api/ (never to Ollama, ComfyUI or anything else directly). */
'use strict';
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const ICON = {
  copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/></svg>',
  ok: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l5 5 9-10"/></svg>',
  play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 4v16l13-8z"/></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  send: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 12 20 4l-6 16-2.5-6.5z"/></svg>',
  down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v12M6 11l6 6 6-6M5 20h14"/></svg>',
  open: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 4h6v6M20 4l-9 9M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5"/></svg>',
  more: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
  x: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6 6 18"/></svg>',
  log: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>',
  edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
};

/* ------------------------------------------------------------------ state */
const S = {
  key: '', cid: null, convs: [], projects: [], status: null,
  turn: null,            // {id, row, text, ...} while Gemma is answering
  pending: [],           // attachments waiting to be sent
  oldest: null, hasMore: false, loadingMore: false,
  watchJobs: new Set(), jobStreamAbort: null,
  panel: null, panelTab: null, follow: true,
  playing: null,         // {mid, playId, btn, queue, audio}
};
try { S.key = localStorage.getItem('gemma_key') || ''; } catch (e) {}

/* ------------------------------------------------------------------ API */
async function api(path, opt = {}) {
  opt.headers = Object.assign({}, opt.headers || {});
  if (S.key) opt.headers['Authorization'] = 'Bearer ' + S.key;
  if (opt.json !== undefined) { opt.body = JSON.stringify(opt.json); opt.headers['Content-Type'] = 'application/json'; delete opt.json; }
  const r = await fetch(path, opt);
  if (r.status === 401) { showKeyGate('That access key was not accepted.'); throw new Error('access key required'); }
  return r;
}
async function jget(path) { const r = await api(path); const x = await r.json().catch(() => ({})); if (!r.ok) throw new Error(x.error || r.statusText); return x; }
async function jsend(path, method, json) { const r = await api(path, {method, json}); const x = await r.json().catch(() => ({})); if (!r.ok) throw new Error(x.error || r.statusText); return x; }
/* NDJSON reader: calls onEvent for each object. */
async function ndjson(resp, onEvent) {
  const reader = resp.body.getReader(), dec = new TextDecoder(); let buf = '';
  for (;;) {
    const got = await reader.read(); if (got.done) break;
    buf += dec.decode(got.value, {stream: true});
    const lines = buf.split('\n'); buf = lines.pop();
    for (const line of lines) { if (!line.trim()) continue; let ev; try { ev = JSON.parse(line); } catch (e) { continue; } onEvent(ev); }
  }
  if (buf.trim()) { try { onEvent(JSON.parse(buf)); } catch (e) {} }
}
/* Protected files (artifacts, attachments) need the key, so they are fetched and shown as blob URLs. */
const blobCache = new Map();
async function blobUrl(path) {
  if (blobCache.has(path)) return blobCache.get(path);
  const p = api(path).then(r => { if (!r.ok) throw new Error('could not load'); return r.blob(); }).then(b => URL.createObjectURL(b));
  blobCache.set(path, p); p.catch(() => blobCache.delete(path));
  return p;
}
function newId() { const a = new Uint8Array(12); crypto.getRandomValues(a); return Array.from(a, b => 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'[b % 62]).join(''); }
function toast(t, ms = 2600) { const el = $('toast'); el.textContent = t; el.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove('show'), ms); }
function fmtSize(n) { if (n == null) return ''; if (n < 1024) return n + ' B'; if (n < 1048576) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' KB'; return (n / 1048576).toFixed(1) + ' MB'; }
async function copyText(text, btn) {
  try { await navigator.clipboard.writeText(text); } catch (e) {
    const t = document.createElement('textarea'); t.value = text; document.body.appendChild(t); t.select(); document.execCommand('copy'); t.remove();
  }
  if (btn) { const old = btn.innerHTML; btn.innerHTML = ICON.ok; btn.classList.add('on'); setTimeout(() => { btn.innerHTML = old; btn.classList.remove('on'); }, 1200); }
}

/* ------------------------------------------------------------------ relative timestamps
   The same formatter the classic page uses (relativeTime/updateRelativeTimes), now on both sides of the chat. */
function relativeTime(ts){if(!ts)return '';let d=new Date(ts),sec=Math.max(0,Math.floor((Date.now()-d.getTime())/1000));if(Number.isNaN(sec))return '';if(sec<45)return 'Just now';if(sec<90)return 'About 1 min ago';if(sec<3600)return 'About '+Math.round(sec/60)+' mins ago';if(sec<5400)return 'About 1 hour ago';if(sec<86400)return 'About '+Math.round(sec/3600)+' hours ago';if(sec<172800)return 'Yesterday';return d.toLocaleDateString()}
function updateRelativeTimes(){document.querySelectorAll('[data-created-at]').forEach(el=>el.textContent=relativeTime(el.dataset.createdAt))}setInterval(updateRelativeTimes,30000);
function stamp(ts) { const s = document.createElement('span'); s.className = 'when'; s.dataset.createdAt = ts || new Date().toISOString(); s.textContent = relativeTime(s.dataset.createdAt); s.title = new Date(s.dataset.createdAt).toLocaleString(); return s; }

/* ------------------------------------------------------------------ syntax highlighting (built in, no network) */
const KW = {
  python: 'False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case self',
  gdscript: 'if elif else for while match break continue pass return class class_name extends is in as self signal func static const enum var onready export setget tool yield preload await super void null true false and or not breakpoint',
  js: 'break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await of null true false undefined',
  kotlin: 'as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor data enum finally get import init internal lateinit open override private protected public sealed set suspend',
  java: 'abstract boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long new package private protected public return short static super switch this throw throws try void volatile while null true false',
  c: 'auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace template typename public private protected virtual new delete nullptr true false using include define',
  bash: 'if then else elif fi for while do done case esac function in return export local echo cd sudo source',
  sql: 'select from where insert into values update set delete create table index join left right inner outer on group by order having limit as and or not null primary key',
  rust: 'as break const continue crate else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while async await dyn',
  go: 'break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false',
};
const TYPES = /^(int|float|bool|str|String|Vector2|Vector3|Node|Node2D|Node3D|Array|Dictionary|dict|list|tuple|set|void|Color|Resource|Object)$/;
const ALIAS = {py: 'python', python3: 'python', gd: 'gdscript', godot: 'gdscript', javascript: 'js', ts: 'js', typescript: 'js', jsx: 'js', tsx: 'js', json: 'js',
  kt: 'kotlin', kts: 'kotlin', sh: 'bash', shell: 'bash', zsh: 'bash', console: 'bash', cpp: 'c', 'c++': 'c', h: 'c', hpp: 'c', cs: 'java', csharp: 'java', rs: 'rust', golang: 'go'};
function highlight(code, lang) {
  lang = (lang || '').toLowerCase(); lang = ALIAS[lang] || lang;
  if (!KW[lang]) return esc(code);
  const kws = new Set(KW[lang].split(' '));
  const hashC = ['python', 'gdscript', 'bash'].includes(lang), slashC = !hashC || lang === 'gdscript' && false;
  let out = '', i = 0; const n = code.length;
  const isId = c => /[A-Za-z0-9_]/.test(c);
  while (i < n) {
    const c = code[i], rest = code.slice(i, i + 3);
    if (hashC && c === '#') { const e = code.indexOf('\n', i); const j = e < 0 ? n : e; out += '<span class="tok-c">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if (!hashC && c === '/' && code[i + 1] === '/') { const e = code.indexOf('\n', i); const j = e < 0 ? n : e; out += '<span class="tok-c">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if (!hashC && c === '/' && code[i + 1] === '*') { const e = code.indexOf('*/', i + 2); const j = e < 0 ? n : e + 2; out += '<span class="tok-c">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if (lang === 'sql' && c === '-' && code[i + 1] === '-') { const e = code.indexOf('\n', i); const j = e < 0 ? n : e; out += '<span class="tok-c">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if ((rest === '"""' || rest === "'''") && (lang === 'python' || lang === 'gdscript')) { const e = code.indexOf(rest, i + 3); const j = e < 0 ? n : e + 3; out += '<span class="tok-s">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if (c === '"' || c === "'" || c === '`' && lang === 'js') {
      let j = i + 1; while (j < n && code[j] !== c && code[j] !== '\n') { if (code[j] === '\\') j++; j++; }
      j = Math.min(n, j + 1); out += '<span class="tok-s">' + esc(code.slice(i, j)) + '</span>'; i = j; continue;
    }
    if (c === '@' && /[A-Za-z_]/.test(code[i + 1] || '')) { let j = i + 1; while (j < n && (isId(code[j]) || code[j] === '.')) j++; out += '<span class="tok-d">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if (/[0-9]/.test(c) && !isId(code[i - 1] || ' ')) { let j = i; while (j < n && /[0-9a-fA-FxXoObB_.eE]/.test(code[j])) j++; out += '<span class="tok-n">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    if (/[A-Za-z_$]/.test(c)) {
      let j = i; while (j < n && (isId(code[j]) || code[j] === '$')) j++;
      const w = code.slice(i, j);
      const kw = lang === 'sql' ? kws.has(w.toLowerCase()) : kws.has(w);
      if (kw) out += '<span class="tok-k">' + esc(w) + '</span>';
      else if (TYPES.test(w) || /^[A-Z][A-Za-z0-9]*[a-z]/.test(w) && lang !== 'bash') out += '<span class="tok-t">' + esc(w) + '</span>';
      else if (code[j] === '(') out += '<span class="tok-f">' + esc(w) + '</span>';
      else out += esc(w);
      i = j; continue;
    }
    if (lang === 'bash' && c === '$') { let j = i + 1; while (j < n && (isId(code[j]) || code[j] === '{' || code[j] === '}')) j++; out += '<span class="tok-b">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
    out += esc(c); i++;
  }
  return out;
}
const EXT_FOR = {python: 'py', gdscript: 'gd', js: 'js', javascript: 'js', typescript: 'ts', json: 'json', kotlin: 'kt', java: 'java', c: 'c', cpp: 'cpp', bash: 'sh', sh: 'sh', sql: 'sql', rust: 'rs', go: 'go', html: 'html', css: 'css', yaml: 'yml', yml: 'yml', toml: 'toml', markdown: 'md', md: 'md', xml: 'xml', csv: 'csv', ini: 'ini', tscn: 'tscn', cfg: 'cfg'};
function codeBlock(code, lang, filename, live) {
  const box = document.createElement('div'); box.className = 'code';
  const lines = code.split('\n').length;
  box.innerHTML = '<div class="head"><span class="lang">' + esc(lang || 'text') + '</span>' + (filename ? '<span class="file">' + esc(filename) + '</span>' : '') +
    '<span class="sp"></span>' + (live ? '<span class="live">writing…</span>' : '') +
    '<button class="icon-btn" data-a="dl" title="Download">' + ICON.down + '</button><button class="icon-btn" data-a="copy" title="Copy code">' + ICON.copy + '</button></div>' +
    '<pre><code>' + highlight(code, lang) + '</code></pre>';
  box.querySelector('[data-a=copy]').onclick = e => copyText(code, e.currentTarget);
  box.querySelector('[data-a=dl]').onclick = () => {
    const name = filename || ('snippet.' + (EXT_FOR[(lang || '').toLowerCase()] || 'txt'));
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([code], {type: 'text/plain'})); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  };
  if (lines > 40 && !live) {
    box.classList.add('collapsed');
    const more = document.createElement('button'); more.className = 'more'; more.textContent = `Show all ${lines} lines`;
    more.onclick = () => { const c = box.classList.toggle('collapsed'); more.textContent = c ? `Show all ${lines} lines` : 'Show less'; };
    box.appendChild(more);
  }
  return box;
}

/* ------------------------------------------------------------------ markdown (safe: everything is escaped first) */
function inline(s) {
  const codes = [];
  s = s.replace(/`([^`\n]+)`/g, (m, c) => { codes.push(c); return '\u0000' + (codes.length - 1) + '\u0000'; });
  s = esc(s);
  s = s.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, (m, t, u) => '<a href="' + u.replace(/"/g, '%22') + '" target="_blank" rel="noopener noreferrer">' + t + '</a>');
  s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)]+[^\s<).,;:!?])/g, (m, p, u) => p + '<a href="' + u.replace(/"/g, '%22') + '" target="_blank" rel="noopener noreferrer">' + u + '</a>');
  s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>').replace(/__([^_\n]+)__/g, '<strong>$1</strong>');
  s = s.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>').replace(/(^|[^\w])_([^_\n]+)_(?!\w)/g, '$1<em>$2</em>');
  s = s.replace(/~~([^~\n]+)~~/g, '<del>$1</del>');
  return s.replace(/\u0000(\d+)\u0000/g, (m, i) => '<code class="inline">' + esc(codes[+i]) + '</code>');
}
function renderMarkdown(el, text, live) {
  el.innerHTML = '';
  const lines = String(text || '').replace(/\r\n?/g, '\n').split('\n');
  let i = 0, para = [];
  const flush = () => { if (para.length) { const p = document.createElement('p'); p.innerHTML = para.map(inline).join('<br>'); el.appendChild(p); para = []; } };
  while (i < lines.length) {
    const line = lines[i];
    const fence = line.match(/^\s*(```+|~~~+)\s*([^\s`]*)\s*(.*)$/);
    if (fence) {
      flush();
      const mark = fence[1], lang = fence[2] || ''; let info = fence[3] || '';
      const fm = info.match(/(?:title|file|filename)\s*=\s*"?([^"\s]+)"?/) || info.match(/^([\w./-]+\.\w+)$/);
      let filename = fm ? fm[1] : '';
      const body = []; i++;
      while (i < lines.length && !lines[i].trim().startsWith(mark.slice(0, 3))) { body.push(lines[i]); i++; }
      const closed = i < lines.length; i++;
      if (!filename && body.length) { const f = body[0].match(/^\s*(?:#|\/\/)\s*(?:file:\s*)?([\w./-]+\.(?:py|gd|js|ts|kt|java|c|cpp|h|sh|json|toml|yml|yaml|md|tscn|cfg|html|css|sql|rs|go))\s*$/i); if (f) filename = f[1]; }
      el.appendChild(codeBlock(body.join('\n'), lang, filename, live && !closed));
      continue;
    }
    if (/^\s*$/.test(line)) { flush(); i++; continue; }
    const h = line.match(/^(#{1,4})\s+(.+)$/);
    if (h) { flush(); const e = document.createElement('h' + h[1].length); e.innerHTML = inline(h[2]); el.appendChild(e); i++; continue; }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { flush(); el.appendChild(document.createElement('hr')); i++; continue; }
    if (/^\s*>/.test(line)) {
      flush(); const q = []; while (i < lines.length && /^\s*>/.test(lines[i])) { q.push(lines[i].replace(/^\s*>\s?/, '')); i++; }
      const b = document.createElement('blockquote'); renderMarkdown(b, q.join('\n')); el.appendChild(b); continue;
    }
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-+/.test(lines[i + 1])) {
      flush(); const rows = []; while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { rows.push(lines[i]); i++; }
      const cells = r => r.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      const t = document.createElement('table');
      t.innerHTML = '<thead><tr>' + cells(rows[0]).map(c => '<th>' + inline(c) + '</th>').join('') + '</tr></thead><tbody>' +
        rows.slice(2).map(r => '<tr>' + cells(r).map(c => '<td>' + inline(c) + '</td>').join('') + '</tr>').join('') + '</tbody>';
      el.appendChild(t); continue;
    }
    const li = line.match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/);
    if (li) {
      flush();
      const root = document.createElement(/\d/.test(li[2]) ? 'ol' : 'ul'); const stack = [{el: root, ind: li[1].length}];
      if (/\d/.test(li[2]) && parseInt(li[2]) > 1) root.start = parseInt(li[2]);
      while (i < lines.length) {
        const m = lines[i].match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/);
        if (!m) { if (/^\s{2,}\S/.test(lines[i]) && stack[stack.length - 1].el.lastChild) { stack[stack.length - 1].el.lastChild.innerHTML += '<br>' + inline(lines[i].trim()); i++; continue; } break; }
        const ind = m[1].length;
        while (stack.length > 1 && ind < stack[stack.length - 1].ind) stack.pop();
        let cur = stack[stack.length - 1];
        if (ind > cur.ind + 1 && cur.el.lastChild) { const sub = document.createElement(/\d/.test(m[2]) ? 'ol' : 'ul'); cur.el.lastChild.appendChild(sub); cur = {el: sub, ind}; stack.push(cur); }
        const item = document.createElement('li'); const task = m[3].match(/^\[( |x)\]\s+(.*)$/i);
        item.innerHTML = task ? (task[1].trim() ? '☑ ' : '☐ ') + inline(task[2]) : inline(m[3]);
        cur.el.appendChild(item); i++;
      }
      el.appendChild(root); continue;
    }
    para.push(line); i++;
  }
  flush();
}

/* ------------------------------------------------------------------ scrolling: follow only while Glen is at the bottom */
const scroller = $('scroller');
function atBottom() { return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 90; }
function toBottom(smooth) { scroller.scrollTo({top: scroller.scrollHeight, behavior: smooth ? 'smooth' : 'auto'}); S.follow = true; $('jump').classList.remove('show', 'new'); }
function contentGrew() { if (S.follow) scroller.scrollTop = scroller.scrollHeight; else $('jump').classList.add('show', 'new'); }
scroller.addEventListener('scroll', () => {
  S.follow = atBottom();
  $('jump').classList.toggle('show', !S.follow);
  if (S.follow) $('jump').classList.remove('new');
  if (scroller.scrollTop < 120 && S.hasMore && !S.loadingMore) loadEarlier();
}, {passive: true});
$('jump').onclick = () => toBottom(true);

/* ------------------------------------------------------------------ messages */
function emptyState() {
  const m = $('messages');
  m.innerHTML = '<div class="empty"><img class="portrait" src="/web/img/gemma_portrait.jpg" alt=""><h1>Hi Glen.</h1><div>What are we working on?</div>' +
    '<div class="ideas"><button>Review this script for me</button><button>Write a GDScript player controller</button><button>Make me a picture</button><button>What\'s in my inbox?</button></div></div>';
  m.querySelectorAll('.ideas button').forEach(b => b.onclick = () => { $('input').value = b.textContent; inputChanged(); $('input').focus(); });
}
function messageRow(m) {
  const row = document.createElement('div'); row.className = 'row ' + (m.role === 'user' ? 'user' : 'assistant');
  if (m.id) row.dataset.mid = m.id;
  if (m.role !== 'user') { const av = document.createElement('img'); av.className = 'av'; av.src = '/web/img/gemma_face.jpg'; av.alt = ''; row.appendChild(av); }
  const col = document.createElement('div'); col.className = 'col';
  const bubble = document.createElement('div'); bubble.className = 'bubble' + (m.role === 'user' ? '' : ' md');
  if (m.role === 'user') bubble.textContent = m.text || ''; else renderMarkdown(bubble, m.text || '');
  if (m.role === 'user' && !m.text && (m.attachments?.length || m.artifacts?.length)) bubble.hidden = true;
  const cards = document.createElement('div'); cards.className = 'cards';
  col.append(bubble, cards);
  (m.attachments || []).forEach(a => cards.appendChild(attachmentCard(a)));
  (m.artifacts || []).forEach(a => cards.appendChild(artifactCard(a)));
  const shown = new Set((m.artifacts || []).map(a => a.id));
  // A finished picture is already on the message as a file; its job card would only repeat it.
  (m.jobs || []).filter(j => !(j.state === 'done' && j.result?.artifact_id && shown.has(j.result.artifact_id))).forEach(j => { cards.appendChild(jobCard(j)); watchJob(j); });
  (m.approvals || []).forEach(p => cards.appendChild(approvalCard(p)));
  if (!cards.children.length) cards.hidden = true;
  if (m.research_log) col.appendChild(researchToggle(m.research_log));
  const meta = document.createElement('div'); meta.className = 'meta';
  const copy = document.createElement('button'); copy.className = 'icon-btn'; copy.title = 'Copy'; copy.innerHTML = ICON.copy;
  copy.onclick = () => copyText(row._text ?? m.text ?? '', copy);
  if (m.role === 'user') meta.append(stamp(m.created_at), copy);
  else {
    meta.append(copy);
    const play = document.createElement('button'); play.className = 'icon-btn speak'; play.title = 'Play aloud'; play.innerHTML = ICON.play;
    play.onclick = () => speak(row, play); meta.append(play, stamp(m.created_at));
  }
  col.appendChild(meta);
  row.appendChild(col);
  row._text = m.text || ''; row._bubble = bubble; row._cards = cards; row._meta = meta; row._col = col;
  return row;
}
function researchToggle(log) {
  const wrap = document.createElement('div'); wrap.className = 'research';
  const b = document.createElement('button'); b.className = 'btn ghost small'; b.innerHTML = ICON.log + 'Research log';
  const pre = document.createElement('pre'); pre.textContent = log; pre.hidden = true;
  b.onclick = () => { pre.hidden = !pre.hidden; };
  wrap.append(b, pre); return wrap;
}
function addCard(row, el) { row._cards.hidden = false; row._cards.appendChild(el); }

async function openChat(cid, opts = {}) {
  if (S.turn && S.turn.cid !== cid) { /* the turn carries on in its own chat; the server saves it */ }
  S.cid = cid; S.oldest = null; S.hasMore = false;
  try { localStorage.setItem('gemma_last_chat', String(cid)); } catch (e) {}
  const c = S.convs.find(x => x.id === cid);
  $('chatTitle').textContent = c?.title || 'Gemma';
  const p = c?.project_id && S.projects.find(x => x.id === c.project_id);
  $('projChip').hidden = !p; if (p) $('projChip').textContent = p.name;
  renderChatList();
  $('side').classList.remove('open');
  const m = $('messages'); m.innerHTML = '<div class="activity"><span class="spin"></span>Loading…</div>';
  let x;
  try { x = await jget(`/api/v1/conversations/${cid}/messages?limit=50`); } catch (e) { m.innerHTML = '<div class="problem">' + esc(e.message) + '</div>'; return; }
  if (S.cid !== cid) return;
  m.innerHTML = '';
  if (!x.messages.length) emptyState();
  x.messages.forEach(msg => m.appendChild(messageRow(msg)));
  S.oldest = x.messages[0]?.id || null; S.hasMore = x.has_more;
  if (S.hasMore) m.prepend(earlierMarker());
  toBottom();
  if (x.active_turn_id && !S.turn) resumeActive(x.active_turn_id, cid);
  if (S.panel) renderPanel();
  if (!opts.keepFocus && innerWidth > 820) $('input').focus();
}
function earlierMarker() { const d = document.createElement('div'); d.id = 'earlier'; d.className = 'note'; d.style.textAlign = 'center'; d.style.margin = '0 0 18px'; d.textContent = 'Scroll up for earlier messages'; return d; }
async function loadEarlier() {
  if (!S.oldest || S.loadingMore) return; S.loadingMore = true;
  const cid = S.cid;
  try {
    const x = await jget(`/api/v1/conversations/${cid}/messages?limit=50&before=${S.oldest}`);
    if (S.cid !== cid) return;
    const m = $('messages'), before = scroller.scrollHeight, top = scroller.scrollTop;
    $('earlier')?.remove();
    const frag = document.createDocumentFragment(); x.messages.forEach(msg => frag.appendChild(messageRow(msg)));
    m.prepend(frag);
    S.oldest = x.messages[0]?.id || S.oldest; S.hasMore = x.has_more; if (S.hasMore) m.prepend(earlierMarker());
    scroller.scrollTop = top + (scroller.scrollHeight - before);    // keep the reading position exactly
  } catch (e) {} finally { S.loadingMore = false; }
}
async function resumeActive(turnId, cid) {
  // Gemma is still answering a turn started elsewhere (another tab or the phone): show it and pick up the answer when saved.
  const row = messageRow({role: 'assistant', text: ''}); row._bubble.innerHTML = '<div class="activity"><span class="spin"></span>Gemma is still answering…</div>';
  $('messages').appendChild(row); contentGrew();
  for (let k = 0; k < 900; k++) {
    await new Promise(r => setTimeout(r, 2000));
    if (S.cid !== cid) return;
    const t = await jget('/api/v1/turns/' + turnId).catch(() => null);
    if (t && t.state !== 'active') { openChat(cid, {keepFocus: true}); return; }
  }
}

/* ------------------------------------------------------------------ cards */
function extOf(name) { const m = String(name || '').match(/\.([A-Za-z0-9]+)$/); return m ? m[1].toLowerCase() : ''; }
function attachmentCard(a) {
  // Pictures Glen attached in earlier versions (stored as message attachments).
  const card = document.createElement('div'); card.className = 'card imgcard';
  const img = document.createElement('img'); img.alt = a.name || 'attachment'; card.appendChild(img);
  blobUrl(a.url).then(u => { img.src = u; img.onclick = () => showImage(u, a.name || 'Picture'); }).catch(() => { card.className = 'card file'; card.innerHTML = '<div class="ic">IMG</div><div class="nm"><b>Picture</b><span>could not be loaded</span></div>'; });
  return card;
}
function artifactCard(a) {
  if (a.kind === 'image' || /^image\//.test(a.mime || '')) return imageCard(a);
  const card = document.createElement('div'); card.className = 'card file'; card.dataset.artifact = a.id;
  const by = a.created_by === 'user' ? 'You attached' : a.created_by === 'claude_code' ? 'Claude Code' : a.created_by === 'gemma' ? 'Gemma made' : (a.created_by || '');
  card.innerHTML = '<div class="ic">' + esc(extOf(a.filename).slice(0, 4) || 'file') + '</div><div class="nm"><b>' + esc(a.filename) + '</b><span>' +
    esc([fmtSize(a.size), by, a.description].filter(Boolean).join(' · ')) + '</span></div><div class="acts"></div>';
  const acts = card.querySelector('.acts');
  const pv = document.createElement('button'); pv.className = 'icon-btn'; pv.title = 'Open'; pv.innerHTML = ICON.open; pv.onclick = () => previewArtifact(a);
  const dl = document.createElement('button'); dl.className = 'icon-btn'; dl.title = 'Download'; dl.innerHTML = ICON.down; dl.onclick = () => downloadArtifact(a);
  acts.append(pv, dl);
  card.querySelector('.nm').style.cursor = 'pointer'; card.querySelector('.nm').onclick = () => previewArtifact(a);
  return card;
}
function imageCard(a) {
  const card = document.createElement('div'); card.className = 'card imgcard'; card.dataset.artifact = a.id;
  const img = document.createElement('img'); img.alt = a.description || a.filename;
  const prov = a.provenance || {};
  const cap = prov.prompt ? prov.prompt : (a.description || a.filename);
  card.appendChild(img);
  const bar = document.createElement('div'); bar.className = 'bar';
  bar.innerHTML = '<span class="cap" title="' + esc(cap) + '">' + esc(cap) + '</span>';
  const dl = document.createElement('button'); dl.className = 'icon-btn'; dl.title = 'Download'; dl.innerHTML = ICON.down; dl.onclick = () => downloadArtifact(a);
  const ed = document.createElement('button'); ed.className = 'icon-btn'; ed.title = 'Ask Gemma to edit this picture'; ed.innerHTML = ICON.edit;
  ed.onclick = () => { $('input').value = `Edit the picture ${a.filename} (${a.id}): `; inputChanged(); $('input').focus(); };
  bar.append(ed, dl); card.appendChild(bar);
  blobUrl(a.url).then(u => { img.src = u; img.onload = () => contentGrew(); img.onclick = () => showImage(u, cap); }).catch(() => { bar.querySelector('.cap').textContent = 'The picture could not be loaded.'; });
  return card;
}
async function downloadArtifact(a) {
  try { const u = await blobUrl(a.url); const l = document.createElement('a'); l.href = u; l.download = a.filename; l.click(); } catch (e) { toast('Download failed: ' + e.message); }
}
/* Document progress: real numbers from the server (done/total/percent from the processing itself). */
function docProgressCard(ev) {
  const card = document.createElement('div'); card.className = 'card progress doc';
  card.innerHTML = '<div class="top"><div class="lbl"></div><div class="pct unknown">…</div></div><div class="track indet"><i></i></div>' +
    '<div class="sub"><span class="msg"></span><span class="sp"></span><span class="cnt"></span></div><div class="stages"></div>';
  updateDocCard(card, ev); return card;
}
const DOC_STAGES = [['reading', 'Reading'], ['chunking', 'Chunking'], ['indexing', 'Indexing'], ['analysing', 'Analysing'], ['synthesising', 'Synthesising'], ['complete', 'Complete']];
function updateDocCard(card, ev) {
  const stage = ev.doc_stage || ev.stage;
  card.querySelector('.lbl').textContent = ev.doc_name ? 'Processing ' + ev.doc_name : 'Processing document';
  const pct = card.querySelector('.pct'), track = card.querySelector('.track');
  if (typeof ev.percent === 'number') { pct.textContent = ev.percent + '%'; pct.classList.remove('unknown'); track.classList.remove('indet'); track.querySelector('i').style.width = ev.percent + '%'; }
  else if (!card._hadPct) { pct.textContent = 'PROCESSING'; }
  if (typeof ev.percent === 'number') card._hadPct = true;
  card.querySelector('.msg').textContent = String(ev.label || '').replace(/\s·\s\d+%$/, '');
  card.querySelector('.cnt').textContent = ev.total ? `${ev.done ?? 0} of ${ev.total} sections` : '';
  const at = DOC_STAGES.findIndex(s => s[0] === stage);
  card.querySelector('.stages').innerHTML = DOC_STAGES.map((s, k) => '<span class="' + (k < at ? 'done' : k === at ? 'act' : '') + '">' + (k < at ? '✓ ' : '') + s[1] + '</span>').join('');
}
function finishDocCard(card, ok) {
  if (!card) return;
  const pct = card.querySelector('.pct'), track = card.querySelector('.track');
  track.classList.remove('indet');
  if (ok) { pct.textContent = 'COMPLETE'; pct.classList.add('unknown'); track.querySelector('i').style.width = '100%'; card.querySelectorAll('.stages span').forEach(s => { s.className = 'done'; if (!s.textContent.startsWith('✓')) s.textContent = '✓ ' + s.textContent; }); }
  else { pct.textContent = 'FAILED'; pct.classList.add('unknown'); pct.style.color = 'var(--err)'; }
}
/* Background jobs: images, Claude Code, specialists, document work. Percentages are the job's own. */
const JOB_LABEL = {image: 'Picture', claude_code: 'Claude Code', specialist: 'Specialist', scheduled_turn: 'Scheduled task'};
function jobCard(j, inPanel) {
  const card = document.createElement('div'); card.className = 'card progress job'; card.dataset.job = j.id; card._noSwap = !!inPanel;
  updateJobCard(card, j); return card;
}
function updateJobCard(card, j) {
  card._job = j;
  if (j.state === 'done' && j.kind === 'image' && j.result?.artifact_id && !card._swapped && !card._noSwap) {
    card._swapped = true;
    jget('/api/v1/artifacts/' + j.result.artifact_id).then(a => { const ic = imageCard(a); card.replaceWith(ic); }).catch(() => {});
  }
  const run = j.state === 'running' || j.state === 'queued';
  const known = typeof j.progress === 'number' && j.state === 'running' && !/loading|queued/i.test(j.progress_text || '');
  const pctTxt = j.state === 'done' ? 'Done' : j.state === 'failed' ? 'Failed' : j.state === 'cancelled' ? 'Cancelled' : j.state === 'interrupted' ? 'Interrupted' :
    known ? Math.round(j.progress * 100) + '%' : j.state === 'queued' ? 'Queued' : '…';
  const r = j.result || {};
  const st = j.state === 'done' ? 'ok' : j.state === 'failed' ? 'bad' : run ? 'run' : 'warn';
  card.innerHTML = '<div class="top"><div class="lbl">' + esc(j.title || JOB_LABEL[j.kind] || 'Task') + '</div><div class="pct' + (known ? '' : ' unknown') + '">' + esc(pctTxt) + '</div></div>' +
    (run ? '<div class="track' + (known ? '' : ' indet') + '"><i style="width:' + (known ? Math.round(j.progress * 100) : 0) + '%"></i></div>' : '') +
    '<div class="sub"><span class="st ' + st + '">' + esc(j.state) + '</span><span>' + esc(j.state === 'failed' ? (j.error || 'failed') : (j.progress_text || '')) + '</span><span class="sp"></span></div>' +
    (r.summary ? '<div class="md jsum" style="margin-top:8px;font-size:14px"></div>' : '') +
    (r.files_changed?.length || r.diff_stat?.length ? '<div class="files">' + esc((r.diff_stat || []).map(f => `${f.path} (+${f.additions ?? 0} −${f.deletions ?? 0})`).join(' · ') || r.files_changed.join(', ')) + '</div>' : '') +
    (r.tests_run?.length ? '<div class="files">Tests run: ' + esc(r.tests_run.join(' · ')) + '</div>' : '');
  if (r.summary) renderMarkdown(card.querySelector('.jsum'), r.summary.length > 1200 ? r.summary.slice(0, 1200) + '…' : r.summary);
  const sub = card.querySelector('.sub');
  if (run) { const c = document.createElement('button'); c.className = 'btn small'; c.textContent = j.cancel_requested ? 'Cancelling…' : 'Cancel'; c.disabled = j.cancel_requested; c.onclick = () => cancelJob(j.id, c); sub.appendChild(c); }
  if (r.workspace) { const v = document.createElement('button'); v.className = 'btn small'; v.textContent = 'Review changes'; v.onclick = () => openReview(r.workspace); sub.appendChild(v); }
  if (j.state === 'done' && r.artifact_id && j.kind !== 'image') { const v = document.createElement('button'); v.className = 'btn small'; v.textContent = 'Open result'; v.onclick = () => jget('/api/v1/artifacts/' + r.artifact_id).then(previewArtifact); sub.appendChild(v); }
}
async function cancelJob(id, btn) { btn.disabled = true; btn.textContent = 'Cancelling…'; try { await jsend(`/api/v1/jobs/${id}/cancel`, 'POST', {}); } catch (e) { toast(e.message); btn.disabled = false; btn.textContent = 'Cancel'; } }
function watchJob(j) {
  if (!j || ['done', 'failed', 'cancelled', 'interrupted'].includes(j.state)) return;
  if (S.watchJobs.has(j.id)) return;
  S.watchJobs.add(j.id); restartJobStream();
}
function restartJobStream() {
  clearTimeout(restartJobStream.t);
  restartJobStream.t = setTimeout(async () => {
    if (S.jobStreamAbort) S.jobStreamAbort.abort();
    if (!S.watchJobs.size) return;
    const ctl = new AbortController(); S.jobStreamAbort = ctl;
    try {
      const r = await api('/api/v1/jobs/stream?ids=' + [...S.watchJobs].join(','), {signal: ctl.signal});
      await ndjson(r, ev => {
        if (ev.type !== 'job') return;
        const j = ev.job;
        document.querySelectorAll(`[data-job="${j.id}"]`).forEach(c => { updateJobCard(c, j); });
        contentGrew();
        if (['done', 'failed', 'cancelled', 'interrupted'].includes(j.state)) {
          S.watchJobs.delete(j.id);
          if (j.state === 'done') toast((j.title || 'Task') + ' finished');
          refreshBadge();
        }
        if (S.panel === 'activity') renderPanel();
      });
    } catch (e) { if (ctl.signal.aborted) return; }
    if (S.jobStreamAbort === ctl && S.watchJobs.size) setTimeout(restartJobStream, 3000);   // reconnect, e.g. after Vision restarts
  }, 150);
}
function approvalCard(p) {
  const card = document.createElement('div'); card.className = 'card approval'; card.dataset.approval = p.id;
  renderApproval(card, p); return card;
}
function renderApproval(card, p) {
  const pv = p.preview || {};
  const risk = pv.risk || 'medium';
  const fields = (pv.fields || []).map(f => '<dt>' + esc(f[0]) + '</dt><dd>' + esc(f[1]) + '</dd>').join('');
  const states = {approved: ['Approved — running…', 'okk'], executed: ['Done', 'okk'], failed: ['It didn\'t work: ' + (p.result?.error?.message || p.result?.error || ''), ''],
    denied: ['Cancelled — nothing was done', 'no'], expired: ['Expired — nothing was done', 'no']};
  card.innerHTML = '<div class="h"><span style="flex:1">' + esc(pv.title || p.title) + '</span><span class="risk ' + (risk === 'high' ? 'high' : '') + '">' + esc(risk) + ' risk</span></div>' +
    (fields ? '<dl>' + fields + '</dl>' : '') + (pv.body ? '<pre>' + esc(pv.body) + '</pre>' : '') +
    '<div class="note" style="margin-bottom:10px">' + esc(p.plugin) + ' · ' + esc(p.tool) + '</div>' +
    (p.state === 'pending' ? '<div class="acts"><button class="btn primary" data-a="once">Allow once</button><button class="btn" data-a="always">Always allow</button><button class="btn ghost" data-a="deny">Cancel</button></div>'
      : '<div class="state ' + (states[p.state]?.[1] || '') + '">' + esc(states[p.state]?.[0] || p.state) + '</div>');
  card.querySelectorAll('[data-a]').forEach(b => b.onclick = async () => {
    card.querySelectorAll('button').forEach(x => x.disabled = true); b.textContent = '…';
    try {
      const a = b.dataset.a;
      const res = a === 'deny' ? await jsend(`/api/v1/approvals/${p.id}/deny`, 'POST', {}) : await jsend(`/api/v1/approvals/${p.id}/approve`, 'POST', {always: a === 'always'});
      document.querySelectorAll(`[data-approval="${p.id}"]`).forEach(c => renderApproval(c, res));
      refreshBadge();
      if (res.state === 'executed' || res.state === 'failed') toast(res.state === 'executed' ? 'Done.' : 'That action failed.');
    } catch (e) { toast(e.message); renderApproval(card, p); }
  });
}

/* ------------------------------------------------------------------ previews and the review (diff) window */
function modal(title, body, foot) {
  $('modalTitle').textContent = title; const mb = $('modalBody'); mb.innerHTML = ''; if (typeof body === 'string') mb.innerHTML = body; else if (body) mb.appendChild(body);
  $('modalBox').classList.remove('img'); $('modalExtra').innerHTML = '';
  const f = $('modalFoot'); f.innerHTML = ''; f.hidden = !foot; if (foot) foot.forEach(b => f.appendChild(b));
  $('modal').classList.add('show');
}
function closeModal() { $('modal').classList.remove('show'); $('modalBody').innerHTML = ''; }
$('modalClose').onclick = closeModal; $('modal').onclick = e => { if (e.target === $('modal')) closeModal(); };
document.addEventListener('keydown', e => { if (e.key === 'Escape' && $('modal').classList.contains('show')) closeModal(); });
function showImage(u, title) { modal(title || 'Picture', '<img src="' + u + '" alt="">'); $('modalBox').classList.add('img'); }
function btn(label, cls, fn) { const b = document.createElement('button'); b.className = 'btn ' + (cls || ''); b.textContent = label; b.onclick = fn; return b; }
async function previewArtifact(a) {
  const ext = extOf(a.filename);
  const dl = btn('Download', '', () => downloadArtifact(a));
  if (a.kind === 'image' || /^image\//.test(a.mime)) { const u = await blobUrl(a.url); showImage(u, a.filename); return; }
  if (ext === 'pdf') { const u = await blobUrl(a.url); modal(a.filename, '<iframe src="' + u + '" sandbox></iframe>', [dl]); return; }
  if (a.text || ['py', 'gd', 'md', 'txt', 'json', 'csv', 'js', 'ts', 'kt', 'java', 'sh', 'toml', 'yml', 'yaml', 'cfg', 'tscn', 'tres', 'html', 'css', 'xml', 'log', 'ini'].includes(ext)) {
    const r = await api(a.url); const t = await r.text();
    if (ext === 'md') { const d = document.createElement('div'); d.className = 'md'; d.style.padding = '18px 22px'; renderMarkdown(d, t); modal(a.filename, d, [dl]); return; }
    const lang = a.language || ext; modal(a.filename, codeBlock(t, lang, a.filename), [dl, btn('Ask Gemma about it', '', () => { closeModal(); $('input').value = `About ${a.filename}: `; inputChanged(); $('input').focus(); })]); return;
  }
  // ZIPs, DOCX, XLSX, PPTX: the server builds a safe text/listing preview.
  try {
    const p = await jget(a.preview_url);
    const d = document.createElement('div'); d.style.padding = '16px 20px';
    if (p.entries) d.innerHTML = '<table class="md" style="width:100%"><thead><tr><th>File</th><th>Size</th></tr></thead><tbody>' + p.entries.map(e => '<tr><td style="font-family:var(--mono)">' + esc(e.name) + '</td><td>' + fmtSize(e.size) + '</td></tr>').join('') + '</tbody></table>';
    else if (p.content != null) { const md = document.createElement('div'); md.className = 'md'; renderMarkdown(md, p.content + (p.truncated ? '\n\n*(preview shortened — download for the whole file)*' : '')); d.appendChild(md); }
    else d.innerHTML = '<p class="note">No preview for this kind of file. Download it to open it.</p>';
    modal(a.filename, d, [dl]);
  } catch (e) { modal(a.filename, '<p class="note" style="padding:18px">No preview for this kind of file. Download it to open it.</p>', [dl]); }
}
async function openReview(wid) {
  let d;
  try { d = await jget(`/api/v1/workspaces/${wid}/diff`); } catch (e) { toast(e.message); return; }
  const box = document.createElement('div'); box.className = 'diff';
  if (!d.files.length) box.innerHTML = '<p class="note" style="padding:18px">No changes in this workspace.</p>';
  const chunks = {}; let cur = null;
  (d.diff || '').split('\n').forEach(l => { const m = l.match(/^\+\+\+ b\/(.+)$/); if (l.startsWith('--- ')) return; if (m) { cur = m[1]; chunks[cur] = []; return; } if (cur) chunks[cur].push(l); });
  d.files.forEach(f => {
    const df = document.createElement('div'); df.className = 'df';
    df.innerHTML = '<div class="dh"><b>' + esc(f.path) + '</b><span class="st">' + esc(f.status) + '</span>' + (f.binary ? '<span class="note">binary</span>' : '<span class="plus">+' + f.additions + '</span><span class="minus">−' + f.deletions + '</span>') +
      '<span class="note">' + esc((f.by || []).join(', ')) + '</span></div>';
    const rv = btn('Revert file', 'small ghost', async () => { await jsend(`/api/v1/workspaces/${wid}/revert`, 'POST', {path: f.path}); toast('Reverted ' + f.path); openReview(wid); });
    df.querySelector('.dh').appendChild(rv);
    const pre = document.createElement('pre');
    (chunks[f.path] || []).forEach(l => { const div = document.createElement('div'); div.className = l.startsWith('@@') ? 'hunk' : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : ''; div.textContent = l || ' '; pre.appendChild(div); });
    df.appendChild(pre); box.appendChild(df);
  });
  if (d.truncated) box.insertAdjacentHTML('beforeend', '<p class="note" style="padding:12px 16px">The diff is long; the rest is in the downloaded ZIP.</p>');
  const ask = text => () => { closeModal(); $('input').value = text; inputChanged(); $('input').focus(); };
  modal('Review changes — workspace ' + wid, box, [
    btn('Accept changes', 'primary', async () => { await jsend(`/api/v1/workspaces/${wid}/accept`, 'POST', {}); toast('Changes accepted as the new baseline.'); closeModal(); }),
    btn('Revert all', 'danger', async () => { if (!confirm('Undo every change in this workspace?')) return; await jsend(`/api/v1/workspaces/${wid}/revert`, 'POST', {}); toast('Reverted.'); closeModal(); }),
    btn('Ask Gemma to fix', '', ask(`In workspace ${wid}, `)),
    btn('Ask Claude to review', '', ask(`Ask Claude Code to review the changes in workspace ${wid}.`)),
    btn('Commit…', '', ask(`Commit the accepted changes from workspace ${wid} with the message: `)),
    btn('Download ZIP', '', async () => { try { const a = await jsend(`/api/v1/workspaces/${wid}/package`, 'POST', {filename: wid + '.zip', conversation_id: S.cid}); downloadArtifact(a); } catch (e) { toast(e.message); } }),
  ]);
  $('modalExtra').innerHTML = '<span class="note">' + d.files.length + ' file' + (d.files.length === 1 ? '' : 's') + '</span>';
}

/* ------------------------------------------------------------------ speaking a reply (only on request, never automatically) */
async function speak(row, button) {
  if (S.playing) { const was = S.playing; stopSpeaking(); if (was.row === row) return; }
  const mid = row.dataset.mid; if (!mid) { toast('That reply is still being saved.'); return; }
  const playId = newId(); const ctl = new AbortController();
  const p = S.playing = {row, button, playId, queue: [], audio: null, ctl, done: false};
  button.innerHTML = ICON.stop; button.classList.add('on'); button.title = 'Stop';
  const next = async () => {
    if (S.playing !== p || p.audio) return;
    const item = p.queue.shift();
    if (!item) { if (p.done) stopSpeaking(); return; }
    try {
      const u = await blobUrl(item.url); if (S.playing !== p) return;
      p.audio = new Audio(u); p.audio.onended = () => { p.audio = null; next(); }; p.audio.onerror = () => { p.audio = null; next(); };
      await p.audio.play();
    } catch (e) { p.audio = null; next(); }
  };
  try {
    const r = await api(`/api/v1/messages/${mid}/speech`, {method: 'POST', json: {play_id: playId, voice_style: 'balanced'}, signal: ctl.signal});
    if (!r.ok) { const x = await r.json().catch(() => ({})); throw new Error(x.code === 'voice_unavailable' ? 'Gemma\'s voice isn\'t running on Vision right now.' : (x.error || 'Could not speak that.')); }
    await ndjson(r, ev => {
      if (ev.type === 'speech' && ev.url) { p.queue.push(ev); next(); }
      else if (ev.type === 'speech_error') toast(ev.error || 'Voice problem');
      else if (ev.type === 'speech_done') { p.done = true; if (!p.audio && !p.queue.length) stopSpeaking(); }
    });
    p.done = true; if (!p.audio && !p.queue.length && S.playing === p) stopSpeaking();
  } catch (e) { if (!ctl.signal.aborted) toast(e.message); if (S.playing === p) stopSpeaking(); }
}
function stopSpeaking() {
  const p = S.playing; if (!p) return; S.playing = null;
  try { p.ctl.abort(); } catch (e) {}
  if (p.audio) { p.audio.pause(); p.audio = null; }
  p.button.innerHTML = ICON.play; p.button.classList.remove('on'); p.button.title = 'Play aloud';
  api(`/api/v1/turns/${p.playId}/stop`, {method: 'POST'}).catch(() => {});
}

/* ------------------------------------------------------------------ attachments */
const IMG_TYPES = ['image/png', 'image/jpeg', 'image/webp'];
const DOC_EXT = {pdf: 'application/pdf', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', txt: 'text/plain'};
function readDataUrl(file) { return new Promise((ok, no) => { const r = new FileReader(); r.onload = () => ok(r.result); r.onerror = () => no(r.error); r.readAsDataURL(file); }); }
async function addFiles(list) {
  const lim = S.status?.limits || {};
  for (const f of list) {
    if (S.pending.length >= 10) { toast('Up to 10 files at a time.'); break; }
    const ext = extOf(f.name);
    const item = {file: f, name: f.name || 'pasted.png', size: f.size, kind: IMG_TYPES.includes(f.type) ? 'image' : DOC_EXT[ext] ? 'document' : 'file', state: 'ready'};
    if (item.kind === 'image' && f.size > (lim.max_image_bytes || 20e6)) item.kind = 'file';
    if (item.kind === 'document' && f.size > (lim.max_document_bytes || 50e6)) { toast(`${f.name} is larger than Gemma accepts for documents.`); continue; }
    if (item.kind === 'image') item.preview = URL.createObjectURL(f);
    if (item.kind === 'file') uploadPending(item);          // large or unusual files go up first, with real progress
    S.pending.push(item);
  }
  renderChips(); inputChanged();
}
function uploadPending(item) {
  item.state = 'uploading'; item.pct = 0;
  const x = new XMLHttpRequest();
  x.open('POST', '/api/v1/artifacts' + (S.cid ? '?conversation_id=' + S.cid : ''));
  if (S.key) x.setRequestHeader('Authorization', 'Bearer ' + S.key);
  x.setRequestHeader('X-Filename', item.name); x.setRequestHeader('X-Client', 'web');
  x.upload.onprogress = e => { if (e.lengthComputable) { item.pct = Math.round(e.loaded / e.total * 100); renderChips(); } };
  x.onload = () => {
    let r = {}; try { r = JSON.parse(x.responseText); } catch (e) {}
    if (x.status < 300 && r.id) { item.state = 'ready'; item.artifact = r.id; } else { item.state = 'failed'; item.error = r.error || ('upload failed (' + x.status + ')'); }
    renderChips(); inputChanged();
  };
  x.onerror = () => { item.state = 'failed'; item.error = 'upload failed'; renderChips(); inputChanged(); };
  item.xhr = x; x.send(item.file);
}
function renderChips() {
  const box = $('chips'); box.innerHTML = '';
  S.pending.forEach((it, k) => {
    const c = document.createElement('div'); c.className = 'chipf';
    c.innerHTML = (it.preview ? '<img src="' + it.preview + '" alt="">' : '<span class="st">' + esc(extOf(it.name) || 'file') + '</span>') +
      '<span title="' + esc(it.name) + '">' + esc(it.name) + '</span><small>' + (it.state === 'uploading' ? it.pct + '%' : it.state === 'failed' ? '<span class="problem">' + esc(it.error) + '</span>' : fmtSize(it.size)) + '</small>';
    const x = document.createElement('button'); x.innerHTML = '×'; x.title = 'Remove'; x.onclick = () => { it.xhr?.abort(); S.pending.splice(k, 1); renderChips(); inputChanged(); };
    c.appendChild(x); box.appendChild(c);
  });
}
$('attach').onclick = () => $('filePick').click();
$('filePick').onchange = e => { addFiles([...e.target.files]); e.target.value = ''; };
let dragDepth = 0;
document.addEventListener('dragenter', e => { if ([...(e.dataTransfer?.types || [])].includes('Files')) { dragDepth++; $('drop').classList.add('show'); e.preventDefault(); } });
document.addEventListener('dragover', e => { if ([...(e.dataTransfer?.types || [])].includes('Files')) e.preventDefault(); });
document.addEventListener('dragleave', () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $('drop').classList.remove('show'); });
document.addEventListener('drop', e => { dragDepth = 0; $('drop').classList.remove('show'); if (e.dataTransfer?.files?.length) { e.preventDefault(); addFiles([...e.dataTransfer.files]); } });
$('input').addEventListener('paste', e => { const fs = [...(e.clipboardData?.files || [])]; if (fs.length) { e.preventDefault(); addFiles(fs); } });

/* ------------------------------------------------------------------ composer and sending */
function inputChanged() {
  const t = $('input'); t.style.height = 'auto'; t.style.height = Math.min(220, t.scrollHeight) + 'px';
  const busy = S.pending.some(p => p.state === 'uploading');
  if (!S.turn) $('send').disabled = busy || (!t.value.trim() && !S.pending.filter(p => p.state === 'ready').length);
}
$('input').addEventListener('input', inputChanged);
$('input').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && innerWidth > 640) { e.preventDefault(); if (!$('send').disabled) $('send').click(); } });
$('send').onclick = () => S.turn ? stopTurn() : send();

function setSending(on) {
  const b = $('send');
  if (on) { b.classList.add('stop'); b.innerHTML = ICON.stop; b.title = 'Stop'; b.disabled = false; }
  else { b.classList.remove('stop'); b.innerHTML = ICON.send; b.title = 'Send'; inputChanged(); }
}
async function stopTurn() { const t = S.turn; if (!t) return; t.stopping = true; $('send').disabled = true; try { await api(`/api/v1/turns/${t.id}/stop`, {method: 'POST'}); } catch (e) {} }

async function send() {
  const text = $('input').value.trim();
  const ready = S.pending.filter(p => p.state === 'ready');
  if (!text && !ready.length) return;
  if (!S.cid) { await newChat(true); if (!S.cid) return; }
  const cid = S.cid;
  const images = [], documents = [], artifact_ids = [];
  try {
    for (const p of ready) {
      if (p.kind === 'image') images.push(await readDataUrl(p.file));
      else if (p.kind === 'document') documents.push({name: p.name, mime: DOC_EXT[extOf(p.name)], data: await readDataUrl(p.file)});
      else if (p.artifact) artifact_ids.push(p.artifact);
    }
  } catch (e) { toast('Could not read a file: ' + e.message); return; }
  const sentFiles = ready.slice(); S.pending = []; renderChips();
  $('input').value = ''; inputChanged();
  document.querySelector('#messages .empty')?.remove();
  const userRow = messageRow({role: 'user', text, created_at: new Date().toISOString()});
  sentFiles.forEach(p => {
    if (p.kind === 'image') { const c = document.createElement('div'); c.className = 'card imgcard'; c.innerHTML = '<img src="' + p.preview + '" alt="">'; c.querySelector('img').onclick = () => showImage(p.preview, p.name); addCard(userRow, c); }
    else addCard(userRow, artifactCard({id: p.artifact || '', filename: p.name, size: p.size, created_by: 'user', mime: p.file.type, url: '', kind: 'file'}));
  });
  if (sentFiles.length) userRow._cards.querySelectorAll('.file .acts').forEach(a => a.remove());
  $('messages').appendChild(userRow);
  const row = messageRow({role: 'assistant', text: ''}); row._meta.style.visibility = 'hidden';
  const act = document.createElement('div'); act.className = 'activity'; act.innerHTML = '<span class="spin"></span><span class="t">Thinking…</span>';
  row._bubble.appendChild(act);
  $('messages').appendChild(row); toBottom();
  const turnId = newId(), reqId = 'w' + newId().slice(0, 10);
  const t = S.turn = {id: turnId, cid, row, text: '', docCard: null, renderAt: 0};
  setSending(true);
  const render = force => {
    const now = performance.now();
    if (!force && now - t.renderAt < 60) { clearTimeout(t.rt); t.rt = setTimeout(() => render(true), 70); return; }
    t.renderAt = now; renderMarkdown(row._bubble, t.text, true); row._text = t.text; contentGrew();
  };
  const client = {client_id: 'web', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || null, location: {status: 'unavailable'}};
  let finished = false;
  try {
    const r = await api(`/api/v1/conversations/${cid}/turns`, {method: 'POST', headers: {'X-Request-Id': reqId},
      json: {message: text || (images.length ? 'Have a look at this.' : 'Here are the files.'), client, turn_id: turnId, images, documents, artifact_ids, voice: false}});
    if (!r.ok || !r.body) { const x = await r.json().catch(() => ({})); throw new Error(x.error || 'Gemma could not start that turn.'); }
    await ndjson(r, ev => {
      if (ev.type === 'status') {
        if (ev.doc_stage || ['reading', 'chunking', 'indexing', 'analysing', 'analysing_retry'].includes(ev.stage)) {
          if (!t.docCard) { t.docCard = docProgressCard(ev); addCard(row, t.docCard); } else updateDocCard(t.docCard, ev);
        }
        if (!t.text) { const at = row._bubble.querySelector('.activity .t'); if (at) at.textContent = ev.label || 'Working…'; }
        setStatus(ev.label || 'Working…', 'busy'); contentGrew();
      } else if (ev.type === 'delta') { t.text += ev.text || ''; render(); setStatus('Gemma is answering…', 'busy'); }
      else if (ev.type === 'reset') { t.text = ''; row._bubble.innerHTML = ''; row._bubble.appendChild(act); }
      else if (ev.type === 'done' || ev.type === 'error') { finished = true; finishTurn(t, ev); }
    });
    if (!finished) {    // the connection dropped: the turn still finishes on Vision; ask what became of it rather than resending
      const x = await waitForTurn(turnId);
      finishTurn(t, x ? {type: 'done', reply: x.reply, research_log: x.research_log, stopped: x.state === 'stopped', message_id: x.answer_message_id, user_message_id: x.user_message_id, reload: true}
        : {type: 'error', error: 'Lost contact with Vision. The reply will appear here when it is saved.'});
    }
  } catch (e) { finishTurn(t, {type: 'error', error: e.message}); }
}
async function waitForTurn(turnId) {
  for (let k = 0; k < 300; k++) {
    const x = await jget('/api/v1/turns/' + turnId).catch(() => null);
    if (x && x.state !== 'active' && x.state !== 'unknown') return x;
    if (x && x.state === 'unknown' && k > 3) return null;
    await new Promise(r => setTimeout(r, 2000));
  }
  return null;
}
function finishTurn(t, ev) {
  if (S.turn !== t) return;
  S.turn = null; clearTimeout(t.rt); setSending(false); setStatus();
  const row = t.row;
  if (ev.type === 'error') {
    finishDocCard(t.docCard, false);
    if (!t.text) row._bubble.innerHTML = '';
    const p = document.createElement('div'); p.className = 'problem'; p.textContent = ev.error || 'Something went wrong.'; row._bubble.appendChild(p);
    row._meta.style.visibility = 'visible'; contentGrew(); return;
  }
  const text = ev.reply ?? t.text;
  renderMarkdown(row._bubble, text); row._text = text;
  if (ev.stopped) row._bubble.insertAdjacentHTML('beforeend', '<div class="stopped">Stopped</div>');
  if (ev.message_id) row.dataset.mid = ev.message_id;
  if (t.docCard) finishDocCard(t.docCard, true);
  (ev.artifacts || []).forEach(a => addCard(row, artifactCard(a)));
  (ev.jobs || []).forEach(j => { addCard(row, jobCard(j)); watchJob(j); });
  (ev.approvals || []).forEach(p => addCard(row, approvalCard(p)));
  if (ev.research_log) row._col.insertBefore(researchToggle(ev.research_log), row._meta);
  row._meta.style.visibility = 'visible';
  const when = row._meta.querySelector('[data-created-at]'); if (when) { when.dataset.createdAt = new Date().toISOString(); when.textContent = relativeTime(when.dataset.createdAt); }
  contentGrew();
  if (ev.reload && S.cid === t.cid) openChat(t.cid, {keepFocus: true});
  loadChats();
  if (ev.approvals?.length || ev.jobs?.length) refreshBadge();
}

/* ------------------------------------------------------------------ status line */
function setStatus(text, mode) {
  const ok = S.status && S.status.model?.ready !== false;
  $('statusDot').className = 'dot ' + (mode === 'busy' ? 'ok' : S.status ? (ok ? 'ok' : 'bad') : 'bad');
  $('statusText').textContent = text || (S.status ? (ok ? 'Vision · ready' : 'Vision · model not ready') : 'Vision unreachable');
}
async function loadStatus() {
  try {
    S.status = await jget('/api/v1/status');
    const b = S.status.server || {};
    $('buildLine').textContent = (S.status.build || '') + (b.commit ? ' · ' + String(b.commit).slice(0, 7) : '');
  } catch (e) { S.status = null; }
  if (!S.turn) setStatus();
}

/* ------------------------------------------------------------------ sidebar: projects, chats, search */
async function loadChats() {
  try {
    const [c, p] = await Promise.all([jget('/api/v1/conversations'), jget('/api/v1/projects').catch(() => ({projects: []}))]);
    S.convs = c.conversations; S.projects = p.projects || [];
  } catch (e) { return; }
  if (!$('searchBox').value.trim()) renderChatList();
}
function plainPreview(t) { return String(t).replace(/```[\w.+-]*/g, ' ').replace(/[*_`#>|]+/g, '').replace(/\s+/g, ' ').trim(); }
function dayGroup(ts) {
  const d = new Date(ts), now = new Date(); const days = Math.floor((new Date(now.toDateString()) - new Date(d.toDateString())) / 864e5);
  return days <= 0 ? 'Today' : days === 1 ? 'Yesterday' : days < 7 ? 'This week' : days < 31 ? 'This month' : 'Older';
}
function renderChatList() {
  const box = $('chatList'); box.innerHTML = '';
  const add = (title) => { const g = document.createElement('div'); g.className = 'group'; g.textContent = title; box.appendChild(g); return g; };
  if (S.projects.length) {
    add('Projects');
    S.projects.forEach(p => {
      const d = document.createElement('div'); d.className = 'chat-item'; d.innerHTML = '<span class="t">📁 ' + esc(p.name) + '</span><span class="note">' + (p.chats || 0) + '</span>';
      d.onclick = () => openPanel('project', p.id); box.appendChild(d);
    });
  }
  let last = '';
  S.convs.forEach(c => {
    const g = dayGroup(c.updated_at); if (g !== last) { add(g); last = g; }
    const d = document.createElement('div'); d.className = 'chat-item' + (c.id === S.cid ? ' active' : '');
    d.innerHTML = '<span class="t">' + esc(c.title || 'New conversation') + (c.preview ? '<span class="p">' + esc(plainPreview(c.preview)) + '</span>' : '') + '</span>';
    const more = document.createElement('button'); more.className = 'more'; more.innerHTML = ICON.more; more.title = 'Rename, move or delete';
    more.onclick = e => { e.stopPropagation(); chatMenu(c); };
    d.appendChild(more); d.onclick = () => openChat(c.id); box.appendChild(d);
  });
  if (!S.convs.length) box.insertAdjacentHTML('beforeend', '<div class="note" style="padding:12px">No chats yet.</div>');
}
function chatMenu(c) {
  const body = document.createElement('div'); body.style.padding = '16px 18px';
  body.innerHTML = '<div class="field"><label>Title</label><input id="cmTitle"></div><div class="field"><label>Project</label><select id="cmProj"><option value="">No project</option>' +
    S.projects.map(p => '<option value="' + p.id + '"' + (p.id === c.project_id ? ' selected' : '') + '>' + esc(p.name) + '</option>').join('') + '</select></div>';
  body.querySelector('#cmTitle').value = c.title || '';
  modal('Chat', body, [
    btn('Save', 'primary', async () => {
      try {
        const t = body.querySelector('#cmTitle').value.trim(); if (t && t !== c.title) await jsend(`/api/v1/conversations/${c.id}`, 'PUT', {title: t});
        const pv = body.querySelector('#cmProj').value; if (String(c.project_id || '') !== pv) await jsend(`/api/v1/conversations/${c.id}/project`, 'PUT', {project_id: pv ? +pv : null});
        closeModal(); await loadChats(); if (c.id === S.cid) openChat(c.id, {keepFocus: true});
      } catch (e) { toast(e.message); }
    }),
    btn('Delete chat', 'danger', async () => {
      if (!confirm('Remove this chat from your lists? (Its messages stay in Gemma\'s database.)')) return;
      try { const r = await jsend(`/api/v1/conversations/${c.id}`, 'DELETE'); closeModal(); await loadChats(); if (c.id === S.cid) { const nx = S.convs[0]; nx ? openChat(nx.id) : (S.cid = null, emptyState()); } } catch (e) { toast(e.message); }
    }),
  ]);
}
async function newChat(silent) {
  try { const c = await jsend('/api/v1/conversations', 'POST', {title: 'New conversation'}); S.convs.unshift(c); await openChat(c.id); if (!silent) $('input').focus(); }
  catch (e) { toast(e.message); }
}
$('newChat').onclick = () => newChat();
$('newProject').onclick = () => {
  const body = document.createElement('div'); body.style.padding = '16px 18px';
  body.innerHTML = '<div class="field"><label>Project name</label><input id="pName" placeholder="e.g. Vulcan Fury"></div><div class="field"><label>Instructions for Gemma in this project (optional)</label><textarea id="pInst" rows="5"></textarea></div><p class="note">Chats in a project share its files and instructions. Nothing from a project is mentioned in chats outside it.</p>';
  modal('New project', body, [btn('Create', 'primary', async () => {
    const name = body.querySelector('#pName').value.trim(); if (!name) return;
    try { await jsend('/api/v1/projects', 'POST', {name, instructions: body.querySelector('#pInst').value}); closeModal(); loadChats(); } catch (e) { toast(e.message); }
  })]);
};
$('burger').onclick = () => $('side').classList.toggle('open');
let searchT;
$('searchBox').addEventListener('input', e => {
  clearTimeout(searchT); const q = e.target.value.trim();
  if (q.length < 2) { renderChatList(); return; }
  searchT = setTimeout(async () => {
    let x; try { x = await jget('/api/v1/search?q=' + encodeURIComponent(q)); } catch (err) { return; }
    if ($('searchBox').value.trim() !== q) return;
    const box = $('chatList'); box.innerHTML = ''; const R = x.results || {};
    const section = (title, items, fn) => {
      if (!items?.length) return; box.insertAdjacentHTML('beforeend', '<div class="group">' + esc(title) + '</div>');
      items.forEach(it => { const d = document.createElement('div'); d.className = 'chat-item'; const [a, b, go] = fn(it); d.innerHTML = '<span class="t">' + esc(a) + '<span class="p">' + esc(b || '') + '</span></span>'; d.onclick = go; box.appendChild(d); });
    };
    section('Chats', R.conversations, it => [it.title || 'Chat', it.snippet, () => openChat(it.conversation_id)]);
    section('Documents', R.documents, it => [it.name, (it.where ? it.where + ' — ' : '') + it.snippet, () => toast('Ask Gemma about ' + it.name)]);
    section('Files', R.artifacts, it => [it.filename, it.description, () => jget('/api/v1/artifacts/' + it.artifact_id).then(previewArtifact)]);
    section('Tasks', R.tasks, it => [it.title, it.state, () => openPanel('activity')]);
    section('Projects', R.projects, it => [it.name, '', () => openPanel('project', it.project_id)]);
    section('Memories', R.memories, it => [it.subject || 'Memory', it.fact, () => openPanel('memory')]);
    if (!box.children.length) box.innerHTML = '<div class="note" style="padding:12px">Nothing found for “' + esc(q) + '”.</div>';
  }, 220);
});

/* ------------------------------------------------------------------ side panel */
document.querySelectorAll('.side-foot button').forEach(b => b.onclick = () => openPanel(b.dataset.panel));
$('panelClose').onclick = () => closePanel();
function closePanel() { S.panel = null; $('app').classList.remove('panel-open'); document.querySelectorAll('.side-foot button').forEach(b => b.classList.remove('on')); }
function openPanel(name, arg) {
  if (S.panel === name && arg === undefined && name !== 'project') { closePanel(); return; }
  S.panel = name; S.panelArg = arg; S.panelTab = null;
  $('app').classList.add('panel-open'); $('side').classList.remove('open');
  document.querySelectorAll('.side-foot button').forEach(b => b.classList.toggle('on', b.dataset.panel === name));
  renderPanel();
}
function tabs(list, cur, fn) {
  const t = $('panelTabs'); t.innerHTML = '';
  list.forEach(([k, label]) => { const b = document.createElement('button'); b.textContent = label; b.className = k === cur ? 'on' : ''; b.onclick = () => { S.panelTab = k; fn(k); renderPanel(); }; t.appendChild(b); });
}
async function renderPanel() {
  const body = $('panelBody'), name = S.panel; $('panelTabs').innerHTML = '';
  const titles = {activity: 'Activity', files: 'Files', memory: 'Memory', settings: 'Settings', project: 'Project'};
  $('panelTitle').textContent = titles[name] || '';
  try {
    if (name === 'activity') await panelActivity(body);
    else if (name === 'files') await panelFiles(body);
    else if (name === 'memory') await panelMemory(body);
    else if (name === 'settings') await panelSettings(body);
    else if (name === 'project') await panelProject(body);
  } catch (e) { body.innerHTML = '<p class="problem">' + esc(e.message) + '</p>'; }
}
async function panelActivity(body) {
  const x = await jget('/api/v1/activity');
  body.innerHTML = '';
  if (x.approvals.length) { body.insertAdjacentHTML('beforeend', '<div class="group">Waiting for you</div>'); x.approvals.forEach(p => { const c = approvalCard(p); c.style.marginBottom = '10px'; body.appendChild(c); }); }
  body.insertAdjacentHTML('beforeend', '<div class="group">Tasks</div>');
  if (!x.jobs.length) body.insertAdjacentHTML('beforeend', '<p class="note">No background tasks yet.</p>');
  x.jobs.forEach(j => { const c = jobCard(j, true); c.style.marginBottom = '10px'; body.appendChild(c); watchJob(j); });
  body.insertAdjacentHTML('beforeend', '<div class="group">Notifications</div>');
  if (!x.notifications.length) body.insertAdjacentHTML('beforeend', '<p class="note">Nothing yet.</p>');
  x.notifications.forEach(n => {
    const d = document.createElement('div'); d.className = 'li';
    d.innerHTML = '<div class="a"><b>' + esc(n.title || n.kind) + '</b></div><div class="s">' + esc(n.text || '') + '</div><div class="note"></div>';
    d.querySelector('.note').appendChild(stamp(n.created_at));
    if (n.conversation_id) { d.style.cursor = 'pointer'; d.onclick = () => openChat(n.conversation_id); }
    body.appendChild(d);
  });
  const unread = x.notifications.filter(n => !n.read_at).map(n => n.id);
  if (unread.length) jsend('/api/v1/notifications/read', 'POST', {ids: unread}).then(refreshBadge).catch(() => {});
}
async function panelFiles(body) {
  const tab = S.panelTab || (S.cid ? 'chat' : 'all');
  tabs([['chat', 'This chat'], ['all', 'All files']], tab, () => {});
  const x = await jget('/api/v1/artifacts' + (tab === 'chat' && S.cid ? '?conversation_id=' + S.cid : '?limit=200'));
  body.innerHTML = '';
  if (!x.artifacts.length) body.innerHTML = '<p class="note">No files yet. Files you attach and files Gemma makes appear here.</p>';
  x.artifacts.forEach(a => { const c = artifactCard(a.kind === 'image' ? Object.assign({}, a, {kind: 'file'}) : a); c.style.marginBottom = '8px'; body.appendChild(c); });
}
async function panelMemory(body) {
  const tab = S.panelTab || 'all';
  const x = await jget('/api/v1/memory?category=' + tab);
  tabs([['all', 'All ' + x.counts.all], ['people', 'People ' + x.counts.people], ['projects', 'Projects ' + x.counts.projects], ['preferences', 'Preferences ' + x.counts.preferences], ['other', 'Other ' + x.counts.other]], tab, () => {});
  body.innerHTML = '<p class="note">What Gemma remembers about you. Forget anything that\'s wrong or that you\'d rather she didn\'t keep.</p>';
  x.memories.forEach(m => {
    const d = document.createElement('div'); d.className = 'li';
    d.innerHTML = '<div class="a"><b>' + esc(m.subject || m.kind) + '</b><span class="st">' + esc(m.category) + '</span></div><div class="s">' + esc(m.fact) + '</div>';
    const f = btn('Forget', 'small ghost', async () => { if (!confirm('Forget this?')) return; await jsend('/api/v1/memories/' + m.id, 'DELETE'); d.remove(); });
    d.querySelector('.a').appendChild(f); body.appendChild(d);
  });
}
const PSTATUS = {ok: ['Connected', 'ok'], connected: ['Connected', 'ok'], disabled: ['Off', ''], not_connected: ['Not connected', 'warn'], unavailable: ['Unavailable', 'bad'],
  needs_reauth: ['Needs signing in again', 'warn'], needs_reconsent: ['Needs permission again', 'warn'], insufficient_permissions: ['Missing permissions', 'warn'], rate_limited: ['Rate limited', 'warn'], error: ['Error', 'bad']};
async function panelSettings(body) {
  const tab = S.panelTab || 'plugins';
  tabs([['plugins', 'Connections'], ['privacy', 'Privacy'], ['about', 'About']], tab, () => {});
  if (tab === 'plugins') {
    const x = await jget('/api/v1/plugins');
    body.innerHTML = '<p class="note">What Gemma can reach. Anything that changes something outside Gemma asks you first.</p>';
    x.plugins.forEach(p => {
      const [label, cls] = PSTATUS[p.health?.status] || [p.health?.status || '?', ''];
      const d = document.createElement('div'); d.className = 'li';
      d.innerHTML = '<div class="a"><b>' + esc(p.name) + '</b><span class="st ' + cls + '">' + esc(label) + '</span><span class="switch' + (p.enabled ? ' on' : '') + '" role="switch" tabindex="0"></span></div>' +
        '<div class="s">' + esc(p.description) + '</div>' + (p.health?.message ? '<div class="s" style="color:var(--warn)">' + esc(p.health.message) + '</div>' : '') +
        (p.connect?.how && p.health?.status !== 'ok' ? '<div class="note" style="margin-top:4px">' + esc(p.connect.how) + '</div>' : '') +
        '<details style="margin-top:6px"><summary class="note" style="cursor:pointer">Permissions and tools</summary><div class="s">' + p.permissions.map(esc).join('<br>') + '</div><div class="note" style="margin-top:6px">' +
        p.tools.map(t => esc(t.name) + (t.mutation ? ' (asks first)' : '')).join(', ') + '</div></details>';
      const sw = d.querySelector('.switch');
      sw.onclick = async () => { try { await jsend(`/api/v1/plugins/${p.id}/enabled`, 'POST', {enabled: !p.enabled}); renderPanel(); } catch (e) { toast(e.message); } };
      const schema = p.settings || {};
      Object.entries(schema).forEach(([k, f]) => {
        if (f.type === 'list') {    // e.g. the folders Gemma may use: one per line, add " write" to allow writing
          const fld = document.createElement('div'); fld.className = 'field';
          fld.innerHTML = '<label>' + esc(f.label || k) + ' — one per line; add “ write” to let Gemma change files there</label><textarea rows="4" spellcheck="false"></textarea>';
          const cur = p.values?.[k] || []; fld.querySelector('textarea').value = cur.map(r => typeof r === 'string' ? r : r.path + (r.mode === 'write' ? ' write' : '')).join('\n');
          fld.appendChild(btn('Save', 'small', async () => {
            const v = fld.querySelector('textarea').value.split('\n').map(x => x.trim()).filter(Boolean).map(x => { const m = x.match(/^(.*?)\s+(read|write)$/); return m ? {path: m[1], mode: m[2]} : {path: x, mode: 'read'}; });
            try { await jsend(`/api/v1/plugins/${p.id}/settings`, 'POST', {[k]: v}); toast('Saved.'); renderPanel(); } catch (e) { toast(e.message); }
          }));
          d.appendChild(fld); return;
        }
        if (f.type !== 'secret' && f.type !== 'string' && f.type !== 'text') return;
        const fld = document.createElement('div'); fld.className = 'field';
        fld.innerHTML = '<label>' + esc(f.label || k) + '</label><input ' + (f.type === 'secret' ? 'type="password" placeholder="paste here (saved privately; never shown again)"' : '') + ' autocomplete="off">';
        if (f.type !== 'secret') fld.querySelector('input').value = p.values?.[k] ?? '';
        const save = btn('Save', 'small', async () => { try { await jsend(`/api/v1/plugins/${p.id}/settings`, 'POST', {[k]: fld.querySelector('input').value}); toast('Saved.'); renderPanel(); } catch (e) { toast(e.message); } });
        fld.appendChild(save); d.appendChild(fld);
      });
      if (p.auth === 'google' || p.auth === 'token') d.appendChild(btn('Disconnect', 'small ghost', async () => { if (!confirm('Disconnect ' + p.name + '?')) return; await jsend(`/api/v1/plugins/${p.id}/disconnect`, 'POST', {}); renderPanel(); }));
      body.appendChild(d);
    });
  } else if (tab === 'privacy') {
    const x = await jget('/api/v1/privacy');
    body.innerHTML = '<div class="group">What Gemma keeps</div><div class="li"><div class="s">' + Object.entries(x.data).map(([k, v]) => esc(k) + ': <b>' + v + '</b>').join(' · ') + '</div></div>' +
      x.notes.map(n => '<p class="note">' + esc(n) + '</p>').join('') + '<div class="group">Always allowed actions</div>';
    const pol = x.policies || [];
    if (!pol.length) body.insertAdjacentHTML('beforeend', '<p class="note">None. Gemma asks before every action that changes something.</p>');
    pol.forEach(p => { const d = document.createElement('div'); d.className = 'li'; d.innerHTML = '<div class="a"><b>' + esc(p.plugin + ' · ' + p.tool) + '</b><span class="st">' + esc(p.policy) + '</span></div>';
      d.querySelector('.a').appendChild(btn('Ask again', 'small ghost', async () => { await jsend('/api/v1/tools/policy', 'POST', {plugin: p.plugin, tool: p.tool, policy: 'ask'}); renderPanel(); })); body.appendChild(d); });
    body.insertAdjacentHTML('beforeend', '<div class="group">Your data</div>');
    body.appendChild(btn('Export everything (ZIP)', '', async () => { toast('Preparing export…'); const r = await api('/api/v1/export'); const b = await r.blob(); const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = 'gemma-export.zip'; a.click(); }));
  } else {
    const st = S.status || await jget('/api/v1/status');
    const u = await jget('/api/v1/usage').catch(() => ({usage: {}}));
    const caps = st.capabilities || {};
    body.innerHTML = '<div class="li"><div class="a"><b>' + esc(st.build) + '</b></div><div class="s">' + esc(JSON.stringify(st.server || {})) + '</div></div>' +
      '<div class="group">Models</div><div class="li"><div class="s">' + esc(Object.entries(st.models || {}).map(([k, v]) => k + ': ' + (v.model || v)).join(' · ')) + '</div></div>' +
      '<div class="group">Ready on Vision</div><div class="li"><div class="s">Voice: ' + (st.voice?.ready ? 'yes' : 'no') + ' · Transcription: ' + (caps.stt ? 'yes' : 'no') + ' · Pictures: ' + (caps.image_generation ? 'yes' : 'no') + ' · Claude Code: ' + (caps.claude_code ? 'yes' : 'no') + '</div></div>' +
      '<div class="group">Usage (30 days)</div><div class="li"><div class="s">' + esc((u.usage || []).map(r => r.kind + ': ' + (Math.round((r.total || 0) * 100) / 100) + ' ' + (r.unit || '') + ' (' + r.events + ')').join(' · ') || 'Nothing recorded yet.') + '</div></div>';
    body.appendChild(btn('Open Diagnostics', '', () => window.open('/diagnostics', 'gemma-diagnostics')));
    body.appendChild(btn('Forget access key on this browser', 'ghost', () => { try { localStorage.removeItem('gemma_key'); } catch (e) {} location.reload(); }));
  }
}
async function panelProject(body) {
  const p = await jget('/api/v1/projects/' + S.panelArg);
  $('panelTitle').textContent = p.name;
  body.innerHTML = '<div class="field"><label>Instructions for Gemma in this project</label><textarea id="pjI" rows="6"></textarea></div>';
  body.querySelector('#pjI').value = p.instructions || '';
  body.appendChild(btn('Save instructions', 'small', async () => { await jsend('/api/v1/projects/' + p.id, 'PUT', {instructions: body.querySelector('#pjI').value}); toast('Saved.'); }));
  body.appendChild(btn('New chat in this project', 'small primary', async () => { const c = await jsend('/api/v1/conversations', 'POST', {title: p.name + ' chat'}); await jsend(`/api/v1/conversations/${c.id}/project`, 'PUT', {project_id: p.id}); await loadChats(); openChat(c.id); }));
  body.insertAdjacentHTML('beforeend', '<div class="group">Chats</div>');
  (p.conversations || []).forEach(c => { const d = document.createElement('div'); d.className = 'chat-item'; d.innerHTML = '<span class="t">' + esc(c.title) + '</span>'; d.onclick = () => openChat(c.id); body.appendChild(d); });
  body.appendChild(btn('Archive project', 'small ghost', async () => { if (!confirm('Archive this project? Its chats stay.')) return; await jsend('/api/v1/projects/' + p.id, 'PUT', {archived: true}); closePanel(); loadChats(); }));
}
async function refreshBadge() {
  try {
    const x = await jget('/api/v1/activity');
    const n = x.approvals.length + x.notifications.filter(v => !v.read_at).length;
    const b = $('actBadge'); b.hidden = !n; b.textContent = n > 9 ? '9+' : n;
  } catch (e) {}
}

/* ------------------------------------------------------------------ dictation (Vision transcribes; nothing leaves the house) */
let rec = null;
$('mic').onclick = async () => {
  if (rec) { rec.stop(); return; }
  if (!S.status?.capabilities?.stt) { toast('Transcription isn\'t installed on Vision yet (pip install faster-whisper).'); return; }
  let stream; try { stream = await navigator.mediaDevices.getUserMedia({audio: true}); } catch (e) { toast('No microphone access.'); return; }
  const chunks = []; rec = new MediaRecorder(stream); $('mic').classList.add('on'); $('mic').title = 'Stop dictating';
  rec.ondataavailable = e => chunks.push(e.data);
  rec.onstop = async () => {
    stream.getTracks().forEach(t => t.stop()); $('mic').classList.remove('on'); $('mic').title = 'Dictate'; rec = null;
    const blob = new Blob(chunks, {type: chunks[0]?.type || 'audio/webm'});
    $('input').placeholder = 'Transcribing…';
    try { const r = await api('/api/v1/transcribe', {method: 'POST', body: blob}); const x = await r.json(); if (!r.ok) throw new Error(x.error); const t = $('input'); t.value = (t.value ? t.value + ' ' : '') + (x.text || '').trim(); inputChanged(); t.focus(); }
    catch (e) { toast(e.message || 'Transcription failed'); }
    $('input').placeholder = 'Message Gemma';
  };
  rec.start();
};

/* ------------------------------------------------------------------ start */
function showKeyGate(err) { $('keyGate').classList.add('show'); $('keyErr').textContent = err || ''; setTimeout(() => $('keyInput').focus(), 50); }
$('keySave').onclick = async () => {
  const k = $('keyInput').value.trim(); if (!k) return;
  S.key = k;
  const r = await fetch('/api/v1/status', {headers: {Authorization: 'Bearer ' + k}});
  if (r.status === 401) { $('keyErr').textContent = 'That access key was not accepted.'; return; }
  try { localStorage.setItem('gemma_key', k); } catch (e) {}
  $('keyGate').classList.remove('show'); start();
};
$('keyInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('keySave').click(); });
async function start() {
  const probe = await fetch('/api/v1/status', {headers: S.key ? {Authorization: 'Bearer ' + S.key} : {}}).catch(() => null);
  if (probe && probe.status === 401) { showKeyGate(S.key ? 'That access key was not accepted.' : ''); return; }
  if (probe && probe.ok) { S.status = await probe.json(); }
  await loadStatus();
  await loadChats();
  let want = null; try { want = +localStorage.getItem('gemma_last_chat'); } catch (e) {}
  const pick = S.convs.find(c => c.id === want) || S.convs.find(c => c.primary) || S.convs[0];
  if (pick) openChat(pick.id); else emptyState();
  refreshBadge();
  setInterval(loadStatus, 30000); setInterval(refreshBadge, 20000);
  // Pick up jobs still running from before (e.g. a picture started on the phone).
  jget('/api/v1/jobs?state=queued,running').then(x => x.jobs.forEach(watchJob)).catch(() => {});
}
start();

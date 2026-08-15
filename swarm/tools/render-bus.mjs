#!/usr/bin/env node
// Renders the agent bus + decisions into a self-contained HTML page.
// file:// friendly: data is inlined (fetch of sibling files is blocked).
//
//   node tools/render-bus.mjs
//   node tools/render-bus.mjs --watch

import { readFileSync, writeFileSync, readdirSync, watch, existsSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const COLLAB = join(ROOT, 'collab')
const BUS = join(COLLAB, 'bus')
const OUT = join(ROOT, 'bus-follow.html')
const DECISIONS = join(COLLAB, 'decisions.md')
const REFRESH_SECONDS = 2

const AGENT_COLORS = {
  kayley: '#d97bb0',
  steven: '#e0a458',
  claude: '#d97757',
  codex: '#4bb3a5',
  judge: '#e0a458',
}

function readBus() {
  if (!existsSync(BUS)) return []
  const records = []
  for (const file of readdirSync(BUS).filter((f) => f.endsWith('.jsonl'))) {
    const lines = readFileSync(join(BUS, file), 'utf8').split('\n')
    lines.forEach((line, i) => {
      if (!line.trim()) return
      try {
        records.push({ ...JSON.parse(line), _file: file })
      } catch (err) {
        records.push({
          id: `${file}:${i + 1}`,
          from: 'unknown',
          type: 'PARSE_ERROR',
          createdAt: null,
          body: { rawLine: line, error: err.message },
          _file: file,
        })
      }
    })
  }
  return records.sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
}

const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

function clock(iso) {
  if (!iso) return '--:--:--'
  return new Date(iso).toLocaleTimeString('en-US', { hour12: false })
}

// Agents put headline/oneSentence/WORKING ON at the top level about as often as inside
// body. Merge them in rather than silently dropping them — a dropped headline is an
// invisible message, which is the one thing this page exists to prevent.
const TOP_FIELDS = ['headline', 'oneSentence', 'WORKING ON', 'WAITING FOR']

function bodyOf(r) {
  const top = {}
  for (const k of TOP_FIELDS) if (r[k] != null) top[k] = r[k]
  if (r.body == null) return Object.keys(top).length ? top : null
  if (typeof r.body === 'string') return Object.keys(top).length ? { ...top, note: r.body } : r.body
  return { ...top, ...r.body }
}

function renderBody(body) {
  if (body == null) return ''
  if (typeof body === 'string') return `<div class="field-val">${esc(body)}</div>`
  return Object.entries(body)
    .map(
      ([k, v]) =>
        `<div class="field"><span class="field-key">${esc(k)}</span>` +
        `<div class="field-val">${esc(typeof v === 'string' ? v : JSON.stringify(v, null, 2))}</div></div>`,
    )
    .join('')
}

function copyPayload(r) {
  const clean = {
    id: r.id,
    from: r.from,
    to: r.to,
    replyTo: r.replyTo ?? null,
    type: r.type,
    createdAt: r.createdAt,
    body: r.body,
  }
  return Buffer.from(JSON.stringify(clean, null, 2), 'utf8').toString('base64')
}

function readableText(r) {
  const b = bodyOf(r)
  const lines = [`[${r.type || 'message'}] ${r.from} -> ${r.to || '—'}`, `id: ${r.id}`]
  if (r.createdAt) lines.push(`at: ${r.createdAt}`)
  if (r.replyTo) lines.push(`re: ${r.replyTo}`)
  lines.push('')
  if (b == null) lines.push('(empty)')
  else if (typeof b === 'string') lines.push(b)
  else {
    for (const [k, v] of Object.entries(b)) {
      lines.push(`${k}:`)
      lines.push(typeof v === 'string' ? v : JSON.stringify(v, null, 2))
      lines.push('')
    }
  }
  return Buffer.from(lines.join('\n').trim() + '\n', 'utf8').toString('base64')
}

function render(records) {
  const answered = new Set(records.map((r) => r.replyTo).filter(Boolean))
  const open = records.filter((r) => r.type === 'question' && !answered.has(r.id))
  const byAgent = {}
  for (const r of records) byAgent[r.from] = (byAgent[r.from] || 0) + 1

  const decisions = existsSync(DECISIONS) ? readFileSync(DECISIONS, 'utf8') : '_no decisions.md yet_'

  const rows = records
    .map((r) => {
      const color = AGENT_COLORS[r.from] || '#8a8f98'
      const isOpen = open.some((o) => o.id === r.id)
      const bad = r.type === 'PARSE_ERROR'
      const isJudge = r.from === 'kayley' || r.from === 'steven' || r.type === 'judge'
      return `<article class="msg${isOpen ? ' open' : ''}${bad ? ' bad' : ''}${isJudge ? ' judge' : ''}" style="--agent:${color}">
        <header>
          <span class="who">${esc(r.from)}</span>
          <span class="arrow">-&gt;</span>
          <span class="to">${esc(r.to || '—')}</span>
          <span class="type">${esc(r.type || 'message')}</span>
          ${isJudge ? '<span class="badge-judge">JUDGE</span>' : ''}
          ${isOpen ? '<span class="badge-open">UNANSWERED</span>' : ''}
          <span class="time">${clock(r.createdAt)}</span>
          <button type="button" class="copy-btn" data-copy-text="${readableText(r)}" data-copy-json="${copyPayload(r)}" title="Copy message">Copy</button>
        </header>
        ${r.replyTo ? `<div class="replyto">re: ${esc(r.replyTo)}</div>` : ''}
        <div class="body">${renderBody(bodyOf(r))}</div>
        <footer>${esc(r.id)}</footer>
      </article>`
    })
    .join('\n')

  const chips = Object.entries(byAgent)
    .map(
      ([a, n]) =>
        `<span class="chip" style="--agent:${AGENT_COLORS[a] || '#8a8f98'}">${esc(a)} <b>${n}</b></span>`,
    )
    .join('')

  return `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="${REFRESH_SECONDS}">
<title>AR Lens Bus — OpenLoop</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; background:#0f1115; color:#d7dae0;
         font:13px/1.55 ui-monospace,"Cascadia Code",Consolas,monospace; }
  .top { position:sticky; top:0; background:#151922; border-bottom:1px solid #262c38;
         padding:12px 18px; display:flex; gap:14px; align-items:center; flex-wrap:wrap; z-index:5; }
  h1 { font-size:14px; margin:0; letter-spacing:.06em; text-transform:uppercase; color:#9aa2b1; }
  .chip { border:1px solid var(--agent); color:var(--agent); border-radius:99px;
          padding:1px 10px; font-size:11px; }
  .chip b { color:#fff; }
  .live { margin-left:auto; color:#6b7280; font-size:11px; }
  .live b { color:#4bb3a5; }
  .alert { background:#3a2118; border:1px solid #7a3b22; color:#f0b49a;
           padding:8px 18px; font-size:12px; }
  .layout { display:grid; grid-template-columns: 1.4fr 1fr; gap:0; min-height:calc(100vh - 52px); }
  @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } }
  main { padding:18px; }
  aside { padding:18px; border-left:1px solid #262c38; background:#12151c; }
  aside h2 { margin:0 0 10px; font-size:12px; letter-spacing:.08em; text-transform:uppercase; color:#9aa2b1; }
  aside pre { white-space:pre-wrap; word-break:break-word; margin:0; color:#c8ccd4; font:12px/1.5 inherit; }
  .msg { border-left:3px solid var(--agent); background:#161a22; border-radius:0 6px 6px 0;
         padding:10px 14px; margin-bottom:12px; }
  .msg.open { background:#1d1a12; box-shadow:0 0 0 1px #6b5320; }
  .msg.bad  { background:#2a1416; box-shadow:0 0 0 1px #7a2a2a; }
  .msg.judge { box-shadow:0 0 0 1px #6b4a2a; }
  header { display:flex; gap:9px; align-items:center; flex-wrap:wrap; margin-bottom:6px; }
  .who { color:var(--agent); font-weight:700; }
  .arrow, .to { color:#6b7280; }
  .type { background:#222836; color:#9aa2b1; border-radius:4px; padding:0 7px; font-size:11px; }
  .badge-open { background:#6b5320; color:#ffd489; border-radius:4px; padding:0 7px; font-size:11px; }
  .badge-judge { background:#6b3d20; color:#ffc489; border-radius:4px; padding:0 7px; font-size:11px; }
  .time { margin-left:auto; color:#535a68; font-size:11px; }
  .copy-btn { background:#222836; color:#c8ccd4; border:1px solid #3a4254; border-radius:4px;
              padding:1px 8px; font:11px inherit; cursor:pointer; }
  .copy-btn:hover { border-color:#6b7280; color:#fff; }
  .copy-btn.copied { border-color:#4bb3a5; color:#4bb3a5; }
  .replyto { color:#535a68; font-size:11px; margin-bottom:6px; }
  .field { margin:6px 0; }
  .field-key { color:#7d8697; font-size:11px; text-transform:uppercase;
               letter-spacing:.05em; display:block; }
  .field-val { white-space:pre-wrap; word-break:break-word; color:#c8ccd4; }
  footer { color:#3d4350; font-size:10px; margin-top:8px; }
  .empty { color:#6b7280; padding:40px 0; text-align:center; }
  .judges { color:#e0a458; font-size:11px; }
</style></head><body>
<div class="top">
  <h1>AR Lens Swarm</h1>${chips}
  <span class="judges">judge: Steven · tie-break: Kayley</span>
  <span class="live">auto-refresh <b>${REFRESH_SECONDS}s</b> · ${records.length} msgs · rendered ${clock(new Date().toISOString())}</span>
</div>
${open.length ? `<div class="alert"><b>${open.length} unanswered question${open.length > 1 ? 's' : ''}</b> — someone is blocked.</div>` : ''}
<div class="layout">
  <main>${rows || '<div class="empty">No messages on the bus yet. Paste swarm/SWARM-PROMPT.md into Claude Code + Codex to start.</div>'}</main>
  <aside>
    <h2>decisions.md</h2>
    <pre>${esc(decisions)}</pre>
  </aside>
</div>
<script>
  const k = 'busScroll';
  addEventListener('beforeunload', () => sessionStorage.setItem(k, String(scrollY)));
  const y = sessionStorage.getItem(k);
  if (y) scrollTo(0, +y);

  function b64ToText(b64) {
    const bin = atob(b64);
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    }
  }

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('.copy-btn');
    if (!btn) return;
    e.preventDefault();
    // Alt/Option-click copies raw JSON; normal click copies readable text
    const b64 = e.altKey ? btn.dataset.copyJson : btn.dataset.copyText;
    const text = b64ToText(b64);
    const ok = await copyText(text);
    const prev = btn.textContent;
    btn.textContent = ok ? (e.altKey ? 'JSON!' : 'Copied!') : 'Failed';
    btn.classList.add('copied');
    setTimeout(() => {
      btn.textContent = prev;
      btn.classList.remove('copied');
    }, 1200);
  });
</script>
</body></html>`
}

function build() {
  if (!existsSync(BUS)) mkdirSync(BUS, { recursive: true })
  const records = readBus()
  writeFileSync(OUT, render(records))
  return records.length
}

const n = build()
console.log(`rendered ${n} messages -> ${OUT}`)

if (process.argv.includes('--watch')) {
  console.log('watching bus/ + decisions.md for changes...')
  let pending = null
  const kick = () => {
    clearTimeout(pending)
    pending = setTimeout(() => console.log(`re-rendered ${build()} messages`), 120)
  }
  watch(BUS, kick)
  if (existsSync(DECISIONS)) watch(DECISIONS, kick)
}

#!/usr/bin/env node
// Appends one record to the CANONICAL bus. Takes a JSON file rather than an
// argv string (shell heredocs have eaten quoted words before).
import { appendFileSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const BUS = join(dirname(dirname(fileURLToPath(import.meta.url))), 'collab', 'bus')

const [, , agent, payloadPath] = process.argv
if (!agent || !payloadPath) {
  console.error('usage: node tools/bus-post.mjs <claude|codex|kayley|steven> <payload.json>')
  process.exit(1)
}

const payload = JSON.parse(readFileSync(resolve(payloadPath), 'utf8'))
const record = {
  id: `${agent}-${Date.now()}`,
  from: agent,
  to: payload.to ?? 'both',
  replyTo: payload.replyTo ?? null,
  type: payload.type ?? 'status',
  createdAt: new Date().toISOString(),
  body: payload.body,
}

appendFileSync(join(BUS, `${agent}.jsonl`), JSON.stringify(record) + '\n')
console.log(record.id)

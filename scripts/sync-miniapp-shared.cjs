#!/usr/bin/env node
'use strict'
// Explicit manifest only; --check performs reads, never mkdir/copy/write.
const fs = require('node:fs')
const path = require('node:path')
const base = path.resolve(__dirname, '..')
const source = path.join(base, 'miniapp-shared')
const manifest = JSON.parse(fs.readFileSync(path.join(source, 'manifest.json'), 'utf8'))
const check = process.argv.includes('--check')
if (process.argv.slice(2).some(arg => arg !== '--check')) throw new Error('Usage: node scripts/sync-miniapp-shared.cjs [--check]')
function safe(root, relative) {
  if (typeof relative !== 'string' || !relative || path.isAbsolute(relative) || relative.includes('\\') || relative.split('/').some(part => !part || part === '.' || part === '..')) throw new Error('Unsafe shared manifest path')
  const result = path.resolve(root, relative)
  if (!result.startsWith(root + path.sep)) throw new Error('Shared path escapes root')
  let current = root
  for (const part of relative.split('/')) {
    current = path.join(current, part)
    if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) throw new Error('Shared symlinks are forbidden: ' + relative)
  }
  return result
}
if (!Array.isArray(manifest.roots) || manifest.roots.join(',') !== 'miniapp,rider-miniapp' || !Array.isArray(manifest.files) || new Set(manifest.files).size !== manifest.files.length) throw new Error('Invalid shared manifest')
let mismatches = 0
for (const relative of manifest.files) {
  const bytes = fs.readFileSync(safe(source, relative))
  for (const name of manifest.roots) {
    const destination = safe(path.join(base, name), relative)
    const equal = fs.existsSync(destination) && bytes.equals(fs.readFileSync(destination))
    if (equal) continue
    if (check) { console.error('Shared copy differs: ' + name + '/' + relative); mismatches++; continue }
    fs.mkdirSync(path.dirname(destination), { recursive: true })
    fs.writeFileSync(destination, bytes)
  }
}
if (mismatches) process.exitCode = 1
else console.log('Shared native copies ' + (check ? 'verified read-only' : 'synchronized') + ': ' + manifest.files.length + ' files × ' + manifest.roots.length + ' roots')

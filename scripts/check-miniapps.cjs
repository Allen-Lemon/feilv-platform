'use strict'
// Read-only aggregate: never syncs sources, uploads, or writes build output into either root.
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const repository = path.resolve(__dirname, '..')
const args = process.argv.slice(2)
if (args.some(arg => !['--check', '--compile', '--release'].includes(arg))) throw new Error('Usage: node scripts/check-miniapps.cjs [--check] [--compile] [--release]')
let failures = 0
function run(script, extra = []) {
  console.log('\nCHECK ' + script + ' ' + extra.join(' '))
  if (!fs.existsSync(script)) { console.error('Missing required checker: ' + script); failures++; return }
  const result = spawnSync(process.execPath, [script, ...extra], { cwd: repository, stdio: 'inherit' })
  if (result.error) console.error(result.error.message)
  if (result.error || result.status !== 0) failures++
}
run(path.join(__dirname, 'sync-miniapp-shared.cjs'), ['--check'])
for (const client of ['USER', 'RIDER']) {
  const root = path.join(repository, client === 'USER' ? 'miniapp' : 'rider-miniapp')
  const options = ['--root', root, '--client', client]
  for (const name of ['check-native', 'test-native']) run(path.join(repository, 'miniapp/scripts', name + '.cjs'), options)
  if (args.includes('--compile')) run(path.join(repository, 'miniapp/scripts/compile-wechat.cjs'), options)
  if (args.includes('--release')) run(path.join(repository, 'miniapp/scripts/check-release.cjs'), options)
}
console.log('\n' + (failures ? 'FAIL ' + failures + ' miniapp checks' : 'PASS both native miniapps and read-only shared manifest check'))
if (failures) process.exitCode = 1

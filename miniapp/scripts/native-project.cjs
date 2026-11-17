'use strict'
const fs = require('node:fs')
const path = require('node:path')

const repository = path.resolve(__dirname, '../..')
const PAGE_PATHS = {
  USER: ['index/index', 'address/address', 'order/create', 'order/confirm', 'order/list', 'order/detail', 'order/track', 'my/my', 'login/login', 'profile/profile', 'wallet/wallet', 'settings/settings', 'legal/legal'],
  RIDER: ['rider/workbench', 'my/my', 'rider/register', 'rider/transfers', 'login/login', 'profile/profile', 'wallet/wallet', 'settings/settings', 'legal/legal', 'order/detail']
}
const TAB_PATHS = { USER: ['index/index', 'order/list', 'my/my'], RIDER: ['rider/workbench', 'my/my'] }
const UPLOAD_EXCLUSIONS = ['scripts', 'node_modules', 'dist', 'package.json', 'package-lock.json', 'README.md', 'NATIVE_MIGRATION.md', 'WECHAT_DEPLOYMENT.md']
function projectOptions(args = process.argv.slice(2)) {
  let client
  let root
  const flags = new Set()
  for (let i = 0; i < args.length; i++) {
    const [flag, inline] = args[i].split(/=(.*)/s)
    if (flag === '--root' || flag === '--client') {
      const value = inline === undefined ? args[++i] : inline
      if (!value || value.startsWith('--')) throw new Error(flag + ' requires a value')
      if (flag === '--root') { if (root) throw new Error('Duplicate --root'); root = path.resolve(value) }
      else { if (client) throw new Error('Duplicate --client'); client = value.toUpperCase() }
    } else if (['--compile', '--release', '--check', '--list'].includes(flag) && inline === undefined) flags.add(flag)
    else throw new Error('Unknown argument: ' + args[i])
  }
  const entryRoot = path.resolve(path.dirname(process.argv[1] || __filename), '..')
  const inferredRoot = root || (path.basename(entryRoot) === 'rider-miniapp' ? entryRoot : path.join(repository, 'miniapp'))
  client = client || (path.basename(inferredRoot) === 'rider-miniapp' ? 'RIDER' : 'USER')
  if (!Object.hasOwn(PAGE_PATHS, client)) throw new Error('Client must be USER or RIDER')
  root = root || path.join(repository, client === 'RIDER' ? 'rider-miniapp' : 'miniapp')
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) throw new Error('Miniapp root does not exist: ' + root)
  return { root, client, flags, pages: PAGE_PATHS[client].map(p => 'pages/' + p), tabs: TAB_PATHS[client].map(p => 'pages/' + p) }
}
function inside(root, file) {
  const relative = path.relative(root, file)
  return relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative)
}
function walk(root) {
  return fs.readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    if (['node_modules', 'dist', '.git'].includes(entry.name)) return []
    const file = path.join(root, entry.name)
    if (entry.isSymbolicLink()) throw new Error('Miniapp root may not contain symlinks: ' + file)
    return entry.isDirectory() ? walk(file) : [file]
  })
}
module.exports = { repository, PAGE_PATHS, TAB_PATHS, UPLOAD_EXCLUSIONS, projectOptions, inside, walk }

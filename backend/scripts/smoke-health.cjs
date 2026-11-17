'use strict'
// 运行真实 JAR 的隔离冒烟验证：仅使用 H2 内存库和本机随机端口。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const net = require('node:net')
const http = require('node:http')
const { spawn } = require('node:child_process')
const { randomBytes } = require('node:crypto')
const jar = path.resolve(__dirname, '../target/feilv-server-1.0.0.jar')
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'feilv-health-smoke-'))
const logPath = path.join(temp, 'server.log')
const log = fs.openSync(logPath, 'w')
let server
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function freePort() { return new Promise((resolve, reject) => { const s = net.createServer(); s.on('error', reject); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)) }) }) }
function request(port, method, route, body) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: route, method, timeout: 3000,
      headers: body ? { 'Content-Type': 'application/json' } : {} }, res => {
      let text = ''; res.setEncoding('utf8'); res.on('data', value => { text += value });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: text ? JSON.parse(text) : null }))
    })
    req.on('error', reject); req.on('timeout', () => req.destroy(new Error('request timeout')))
    req.end(body)
  })
}
;(async () => {
  try {
    assert.ok(fs.existsSync(jar), '请先执行 mvn package')
    const port = await freePort()
    server = spawn(java, ['-jar', jar, '--server.address=127.0.0.1', '--server.port=' + port,
      '--spring.profiles.active=health-smoke', '--spring.datasource.url=jdbc:h2:mem:healthsmoke;DB_CLOSE_DELAY=-1',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.datasource.driver-class-name=org.h2.Driver',
      '--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect', '--spring.jpa.hibernate.ddl-auto=create-drop',
      '--spring.sql.init.mode=never'], { stdio: ['ignore', log, log],
      env: { ...process.env, JWT_SECRET: randomBytes(32).toString('hex'), AUTH_DEMO_SMS_ENABLED: 'false' } })
    let startError
    server.on('error', error => { startError = error })
    const exited = new Promise(resolve => server.once('exit', resolve))
    server.exited = exited
    const deadline = Date.now() + 90000
    let health
    while (Date.now() < deadline) {
      if (startError) throw startError
      if (server.exitCode !== null) throw new Error('后端提前退出，请查看日志')
      try { health = await request(port, 'GET', '/health'); if (health.status === 200) break } catch {}
      await pause(500)
    }
    assert.equal(health && health.status, 200, '后端未在超时前就绪')
    assert.equal(health.body.data.status, 'UP')
    console.log('PASS GET /health -> 200 UP (isolated H2)')
    const wrongMethod = await request(port, 'GET', '/api/v1/auth/sms-code')
    assert.equal(wrongMethod.status, 405); assert.equal(wrongMethod.body.code, 405)
    assert.match(wrongMethod.headers.allow, /POST/)
    console.log('PASS GET /api/v1/auth/sms-code -> 405, Allow: POST')
    const post = await request(port, 'POST', '/api/v1/auth/sms-code', '{}')
    assert.equal(post.status, 200); assert.equal(post.body.code, 400)
    console.log('PASS POST /api/v1/auth/sms-code empty body -> existing parameter validation (400 business code)')
    const protectedApi = await request(port, 'GET', '/api/v1/admin/stats')
    assert.equal(protectedApi.status, 401)
    console.log('PASS admin API still requires authentication -> 401')
    assert.ok(!fs.readFileSync(logPath, 'utf8').includes('系统异常'), '方法错误不应记录为系统异常')
    console.log('PASS no generic system-error log for method mismatch')
  } catch (error) {
    console.error(error.stack || error.message)
    process.exitCode = 1
  } finally {
    if (server && server.pid && server.exitCode === null) {
      server.kill('SIGTERM')
      const stopped = await Promise.race([server.exited.then(() => true), pause(5000).then(() => false)])
      if (!stopped) { server.kill('SIGKILL'); await server.exited }
      console.log('Smoke server stopped')
    }
    fs.closeSync(log)
    console.log('Smoke log: ' + logPath)
  }
})()

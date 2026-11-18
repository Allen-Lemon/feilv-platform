'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

// 仅检查构建契约，不执行 Docker、Maven、网络请求或后端进程。
const dockerfile = fs.readFileSync(path.resolve(__dirname, '../Dockerfile'), 'utf8').replace(/\r\n/g, '\n')
const instructions = dockerfile.replace(/[ \t]*\\\n[ \t]*/g, ' ').split('\n')
  .map(line => line.trim()).filter(line => line && !line.startsWith('#'))
const runtimeIndex = instructions.findIndex(line => line.startsWith('FROM eclipse-temurin:'))
const build = instructions.slice(0, runtimeIndex)
const run = build.filter(line => line.startsWith('RUN '))

test('keeps BuildKit syntax and Java 17 Maven build image', () => {
  assert.match(dockerfile, /^# syntax=docker\/dockerfile:1\.7\n/)
  assert.equal(build[0], 'FROM maven:3.9.9-eclipse-temurin-17 AS build')
  assert.equal(instructions.filter(line => line.startsWith('FROM ')).length, 2)
})

test('packages once after copying the POM and sources without eager go-offline', () => {
  assert.doesNotMatch(dockerfile, /dependency:go-offline/)
  assert.equal(run.length, 1)
  assert.equal((run[0].match(/\bmvn\b/g) || []).length, 1)
  assert.match(run[0], /\bmvn -B\b.*\bclean package -DskipTests$/)
  const pomIndex = build.indexOf('COPY pom.xml .')
  const sourceIndex = build.indexOf('COPY src ./src')
  assert.ok(pomIndex >= 0 && sourceIndex > pomIndex)
  assert.ok(build.indexOf(run[0]) > sourceIndex)
})

test('persists Maven artifacts across builds with a locked BuildKit cache', () => {
  assert.match(run[0], /--mount=type=cache,id=feilv-maven-repository,target=\/root\/\.m2\/repository,sharing=locked(?:\s|$)/)
})

test('bounds native Maven Resolver requests and keeps download logs visible', () => {
  assert.match(run[0], /(?:^|\s)-Daether\.connector\.connectTimeout=10000(?:\s|$)/)
  assert.match(run[0], /(?:^|\s)-Daether\.connector\.requestTimeout=60000(?:\s|$)/)
  assert.doesNotMatch(run[0], /(?:^|\s)(?:-ntp|--no-transfer-progress|-q|--quiet)(?:\s|$)/)
})

test('accepts optional Maven settings only through a build secret', () => {
  assert.match(run[0], /--mount=type=secret,id=maven_settings,target=\/root\/\.m2\/settings\.xml,required=false(?:\s|$)/)
  assert.deepEqual(build.filter(line => line.startsWith('COPY ')), ['COPY pom.xml .', 'COPY src ./src'])
  assert.ok(build.every(line => !/^(?:ARG|ENV)\s/.test(line)))
})

test('keeps the runtime image, health check and entrypoint unchanged', () => {
  assert.deepEqual(instructions.slice(runtimeIndex), [
    'FROM eclipse-temurin:17-jre-jammy',
    'RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*',
    'WORKDIR /app',
    'ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"',
    'COPY --from=build /workspace/target/*.jar /app/feilv-server.jar',
    'EXPOSE 8080',
    'HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 CMD curl --fail --silent --show-error --max-time 4 http://127.0.0.1:8080/health || exit 1',
    'ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/feilv-server.jar"]'
  ])
})

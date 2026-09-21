import { spawn } from 'node:child_process'
import { closeSync, openSync } from 'node:fs'
import { cp, mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export default async function setup() {
  const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const target = resolve(frontend, '../main/target')
  const source = join(target, 'web')
  await stat(join(source, 'web.jar'))
  if (await stat(join(source, 'lib/data')).then(() => true, () => false)) throw new Error('Build distribution contains runtime data')
  const owned = await mkdtemp(join(target, 'boot-browser-'))
  const distribution = join(owned, 'web'), working = join(owned, 'working'), keys = join(owned, 'keys')
  await cp(source, distribution, { recursive: true })
  await mkdir(working)
  const port = await new Promise<number>((accept, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (!address || typeof address === 'string') { server.close(); reject(new Error('Missing test port')); return }
      server.close(error => error ? reject(error) : accept(address.port))
    })
  })
  await writeFile(join(working, 'application.yml'), `server:\n  address: 127.0.0.1\n  port: ${port}\nw2l:\n  secrets:\n    directory: '${keys.replaceAll("'", "''")}'\n`)
  const environment = { ...process.env }
  for (const name of ['WEB_TEST_MASTER_KEY', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) delete environment[name]
  // A short test-only TEMP avoids the host JDK's Windows temporary socket limitation.
  const temporary = resolve(frontend, '../../../target/web-boot-tests')
  await mkdir(temporary, { recursive: true }); environment.TEMP = temporary; environment.TMP = temporary
  const java = environment.JAVA_HOME ? join(environment.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
  const log = join(owned, 'startup.log'), output = openSync(log, 'w')
  const child = spawn(java, ['-jar', join(distribution, 'web.jar')], { cwd: working, env: environment, stdio: ['ignore', output, output], windowsHide: true })
  closeSync(output)
  let startupError: Error | undefined
  child.once('error', error => { startupError = error })
  const cleanup = async () => {
    if (child.exitCode === null && child.signalCode === null && !startupError) {
      const exited = new Promise<void>(accept => child.once('exit', () => accept()))
      child.kill()
      await Promise.race([exited, new Promise<void>((_, reject) => setTimeout(() => reject(new Error('Spring Boot test process did not stop')), 10000))])
    }
    if (dirname(owned) !== target || !basename(owned).startsWith('boot-browser-')) throw new Error('Invalid owned cleanup directory')
    await rm(owned, { recursive: true })
  }
  try {
    const origin = `http://127.0.0.1:${port}`, deadline = Date.now() + 40000
    while (Date.now() < deadline) {
      if (startupError || child.exitCode !== null) throw startupError ?? new Error(await readFile(log, 'utf8'))
      const ready = await fetch(`${origin}/api/v1/health`, { signal: AbortSignal.timeout(1000) }).then(result => result.ok, () => false)
      if (ready) { process.env.W2L_BOOT_TEST_ORIGIN = origin; return cleanup }
      await new Promise(accept => setTimeout(accept, 100))
    }
    throw new Error(`Spring Boot startup timed out: ${await readFile(log, 'utf8')}`)
  } catch (failure) { await cleanup(); throw failure }
}

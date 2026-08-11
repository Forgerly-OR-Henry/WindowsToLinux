import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const playwrightCli = resolve(frontendDirectory, 'node_modules', 'playwright', 'cli.js')
const browsersDirectory = resolve(frontendDirectory, '.playwright-browsers')

const result = spawnSync(process.execPath, [playwrightCli, ...process.argv.slice(2)], {
  cwd: frontendDirectory,
  env: {
    ...process.env,
    PLAYWRIGHT_BROWSERS_PATH: browsersDirectory,
  },
  stdio: 'inherit',
})

if (result.error) {
  throw result.error
}

process.exitCode = result.status ?? 1

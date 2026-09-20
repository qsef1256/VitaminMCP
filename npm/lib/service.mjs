import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ACTIONS = new Set(['install', 'uninstall', 'status']);

export function serviceAction(args) {
  if (args[0] !== 'service') return null;
  if (args.length !== 2 || !ACTIONS.has(args[1])) {
    throw new Error('service requires one action: install, uninstall, or status');
  }
  return args[1];
}

/** Runs the Windows-only installer kept outside the cross-platform launcher logic. */
export function runServiceAction(
  action,
  release,
  {
    platform = process.platform,
    node = process.execPath,
    java = null,
    npmCli = npmCliPath(node),
    packageRoot = path.join(HERE, '..'),
    home = process.env.VITAMINMCP_HOME ?? path.join(os.homedir(), '.vitaminmcp'),
    spawnProcess = spawn,
  } = {},
) {
  if (platform !== 'win32') {
    throw new Error('VitaminMCP service management is available only on Windows');
  }
  if (action === 'install' && !npmCli) {
    throw new Error('service install needs npm; run it through npx or install Node with npm');
  }

  const script = path.join(HERE, '..', 'service', 'windows-service.ps1');
  const args = [
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', script,
    '-Action', action,
    '-PackageRoot', packageRoot,
    '-NodePath', node,
    '-DataHome', home,
    '-Version', release,
  ];
  if (npmCli) args.push('-NpmCliPath', npmCli);
  if (java) args.push('-JavaPath', executablePath(java));

  const localJar = path.join(packageRoot, '..', 'build', 'dist', 'mcp-server.jar');
  if (!existsSync(path.join(packageRoot, 'checksums.json')) && existsSync(localJar)) {
    args.push('-ServerJar', localJar);
  }

  const child = spawnProcess('powershell.exe', args, { stdio: 'inherit' });
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (code, signal) => resolve(signal ? 1 : (code ?? 1)));
  });
}

function executablePath(command) {
  if (path.isAbsolute(command)) return command;
  const found = spawnSync('where.exe', [command], { encoding: 'utf8' });
  const resolved = found.status === 0 ? found.stdout.split(/\r?\n/, 1)[0]?.trim() : null;
  return resolved || command;
}

function npmCliPath(node) {
  if (process.env.npm_execpath && existsSync(process.env.npm_execpath)) {
    return process.env.npm_execpath;
  }
  const besideNode = path.join(path.dirname(node), 'node_modules', 'npm', 'bin', 'npm-cli.js');
  if (existsSync(besideNode)) return besideNode;

  const found = spawnSync('where.exe', ['npm.cmd'], { encoding: 'utf8' });
  if (found.status !== 0) return null;
  const npm = found.stdout.split(/\r?\n/, 1)[0]?.trim();
  return npm || null;
}

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

/** The floor the jars are compiled against. Below it they will not load at all. */
export const REQUIRED_JAVA = 21;

/** Keeps every launcher-started JVM small, including the optional shared HTTP server. */
export function mcpServerArgs(server, args = []) {
  return ['-Xms16m', '-Xmx128m', '-XX:+UseSerialGC', '-jar', server, ...args];
}

/**
 * The java to run the jars with: JAVA_HOME if it points at one, otherwise whatever is on PATH.
 *
 * JAVA_HOME wins because a machine with several JDKs usually means one of them was chosen
 * deliberately, and PATH is the one nobody remembers setting.
 */
export function findJava() {
  const home = process.env.JAVA_HOME;
  if (home) {
    const candidate = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

/**
 * Checks the java that will actually be used, and says what is wrong in terms of a fix.
 *
 * Worth doing before spawning: java's own failure for a too-old runtime is
 * `UnsupportedClassVersionError` naming class file version 65, which is a puzzle rather than a
 * message, and an MCP client shows it — if it shows anything — as a server that died at startup.
 */
export function checkJava(java) {
  const probe = spawnSync(java, ['-version'], { encoding: 'utf8' });

  if (probe.error) {
    return {
      ok: false,
      message:
        `No Java found. VitaminMCP runs on the JVM, so it needs Java ${REQUIRED_JAVA} or later ` +
        `on this machine.\n` +
        `  Tried: ${java}\n` +
        `  Install a JDK (https://adoptium.net/), or point JAVA_HOME at one you already have.`,
    };
  }

  // Every JVM prints its version to stderr, and has since before anyone thought to standardise it.
  const output = `${probe.stderr || ''}${probe.stdout || ''}`;
  const match = output.match(/version "(\d+)(?:\.(\d+))?/);
  if (!match) {
    // An unrecognised banner is not a reason to refuse; the jars themselves will say so if it
    // really is too old.
    return { ok: true, version: null };
  }

  const major = match[1] === '1' ? Number(match[2]) : Number(match[1]);
  if (major < REQUIRED_JAVA) {
    return {
      ok: false,
      version: major,
      message:
        `Java ${major} is too old — VitaminMCP needs ${REQUIRED_JAVA} or later.\n` +
        `  Using: ${java}\n` +
        `  Install a newer JDK (https://adoptium.net/), or point JAVA_HOME at one you already have.`,
    };
  }
  return { ok: true, version: major };
}

import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const RELEASES = 'https://github.com/Backas03/VitaminMCP/releases/download';

/** The jar this package launches, and the one it launches in turn. */
export const MCP_SERVER_JAR = 'mcp-server.jar';

/** The optional viewer archive fetched only when a world view is requested. */
export const VIEWER_ASSET = 'bot-runner-viewer-win-x64.tgz';

let checksumsPromise;
let manifestPromise;

const REPORT = 'https://github.com/Backas03/VitaminMCP/issues';

/**
 * The exact bytes this version of the package expects, stamped in at release.
 *
 * Pinning them here rather than trusting whatever the release currently serves is the point: a
 * release asset can be replaced after the fact, and a published npm version cannot.
 *
 * The file names the version it was stamped for, and that is checked. Otherwise the one mistake
 * this design invites — publishing a version bump without re-stamping — would ship a package that
 * downloads one release and checks it against another, and every install would fail on a hash
 * mismatch that reads exactly like a compromised download.
 */
async function checksums(version) {
  checksumsPromise ??= fs
    .readFile(path.join(HERE, '..', 'checksums.json'), 'utf8')
    .then(JSON.parse)
    .catch((cause) => {
      throw new Error(
        'This package was published without checksums.json, so it cannot verify what it ' +
          `downloads and will not download anything. Please report it at ${REPORT}.`,
        { cause },
      );
    });

  const stamped = await checksumsPromise;
  if (stamped.version !== version) {
    throw new Error(
      `This package is version ${version} but its checksums were stamped for ` +
        `${stamped.version}, so it cannot tell a bad download from a mismatched one. Nothing ` +
        `was installed. Please report it at ${REPORT}.`,
    );
  }
  return stamped.jars ?? {};
}

async function assetChecksums(version) {
  manifestPromise ??= fs
    .readFile(path.join(HERE, '..', 'checksums.json'), 'utf8')
    .then(JSON.parse);
  const stamped = await manifestPromise;
  if (stamped.version !== version) {
    throw new Error(`This package is version ${version} but its checksums were stamped for ${stamped.version}.`);
  }
  return stamped.assets ?? {};
}

/**
 * Where downloaded jars live: one directory per version, so switching versions never mixes them.
 *
 * The same root the agent writes its handshake into, so one variable moves everything VitaminMCP
 * keeps outside the project.
 */
export function cacheDirectory(version) {
  const root = process.env.VITAMINMCP_HOME || path.join(os.homedir(), '.vitaminmcp');
  return path.join(root, 'jars', version);
}

/** A jar already on this machine, or null. Never downloads. */
export async function cachedJar(version, name) {
  const file = path.join(cacheDirectory(version), name);
  try {
    await fs.access(file);
  } catch {
    return null;
  }
  const expected = (await checksums(version))[name];
  if (!expected) return null;
  if (await verifyFileHash(file, expected)) return file;
  await fs.rm(file, { force: true });
  return null;
}

/**
 * Makes sure one jar is on this machine, downloading it if not, and returns its path.
 *
 * Downloads land on a `.part` file and are renamed once the hash matches, so an interrupted
 * download is never mistaken for a finished one — and the server, which waits for exactly that
 * rename, never opens a half-written jar.
 */
export async function ensureJar(version, name, { log = () => {} } = {}) {
  const directory = cacheDirectory(version);
  const file = path.join(directory, name);

  const existing = await cachedJar(version, name);
  if (existing) {
    return existing;
  }

  const expected = (await checksums(version))[name];
  if (!expected) {
    throw new Error(`checksums.json does not cover ${name}, so it will not be downloaded.`);
  }

  await fs.mkdir(directory, { recursive: true });
  const partial = `${file}.part`;
  const url = `${RELEASES}/${version}/${name}`;

  // Claimed before the request goes out, not after it answers. The server waits on this file to
  // decide whether a jar is coming or simply absent, and between spawning it and the first byte
  // arriving there is easily enough time for it to ask.
  await fs.writeFile(partial, '');

  log(`downloading ${name} (${version})`);

  const response = await fetch(url, { redirect: 'follow' });
  if (!response.ok || !response.body) {
    throw new Error(
      `Could not download ${name}: ${response.status} ${response.statusText}\n  ${url}`,
    );
  }

  const hash = createHash('sha256');
  const source = Readable.fromWeb(response.body);
  source.on('data', (chunk) => hash.update(chunk));

  try {
    await pipeline(source, createWriteStream(partial));

    const actual = hash.digest('hex');
    if (actual !== expected) {
      throw new Error(
        `${name} does not match the checksum this package was published with.\n` +
          `  expected ${expected}\n` +
          `  received ${actual}\n` +
          `  from     ${url}\n` +
          'Nothing was installed. Please report this.',
      );
    }

    await fs.rename(partial, file);
  } catch (error) {
    await fs.rm(partial, { force: true });
    throw error;
  }

  log(`${name} ready`);
  return file;
}

/** Where a jar will be once it has downloaded, whether or not it has. */
export function jarPath(version, name) {
  return path.join(cacheDirectory(version), name);
}

export function assetCacheDirectory(version) {
  const root = process.env.VITAMINMCP_HOME || path.join(os.homedir(), '.vitaminmcp');
  return path.join(root, 'assets', version);
}

export async function cachedAsset(version, name) {
  const file = path.join(assetCacheDirectory(version), name);
  try {
    await fs.access(file);
  } catch {
    return null;
  }
  const expected = (await assetChecksums(version))[name];
  if (!expected) return null;
  if (await verifyFileHash(file, expected)) return file;
  await fs.rm(file, { force: true });
  return null;
}

/** Fetches a pinned platform asset only after Node fallback selection has failed. */
export async function ensureAsset(version, name, { log = () => {} } = {}) {
  const directory = assetCacheDirectory(version);
  const file = path.join(directory, name);
  const existing = await cachedAsset(version, name);
  if (existing) return existing;
  const expected = (await assetChecksums(version))[name];
  if (!expected) throw new Error(`checksums.json does not cover asset ${name}, so it will not be downloaded.`);
  await fs.mkdir(directory, { recursive: true });
  const partial = `${file}.part`;
  await fs.writeFile(partial, '');
  const url = `${RELEASES}/${version}/${name}`;
  log(`downloading ${name} (${version})`);
  const response = await fetch(url, { redirect: 'follow' });
  if (!response.ok || !response.body) {
    await fs.rm(partial, { force: true });
    throw new Error(`Could not download ${name}: ${response.status} ${response.statusText}\n  ${url}`);
  }
  const hash = createHash('sha256');
  const source = Readable.fromWeb(response.body);
  source.on('data', (chunk) => hash.update(chunk));
  try {
    await pipeline(source, createWriteStream(partial));
    const actual = hash.digest('hex');
    if (actual !== expected) {
      throw new Error(`${name} does not match its pinned checksum.\n  expected ${expected}\n  received ${actual}`);
    }
    await fs.rename(partial, file);
    if (process.platform !== 'win32') await fs.chmod(file, 0o755);
  } catch (error) {
    await fs.rm(partial, { force: true });
    throw error;
  }
  log(`${name} ready`);
  return file;
}

export function assetPath(version, name) {
  return path.join(assetCacheDirectory(version), name);
}

async function sha256File(file) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) {
    hash.update(chunk);
  }
  return hash.digest('hex');
}

/** Verifies a cached release byte-for-byte without loading the whole file into memory. */
export async function verifyFileHash(file, expected) {
  return (await sha256File(file)) === expected;
}

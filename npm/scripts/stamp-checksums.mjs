#!/usr/bin/env node

/**
 * Pins a release into this package: its version, and the exact bytes of the jars it will download.
 *
 * Run after the release exists and before publishing. Two sources, and the second is the one to
 * prefer at release time:
 *
 *   node scripts/stamp-checksums.mjs --dist ../build/dist   hashes jars you just built
 *   node scripts/stamp-checksums.mjs --tag 2.0.0            reads what the release actually serves
 *
 * The version always comes from build-logic, which is the one place this project keeps it.
 */

import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PACKAGE = path.join(HERE, '..');
const REPOSITORY = path.join(PACKAGE, '..');

const WANTED = ['mcp-server.jar'];
const ASSETS = [
  'bot-runner-win-x64.exe',
  'bot-runner-linux-x64',
  'bot-runner-linux-arm64',
  'bot-runner-darwin-x64',
  'bot-runner-darwin-arm64',
  'bot-runner-viewer-win-x64.tgz',
];
const API = 'https://api.github.com/repos/Backas03/VitaminMCP/releases/tags';

/** The version this project is on, read from the single place that declares it. */
async function projectVersion() {
  const conventions = path.join(
    REPOSITORY,
    'build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts',
  );
  const source = await fs.readFile(conventions, 'utf8');
  const match = source.match(/^version\s*=\s*"([^"]+)"/m);
  if (!match) {
    throw new Error(`No version declared in ${conventions}`);
  }
  return match[1];
}

/** Hashes jars built into a directory. */
async function fromDist(directory) {
  const checksums = {};
  for (const name of WANTED) {
    const versioned = (await fs.readdir(directory)).find(
      (file) => file === name || (file.startsWith(name.replace('.jar', '-')) && file.endsWith('.jar')),
    );
    if (!versioned) {
      throw new Error(`${directory} holds no ${name}. Run 'gradlew dist' first.`);
    }
    const bytes = await fs.readFile(path.join(directory, versioned));
    checksums[name] = createHash('sha256').update(bytes).digest('hex');
  }
  return checksums;
}

async function fromDistAssets(directory) {
  const assets = {};
  for (const name of ASSETS) {
    const source = path.join(directory, name.endsWith('.tgz') ? 'assets' : 'runners');
    const entries = await fs.readdir(source).catch(() => []);
    if (!entries.includes(name)) {
      throw new Error(`${source} holds no ${name}. Build the runner and viewer assets first.`);
    }
    assets[name] = createHash('sha256').update(await fs.readFile(path.join(source, name))).digest('hex');
  }
  return assets;
}

/** Reads the digests GitHub reports for what a release actually serves. */
async function fromTag(tag) {
  const response = await fetch(`${API}/${tag}`, {
    headers: { accept: 'application/vnd.github+json' },
  });
  if (!response.ok) {
    throw new Error(`No release ${tag}: ${response.status} ${response.statusText}`);
  }

  const assets = (await response.json()).assets ?? [];
  const checksums = {};
  for (const name of WANTED) {
    const asset = assets.find((candidate) => candidate.name === name);
    if (!asset) {
      throw new Error(`Release ${tag} has no asset named ${name}.`);
    }
    if (!asset.digest?.startsWith('sha256:')) {
      throw new Error(`Release ${tag} reports no sha256 for ${name}.`);
    }
    checksums[name] = asset.digest.slice('sha256:'.length);
  }
  return checksums;
}

async function fromTagAssets(tag) {
  const response = await fetch(`${API}/${tag}`, {
    headers: { accept: 'application/vnd.github+json' },
  });
  if (!response.ok) throw new Error(`No release ${tag}: ${response.status} ${response.statusText}`);
  const assets = (await response.json()).assets ?? [];
  const checksums = {};
  for (const name of ASSETS) {
    const asset = assets.find((candidate) => candidate.name === name);
    if (!asset?.digest?.startsWith('sha256:')) {
      throw new Error(`Release ${tag} has no sha256 asset named ${name}.`);
    }
    checksums[name] = asset.digest.slice('sha256:'.length);
  }
  return checksums;
}

/**
 * Refuses a publish that would ship checksums belonging to another version.
 *
 * Hooked to prepublishOnly, because the stamp is the one release step with no other symptom: the
 * package builds, installs and looks right, and only fails on a user's machine.
 */
async function verify() {
  const version = JSON.parse(await fs.readFile(path.join(PACKAGE, 'package.json'), 'utf8')).version;

  let stamped;
  try {
    stamped = JSON.parse(await fs.readFile(path.join(PACKAGE, 'checksums.json'), 'utf8'));
  } catch {
    throw new Error(
      'checksums.json is missing. Run: node scripts/stamp-checksums.mjs --tag ' + version,
    );
  }

  if (stamped.version !== version) {
    throw new Error(
      `checksums.json was stamped for ${stamped.version} but this package is ${version}. ` +
        `Run: node scripts/stamp-checksums.mjs --tag ${version}`,
    );
  }
  for (const name of WANTED) {
    if (!stamped.jars?.[name]) {
      throw new Error(`checksums.json has no hash for ${name}.`);
    }
  }
  for (const name of ASSETS) {
    if (!stamped.assets?.[name]) throw new Error(`checksums.json has no asset hash for ${name}.`);
  }
  process.stdout.write(`checksums.json matches vitaminmcp ${version}
`);
}

/**
 * Puts the project version into the two files that repeat it, and nothing else.
 *
 * Belongs to a version-bump commit rather than to a release: the release workflow checks these
 * against the tag before it builds anything, so a stale one has to fail in the repository rather
 * than halfway through publishing.
 */
async function sync() {
  const version = await projectVersion();

  const manifestFile = path.join(PACKAGE, 'package.json');
  const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
  manifest.version = version;
  await fs.writeFile(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`);

  const serverFile = path.join(REPOSITORY, 'server.json');
  const server = JSON.parse(await fs.readFile(serverFile, 'utf8'));
  server.version = version;
  for (const entry of server.packages ?? []) {
    entry.version = version;
  }
  await fs.writeFile(serverFile, `${JSON.stringify(server, null, 2)}\n`);

  // The Claude Code plugin repeats the version twice more, and `claude plugin validate` fails if
  // the two disagree with each other — but nothing checks either against the project.
  const pluginFile = path.join(REPOSITORY, 'claude-code/.claude-plugin/plugin.json');
  const plugin = JSON.parse(await fs.readFile(pluginFile, 'utf8'));
  plugin.version = version;
  await fs.writeFile(pluginFile, `${JSON.stringify(plugin, null, 2)}\n`);

  const marketFile = path.join(REPOSITORY, '.claude-plugin/marketplace.json');
  const market = JSON.parse(await fs.readFile(marketFile, 'utf8'));
  for (const entry of market.plugins ?? []) {
    if (entry.name === 'vitaminmcp') {
      entry.version = version;
    }
  }
  await fs.writeFile(marketFile, `${JSON.stringify(market, null, 2)}\n`);

  return version;
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--verify')) {
    return verify();
  }
  if (argv.includes('--sync')) {
    const version = await sync();
    process.stdout.write(`npm/package.json and server.json set to ${version}\n`);
    return;
  }

  const distAt = argv.indexOf('--dist');
  const tagAt = argv.indexOf('--tag');

  const version = await projectVersion();

  let checksums;
  let assets;
  if (distAt >= 0) {
    checksums = await fromDist(path.resolve(argv[distAt + 1] ?? path.join(REPOSITORY, 'build/dist')));
    assets = await fromDistAssets(path.resolve(argv[distAt + 1] ?? path.join(REPOSITORY, 'build/dist')));
  } else if (tagAt >= 0) {
    checksums = await fromTag(argv[tagAt + 1] ?? version);
    assets = await fromTagAssets(argv[tagAt + 1] ?? version);
  } else {
    checksums = await fromTag(version);
    assets = await fromTagAssets(version);
  }

  await fs.writeFile(
    path.join(PACKAGE, 'checksums.json'),
    `${JSON.stringify({ version, jars: checksums, assets }, null, 2)}\n`,
  );

  await sync();

  process.stdout.write(`vitaminmcp ${version}\n`);
  for (const [name, hash] of Object.entries(checksums)) {
    process.stdout.write(`  ${name}  ${hash}\n`);
  }
}

main().catch((error) => {
  process.stderr.write(`${error.message ?? error}\n`);
  process.exit(1);
});

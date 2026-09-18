import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import minecraftData from 'minecraft-data';

import { keptFiles } from '../scripts/slim-minecraft-data.mjs';
import { PING_VERSION, versionForProtocol } from '../src/version.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = path.join(root, 'node_modules', 'minecraft-data');
const dataRoot = path.join(packageRoot, 'minecraft-data', 'data');

/**
 * These guard a build-time prune whose failure mode is silence: drop a file a supported version
 * borrows and the runner still builds, still starts, and dies the first time a bot touches a
 * recipe. minecraft-data decides which version borrows what, and it changes that between
 * releases, so the assertions below are about the shape of the answer rather than a fixed list.
 */

const FLOOR = '1.21';

test('the keep-set covers every file the supported versions ask for', () => {
  const keep = keptFiles(packageRoot, FLOOR);

  assert.ok(keep.size > 100, `only ${keep.size} files kept, which is too few to be right`);
  for (const file of keep) {
    assert.ok(fs.existsSync(file), `${file} is in the keep-set but not on disk`);
  }
});

test('it keeps the older directories the supported versions borrow from', () => {
  const directories = new Set(
    [...keptFiles(packageRoot, FLOOR)].map((file) => path.basename(path.dirname(file))),
  );

  // Not a wish-list: 1.21.x reads recipes, tints and more out of these. A prune by directory name
  // would drop them, the build would still succeed, and the runner would fail in the field.
  assert.ok(directories.size > 1, 'only one directory kept, so cross-version borrowing was missed');
  for (const borrowed of [...directories].filter((name) => !name.startsWith(FLOOR))) {
    assert.ok(
      fs.existsSync(path.join(dataRoot, 'pc', borrowed)),
      `${borrowed} was kept but does not exist, so data.js is being parsed wrongly`,
    );
  }
});

test('it keeps every version above the floor, not only the floor\'s own line', () => {
  const directories = new Set(
    [...keptFiles(packageRoot, FLOOR)].map((file) => path.basename(path.dirname(file))),
  );

  assert.ok(directories.has('26.1'), '26.1 is not bundled, so the prune stops at the 1.21 line');
  for (const version of releasesAtOrAbove(FLOOR)) {
    assert.ok(directories.has(version), `${version} ships in minecraft-data but is not bundled`);
  }
});

test('the bundle covers every version in versions.yaml', () => {
  const directories = new Set(
    [...keptFiles(packageRoot, FLOOR)].map((file) => path.basename(path.dirname(file))),
  );
  const matrix = fs.readFileSync(path.join(root, '..', '..', 'versions.yaml'), 'utf8');
  const versions = [...matrix.matchAll(/paper:\s*\{\s*version:\s*"([^"]+)"/g)].map((m) => m[1]);
  assert.ok(versions.length > 0, 'versions.yaml lists no Paper versions, so the parse is wrong');

  for (const version of versions) {
    const protocol = minecraftData.versionsByMinecraftVersion.pc[version]?.version;
    assert.ok(protocol, `minecraft-data has never heard of ${version}, so the runner cannot join it`);
    assert.ok(
      directories.has(versionForProtocol(protocol)),
      `${version} (protocol ${protocol}) is in versions.yaml but its data is not bundled`,
    );
  }
});

function releasesAtOrAbove(floor) {
  const versions = minecraftData.versionsByMinecraftVersion.pc;
  return fs.readdirSync(path.join(dataRoot, 'pc')).filter((name) => {
    const known = versions[name];
    return known?.releaseType === 'release' && atLeast(name, floor);
  });
}

function atLeast(version, floor) {
  const a = version.split('.').map(Number);
  const b = floor.split('.').map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
    if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) > (b[i] ?? 0);
  }
  return true;
}

test('it drops Bedrock and the versions below the floor', () => {
  const keep = keptFiles(packageRoot, FLOOR);

  for (const file of keep) {
    assert.ok(!file.includes(`${path.sep}bedrock${path.sep}`), `${file} is Bedrock data`);
  }

  const dropped = path.join(dataRoot, 'pc', '1.8', 'blocks.json');
  if (fs.existsSync(dropped)) {
    assert.ok(!keep.has(dropped), '1.8 block data is bundled, so the prune did nothing');
  }
});

test('the version the ping is written with is one the build keeps', () => {
  const kept = [...keptFiles(packageRoot, FLOOR)].some(
    (file) => path.basename(path.dirname(file)) === PING_VERSION,
  );

  // Otherwise every run dies at startup: the ping happens before anything knows what the server
  // speaks, so its data has to be in the bundle unconditionally.
  assert.ok(kept, `the ping uses ${PING_VERSION}, which this build does not bundle`);
});

test('a floor newer than anything minecraft-data ships fails the build', () => {
  assert.throws(() => keptFiles(packageRoot, '99.1'), /No minecraft-data entries matched/);
});

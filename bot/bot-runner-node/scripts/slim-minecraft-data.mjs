/**
 * Keeps the bundled copy of minecraft-data down to the versions this project supports.
 *
 * minecraft-data ships every version of both editions — 426MB of JSON, of which 331MB is Bedrock
 * that a Paper testing tool can never reach. `data.js` reaches all of it through **static**
 * `require()` calls behind lazy getters, so it costs nothing at runtime but everything to
 * esbuild, which resolves each one at build time and inlines the file. That is how a Node SEA
 * whose predecessor was an 88MB jar became a 564MB executable that every user downloads.
 *
 * The obvious prune — keep `pc/1.21*`, drop the rest — is wrong. A version entry borrows files
 * from older directories: 1.21.x reads recipes, tints and more out of pc/1.16.1, pc/1.20,
 * pc/1.20.2, pc/1.20.3 and pc/1.20.5. So the keep-set is *derived* from data.js rather than
 * guessed from directory names, and it stays correct when minecraft-data reshuffles which
 * version borrows what.
 *
 * What is dropped becomes a module that throws when something asks for it. An empty object would
 * be worse: a version outside the supported range would silently behave as though the game had
 * no blocks, instead of saying it was never bundled.
 */

import fs from 'node:fs';
import path from 'node:path';

/** Where a `require` inside data.js points, as it is written there. */
const DATA_REFERENCE = /\.\/minecraft-data\/data\/[^"']+/g;

/** `  'pc': {` — the edition. */
const EDITION_LINE = /^ {2}'([a-z]+)': \{/;

/** `    '1.21.8': {` — the version inside an edition. */
const VERSION_LINE = /^ {4}'([^']+)': \{/;

/**
 * Common data is loaded by index.js and lib/supportsFeature.js directly, for both editions and
 * regardless of version, so it is never droppable. It is also small — under half a megabyte for
 * the two of them together.
 */
const ALWAYS = /[\\/]data[\\/](pc|bedrock)[\\/]common[\\/]/;

/** Anything under minecraft-data's data directory, which is all this plugin concerns itself with. */
const ANY_DATA_FILE = /[\\/]minecraft-data[\\/]data[\\/].*\.json$/;

/**
 * The versions worth bundling: the floor and everything minecraft-data knows above it — not the
 * floor's own line, which stopped meaning "everything newer" when 1.21.11 was followed by 26.1.
 * Wider than versions.yaml on purpose, so a release newer than the last compatibility run still
 * connects instead of being refused.
 */
function supported(floor) {
  const minimum = parse(floor);
  return (version) => version != null && compare(parse(version), minimum) >= 0;
}

function parse(version) {
  return String(version).split('-', 1)[0].split('.').map((part) => Number.parseInt(part, 10) || 0);
}

function compare(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
    const difference = (a[i] ?? 0) - (b[i] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

/** Every data file the supported versions reach, including the ones they borrow from elsewhere. */
export function keptFiles(packageRoot, floor) {
  return keptFilesForVersions(packageRoot, supported(floor), `${floor} and later`);
}

/**
 * Every data file a set of exact Minecraft versions reaches.
 *
 * The viewer has two extra consumers of minecraft-data: it asks for the version it renders and its
 * model code asks for 1.16.2's tint table. Keeping this primitive here lets both the SEA plugin and
 * the optional viewer asset use the same data.js-derived reference walk.
 */
export function keptFilesForVersions(packageRoot, versions, description) {
  const { matches, label } = selector(versions, description);
  const source = fs.readFileSync(path.join(packageRoot, 'data.js'), 'utf8');

  const keep = new Set();
  let edition = null;
  let version = null;

  for (const line of source.split(/\r?\n/)) {
    const isEdition = line.match(EDITION_LINE);
    if (isEdition) {
      edition = isEdition[1];
      version = null;
      continue;
    }
    const isVersion = line.match(VERSION_LINE);
    if (isVersion) {
      version = isVersion[1];
    }
    if (edition !== 'pc' || !matches(version)) continue;

    for (const reference of line.matchAll(DATA_REFERENCE)) {
      keep.add(path.resolve(packageRoot, reference[0]));
    }
  }

  if (keep.size === 0) {
    throw new Error(
      `No minecraft-data entries matched ${label}. Either the requested versions `
        + 'moved past what this copy of '
        + 'minecraft-data ships, or data.js changed shape — check it before trusting this build.',
    );
  }
  return keep;
}

function selector(versions, description) {
  if (typeof versions === 'function') {
    return { matches: versions, label: description ?? 'the requested versions' };
  }
  if (versions instanceof RegExp) {
    return { matches: (version) => versions.test(version ?? ''), label: description ?? versions.source };
  }
  const wanted = versions instanceof Set ? versions : new Set(versions);
  return { matches: (version) => wanted.has(version), label: description ?? [...wanted].join(', ') };
}

/** An esbuild plugin that inlines the supported versions and refuses the rest. */
export function slimMinecraftData(packageRoot, floor) {
  const keep = keptFiles(packageRoot, floor);

  return {
    name: 'slim-minecraft-data',
    setup(build) {
      build.onLoad({ filter: ANY_DATA_FILE }, ({ path: file }) => {
        if (ALWAYS.test(file) || keep.has(path.resolve(file))) {
          return null;
        }
        const name = path.basename(path.dirname(file));
        return {
          loader: 'js',
          contents:
            `throw new Error("minecraft-data for ${name} is not in this runner: it is built for `
            + `Minecraft ${floor} and later. Run the server on a supported version, or update the `
            + `runner's minecraft-data and rebuild.");`,
        };
      });
    },
  };
}

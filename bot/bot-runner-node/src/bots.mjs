import mineflayer from 'mineflayer';
import { mkdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import prismarineAuth from 'prismarine-auth';
import { configurePathfinder, loadPathfinder, moveTo } from './movement.mjs';
import { reachable } from './movement.mjs';
import { stopAllViews, stopView as stopBotView, view as startView } from './viewer.mjs';

import { collect, forget } from './clientview.mjs';
import { addressField, identity } from './identity.mjs';

const { Authflow } = prismarineAuth;

const DEFAULT_ACCOUNTS_DIRECTORY = resolve(
  process.env.VITAMINMCP_ACCOUNTS_DIR ?? join(homedir(), '.vitaminmcp', 'accounts'),
);

/** How long a bot has to get from a socket to standing in the world. */
const LOGIN_TIMEOUT_MILLIS = 30_000;

/** How long it then has to stop falling. */
const SETTLE_TIMEOUT_MILLIS = 15_000;

/** Matching the Java runner: five polls 50ms apart with no change in Y is "landed". */
const SETTLED_CHECKS = 5;
const SETTLE_POLL_MILLIS = 50;

/** How long the client gets to be sent the chunk it is standing in. */
const WORLD_TIMEOUT_MILLIS = 15_000;

/** The bots this runner holds, and the server they all connect to. */
export class BotRegistry {
  #host;

  #port;

  #version;

  #accounts;

  #bots = new Map();

  constructor(host, port, version, accounts = new MicrosoftAccounts()) {
    this.#host = host;
    this.#port = port;
    this.#version = version;
    this.#accounts = accounts;
  }

  /** Connects a bot and waits until it is standing in the world. */
  async spawn(name, clientIp, auth = 'offline', account = '') {
    // The Java runner overwrites the map entry, so a repeated spawn is not an error there and must
    // not become one here. Closing the old socket first is the only difference: leaving it open
    // would hold a player slot under a name this runner no longer tracks.
    const existing = this.#bots.get(name);
    if (existing) {
      this.#bots.delete(name);
      quietly(() => existing.quit());
    }

    const mode = authenticationMode(auth);
    if (mode === 'microsoft' && clientIp?.trim()) {
      throw new Error('clientIp forwarding is only available for offline bots');
    }

    const accountKey = account?.trim() || name;
    const authenticated = mode === 'microsoft'
      ? await this.#accounts.require(accountKey)
      : null;
    if (authenticated && authenticated.profile.name.toLowerCase() !== name.toLowerCase()) {
      throw new Error(
        `Microsoft account '${accountKey}' owns profile '${authenticated.profile.name}', `
          + `not '${name}'. Call bot_spawn with name='${authenticated.profile.name}'.`,
      );
    }

    const id = authenticated
      ? identity(authenticated.profile.name, { uuid: canonicalUuid(authenticated.profile.id) })
      : identity(name);
    const bot = mineflayer.createBot(
      connectionOptions(
        this.#host,
        this.#port,
        this.#version,
        id,
        clientIp,
        mode,
        accountKey,
        authenticated?.profilesFolder,
        (code) => {
          this.#accounts.invalidate(accountKey);
          throw new Error(loginRequired(accountKey, code));
        },
      ),
    );
    loadPathfinder(bot);

    // Before waiting to join, not after: messages are events, and a plugin that greets or refuses
    // on join says so within a tick of the bot arriving. Attaching afterwards loses exactly the
    // messages most worth having.
    collect(bot, name);

    try {
      await joined(bot, name);
    } catch (failure) {
      quietly(() => bot.end());
      throw failure;
    }

    // From here the bot outlives this call, so it needs an owner for its failures. An unhandled
    // 'error' is fatal to the process in Node, which would turn one kicked bot into a dead runner
    // and lose every other bot with it.
    bot.on('error', () => {});
    bot.on('kicked', () => {});

    this.#bots.set(name, bot);
    configurePathfinder(bot);
    await settle(bot, name);
    await worldKnown(bot, name);
    return {
      ...position(bot),
      playerName: id.name,
      uuid: id.uuid,
    };
  }

  async move(name, x, y, z, mode, timeoutMillis) {
    await moveTo(this.require(name), name, x, y, z, mode, timeoutMillis);
  }

  async assertReachable(name, x, y, z, timeoutMillis) {
    const bot = name && name.trim()
      ? this.require(name)
      : this.#bots.values().next().value;
    if (!bot) {
      throw new Error('no bot is available to inspect the loaded world for reachability');
    }
    return reachable(bot, x, y, z, timeoutMillis);
  }

  async view(name, what, mode) {
    return startView(this.require(name), name, what, mode, this.#version);
  }

  stopView(name) {
    stopBotView(this.require(name));
  }

  despawn(name) {
    const bot = this.#bots.get(name);
    if (!bot) {
      return;
    }
    this.#bots.delete(name);
    forget(name);
    stopBotView(bot);
    quietly(() => bot.quit());
  }

  position(name) {
    return position(this.require(name));
  }

  /** Disconnects every bot. */
  shutdown() {
    stopAllViews();
    for (const [name, bot] of this.#bots) {
      forget(name);
      quietly(() => bot.quit());
    }
    this.#bots.clear();
  }

  /** The bot, or the error the Java runner raises for the same mistake. */
  require(name) {
    const bot = this.#bots.get(name);
    if (!bot) {
      throw new Error(`no bot named ${name} — spawn it before acting with it`);
    }
    return bot;
  }
}

/** Builds a login, adding BungeeCord forwarding only for an explicit offline-mode IP test. */
export function connectionOptions(
  host,
  port,
  version,
  id,
  clientIp,
  auth = 'offline',
  account = id.name,
  profilesFolder = DEFAULT_ACCOUNTS_DIRECTORY,
  onMsaCode = undefined,
) {
  const mode = authenticationMode(auth);
  const options = {
    host,
    port,
    username: mode === 'microsoft' ? account : id.name,
    auth: mode,
    version,
    checkTimeoutInterval: LOGIN_TIMEOUT_MILLIS,
  };
  if (mode === 'microsoft') {
    options.profilesFolder = profilesFolder;
    options.onMsaCode = onMsaCode;
  } else if (clientIp && clientIp.trim()) {
    options.fakeHost = addressField(host, clientIp.trim(), id);
  }
  return options;
}

/** Microsoft device-code authentication that survives a failed first spawn through its cache. */
export class MicrosoftAccounts {
  #directory;

  #createFlow;

  #states = new Map();

  constructor(
    directory = DEFAULT_ACCOUNTS_DIRECTORY,
    createFlow = (account, profilesFolder, onCode) => new Authflow(
      account,
      profilesFolder,
      undefined,
      onCode,
    ),
  ) {
    this.#directory = resolve(directory);
    this.#createFlow = createFlow;
  }

  async require(account) {
    let state = this.#states.get(account);
    if (!state) {
      state = this.#start(account);
      this.#states.set(account, state);
    }
    if (state.status === 'starting') {
      await state.firstUpdate;
    }
    if (state.status === 'ready') {
      return { profile: state.profile, profilesFolder: this.#directory };
    }
    if (state.status === 'failed') {
      this.#states.delete(account);
      throw state.error;
    }
    throw new Error(loginRequired(account, state.code));
  }

  invalidate(account) {
    this.#states.delete(account);
  }

  #start(account) {
    let announce;
    const state = {
      status: 'starting',
      code: null,
      profile: null,
      error: null,
      firstUpdate: new Promise((resolveFirstUpdate) => {
        announce = resolveFirstUpdate;
      }),
    };

    const authenticate = async () => {
      await mkdir(this.#directory, { recursive: true, mode: 0o700 });
      const flow = this.#createFlow(account, this.#directory, (code) => {
        state.status = 'pending';
        state.code = code;
        announce();
      });
      const result = await flow.getMinecraftJavaToken({
        fetchProfile: true,
        fetchCertificates: false,
      });
      if (!result.profile?.name || !result.profile?.id) {
        throw new Error(`Microsoft account '${account}' has no Minecraft Java profile`);
      }
      state.status = 'ready';
      state.profile = result.profile;
      announce();
    };

    authenticate().catch((error) => {
      state.status = 'failed';
      state.error = error instanceof Error ? error : new Error(String(error));
      announce();
    });
    return state;
  }
}

function authenticationMode(auth) {
  const mode = String(auth || 'offline').toLowerCase();
  if (mode !== 'offline' && mode !== 'microsoft') {
    throw new Error(`unknown bot auth '${auth}'; use offline or microsoft`);
  }
  return mode;
}

function loginRequired(account, code) {
  const uri = code?.verification_uri || 'https://www.microsoft.com/link';
  const userCode = code?.user_code || '(request a new code)';
  return `Microsoft login required for account '${account}'. Open ${uri} and enter code `
    + `${userCode}, complete sign-in, then call bot_spawn again with the same name and account.`;
}

function canonicalUuid(value) {
  const compact = String(value).replaceAll('-', '');
  if (!/^[0-9a-fA-F]{32}$/.test(compact)) {
    throw new Error(`Microsoft returned an invalid Minecraft profile UUID: ${value}`);
  }
  return [
    compact.slice(0, 8),
    compact.slice(8, 12),
    compact.slice(12, 16),
    compact.slice(16, 20),
    compact.slice(20),
  ].join('-').toLowerCase();
}

function position(bot) {
  const at = bot.entity?.position;
  return at ? { x: at.x, y: at.y, z: at.z } : { x: 0, y: 0, z: 0 };
}

/**
 * Waits until the bot has stopped falling.
 *
 * A bot that answers `spawn` mid-fall reports a position it is about to leave, and every
 * coordinate assertion downstream inherits the error. The Java runner settles the same way, with
 * the same numbers.
 */
async function settle(bot, name) {
  const deadline = Date.now() + SETTLE_TIMEOUT_MILLIS;
  let lastY = Number.NaN;
  let settledChecks = 0;

  while (Date.now() < deadline) {
    const at = bot.entity?.position;
    if (at) {
      if (Math.abs(at.y - lastY) < 1.0e-6) {
        if (++settledChecks >= SETTLED_CHECKS) {
          return;
        }
      } else {
        settledChecks = 0;
        lastY = at.y;
      }
    }
    await delay(SETTLE_POLL_MILLIS);
  }

  const at = bot.entity?.position;
  throw new Error(
    `Bot ${name} never settled within ${SETTLE_TIMEOUT_MILLIS}ms; last position `
      + (at ? `${at.x}, ${at.y}, ${at.z}` : 'unknown'),
  );
}

/**
 * Waits until the bot's client knows the world it is standing in.
 *
 * `spawn` fires on the position packet, which can arrive before the chunk does. A bot that acts in
 * that gap sends block actions against blocks it has never been told about, and the server answers
 * with nothing at all — indistinguishable, from the caller's side, from a plugin cancelling the
 * action silently. A dogfooding round spent most of itself on that ambiguity
 * (dogfood/JOURNAL.md, 2026-08-23).
 *
 * This closes the client half. The server half — a plugin, or Paper itself, dropping interactions
 * from a player who has only just joined — cannot be waited out from here, and is why
 * `breakBlock` reports whether the server acknowledged the dig.
 */
async function worldKnown(bot, name) {
  const deadline = Date.now() + WORLD_TIMEOUT_MILLIS;

  while (Date.now() < deadline) {
    const at = bot.entity?.position;
    if (at && bot.blockAt(at.offset(0, -1, 0))) {
      return;
    }
    await delay(SETTLE_POLL_MILLIS);
  }

  throw new Error(
    `Bot ${name} joined but its client was never sent the world around it within `
      + `${WORLD_TIMEOUT_MILLIS}ms`,
  );
}

/** Resolves when the bot is in the world; rejects on a kick, an error, or the timeout. */
function joined(bot, name) {
  return new Promise((resolve, reject) => {
    const finish = (settleFn, value) => {
      clearTimeout(timer);
      bot.removeListener('spawn', onSpawn);
      bot.removeListener('kicked', onKicked);
      bot.removeListener('error', onError);
      settleFn(value);
    };

    const onSpawn = () => finish(resolve);
    const onKicked = (reason) => finish(reject, new Error(`Bot ${name} was kicked: ${describe(reason)}`));
    const onError = (error) => finish(reject, error);

    const timer = setTimeout(
      () => finish(reject, new Error(`Bot ${name} did not join within ${LOGIN_TIMEOUT_MILLIS}ms`)),
      LOGIN_TIMEOUT_MILLIS,
    );

    bot.once('spawn', onSpawn);
    bot.once('kicked', onKicked);
    bot.once('error', onError);
  });
}

/** A kick reason is chat JSON as often as a string, and both have to end up readable. */
function describe(reason) {
  if (typeof reason === 'string') {
    return reason;
  }
  try {
    return JSON.stringify(reason);
  } catch {
    return String(reason);
  }
}

/** Closing an already-closed socket must not be the reason a shutdown fails. */
function quietly(action) {
  try {
    action();
  } catch {
    // Intentionally ignored.
  }
}

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

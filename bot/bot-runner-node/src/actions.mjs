import { javaDouble } from './protocol.mjs';
import { plainText } from './text.mjs';
import { Vec3 } from 'vec3';

/**
 * What a bot can do once it is in the world.
 *
 * A port of `BotActions.java`, and mostly at packet level rather than through mineflayer's
 * convenience methods. That is deliberate: `bot.dig` and `bot.activateEntity` look at the target
 * first and wait for the result, which is more correct than what the Java runner does and would
 * therefore be a behaviour change. Parity comes first; the better behaviour is a later decision,
 * taken on purpose and written down.
 */

/** Direction, in Minecraft's own order. */
const FACES = { down: 0, up: 1, north: 2, south: 3, west: 4, east: 5 };

/** PlayerAction. */
const START_DIGGING = 0;
const FINISH_DIGGING = 2;

/** Hand. */
const MAIN_HAND = 0;

/** InteractAction, as the `mouse` field spells it. */
const INTERACT = 0;
const INTERACT_AT = 2;

/** No entity matched. */
const NO_ENTITY = -1;

/** Block interactions sent by each bot, which the server uses to acknowledge them in order. */
const sequences = new WeakMap();

function nextSequence(bot) {
  const next = (sequences.get(bot) ?? 0) + 1;
  sequences.set(bot, next);
  return next;
}

/** The same guard `BotActions.require()` applies, and the same words. */
function requireInWorld(bot, name) {
  if (!bot.entity || !bot._client?.socket?.writable) {
    throw new Error(`Bot ${name} is not in the world`);
  }
}

/** How long the server gets to confirm a dig before it counts as never having arrived. */
const DIG_ACK_TIMEOUT_MILLIS = 1500;

/** How long the resulting block change gets to come back after the confirmation. */
const DIG_SETTLE_MILLIS = 300;

/** How long a client gets to receive the block before an interaction is sent. */
const BLOCK_KNOWN_TIMEOUT_MILLIS = 15_000;

/** One client tick for a block update already queued by a preceding server command. */
const BLOCK_UPDATE_SETTLE_MILLIS = 50;

/**
 * Breaks a block, and says what became of the attempt.
 *
 * <p>This used to write two packets and answer `sent`, which made a dig the server cancelled and
 * a dig the server never received the same observation — and a plugin that cancels
 * BlockBreakEvent silently is exactly the thing someone reaches for this tool to find. A whole
 * dogfooding round went on telling those apart by hand (dogfood/JOURNAL.md, 2026-08-23).
 *
 * <p>The server settles it for us. Every block action carries a sequence number, and the server
 * answers with the highest one it has resolved — that is what the field is for, so the client
 * knows when to stop predicting and accept what it is told. An acknowledgement means the dig
 * reached the world and was dealt with; the block then says whether it was allowed.
 */
export async function breakBlock(bot, name, x, y, z) {
  requireInWorld(bot, name);
  const location = { x, y, z };
  const at = new Vec3(x, y, z);

  const before = bot.blockAt(at)?.name ?? null;
  if (before === null) {
    // Not a refusal and not a failure to send: this client has never been told what is there,
    // which usually means it has only just joined and its chunks have not arrived.
    return `the bot's client has no block at ${x}, ${y}, ${z} yet, so nothing was dug`;
  }

  const started = nextSequence(bot);
  const finished = nextSequence(bot);

  // Registered before the packets go out. The acknowledgement can come back inside the same tick.
  const confirmed = acknowledgement(bot, finished);

  bot._client.write('block_dig', {
    status: START_DIGGING,
    location,
    face: FACES.up,
    sequence: started,
  });
  bot._client.write('block_dig', {
    status: FINISH_DIGGING,
    location,
    face: FACES.up,
    sequence: finished,
  });

  if (!await confirmed) {
    return `the server did not acknowledge the dig within ${DIG_ACK_TIMEOUT_MILLIS}ms, `
      + 'so it never reached the world';
  }

  await delay(DIG_SETTLE_MILLIS);
  const after = bot.blockAt(at)?.name ?? null;

  return after === before
    ? `the server acknowledged the dig and left ${before} in place, so something refused it`
    : `broke ${before}`;
}

/** Resolves true once the server says it has resolved everything up to `sequence`. */
function acknowledgement(bot, sequence) {
  return new Promise((resolve) => {
    const finish = (value) => {
      clearTimeout(timer);
      bot._client.removeListener('acknowledge_player_digging', onAcknowledged);
      resolve(value);
    };

    const onAcknowledged = (packet) => {
      if (packet?.sequenceId >= sequence) {
        finish(true);
      }
    };

    const timer = setTimeout(() => finish(false), DIG_ACK_TIMEOUT_MILLIS);
    bot._client.on('acknowledge_player_digging', onAcknowledged);
  });
}

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

/** Runs a command as the bot. The leading slash is not part of the packet. */
export function command(bot, name, line) {
  requireInWorld(bot, name);
  bot._client.write('chat_command', {
    command: line.startsWith('/') ? line.slice(1) : line,
    timestamp: BigInt(Date.now()),
    salt: 0n,
    argumentSignatures: [],
    messageCount: 0,
    acknowledged: Buffer.alloc(3),
    checksum: 0,
  });
}

/**
 * Says something in chat.
 *
 * Through `/say`, exactly as the Java runner does it. A bot cannot send a signed chat message
 * without a Mojang key, and an unsigned one is refused from 1.19 on, so the command is the only
 * route that reaches the same listeners.
 */
export function chat(bot, name, message) {
  command(bot, name, `say ${message}`);
}

/** Right-clicks a block — which is how a container or a plugin menu gets opened. */
export async function useBlock(bot, name, x, y, z, face) {
  requireInWorld(bot, name);

  const direction = !face || !face.trim() ? FACES.up : FACES[face.trim().toLowerCase()];
  if (direction === undefined) {
    throw new Error(
      `Unknown face '${face}'. Use ${Object.keys(FACES).join(', ')}.`,
    );
  }

  const location = new Vec3(x, y, z);
  await blockKnown(bot, name, location);

  bot._client.write('block_place', {
    hand: MAIN_HAND,
    location: { x, y, z },
    direction,
    cursorX: 0.5,
    cursorY: 0.5,
    cursorZ: 0.5,
    insideBlock: false,
    worldBorderHit: false,
    sequence: nextSequence(bot),
  });
}

/**
 * Waits until the client has a block record for an interaction target.
 *
 * <p>A console command can change a block before its block update reaches the bot. Sending the
 * interaction in that gap is legal at the protocol level, but the client has not loaded the target
 * yet and Paper may drop the window-opening packet. This was the intermittent compatibility
 * failure on the otherwise deterministic chest check.
 */
async function blockKnown(bot, name, location) {
  const deadline = Date.now() + BLOCK_KNOWN_TIMEOUT_MILLIS;
  while (Date.now() < deadline) {
    if (bot.blockAt(location)) {
      // A command_exec that changed this block has already completed on the server, but the
      // corresponding packet may still be queued on the bot socket. Give that one client tick
      // a chance to settle before sending the interaction packet.
      await delay(BLOCK_UPDATE_SETTLE_MILLIS);
      return;
    }
    await delay(50);
  }

  throw new Error(
    `Bot ${name} was not told about the block at ${location.x} ${location.y} ${location.z} within `
      + `${BLOCK_KNOWN_TIMEOUT_MILLIS}ms`,
  );
}

/** Right-clicks the nearest entity to a point, and returns which one it was. */
export function useEntity(bot, name, x, y, z, radius, type) {
  requireInWorld(bot, name);

  const entityId = entityNear(bot, x, y, z, radius, type);
  if (entityId === NO_ENTITY) {
    const nearby = describeEntitiesNear(bot, x, y, z, radius);
    throw new Error(
      `no ${!type || !type.trim() ? 'entity' : type} within ${javaDouble(radius)} blocks of `
        + `${javaDouble(x)} ${javaDouble(y)} ${javaDouble(z)}`
        + (nearby === ''
          ? '. The bot has been told about no entities near there at all — check the coordinates,'
            + ' and that the bot is close enough to have them in view.'
          : `. Nearby: ${nearby}`),
    );
  }

  if (interactCarriesMouseButton(bot)) {
    // Both packets, in this order, because that is what a real client sends and what the Java
    // runner reproduces: a plugin listening only for the second sees nothing without the first.
    bot._client.write('use_entity', {
      target: entityId,
      mouse: INTERACT_AT,
      x: 0.0,
      y: 1.0,
      z: 0.0,
      hand: MAIN_HAND,
      sneaking: false,
    });
    bot._client.write('use_entity', {
      target: entityId,
      mouse: INTERACT,
      hand: MAIN_HAND,
      sneaking: false,
    });
  } else {
    // 26.1: one packet with a hand and a hit position; attacks have their own packet.
    bot._client.write('use_entity', {
      target: entityId,
      hand: MAIN_HAND,
      location: { x: 0.0, y: 1.0, z: 0.0 },
      sneaking: false,
    });
  }
  return entityId;
}

/** Through 1.21.11 `use_entity` has a `mouse` field; from 26.1 it has a `location` instead. */
function interactCarriesMouseButton(bot) {
  const fields = bot.registry?.protocol?.play?.toServer?.types?.packet_use_entity?.[1];
  if (!Array.isArray(fields)) {
    throw new Error('The protocol definition for use_entity is missing, so the bot cannot interact with entities.');
  }
  return fields.some((field) => field?.name === 'mouse');
}

/** Left-clicks the nearest tracked entity. */
export function attackEntity(bot, name, x, y, z, radius, type) {
  requireInWorld(bot, name);
  const entityId = entityNear(bot, x, y, z, radius, type);
  if (entityId === NO_ENTITY) {
    throw new Error(`no ${!type || !type.trim() ? 'entity' : type} within ${javaDouble(radius)} blocks of `
      + `${javaDouble(x)} ${javaDouble(y)} ${javaDouble(z)}`);
  }
  const entity = bot.entities[entityId];
  bot.attack(entity);
  return entityId;
}

/** Selects a hotbar slot. */
export function holdItem(bot, name, slot) {
  requireInWorld(bot, name);
  if (!Number.isInteger(slot) || slot < 0 || slot > 8) {
    throw new Error(`Hotbar slot must be between 0 and 8, got '${slot}'.`);
  }
  bot.setQuickBarSlot(slot);
}

/** Drops the held item, either one item or the whole held stack. */
export async function dropItem(bot, name, count) {
  requireInWorld(bot, name);
  if (!bot.heldItem) {
    throw new Error(`Bot ${name} is holding no item to drop.`);
  }
  const amount = count == null || String(count).trim() === '' ? bot.heldItem.count : Number(count);
  if (!Number.isInteger(amount) || amount <= 0 || amount > bot.heldItem.count) {
    throw new Error(`Drop count must be between 1 and ${bot.heldItem.count}, got '${count}'.`);
  }
  if (amount === bot.heldItem.count) {
    await bot.tossStack(bot.heldItem);
  } else {
    await bot.toss(bot.heldItem.type, bot.heldItem.metadata, amount);
  }
}

/** Places the held item against a block face. */
export async function placeBlock(bot, name, x, y, z, face) {
  requireInWorld(bot, name);
  if (!bot.heldItem) {
    throw new Error(`Bot ${name} is holding no item to place.`);
  }
  const reference = bot.blockAt(new Vec3(x, y, z));
  if (!reference) {
    throw new Error(`Bot ${name} has not loaded block ${x}, ${y}, ${z}.`);
  }
  const direction = faceVector(face);
  await bot.placeBlock(reference, direction);
}

/** Performs one client jump, using a physics tick as the release barrier. */
export async function jump(bot, name) {
  requireInWorld(bot, name);
  bot.setControlState('jump', true);
  await new Promise((resolve) => bot.once('physicsTick', resolve));
  bot.setControlState('jump', false);
}

/** Sets sneaking on or off. */
export function sneak(bot, name, state) {
  requireInWorld(bot, name);
  bot.setControlState('sneak', booleanState(state));
}

/** Sets sprinting on or off. */
export function sprint(bot, name, state) {
  requireInWorld(bot, name);
  bot.setControlState('sprint', booleanState(state));
}

/** Turns the client toward a world point. */
export async function lookAt(bot, name, x, y, z) {
  requireInWorld(bot, name);
  await bot.lookAt(new Vec3(x, y, z), true);
}

/** Clicks a slot in the menu the server has open for this bot. */
export async function clickSlot(bot, name, slot, click) {
  requireInWorld(bot, name);

  if (!bot.currentWindow) {
    throw new Error(
      `Bot ${name} has no menu open, so slot ${slot} cannot be clicked. Wait for inventory_open `
        + 'first — a menu does not open synchronously with the command that causes it.',
    );
  }

  let mouseButton;
  let mode;
  switch ((click ?? 'left').toLowerCase()) {
    case 'left':
      [mouseButton, mode] = [0, 0];
      break;
    case 'right':
      [mouseButton, mode] = [1, 0];
      break;
    case 'shift_left':
      [mouseButton, mode] = [0, 1];
      break;
    case 'shift_right':
      [mouseButton, mode] = [1, 1];
      break;
    default:
      throw new Error(
        `Unknown click '${click}'. Use left, right, shift_left or shift_right.`,
      );
  }

  // Through mineflayer rather than by hand: the click packet carries a state id and hashed slot
  // contents whose shape moves between versions, which is the pain this runner exists to avoid.
  await bot.clickWindow(slot, mouseButton, mode);
}

/** Closes the open menu. Doing it when nothing is open is not an error. */
export function closeMenu(bot, name) {
  requireInWorld(bot, name);
  if (bot.currentWindow) {
    bot.closeWindow(bot.currentWindow);
  }
}

/** The menu the client was told about, or null when none is open. */
export function menu(bot, name) {
  requireInWorld(bot, name);
  const window = bot.currentWindow;
  return window ? { containerId: window.id, title: plainText(window.title) } : null;
}

/** The nearest tracked entity to a point, or {@link NO_ENTITY}. */
function entityNear(bot, x, y, z, radius, type) {
  let best = null;
  let bestDistance = Number.MAX_VALUE;

  for (const entity of Object.values(bot.entities)) {
    if (entity === bot.entity) {
      continue;
    }
    if (type && type.trim() && !matchesType(entity, type.trim())) {
      continue;
    }
    const distance = distanceTo(entity, x, y, z);
    if (distance <= radius && distance < bestDistance) {
      best = entity;
      bestDistance = distance;
    }
  }
  return best === null ? NO_ENTITY : best.id;
}

/** What is actually near a point, for when nothing matched. */
function describeEntitiesNear(bot, x, y, z, radius) {
  return Object.values(bot.entities)
    .filter((entity) => entity !== bot.entity && distanceTo(entity, x, y, z) <= Math.max(radius * 4, 16))
    .sort((a, b) => distanceTo(a, x, y, z) - distanceTo(b, x, y, z))
    .slice(0, 8)
    .map((entity) => {
      const at = entity.position;
      return `${typeOf(entity)} at ${fixed(at.x)} ${fixed(at.y)} ${fixed(at.z)} `
        + `(${fixed(distanceTo(entity, x, y, z))} away)`;
    })
    .join('; ');
}

function distanceTo(entity, x, y, z) {
  const at = entity.position;
  if (!at) {
    return Number.MAX_VALUE;
  }
  return Math.sqrt((at.x - x) ** 2 + (at.y - y) ** 2 + (at.z - z) ** 2);
}

/** The Java runner reports the protocol's entity type name; mineflayer spells it in lower case. */
function typeOf(entity) {
  return entity.name ?? entity.displayName ?? entity.entityType ?? '';
}

function matchesType(entity, type) {
  const wanted = type.toLowerCase();
  return [entity.name, entity.displayName, entity.entityType]
    .some((candidate) => typeof candidate === 'string' && candidate.toLowerCase() === wanted);
}

function faceVector(face) {
  switch ((face ?? 'up').trim().toLowerCase()) {
    case 'down': return new Vec3(0, -1, 0);
    case 'up': return new Vec3(0, 1, 0);
    case 'north': return new Vec3(0, 0, -1);
    case 'south': return new Vec3(0, 0, 1);
    case 'west': return new Vec3(-1, 0, 0);
    case 'east': return new Vec3(1, 0, 0);
    default: throw new Error(`Unknown face '${face}'. Use down, up, north, south, west or east.`);
  }
}

function booleanState(state) {
  const value = String(state ?? '').trim().toLowerCase();
  if (value === 'on' || value === 'true' || value === 'start' || value === '1') return true;
  if (value === 'off' || value === 'false' || value === 'stop' || value === '0') return false;
  throw new Error(`State must be on or off, got '${state}'.`);
}

/** Java formats these with %.1f, which rounds half away from zero rather than to even. */
function fixed(value) {
  return value.toFixed(1);
}

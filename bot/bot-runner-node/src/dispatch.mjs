import * as actions from './actions.mjs';
import * as clientview from './clientview.mjs';
import * as protocol from './protocol.mjs';

/**
 * The line protocol, in the one place it is spoken.
 *
 * A port of `RunnerDispatch.java`. Commands arrive one per line and are answered one per line, in
 * order — the Java side reads a single reply per command and would desynchronise permanently if
 * two overlapped, so this awaits each one rather than dispatching concurrently.
 */
export class Dispatch {
  #bots;

  constructor(bots) {
    this.#bots = bots;
  }

  /** Handles one command line. Returns the reply, or null for `shutdown`. */
  async handle(line) {
    const command = protocol.decode(line);
    if (command.length === 0 || command[0] === '') {
      return '';
    }
    if (command[0] === protocol.SHUTDOWN) {
      return null;
    }

    const verb = command[0];
    try {
      return await this.#run(verb, command);
    } catch (error) {
      return protocol.encode(protocol.ERROR, verb, protocol.sanitize(error?.message ?? error));
    }
  }

  async #run(verb, command) {
    switch (verb) {
      case protocol.SPAWN: {
        const clientIp = command.length > 2 ? command[2] : '';
        const auth = command.length > 3 ? command[3] : 'offline';
        const account = command.length > 4 ? command[4] : '';
        return spawnLine(verb, await this.#bots.spawn(command[1], clientIp, auth, account));
      }

      case protocol.DESPAWN: {
        this.#bots.despawn(command[1]);
        return ok(verb);
      }

      case protocol.POSITION:
        return positionLine(verb, this.#bots.position(command[1]));

      case protocol.MOVE: {
        const mode = command.length > 5 ? command[5] : 'path';
        const timeout = command.length > 6 ? command[6] : undefined;
        await this.#bots.move(
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
          mode,
          timeout,
        );
        return ok(verb);
      }

      case protocol.BREAK: {
        // Carries what became of the dig, which is the whole point of waiting for it.
        const outcome = await actions.breakBlock(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
        );
        return protocol.encode(protocol.OK, verb, outcome);
      }

      case protocol.COMMAND: {
        actions.command(this.#bots.require(command[1]), command[1], command[2]);
        return ok(verb);
      }

      case protocol.CHAT: {
        actions.chat(this.#bots.require(command[1]), command[1], command[2]);
        return ok(verb);
      }

      case protocol.USE: {
        await actions.useBlock(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
          command.length > 5 ? command[5] : '',
        );
        return ok(verb);
      }

      case protocol.USE_ENTITY: {
        const radius = command.length > 5 && command[5].trim() !== '' ? Number(command[5]) : 2.0;
        const type = command.length > 6 ? command[6] : null;
        const entityId = actions.useEntity(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
          radius,
          type,
        );
        return protocol.encode(protocol.OK, verb, String(entityId));
      }

      case protocol.ATTACK_ENTITY: {
        const radius = command.length > 5 && command[5].trim() !== '' ? Number(command[5]) : 2.0;
        const type = command.length > 6 ? command[6] : null;
        const entityId = actions.attackEntity(
          this.#bots.require(command[1]), command[1], Number(command[2]), Number(command[3]),
          Number(command[4]), radius, type);
        return protocol.encode(protocol.OK, verb, String(entityId));
      }

      case protocol.HOLD_ITEM:
        actions.holdItem(this.#bots.require(command[1]), command[1], Number(command[2]));
        return ok(verb);

      case protocol.DROP_ITEM:
        await actions.dropItem(this.#bots.require(command[1]), command[1],
          command.length > 2 ? command[2] : undefined);
        return ok(verb);

      case protocol.PLACE_BLOCK:
        await actions.placeBlock(this.#bots.require(command[1]), command[1], Number(command[2]),
          Number(command[3]), Number(command[4]), command.length > 5 ? command[5] : 'up');
        return ok(verb);

      case protocol.JUMP:
        await actions.jump(this.#bots.require(command[1]), command[1]);
        return ok(verb);

      case protocol.SNEAK:
        actions.sneak(this.#bots.require(command[1]), command[1], command[2]);
        return ok(verb);

      case protocol.SPRINT:
        actions.sprint(this.#bots.require(command[1]), command[1], command[2]);
        return ok(verb);

      case protocol.LOOK_AT:
        await actions.lookAt(this.#bots.require(command[1]), command[1], Number(command[2]),
          Number(command[3]), Number(command[4]));
        return ok(verb);

      case protocol.ASSERT_REACHABLE: {
        const name = command.length > 1 ? command[1] : '';
        const offset = 2;
        const result = await this.#bots.assertReachable(name, Number(command[offset]),
          Number(command[offset + 1]), Number(command[offset + 2]),
          command.length > offset + 3 ? Number(command[offset + 3]) : undefined);
        return protocol.encode(protocol.OK, verb, String(result.reachable), result.status);
      }

      case protocol.VIEW: {
        const stop = command.length > 4 && command[4] === 'true';
        if (stop) {
          this.#bots.stopView(command[1]);
          return protocol.encode(protocol.OK, verb, 'stopped');
        }
        const url = await this.#bots.view(
          command[1], command.length > 2 ? command[2] : 'world',
          command.length > 3 ? command[3] : 'third_person');
        return protocol.encode(protocol.OK, verb, url);
      }

      case protocol.CLICK: {
        await actions.clickSlot(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          command.length > 3 ? command[3] : 'left',
        );
        return ok(verb);
      }

      case protocol.CLOSE_MENU: {
        actions.closeMenu(this.#bots.require(command[1]), command[1]);
        return ok(verb);
      }

      case protocol.MENU: {
        const open = actions.menu(this.#bots.require(command[1]), command[1]);
        return protocol.encode(
          protocol.OK,
          verb,
          String(open === null ? -1 : open.containerId),
          open === null ? '' : protocol.sanitize(open.title),
        );
      }

      case protocol.INSPECT: {
        const view = clientview.inspect(this.#bots.require(command[1]), command[1]);
        const open = view.menu;
        const board = view.scoreboard;
        return protocol.encode(
          protocol.OK,
          verb,
          String(open === null ? -1 : open.containerId),
          open === null ? '' : protocol.sanitize(open.title),
          items(view.items),
          messageList(view.messages),
          bossBarList(view.bossBars),
          board === null ? '' : protocol.sanitize(board.title),
          board === null ? '' : recordList(board.lines),
          view.health == null ? '' : protocol.javaFloat(view.health),
          view.food == null ? '' : String(view.food),
          view.experienceLevel == null ? '' : String(view.experienceLevel),
          view.totalExperience == null ? '' : String(view.totalExperience),
          view.experienceProgress == null ? '' : protocol.javaFloat(view.experienceProgress),
          recordList(view.effects),
          String(view.nextMessageSequence),
          protocol.sanitize(view.messageStreamId),
        );
      }

      // Not protocol. A development aid for seeing what prismarine actually hands over, because
      // the shapes of item components and window titles are not documented anywhere useful.
      case '__dump': {
        const bot = this.#bots.require(command[1]);
        const window = bot.currentWindow;
        const item = window?.slots?.find((slot) => slot);
        return protocol.encode(protocol.OK, verb, JSON.stringify({
          windowTitle: window?.title,
          windowTitleType: typeof window?.title,
          scoreboardTitle: bot.scoreboard?.sidebar?.title,
          scoreboardTitleType: typeof bot.scoreboard?.sidebar?.title,
          scoreboardItems: bot.scoreboard?.sidebar?.items?.map((i) => ({ name: i.name, value: i.value })),
          itemKeys: item ? Object.keys(item) : null,
          customName: item?.customName,
          customLore: item?.customLore,
          components: item?.components,
        }, (key, value) => (typeof value === 'bigint' ? String(value) : value)));
      }

      default:
        return protocol.encode(protocol.ERROR, verb, `unknown command '${verb}'`);
    }
  }
}

function ok(verb) {
  return protocol.encode(protocol.OK, verb);
}

/** `slot ␟ itemId ␟ amount ␟ name ␟ customModelData ␟ lore`, joined by ␞. */
function items(list) {
  return list
    .map((item) => [
      item.slot,
      protocol.sanitize(item.itemId),
      item.amount,
      protocol.sanitize(item.name),
      protocol.sanitize(item.customModelData),
      protocol.sanitize(item.lore),
    ].join(protocol.UNIT_SEPARATOR))
    .join(protocol.RECORD_SEPARATOR);
}

/** `sequence ␟ timestamp ␟ text`, oldest first, joined by ␞. */
function messageList(messages) {
  return messages
    .map((message) => [
      message.sequence,
      message.timestamp,
      protocol.sanitize(message.text),
    ].join(protocol.UNIT_SEPARATOR))
    .join(protocol.RECORD_SEPARATOR);
}

/** `title ␟ progress ␟ colour`, one record each. */
function bossBarList(bars) {
  return bars
    .map((bar) => [
      protocol.sanitize(bar.title),
      protocol.javaFloat(bar.progress),
      protocol.sanitize(bar.color),
    ].join(protocol.UNIT_SEPARATOR))
    .join(protocol.RECORD_SEPARATOR);
}

function recordList(values) {
  return values.map(protocol.sanitize).join(protocol.RECORD_SEPARATOR);
}

function positionLine(verb, at) {
  return protocol.encode(
    protocol.OK,
    verb,
    protocol.javaDouble(at.x),
    protocol.javaDouble(at.y),
    protocol.javaDouble(at.z),
  );
}

function spawnLine(verb, at) {
  return protocol.encode(
    protocol.OK,
    verb,
    protocol.javaDouble(at.x),
    protocol.javaDouble(at.y),
    protocol.javaDouble(at.z),
    protocol.sanitize(at.playerName),
    protocol.sanitize(at.uuid),
  );
}

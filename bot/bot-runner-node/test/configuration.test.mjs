/**
 * The configuration phase, against a server that behaves like a Paper server with a pack plugin.
 *
 * The unit tests in join.test.mjs check what gets written. These check that writing it is what
 * lets a real mineflayer client out of the configuration phase at all — which is the whole bug,
 * and the part a fake client cannot show.
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import mc from 'minecraft-protocol';
import mineflayer from 'mineflayer';

import { answerResourcePacks } from '../src/join.mjs';

const VERSION = '1.21.8';
const PACK = '11112222-3333-4444-5555-666677778888';

/** Terminal results — the ones vanilla treats as "the client is done with this pack". */
const TERMINAL = new Set([0, 1, 2]);

/**
 * A server that holds the configuration phase open until its pack is answered.
 *
 * This is what a plugin sending a pack from `AsyncPlayerConnectionConfigureEvent` looks like from
 * the wire: `finish_configuration` is not sent, and nothing else happens either, until the client
 * reports what became of the pack.
 */
function packPushingServer() {
  const server = mc.createServer({ 'online-mode': false, version: VERSION, port: 0 });
  server.responses = [];

  server.on('login', (client) => {
    const write = client.write.bind(client);
    let held = null;

    client.write = (name, params) => {
      if (name === 'finish_configuration' && !held) {
        held = () => write('finish_configuration', params);
        write('add_resource_pack', {
          uuid: PACK,
          url: 'http://192.0.2.1:8163/pack.zip',
          hash: '',
          forced: true,
          hasPromptMessage: false,
        });
        return undefined;
      }
      return write(name, params);
    };

    client.on('resource_pack_receive', (response) => {
      server.responses.push({ ...response, state: client.state });
      if (held && TERMINAL.has(response.result)) {
        const release = held;
        held = null;
        release();
      }
    });
  });

  return server;
}

async function listening(server) {
  await new Promise((resolve) => server.once('listening', resolve));
  return server.socketServer.address().port;
}

function connect(port) {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port, username: 'PackBot', auth: 'offline', version: VERSION,
  });
  // This server never sends a login packet, so the bot is expected to end unhappily once the
  // assertions are done. Its failures are not the subject.
  bot.on('error', () => {});
  bot.on('kicked', () => {});
  return bot;
}

/** Polls rather than waiting a fixed time, so the test is as fast as the connection is. */
async function until(condition, timeoutMillis) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    if (condition()) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  return false;
}

test('a pack pushed during configuration no longer strands the bot there', async () => {
  const server = packPushingServer();
  const port = await listening(server);
  const bot = connect(port);
  answerResourcePacks(bot);

  try {
    assert.ok(
      await until(() => bot._client.state === 'play', 20_000),
      `never left configuration; server saw ${JSON.stringify(server.responses)}`,
    );
    assert.deepEqual(server.responses, [
      { uuid: PACK, result: 3, state: 'configuration' },
      { uuid: PACK, result: 0, state: 'configuration' },
    ]);
  } finally {
    bot.end();
    server.close();
  }
});

test('an unanswered pack is what the stall looked like', async () => {
  const server = packPushingServer();
  const port = await listening(server);
  const bot = connect(port);

  try {
    // Long enough for the pack to have been pushed and gone unanswered; mineflayer on its own
    // only emits `resourcePack` and waits for a bot author who is not there.
    // The answered case above clears configuration in well under half a second.
    await until(() => server.responses.length > 0, 2_500);

    assert.deepEqual(server.responses, []);
    assert.equal(bot._client.state, 'configuration');
  } finally {
    bot.end();
    server.close();
  }
});

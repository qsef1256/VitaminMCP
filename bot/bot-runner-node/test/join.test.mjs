import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import mc from 'minecraft-protocol';

import { answerResourcePacks, describeProgress, traceProgress } from '../src/join.mjs';

/** A stand-in for the minecraft-protocol client, which is all these two functions touch. */
function fakeBot(state = 'configuration') {
  const client = new EventEmitter();
  client.state = state;
  client.written = [];
  client.write = (name, params) => client.written.push({ name, params });
  return { _client: client };
}

test('a pack pushed during configuration is answered rather than waited out', () => {
  const bot = fakeBot();
  answerResourcePacks(bot);

  bot._client.emit('add_resource_pack', {
    uuid: '3d3c2a1b-0000-4000-8000-abcdefabcdef',
    url: 'http://10.0.0.4:8163/pack.zip',
    forced: true,
  });

  assert.deepEqual(bot._client.written, [
    { name: 'resource_pack_receive', params: { result: 3, uuid: '3d3c2a1b-0000-4000-8000-abcdefabcdef' } },
    { name: 'resource_pack_receive', params: { result: 0, uuid: '3d3c2a1b-0000-4000-8000-abcdefabcdef' } },
  ]);
});

test('the uuid is echoed off the request, not converted', () => {
  const bot = fakeBot();
  answerResourcePacks(bot);

  bot._client.emit('add_resource_pack', { uuid: '739c91e0-896a-3fbf-aeb9-a42b53e447ed' });

  // The nil uuid is what mineflayer's own acceptResourcePack sends here, and it names a pack no
  // server has ever heard of — the connection goes on waiting.
  for (const written of bot._client.written) {
    assert.equal(written.params.uuid, '739c91e0-896a-3fbf-aeb9-a42b53e447ed');
  }
});

test('a pre-1.20.3 request is answered with the hash it identified itself by', () => {
  const bot = fakeBot();
  answerResourcePacks(bot);

  bot._client.emit('resource_pack_send', { url: 'http://host/pack.zip', hash: 'abc123' });

  assert.deepEqual(bot._client.written.map((written) => written.params), [
    { result: 3, hash: 'abc123' },
    { result: 0, hash: 'abc123' },
  ]);
});

test('several packs are each answered', () => {
  const bot = fakeBot();
  answerResourcePacks(bot);

  bot._client.emit('add_resource_pack', { uuid: 'one' });
  bot._client.emit('add_resource_pack', { uuid: 'two' });

  assert.deepEqual(bot._client.written.map((written) => written.params.uuid), ['one', 'one', 'two', 'two']);
});

test('a socket that fails mid-answer does not take the runner down', () => {
  const bot = fakeBot();
  bot._client.write = () => {
    throw new Error('socket closed');
  };
  answerResourcePacks(bot);

  assert.doesNotThrow(() => bot._client.emit('add_resource_pack', { uuid: 'one' }));
});

test('a stalled join reports the state and packet it stopped at', () => {
  const bot = fakeBot();
  const progress = traceProgress(bot);

  bot._client.emit('packet', {}, { name: 'registry_data', state: 'configuration' });
  bot._client.emit('packet', {}, { name: 'add_resource_pack', state: 'configuration' });

  assert.equal(
    describeProgress(bot, progress),
    'last state: configuration, last packet received: add_resource_pack',
  );
});

test('a connection that never got a packet says so instead of naming one', () => {
  const bot = fakeBot('login');

  assert.equal(
    describeProgress(bot, traceProgress(bot)),
    'last state: login, last packet received: none',
  );
});

test('the answer survives the real 1.21.8 serializer with its uuid intact', () => {
  const bot = fakeBot();
  answerResourcePacks(bot);
  bot._client.emit('add_resource_pack', { uuid: '739c91e0-896a-3fbf-aeb9-a42b53e447ed' });

  // The point of the round trip: a shape the unit tests above accept can still be unwritable, and
  // a uuid the server cannot match is answered by going on waiting rather than by an error.
  const serializer = mc.createSerializer({ state: 'configuration', isServer: false, version: '1.21.8' });
  const deserializer = mc.createDeserializer({ state: 'configuration', isServer: true, version: '1.21.8' });

  for (const { name, params } of bot._client.written) {
    const wire = serializer.createPacketBuffer({ name, params });
    assert.deepEqual(deserializer.parsePacketBuffer(wire).data.params, params);
  }
});

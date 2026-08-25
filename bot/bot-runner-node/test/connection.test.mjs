import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { connectionOptions, MicrosoftAccounts } from '../src/bots.mjs';
import { identity } from '../src/identity.mjs';

const botIdentity = identity('ReusableBot');

test('normal login does not require BungeeCord forwarding', () => {
  const options = connectionOptions('127.0.0.1', 25565, '1.21.8', botIdentity, null);

  assert.equal(options.fakeHost, undefined);
  assert.equal(options.username, 'ReusableBot');
});

test('clientIp explicitly opts into BungeeCord forwarding', () => {
  const options = connectionOptions('127.0.0.1', 25565, '1.21.8', botIdentity, '203.0.113.9');

  assert.equal(options.fakeHost.split('\0').length, 3);
  assert.equal(options.fakeHost.split('\0')[1], '203.0.113.9');
});

test('microsoft login uses its account cache instead of offline identity', () => {
  const onCode = () => {};
  const options = connectionOptions(
    '127.0.0.1',
    25565,
    '1.21.8',
    botIdentity,
    null,
    'microsoft',
    'qa-primary',
    'C:\\private-cache',
    onCode,
  );

  assert.equal(options.auth, 'microsoft');
  assert.equal(options.username, 'qa-primary');
  assert.equal(options.profilesFolder, 'C:\\private-cache');
  assert.equal(options.onMsaCode, onCode);
  assert.equal(options.fakeHost, undefined);
});

test('first microsoft spawn reports device code and a retry reuses the completed login', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'vitaminmcp-auth-'));
  let complete;
  const accounts = new MicrosoftAccounts(directory, (account, profilesFolder, onCode) => ({
    getMinecraftJavaToken() {
      assert.equal(account, 'qa-primary');
      assert.equal(profilesFolder, directory);
      onCode({
        verification_uri: 'https://www.microsoft.com/link',
        user_code: 'ABCD-EFGH',
      });
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
  }));

  try {
    await assert.rejects(
      accounts.require('qa-primary'),
      /ABCD-EFGH.*call bot_spawn again/,
    );

    complete({ profile: { name: 'RealPlayer', id: '00112233445566778899aabbccddeeff' } });
    await new Promise((resolve) => setImmediate(resolve));

    const ready = await accounts.require('qa-primary');
    assert.equal(ready.profile.name, 'RealPlayer');
    assert.equal(ready.profilesFolder, directory);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

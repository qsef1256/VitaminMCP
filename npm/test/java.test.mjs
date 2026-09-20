import assert from 'node:assert/strict';
import test from 'node:test';

import { mcpServerArgs } from '../lib/java.mjs';

test('bounds server memory and forwards the selected transport', () => {
  assert.deepEqual(mcpServerArgs('server.jar', ['--http', '25584']), [
    '-Xms16m',
    '-Xmx128m',
    '-XX:+UseSerialGC',
    '-jar',
    'server.jar',
    '--http',
    '25584',
  ]);
});

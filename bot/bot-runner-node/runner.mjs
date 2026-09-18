#!/usr/bin/env node
/**
 * The bot runner, on mineflayer.
 *
 * Speaks the VitaminMCP stdio line protocol and is launched as the runner child process:
 *
 *   node runner.mjs <host> <port> [protocol]
 *
 * Everything it prints on stdout is protocol. Diagnostics go to stderr, which the MCP server
 * inherits — a stray `console.log` here is a desynchronised session, not a stray log line.
 */
import { createInterface } from 'node:readline';

import { BotRegistry } from './src/bots.mjs';
import { Dispatch } from './src/dispatch.mjs';
import * as protocol from './src/protocol.mjs';
import { pingProtocol, versionForProtocol } from './src/version.mjs';

// stdout is the protocol channel; a dependency's console.log (mineflayer did this on 26.1, with a
// stack trace) desynchronises every later reply. `write` below is the only path to stdout.
for (const level of ['log', 'info', 'debug']) {
  console[level] = (...messages) => process.stderr.write(`${messages.map(String).join(' ')}\n`);
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.length < 2) {
    process.stderr.write('usage: runner.mjs <host> <port> [protocol]\n');
    process.exit(2);
  }

  const host = argv[0];
  const port = Number(argv[1]);

  let bots;
  let negotiated;
  try {
    negotiated = argv.length > 2 ? Number(argv[2]) : await pingProtocol(host, port);
    bots = new BotRegistry(host, port, versionForProtocol(negotiated));
  } catch (error) {
    // The Java side reads this line and reports it as the reason the runner did not start, so it
    // has to arrive on stdout in protocol form rather than as a stack trace on stderr.
    write(protocol.encode(protocol.ERROR, 'startup', `${error?.name ?? 'Error'}: ${error?.message ?? error}`));
    process.stderr.write(`${error?.stack ?? error}\n`);
    process.exit(1);
  }

  write(protocol.encode(protocol.READY, String(negotiated)));

  const dispatch = new Dispatch(bots);
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });

  for await (const line of lines) {
    const reply = await dispatch.handle(line);
    if (reply === null) {
      break;
    }
    if (reply !== '') {
      write(reply);
    }
  }

  bots.shutdown();
  process.exit(0);
}

main().catch((error) => {
  process.stderr.write(`${error?.stack ?? error}\n`);
  process.exit(1);
});

function write(line) {
  process.stdout.write(`${line}\n`);
}

#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  MCP_SERVER_JAR, assetPath, ensureAsset, ensureJar,
} from '../lib/jars.mjs';
import { checkJava, findJava, mcpServerArgs } from '../lib/java.mjs';
import { checkNode, findNode, runnerAssetName, viewerAssetName } from '../lib/node.mjs';
import { runServiceAction, serviceAction } from '../lib/service.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * stdout is the MCP channel and carries nothing but JSON-RPC. Everything this launcher has to say
 * goes to stderr, where a client shows it as server output rather than trying to parse it.
 */
function say(message) {
  process.stderr.write(`[vitaminmcp] ${message}\n`);
}

function die(message) {
  process.stderr.write(`[vitaminmcp] ${message}\n`);
  process.exit(1);
}

async function version() {
  const manifest = await fs.readFile(path.join(HERE, '..', 'package.json'), 'utf8');
  return JSON.parse(manifest).version;
}

const HELP = `vitaminmcp — MCP server for testing Minecraft plugins

  Launched by an MCP client, not usually by hand. To connect it to Claude Code:

    claude mcp add vitaminmcp -- npx -y vitaminmcp

  The agent plugin still has to be installed on the Minecraft server itself. Once this server is
  connected, the /mcp__vitaminmcp__setup command walks through it.

Options
  --help        this text
  --version     the version this launcher will run
  --jars        download the jars and print where they are, without starting anything
  --http [port] serve MCP over loopback HTTP (default port 25584)
  service install|uninstall|status
                manage one shared Windows service (install also updates it)

Environment
  JAVA_HOME               the JDK to run the jars with; Java 21 or later
  VITAMINMCP_HOME         where jars and agent handshakes are kept (default ~/.vitaminmcp)
  VITAMINMCP_TOKEN        an agent token, for a server that leaves no local handshake
  VITAMINMCP_SERVER_JAR   run this mcp-server jar instead of a downloaded one
  VITAMINMCP_RUNNER_JAR   use this runner path instead of selecting one automatically
  VITAMINMCP_NODE         Node executable for the source runner fallback
  VITAMINMCP_NODE_RUNNER  path to a bundled runner.mjs when Node is available
`;

async function main() {
  const argv = process.argv.slice(2);
  const release = await version();

  if (argv.includes('--help') || argv.includes('-h')) {
    process.stderr.write(HELP);
    return 0;
  }
  if (argv.includes('--version') || argv.includes('-v')) {
    process.stderr.write(`${release}\n`);
    return 0;
  }

  const service = serviceAction(argv);
  if (service) {
    if (service === 'install') {
      const java = findJava();
      const usable = checkJava(java);
      if (!usable.ok) die(usable.message);
      return await runServiceAction(service, release, { java });
    }
    return await runServiceAction(service, release);
  }

  const java = findJava();
  const usable = checkJava(java);
  if (!usable.ok) {
    die(usable.message);
  }

  // The server jar is small and nothing works without it, so it is waited for. The runner is
  // ninety megabytes and only bots need it, so it is fetched alongside: a client that never
  // spawns a bot never waits for it, and one that does waits inside a tool call rather than
  // inside a startup timeout.
  let server = process.env.VITAMINMCP_SERVER_JAR;
  if (!server) {
    try {
      server = await ensureJar(release, MCP_SERVER_JAR, { log: say });
    } catch (error) {
      die(String(error.message ?? error));
    }
  }

  const configuredRunner = process.env.VITAMINMCP_RUNNER_JAR;
  const node = findNode();
  const nodeCheck = checkNode(node);
  let viewerAsset;
  try {
    viewerAsset = viewerAssetName();
  } catch {
    // The source runner can still be used on platforms whose native viewer asset is not released.
    viewerAsset = '';
  }
  const sourceRunner = process.env.VITAMINMCP_NODE_RUNNER
    ?? path.join(HERE, '..', 'runner', 'runner.mjs');
  let runner;
  let runnerReady;
  if (configuredRunner) {
    runner = configuredRunner;
    runnerReady = Promise.resolve(runner);
  } else if (nodeCheck.ok && existsSync(sourceRunner)) {
    runner = sourceRunner;
    runnerReady = Promise.resolve(runner);
  } else {
    const asset = runnerAssetName();
    runner = assetPath(release, asset);
    runnerReady = ensureAsset(release, asset, { log: say });
  }

  if (argv.includes('--jars')) {
    await runnerReady;
    process.stderr.write(`${server}\n${runner}\n`);
    return 0;
  }

  await runnerReady;
  const env = serverEnvironment(runner, release, viewerAsset);
  return await run(java, server, env, argv);
}

function serverEnvironment(runner, release, viewerAsset) {
  const env = {
    ...process.env,
    VITAMINMCP_RUNNER_JAR: runner,
    VITAMINMCP_VERSION: release,
    VITAMINMCP_VIEWER_ASSET: viewerAsset,
  };
  // The loader imports the pinned archive only when the Node runner receives bot_view(world).
  if (!env.VITAMINMCP_VIEWER_PATH) {
    env.VITAMINMCP_VIEWER_PATH = path.join(HERE, '..', 'lib', 'viewer-loader.mjs');
  }
  return env;
}

/** Runs the server jar, and lives exactly as long as it does. */
function run(java, server, env, args) {
  const child = spawn(java, mcpServerArgs(server, args), {
    stdio: 'inherit',
    env,
  });

  return new Promise((resolve) => {
    for (const signal of ['SIGINT', 'SIGTERM']) {
      process.on(signal, () => child.kill(signal));
    }

    child.on('error', (error) => {
      say(`could not start java: ${error.message}`);
      resolve(1);
    });

    child.on('exit', (code, signal) => {
      resolve(signal ? 1 : (code ?? 0));
    });
  });
}

main().then(
  (code) => process.exit(code),
  (error) => die(String(error?.stack ?? error)),
);

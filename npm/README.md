# vitaminmcp

**MCP server for testing Minecraft plugins.** Drives a real Paper/Purpur server and real protocol
bots from an AI agent, so a plugin can be tested end to end without opening the game.

This package is the launcher. It fetches the jars it needs on first run and speaks stdio to your
MCP client — it is not the whole product on its own: the agent is a Paper plugin, and it goes on
the Minecraft server.

```bash
claude mcp add vitaminmcp -- npx -y vitaminmcp
```

Or in `.mcp.json`, `claude_desktop_config.json`, or whatever your client calls it:

```json
{
  "mcpServers": {
    "vitaminmcp": {
      "command": "npx",
      "args": ["-y", "vitaminmcp"]
    }
  }
}
```

Then, in Claude Code, `/mcp__vitaminmcp__setup` walks through the other half — putting
`VitaminMCP.jar` in the server's `plugins/`, restarting it, and connecting. Or just ask:

> **Prompt:** Set up VitaminMCP on my Minecraft server at ~/servers/test and connect to it.

Once the plugin is running, `session_start` needs no arguments for a server on this machine: the
agent leaves its host, ports and token where this server reads them.

## What you get

- Spawn and control test players — real protocol clients, not mock `Player` objects
- Execute commands as the console or as a player
- Open, read, click and assert on inventories and plugin GUIs
- Wait for events and conditions instead of sleeping
- Read live server state: events, logs, exceptions, permissions
- Paper / Purpur 1.21 through 1.21.8, from one install

## Requires

- **Java 21 or later** on this machine — the jars run on the JVM. Point `JAVA_HOME` at it, or have
  `java` on `PATH`
- **Node 18.17 or later** when using the source runner fallback. If Node is absent, the launcher
  selects a pinned platform runner asset instead.
- **Paper 1.21 or later** on the Minecraft server, with `VitaminMCP.jar` in its `plugins/`

## Environment

| | |
|---|---|
| `JAVA_HOME` | the JDK to run the jars with |
| `VITAMINMCP_HOME` | where jars and agent handshakes are kept. Default `~/.vitaminmcp` |
| `VITAMINMCP_TOKEN` | an agent token, for a server that leaves no local handshake |
| `VITAMINMCP_SERVER_JAR` | run this `mcp-server.jar` instead of a downloaded one |
| `VITAMINMCP_RUNNER_JAR` | use this runner path instead of automatic selection |
| `VITAMINMCP_NODE` | Node executable for the source runner fallback |
| `VITAMINMCP_NODE_RUNNER` | bundled `runner.mjs` path when Node is available |
| `VITAMINMCP_VIEWER_PATH` | local viewer module to use instead of the pinned optional asset |

## What it downloads

On first run, from [the GitHub release](https://github.com/Backas03/VitaminMCP/releases)
matching this package's version, into `~/.vitaminmcp/jars/<version>/`:

- `mcp-server.jar` (~2 MB) — waited for, since nothing works without it
- a source Node runner when Node and the bundled runner are available — no runner asset download
- otherwise one platform runner asset (`win-x64`, `linux-x64`, `linux-arm64`, `darwin-x64` or
  `darwin-arm64`), checked against a SHA-256 pinned into this package

The trimmed `bot-runner-viewer-win-x64.tgz` is separate and is downloaded into
`~/.vitaminmcp/assets/<version>/` only when `bot_view` asks for a world view. It is checked against
the SHA-256 pinned into this package at publish time, extracted on first use, and never fetched by
an installation that only spawns bots or uses the inventory viewer. The current native viewer asset
is Windows x64; on Linux and macOS, set `VITAMINMCP_VIEWER_PATH` to a local sidecar until those
assets are released.

Every downloaded file is checked against its pinned SHA-256. A file that does not match is deleted
rather than run.

Full documentation, design notes and the plugin itself:
**[github.com/Backas03/VitaminMCP](https://github.com/Backas03/VitaminMCP)**

MIT.

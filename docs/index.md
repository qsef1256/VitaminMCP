# VitaminMCP — a Minecraft MCP plugin for testing plugins with AI agents

VitaminMCP is an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server that runs
**inside a Minecraft server**, as a Paper/Purpur plugin. An AI agent — Claude Code, Cursor, Codex,
Gemini CLI, any MCP client — connects to it and tests plugins end to end: real bot players join
over the Minecraft protocol, run commands, click through GUIs, and the agent reads back the
server's events, logs, exceptions and live state to assert on what actually happened.

The plugin under test is not modified, instrumented or compiled against anything. Anything
installed on the server is testable, including plugins you did not write.

```text
claude mcp add vitaminmcp -- npx -y vitaminmcp
```

Then drop `VitaminMCP.jar` from the
[latest release](https://github.com/Backas03/VitaminMCP/releases/latest) into `plugins/` and start
the server.

## Documentation

- [README](https://github.com/Backas03/VitaminMCP#readme) — what it is, the tools, and setup
- [Reference](reference.md) — how the pieces fit, every tool and step, version support, building from source
- [Usage](usage.md) — every tool, scenario runs, multi-server sessions
- [Design](design.md) — why it is built the way it is
- [Publishing](publishing.md) — release and registry process

## Where to get it

- [GitHub — Backas03/VitaminMCP](https://github.com/Backas03/VitaminMCP)
- [npm — `vitaminmcp`](https://www.npmjs.com/package/vitaminmcp)
- MCP registry — `io.github.Backas03/vitaminmcp`

Supports Paper and Purpur **1.21 through 26.1** from a single install. MIT licensed.

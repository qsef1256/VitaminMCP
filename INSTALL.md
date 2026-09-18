# Installing VitaminMCP

The short version is in the [README](README.md#setup). This is the long one: every client,
every setting that matters, and how to reach a server that is not on this machine.

Two halves, and neither is useful alone: an **MCP server on your machine**, which your client
launches, and the **agent plugin on the Minecraft server**, which is where everything worth asking
about happens.

## 1. Connect your MCP client

The MCP server is plain stdio: **any client that can launch `npx -y vitaminmcp` works** — Claude
Code, Cursor, Codex, Gemini CLI, Windsurf, Claude Desktop, VS Code. This is the one configuration
every client expresses in its own file:

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

**Claude Code** has a shortcut: the plugin brings the MCP server *and* the working knowledge of how
to drive it, as a skill that loads itself when a question calls for it. Type these into the Claude
Code prompt (they are Claude Code commands, not shell commands):

```text
/plugin marketplace add Backas03/VitaminMCP
/plugin install vitaminmcp@vitaminmcp
```

Everywhere else, register the server where that client keeps its MCP configuration:

| Client | Where |
|---|---|
| **Claude Code** (without the plugin) | `claude mcp add vitaminmcp -- npx -y vitaminmcp` in a shell, or the JSON above in the project's `.mcp.json` |
| **Cursor** | the JSON above in `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global) |
| **Codex CLI** | `codex mcp add vitaminmcp -- npx -y vitaminmcp`, or in `~/.codex/config.toml`: `[mcp_servers.vitaminmcp]` with `command = "npx"`, `args = ["-y", "vitaminmcp"]` |
| **Gemini CLI** | `gemini mcp add vitaminmcp npx -y vitaminmcp`, or the JSON above in `~/.gemini/settings.json` |
| **Windsurf** | the JSON above in `~/.codeium/windsurf/mcp_config.json` |
| **Claude Desktop** | the JSON above in `claude_desktop_config.json` |
| **VS Code** | `.vscode/mcp.json`, under a `"servers"` key instead of `"mcpServers"` |
| anything else | wherever that client takes a stdio MCP server; the command is always `npx -y vitaminmcp` |

Every tool works the same in every client. What only Claude Code gets is the plugin's *skill* — the
written testing playbook. Other clients still receive the operating knowledge that matters at call
time: `session_start` returns the agent's full tool definitions, and the tool descriptions carry
their own warnings.

That is the whole client side. Nothing to download by hand and no path to get right: the
[`vitaminmcp`](https://www.npmjs.com/package/vitaminmcp) package fetches the jars it needs on first
run, into `~/.vitaminmcp/jars/<version>/`, each checked against a SHA-256 pinned into the package
when it was published.

`mcp-server.jar` is two megabytes and is waited for. With Node installed, the source runner is used
directly and no runner asset is downloaded. Without Node, the launcher selects the native runner
asset for the current platform, and every supported platform has one.

`mcp-server` speaks stdio. It has no port and no token: it is a child process of the client, so the
trust relationship already exists. Only the agent side crosses a network, which is why only the
agent side authenticates.

> Needs **Node 18.17+** for `npx`, and **Java 21** to run the jars. No npm, or nothing to download
> with? [Install from the jars](#installing-from-the-jars-instead).

## 2. Install the plugin on the server

Ask, and the agent does it. The MCP server publishes a `setup` prompt that walks the agent through
this step — it checks the server is Paper 1.21+, puts the jar in `plugins/`, restarts, and
connects. Clients surface MCP prompts under their own names, built from the name the *server* was
registered under. In Claude Code:

```text
/mcp__plugin_vitaminmcp_vitaminmcp__setup    # installed as the plugin
/mcp__vitaminmcp__setup                      # added with claude mcp add vitaminmcp
```

`/mcp` lists what yours is actually called. In a client that lists prompts elsewhere (or not at
all), just ask in plain words:

> **Prompt:** Set up VitaminMCP on my Minecraft server at ~/servers/test and connect to it.

By hand instead:

**Download `VitaminMCP.jar`** from
[Releases](https://github.com/Backas03/VitaminMCP/releases/latest) into the server's
`plugins/` — an ordinary Bukkit/Paper plugin, no server flags and no java agent to attach — and
start the server.

```
[VitaminMCP] No auth token was configured, so one was generated and written to config.yml: kQ8s...
[VitaminMCP] MCP endpoint listening on http://127.0.0.1:25585/mcp
```

**You do not need to copy that token.** A client on the same machine reads it from the agent's own
handshake. Copy it only for a client somewhere else.

That is the minimum install. Every other setting is documented in
[config.yml](agent/agent-mcp/src/main/resources/config.yml), alongside why each default is what it
is. Three defaults to know before you change anything:

- **`read-only: true` is the default.** State-changing tools like `command_exec` are not exposed at
  all — a default install cannot alter the server even with a valid token. Turn it off only when
  you need to.
- **The endpoint never opens unauthenticated.** An empty `auth-token` is filled in with a generated
  one rather than waved through, and if it cannot be written the plugin still refuses to start
  What was never negotiable is that a token exists; making you fetch one out of a crash log was not
  part of it.
- **Moving `bind-address` off loopback makes TLS mandatory.** The token grants console access, and
  over plain HTTP it crosses the network in the clear where anything on the path can read it. So
  that combination is a refusal to start, not a warning. Satisfy it with either `tls.enabled` (the
  agent serves HTTPS itself) or `tls.terminated-upstream` (a proxy in front terminates it). The
  agent will not generate a self-signed certificate for you — convenient, but it would teach every
  client to skip verification.

## 3. Server setup, if you want bots

Skip this section if you only need the agent.

Bot joins and actions change server state, so first set `read-only: false` in
`plugins/VitaminMCP/config.yml` and restart the server.

Offline authentication is the default. It is convenient for an isolated test server and reuses
the same deterministic UUID when the bot name is reused:

```properties
# server.properties
online-mode=false
```
> **Never expose an offline-mode server to the internet.** This is a test-harness configuration,
> not a production one. No BungeeCord setting is required for normal Node logins.

Reusing a bot name reuses its deterministic offline UUID. Set BungeeCord forwarding explicitly only
for a test that passes `clientIp` and needs a spoofed address or UUID.

To keep `online-mode=true`, use a dedicated Microsoft account instead:

```json
bot_spawn {"name":"RealProfileName", "auth":"microsoft", "account":"qa-primary"}
```

The first call returns a Microsoft device-login URL and code. Complete the login, then repeat the
same call. `account` is a local cache key and defaults to `name`; cached tokens live under
`~/.vitaminmcp/accounts`, or `VITAMINMCP_ACCOUNTS_DIR` if set before the MCP server starts. The
authenticated Java profile name must match `name`. `clientIp` forwarding is offline-only.

`move_to` walks to its destination by default, using the same client-side physics loop that sends
the movement packets between the two points. That means plugins listening for pressure plates and
movement events observe the route. A path that cannot be found fails with `No path exists`; a path
that does not arrive before `timeoutMillis` fails with `did not arrive ... within ...`.

For setup steps that only need a bot at a coordinate, use `"mode":"teleport"`. This retains the
legacy one-position-packet behaviour and is still fast, but it does not fire the events that a
walking player would have caused.

Walking does not dig through or place blocks. The pathfinder is intentionally configured for
ordinary traversal so a test wall remains a test wall.

## 4. Connect

Just ask. These are prompts — copy one and fill in your own values.

**A server on this machine**

> **Prompt:** Connect to the Minecraft server on this machine, then tell me the server version and
> which plugins are loaded.

**Behind an SSH tunnel** — say which local ports the tunnel forwards

> **Prompt:** The test server is tunnelled to this machine — Minecraft on localhost:10000, the agent
> on localhost:25685. Token is `kQ8s…`. Connect and confirm it is alive.

Or keep the token out of the conversation and point at a file instead — the agent reads it and
passes it to `session_start`:

> **Prompt:** The test server is tunnelled to this machine — Minecraft on localhost:10000, the agent
> on localhost:25685. The token is in `~/.secrets/vitaminmcp-token`. Connect and confirm it is
> alive.

For a token that never appears in a prompt at all, set `VITAMINMCP_TOKEN` in the MCP server's
environment (an `"env"` block next to `"command"` in the client configuration) — `session_start`
falls back to it whenever no `token` argument is given.

**Remote, over TLS** — paste the block the agent printed at startup

> **Prompt:** Connect using this: host 203.0.113.10, mcpPort 25585, tls true, token `YLwNyFij…`,
> fingerprint `sha256:ffb61d8f…f163`. Minecraft is on 25565.

Or with the token in a file rather than in the conversation:

> **Prompt:** Connect using this: host 203.0.113.10, mcpPort 25585, tls true, fingerprint
> `sha256:ffb61d8f…f163`, token in `~/.secrets/vitaminmcp-token`. Minecraft is on 25565.

**For anything not on this machine, include the port numbers and the token.** Without them the
agent has to guess at defaults, and a wrong guess surfaces as a rejected token rather than a wrong
address — the same failure whichever detail was missing.

### What the agent calls: `session_start`

```text
session_start
```

No arguments. The agent writes its host, both ports and its token to
`~/.vitaminmcp/agents/<port>.properties` while it runs, and `session_start` reads them — so for a
server on this machine there is nothing to pass and nothing to look up. A successful connection
returns the server version, TPS and plugin list, the agent's real tool definitions, and the current
session roster. Sessions whose runner process has exited are removed from that roster.

Pass what differs, and only that. A server somewhere else needs `host` and `token`, because a token
minted on this machine says nothing about a server on another one and is not sent there:

```json
{
  "host": "203.0.113.10",
  "token": "auth-token from config.yml",
  "tls": "true",
  "tlsFingerprint": "sha256:ffb61d8f...f163"
}
```

**A proxied network is several servers.** Open one session per backend — they coexist, and starting
one never disturbs another, which matters because closing a session disconnects its bots. `port` is
the proxy's in every session; what tells them apart is `mcpPort`, the agent inside each backend.
With more than one agent running locally that is also what picks between them, and omitting it is
an error naming them rather than a guess.

```jsonc
session_start {"session": "lobby",    "mcpPort": 25585, "port": 25577, "minecraftProtocol": 772}
session_start {"session": "survival", "mcpPort": 25586, "port": 25577, "minecraftProtocol": 772}
bot_spawn     {"session": "lobby", "name": "Tester1"}
```

Normally omit `minecraftProtocol`. Set it to the backend's numeric protocol only when a proxy's
server-list ping advertises the protocol from the request instead of the backend's protocol.

Every other tool takes `session`. Omit it and it resolves only while one session is open; with
several it is an error naming them, rather than a guess about which server you meant. The full
walkthrough is in [docs/usage.md](docs/usage.md).

## Installing from the jars instead

`npx` is a convenience, not a requirement. **Two artifacts**, plus the optional platform runner
assets,
are attached to every
[release](https://github.com/Backas03/VitaminMCP/releases/latest), and **each goes
somewhere different:**

| File | Where | What |
|---|---|---|
| `VitaminMCP.jar` | the server's `plugins/` | the agent — an ordinary Bukkit/Paper plugin |
| `mcp-server.jar` | anywhere (remember the path) | your MCP client launches it |
| `runner.mjs` or a platform `bot-runner-*` asset | beside `mcp-server.jar` | `mcp-server` launches it as a child process |

To build them yourself instead:

```bash
./gradlew dist
```

Either way, point the client at the jar rather than at the package — same registration as step 1,
different command:

```json
{
  "mcpServers": {
    "vitaminmcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/mcp-server.jar"]
    }
  }
}
```

Or in Claude Code: `claude mcp add vitaminmcp -- java -jar /absolute/path/mcp-server.jar`.

`VITAMINMCP_RUNNER_JAR`, or `session_start`'s `runnerJar`, names the Node script or native runner.

**One Node runner, every supported version.** It normally pings the server before any bot connects
and selects the matching mineflayer data, so the same source runner works on 1.21 through 26.1. A
proxy that echoes the ping request's protocol needs `session_start.minecraftProtocol` set to the
backend's actual numeric protocol.

---

## A server on another machine

Two ways: forward the ports over SSH, or expose the agent with TLS. If you already have SSH to the
box, the tunnel is less work and exposes nothing.

### Over an SSH tunnel

Leave the agent on its loopback default and forward both ports:

```bash
ssh -L 25585:127.0.0.1:25585 -L 25565:127.0.0.1:25565 user@your-server
```

Then connect as if everything were local — `host: "127.0.0.1"`, no `tls`, no `tlsFingerprint`. The
agent sees a loopback connection because, from its side, that is what it is. Nothing on the server
is published to the network, and the token never crosses it in the clear: SSH is the transport
security that TLS would otherwise have to provide.

Forward **both** ports. `mcpPort` is how tools reach the agent, and `port` is where bots connect —
forwarding only the first gives you a working `server_info` and a `bot_spawn` that cannot connect.

> **Pick local ports that are actually free.** `ssh -L` binds the local side, and if something on
> your machine already holds that port, the tunnel does not take it — your requests reach the other
> program instead. The failure that produces is misleading: a different VitaminMCP agent answering
> on 25585 rejects your token, so it reads as a wrong token rather than a wrong destination. When
> in doubt map to a distinct local port (`-L 25685:127.0.0.1:25585`) and pass that as `mcpPort`.

### Exposing the agent with TLS

Once `bind-address` leaves loopback the agent will not start without TLS. Set up a certificate and
start it, and **the agent prints everything needed to connect**:

```
[VitaminMCP] MCP endpoint listening on https://203.0.113.10:25585/mcp
[VitaminMCP] Connect with session_start:
  "host": "203.0.113.10", "mcpPort": 25585, "tls": "true",
  "token": "YLwNyFij...",
  "tlsFingerprint": "sha256:ffb61d8f...f163"
```

Paste it and you are done. **A self-signed certificate still requires installing nothing on the
client** — `tlsFingerprint` pins that one certificate. No exporting, no copying, no truststore.

With a real certificate (Let's Encrypt and friends), drop `tlsFingerprint` and verification
proceeds normally.

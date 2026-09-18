# VitaminMCP

**Minecraft automation testing MCP server plugin for AI agents.**

VitaminMCP is a **Paper/Purpur server plugin.** Drop `VitaminMCP.jar` into `plugins/`, start the
server, and it opens an MCP endpoint from inside the running server — so an AI agent can drive that
server and read back what happened, while real bot clients connect to it over the Minecraft
protocol.

**Nothing about the plugin you are testing changes.** No test framework to adopt, no source to
instrument, no harness to compile against, no mock server standing in for a real one: the plugin
under test runs on a real server through its real lifecycle, and VitaminMCP watches it from the next
plugin slot over. Which also means it works on plugins you did not write — anything already
installed is testable.

Drive a real Minecraft server and real players through MCP tools, and run end-to-end plugin tests
without opening the game.

- Spawn and control test players — real protocol clients, not mock `Player` objects
- Execute commands as the console or as a player
- Open, read, click and assert on inventories and plugin GUIs
- Right-click NPCs and villagers, the way a shop or quest giver is actually triggered
- Move players, break and use blocks, chat
- Wait for events and conditions instead of sleeping
- Assert on blocks, players, events, inventories and the messages a player received
- Read the player's whole screen: menus, chat, action bar, titles, boss bars, scoreboard
- Read live server state: events, logs, exceptions, permissions
- Drive **several servers at once** — one session per backend of a BungeeCord network, bots staying
  connected across all of them
- Paper / Purpur **1.21 through 1.21.11**, from one install — the runner works out which protocol the
  server speaks and adapts

Full usage is in `docs/usage.md`. Contribution rules are in `CONTRIBUTING.md`, and release steps
are in `docs/publishing.md`.

---

## How it fits together

Three jars, in three different places. Only the first is a Minecraft plugin.

```text
  your MCP client (Claude Code, ...)
        |
        |  stdio
        v
  mcp-server.jar ---- HTTP(S) + token ---->  VitaminMCP.jar  <- the plugin, inside your server
        |                                    sees events, logs, exceptions, live state
        |  spawns
        v
  Node runner -------- Minecraft protocol ->  the same server, on :25565
                                             sees what a player's client was actually sent
```

| | Runs | Role |
|---|---|---|
| `VitaminMCP.jar` | **in the server, as a plugin** | Listens to every event, taps the log, and serves an authenticated MCP endpoint. The only piece with a view of server internals |
| `mcp-server.jar` | on your machine, as a child of your MCP client | Speaks stdio to the client and HTTP to the plugin, and owns the bots |
| `runner.mjs` or a platform `bot-runner-*` asset | on your machine, as a child of `mcp-server` | Connects real clients over the real protocol — login, packets, GUIs and all |

The plugin sees server-side events, logs, permissions and state; the Node runner sees what a real
client receives. Read-only mode is the default, and bots are optional.

---

## Example

Ask the agent to test a plugin, or pass a scenario to `bot_run_scenario`:

```json
[
  {"action":"spawn", "bot":"Tester1"},
  {"action":"command", "bot":"Tester1", "command":"shop"},
  {"action":"wait_for", "condition":"inventory_open", "name":"Tester1", "title":"Shop"},
  {"action":"assert_inventory", "bot":"Tester1", "slots":[
    {"slot":11, "material":"DIAMOND_SWORD", "name":"Diamond Sword"}
  ]}
]
```

---

## Tools

Two groups. **Session tools** live in `mcp-server` and are always present. **Agent tools** are
proxied from the plugin, so which ones exist is decided by the server you connected to —
`session_start` returns their real definitions in `agentTools`.

### Connection

| | |
|---|---|
| `session_start` | Connect to a server and its agent. Every other tool needs it. Several sessions can be open at once — one per backend of a proxied network |
| `session_reset` | Disconnect every bot, keeping the connection. Use between independent tests. World state is **not** rolled back. `close: true` ends the session instead |

### Players

| | |
|---|---|
| `bot_spawn` | Connect an offline bot or a Microsoft-authenticated account and wait until it is standing in the world |
| `bot_inspect` | What the bot's client was actually sent: menu contents, messages (chat, action bar, title, subtitle) with the millisecond each arrived and a cursor to read only what came after an action, boss bars, sidebar scoreboard, health, food, experience and active effects |
| `bot_view` | Open a localhost-only live world or inventory view for a bot. The inventory view needs nothing extra; the world view downloads an optional asset the first time it is asked for, published for Windows x64 |
| `bot_run_scenario` | Run a whole scenario. Stops at the first failure with evidence attached |

### Server

| | |
|---|---|
| `server_info` | Version, TPS, players online, installed plugins, capture statistics |
| `command_exec` | Run a command as the console or as a player, vanilla commands included. **Changes the server** — absent entirely unless `read-only: false`. When nothing takes the command it says why, rather than only that it did not |

### World and state

| | |
|---|---|
| `state_query` `kind="player"` | Position, gamemode, op, IP, and any permission nodes you name |
| `state_query` `kind="block"` | The block at a coordinate |
| `state_query` `kind="inventory"` | The menu a player has open — the only place a plugin GUI's contents exist |

### Events and logs

| | |
|---|---|
| `events_summary` | Counts by event type. Call this before `events_query` — it stays small however busy the server is |
| `events_query` | Individual events, filtered by type and player, paged by cursor |
| `logs_query` | Logs by minimum severity and regular expression |
| `exceptions_recent` | Distinct exceptions with occurrence counts and first-seen times. Pass `hash` for a stack trace |

### Waiting

`wait_for` blocks until a condition holds, checked every tick inside the server.

| Condition | |
|---|---|
| `inventory_open` | a menu opened, optionally matching a title |
| `inventory_contains` | an item reached a slot — for GUIs filled after they open |
| `event` | an event fired, optionally for one player |
| `player_online` / `player_offline` | a player joined or left |
| `player_state` | `online` / `gameMode` / `op` reached a value |
| `player_near` | a player came within a radius |
| `block_is` / `block_is_not` | a block became, or stopped being, a material |
| `log_matches` | a log line matched a regex — for async work that changes nothing observable |
| `ticks` | the server advanced N ticks |

**There is no sleep, and there will not be one.** A fixed wait is a guess about timing that is right
on an idle server and wrong on a busy one — that is the entire mechanism by which flaky tests are
made. On timeout, `wait_for` returns the events and logs from that moment.

### Actions — scenario steps

Available inside `bot_run_scenario`.

| | |
|---|---|
| `spawn` / `despawn` | connect or disconnect a bot |
| `move_to` | walk to coordinates by default; use `mode: "teleport"` for fast setup placement. Optional `timeoutMillis` distinguishes a sealed route from a walk that did not arrive in time |
| `break_block` / `use_block` | break, or right-click a block — `use_block` is how you open a chest |
| `use_entity` | right-click an NPC, villager or armour stand, named by the coordinates it stands at |
| `attack_entity` | left-click the nearest NPC, mob or armour stand at coordinates |
| `hold_item` / `drop_item` | select a hotbar slot, or drop the held item/one held item |
| `place_block` | place the held item against a block face |
| `jump` / `sneak` / `sprint` | perform one jump, or set the movement state on/off |
| `look_at` | look at world coordinates directly |
| `assert_reachable` | ask whether a loaded path exists without moving; set `reachable: false` for sealed-region assertions |
| `click_slot` | click a slot: `left`, `right`, `shift_left`, `shift_right` |
| `close_menu` | close the open menu |
| `chat` / `command` | say something, or run a command as the bot |
| `console` | run a command as the console |
| `wait_for` | any condition above |

### Assertions — scenario steps

Verification is the point, so this is where the surface is widest.

| | Checks |
|---|---|
| `assert_inventory` | per slot: `material`, `name`, `amount`, `lore`, `customModelData`, `modelDataString`, `empty` — plus the menu's `title` and `size` |
| `assert_player` | `online`, `gameMode`, `op`. Waits rather than reads, because `/op` resolves asynchronously |
| `assert_block` | the material at a coordinate |
| `assert_event` | an event fired, optionally for one player, since the scenario began |
| `assert_message` | the server told this bot something containing a string |

Use `bot_inspect` for messages, screen state and effects; use `state_query` for server state. Pass
proxied parameters flat at the top level. Full parameters are in [docs/usage.md](docs/usage.md).

---

## Requirements

These are the requirements for using a prebuilt release:

| | |
|---|---|
| Minecraft server | **Paper 1.21 or later** (Purpur and other Paper forks work) |
| Java | 21, for the Paper server and local MCP server |
| Node | 18.17 or later, for `npx` |

### Version support

| Minecraft version | Windows | Linux | macOS | Status |
|---|:---:|:---:|:---:|---|
| 1.18 – 1.20.6 | 🔴 | 🔴 | 🔴 | Below the Paper agent floor |
| **1.21 – 1.21.11** | **🟢** | **🟢** | **🟢** | **Supported and live-tested** |
| 26.1, 26.2 and later | 🟡 | 🟡 | 🟡 | Released; each needs a compatibility run before it is added |

#### Runner support by operating system

| Operating system | Node source runner | Native runner asset | Meaning |
|---|:---:|:---:|---|
| **Windows x64** | 🟢 | 🟢 | Published, and the platform the matrix is run on |
| **Linux x64 / arm64** | 🟢 | 🟢 | Published since 3.0.0 |
| **macOS Intel / Apple Silicon** | 🟢 | 🟢 | Published since 3.0.0, ad-hoc signed |

**Legend:** 🟢 supported · 🟡 planned or requires the stated runtime · 🔴 unsupported.

**1.21 through 1.21.11 are supported today**, and every one of them runs in the matrix
(`versions.yaml`). **1.21.11 is where that line ends** — Minecraft moved to calendar versions after
it, so what follows 1.21.11 is 26.1 and 26.2 rather than a 1.21.12. Those are released and are not
in the matrix yet: adding one is a compatibility run against a real server plus a check that the
runner's bundled data still covers it, never an edit to `versions.yaml` alone.

**Where each platform's claim comes from.** The matrix is run on Windows, against Paper builds it
downloads itself — so what it proves is the same on any host, because the server it talks to is the
same server. Each release builds its native runner on the operating system that runner is for,
never cross-built, and every one of them is started in CI and has to refuse its own entry point
with the expected exit code before it is uploaded. The world view is the one piece that is still
Windows-only, and it says so where it is offered.

**You install one Node runner whatever the version.** It asks the server what it speaks and
selects the matching minecraft-data entry, so there is no protocol-specific runner to choose.

## Building from source

Most users do not need this section. Contributors need JDK 21 and Node/npm:

```bash
./gradlew build
cd bot/bot-runner-node && npm ci && npm test
```

Native runners are built with `npm run build:sea -- win32-x64`, `linux-x64`, `linux-arm64`,
`darwin-x64` or `darwin-arm64`. macOS assets receive an ad-hoc signature in the release workflow.

Outside the supported range, things fail clearly rather than misbehaving: an older server declines
to load the agent, and a server whose protocol has no minecraft-data entry is named at startup.

Agent support and bot support can also differ. The agent needs a compatible Paper API; bots need a
matching minecraft-data entry and a supported runner environment. So a server may be readable by
the agent before bots can join it — inspection, logs and events all still work without them.

---

## Install

Two halves, and neither is useful alone: an **MCP server on your machine**, which your client
launches, and the **agent plugin on the Minecraft server**, which is where everything worth asking
about happens.

### 1. Connect your MCP client

**In Claude Code**, install the plugin — it brings the MCP server and the working knowledge of how
to drive it, as a skill that loads itself when a question calls for it:

```bash
/plugin marketplace add Backas03/VitaminMCP
```

```bash
/plugin install vitaminmcp@vitaminmcp
```

**Any other MCP client**, or Claude Code without the skill:

```bash
claude mcp add vitaminmcp -- npx -y vitaminmcp
```

Or directly in `.mcp.json`:

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

### 2. Install the plugin on the server

Ask, and the agent does it — this is a command your client offers once step 1 is done:

```text
/mcp__vitaminmcp__setup
```

It checks the server is Paper 1.21+, puts the jar in `plugins/`, restarts, and connects. By hand
instead:

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

### 3. Server setup, if you want bots

Skip this section if you only need the agent.

Bot joins and actions change server state, so set `read-only: false` in
`plugins/VitaminMCP/config.yml` and restart before using them.

#### Microsoft account — keep `online-mode=true`

Use a dedicated test account whose Minecraft profile name is `Tester1`:

```json
bot_spawn {"name":"Tester1", "auth":"microsoft", "account":"qa-primary"}
```

The first call returns a Microsoft device-login URL and code. Complete that login in a browser,
then make the same call again. `account` is only a local cache key — it may be an email or a harmless
alias — and is never sent to the Minecraft server. Tokens are kept under
`~/.vitaminmcp/accounts`; set `VITAMINMCP_ACCOUNTS_DIR` before the MCP server starts to move that
private cache. The authenticated profile name must match `name`, so scenario steps and server-side
queries address the same player.

#### Offline bot — isolated harness only

Offline is the default when `auth` is omitted. It reuses the same deterministic UUID when the bot
name is reused and requires:

```properties
# server.properties
online-mode=false
```
> **Never expose an offline-mode server to the internet.** This is a test-harness configuration,
> not a production one. No BungeeCord setting is required for normal Node logins.

Reusing a bot name reuses its deterministic offline UUID. Set BungeeCord forwarding explicitly only
for a test that passes `clientIp` and needs a spoofed address or UUID. `clientIp` is incompatible
with Microsoft authentication.

`move_to` walks to its destination by default, using the same client-side physics loop that sends
the movement packets between the two points. That means plugins listening for pressure plates and
movement events observe the route. A path that cannot be found fails with `No path exists`; a path
that does not arrive before `timeoutMillis` fails with `did not arrive ... within ...`.

For setup steps that only need a bot at a coordinate, use `"mode":"teleport"`. This retains the
legacy one-position-packet behaviour and is still fast, but it does not fire the events that a
walking player would have caused.

Walking does not dig through or place blocks. The pathfinder is intentionally configured for
ordinary traversal so a test wall remains a test wall.

### 4. Connect

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
session_start {"session": "lobby",    "mcpPort": 25585, "port": 25577}
session_start {"session": "survival", "mcpPort": 25586, "port": 25577}
bot_spawn     {"session": "lobby", "name": "Tester1"}
```

Every other tool takes `session`. Omit it and it resolves only while one session is open; with
several it is an error naming them, rather than a guess about which server you meant. The full
walkthrough is in [docs/usage.md](docs/usage.md).

#### Or just ask

These are prompts — copy one and fill in your own values.

**A server on this machine**

> **Prompt:** Connect to the Minecraft server on this machine, then tell me the server version and
> which plugins are loaded.

**Behind an SSH tunnel** — say which local ports the tunnel forwards

> **Prompt:** The test server is tunnelled to this machine — Minecraft on localhost:10000, the agent
> on localhost:25685. Token is `kQ8s…`. Connect and confirm it is alive.

**Remote, over TLS** — paste the block the agent printed at startup

> **Prompt:** Connect using this: host 203.0.113.10, mcpPort 25585, tls true, token `YLwNyFij…`,
> fingerprint `sha256:ffb61d8f…f163`. Minecraft is on 25565.

**For anything not on this machine, include the port numbers and the token.** Without them the
agent has to guess at defaults, and a wrong guess surfaces as a rejected token rather than a wrong
address — the same failure whichever detail was missing.

### Installing from the jars instead

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

Either way, point the client at the jar rather than at the package:

```bash
claude mcp add vitaminmcp -- java -jar /absolute/path/mcp-server.jar
```

`VITAMINMCP_RUNNER_JAR`, or `session_start`'s `runnerJar`, names the Node script or native runner.

**One Node runner, every supported version.** It pings the server before any bot connects and
selects the matching mineflayer data, so the same source runner works on 1.21 through 1.21.11.

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

---

## Running against several versions

The same scenario can be run across every supported version in one pass. The matrix is
`versions.yaml`, not code — adding a version is a single block. Server jars are
downloaded from the PaperMC API and started natively (no Docker, no ViaProxy;
no extra translation layer).

**The protocol is deliberately not in that file.** The Node runner asks each server what it speaks
and selects the matching minecraft-data entry, so a version needs nothing there beyond the build
to download.

Versions beyond 1.21.11 — which now means 26.1 and up, since the 1.21 line ended there — require a
compatibility run before they are added, and a check that the runner's trimmed data still covers
them. The runner selects the matching data version from the server handshake, and refuses clearly
rather than half-working when it has no entry.

---

## License

MIT — see [LICENSE](LICENSE).

The distributed jars bundle third-party code, relocated so it cannot collide with the server or
other plugins:

| | Bundled in | License |
|---|---|---|
| Jackson | `VitaminMCP.jar`, `mcp-server.jar` | Apache-2.0 |
| ClassGraph | `VitaminMCP.jar` | MIT |
| mineflayer, minecraft-data, mineflayer-pathfinder | Node runner dependencies | MIT |

Their license and notice files travel inside the jars under `META-INF/` — relocating a package
renames it, it does not lift the obligation to carry the notice.

`paper-api`, `log4j-core` and the JetBrains annotations are compile-only and are not distributed.
The agent compiles against Paper's API, which is LGPL-3.0; the jar does not contain it, and the
server already provides it. Nothing here touches `paper-server` (GPL-3.0) — the agent uses the
Bukkit/Paper API only, never NMS.

# Reference

The long version of the [README](https://github.com/Backas03/VitaminMCP#readme): how the pieces
fit together, what every tool, condition and scenario step does, what the version and platform
support claims rest on, how to build from source, and what the jars bundle.

Setup is in [INSTALL.md](https://github.com/Backas03/VitaminMCP/blob/master/INSTALL.md), full
usage in [usage.md](usage.md), contribution rules in
[CONTRIBUTING.md](https://github.com/Backas03/VitaminMCP/blob/master/CONTRIBUTING.md), and
release steps in [publishing.md](publishing.md).

---

## How it fits together

Three jars, in three different places. Only the first is a Minecraft plugin.

```text
  your MCP client (Claude Code, Cursor, Codex, Gemini CLI, ...)
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
| `session_start` | Connect to a server and its agent. Every other tool needs it. Several sessions can be open at once — one per backend of a proxied network. A proxy that echoes the ping request's protocol may need the backend's `minecraftProtocol` |
| `session_reset` | Disconnect every bot, keeping the connection. Use between independent tests. World state is **not** rolled back. `close: true` ends the session instead |

### Players

| | |
|---|---|
| `bot_spawn` | Connect an offline or Microsoft-authenticated bot and wait until it is standing in the world. Offline UUIDs derive from the name |
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
| `state_query` `kind="plugin"` | A plugin's commands, permissions and live config |

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
proxied parameters flat at the top level. Full parameters are in [usage.md](usage.md).

---

## Requirements

These are the requirements for using a prebuilt release:

| | |
|---|---|
| Minecraft server | **Paper 1.21 or later** (Purpur and other Paper forks work) |
| Java | 21 for the local MCP server. The Paper server has its own requirement: 21 for 1.21.x, 25 for 26.1 |
| Node | 18.17 or later, for `npx` |

### Version support

| Minecraft version | Windows | Linux | macOS | Status |
|---|:---:|:---:|:---:|---|
| 1.18 – 1.20.6 | 🟡 | 🟡 | 🟡 | Planned; below the current agent floor (1.21) |
| **1.21 – 1.21.11** | **🟢** | **🟢** | **🟢** | **Supported and live-tested** |
| **26.1 – 26.1.2** | **🟢** | **🟢** | **🟢** | **Supported and live-tested**; the server needs Java 25 |
| 26.2 and later | 🟡 | 🟡 | 🟡 | Released; each needs a compatibility run before it is added |

#### Runner support by operating system

| Operating system | Node source runner | Native runner asset | Meaning |
|---|:---:|:---:|---|
| **Windows x64** | 🟢 | 🟢 | Published, and the platform the matrix is run on |
| **Linux x64 / arm64** | 🟢 | 🟢 | Published since 3.0.0 |
| **macOS Intel / Apple Silicon** | 🟢 | 🟢 | Published since 3.0.0, ad-hoc signed |

**Legend:** 🟢 supported · 🟡 planned or requires the stated runtime · 🔴 unsupported.

**1.21 through 26.1 are supported today**, and every one of them runs in the matrix
(`versions.yaml`). **1.21.11 is where the 1.21 line ends** — Minecraft moved to calendar versions
after it, so what follows 1.21.11 is 26.1 rather than a 1.21.12. 26.1, 26.1.1 and 26.1.2 share one
protocol and are covered together. 26.2 is released and is not in the matrix yet: adding it is a
compatibility run against a real server plus a check that the runner's bundled data covers it,
never an edit to `versions.yaml` alone.

**Paper 26.1 runs on Java 25**, where the 1.21 line ran on 21. That is Paper's requirement, not this
project's: the jars here still need only Java 21, and the agent loads on either.

The world view (`bot_view`) is not offered on 26.1 yet; everything else is.

**Where each platform's claim comes from.** The matrix is run on Windows, against Paper builds it
downloads itself — so what it proves is the same on any host, because the server it talks to is the
same server. Each release builds its native runner on the operating system that runner is for,
never cross-built, and every one of them is started in CI and has to refuse its own entry point
with the expected exit code before it is uploaded. The world view is the one piece that is still
Windows-only, and it says so where it is offered.

**You install one Node runner whatever the version.** It normally asks the server what it speaks
and selects the matching minecraft-data entry, so there is no protocol-specific runner to choose.
If a proxy echoes the ping request's protocol instead of its backend's, pass the backend's numeric
protocol as `session_start.minecraftProtocol`.

Outside the supported range, things fail clearly rather than misbehaving: an older server declines
to load the agent, and a server whose protocol has no minecraft-data entry is named at startup.

Agent support and bot support can also differ. The agent needs a compatible Paper API; bots need a
matching minecraft-data entry and a supported runner environment. So a server may be readable by
the agent before bots can join it — inspection, logs and events all still work without them.

---

## Building from source

Most users do not need this section. Contributors need JDK 21 and Node/npm:

```bash
./gradlew build
cd bot/bot-runner-node && npm ci && npm test
```

Native runners are built with `npm run build:sea -- win32-x64`, `linux-x64`, `linux-arm64`,
`darwin-x64` or `darwin-arm64`. macOS assets receive an ad-hoc signature in the release workflow.

---

## Running against several versions

The same scenario can be run across every supported version in one pass. The matrix is
`versions.yaml`, not code — adding a version is a single block. Server jars are
downloaded from the PaperMC API and started natively (no Docker, no ViaProxy;
no extra translation layer).

**The protocol is deliberately not in that file.** The Node runner asks each server what it speaks
and selects the matching minecraft-data entry, so a version needs nothing there beyond the build
to download.

Versions beyond 26.1 — 26.2 and whatever follows — require a compatibility run before they are
added, and a check that the runner's trimmed data covers them. The runner bundles every version
minecraft-data ships from the floor up, selects the matching one from the server handshake, and
refuses clearly rather than half-working when it has no entry.

---

## License

MIT — see [LICENSE](https://github.com/Backas03/VitaminMCP/blob/master/LICENSE).

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

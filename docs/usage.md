# Usage

Installation is in [../README.md](../README.md). This document picks up after it.

There are two ways to use this, and they need different things installed.

| | Needs | Can do |
|---|---|---|
| **Investigate only** | the agent plugin | ask a running server what happened |
| **Test as well** | + mcp-server + a runner | attach bots, make them act, verify the result |

The investigate-only side is the read-only default as shipped. It is the only one that belongs on a
production server.

---

# A. Investigating

## Always start with `server_info`

It returns the version, TPS, player count, installed plugins, and **capture status**. That last one
matters — a non-zero `eventsDropped` means the buffer overflowed, and every query after that is
reading data with holes in it.

The response also carries `latestEventCursor` / `latestLogCursor`. Start from those when you only
want "what happens from now on" and you will not re-read the past.

## Events — summary first, detail second

```
events_summary  →  events_query
```

**Do not reverse the order.** `events_summary` stays small however busy the server is, and it tells
you which types are worth looking at in detail. Skip it and call `events_query` first, and you spend
the response budget on events you do not care about.

| Tool | Parameters |
|---|---|
| `events_summary` | `from`, `to` (epoch ms, both optional) |
| `events_query` | `types[]`, `player`, `cursor`, `limit` |

**High-frequency events only appear when named in `types`.** `PlayerMoveEvent`,
`BlockPhysicsEvent`, `ChunkLoadEvent` and entity movement are the ones. They are not even captured
by default, so if you genuinely need them you also have to enable `capture-high-frequency` in
`config.yml`.

## Logs — found by pattern

```
logs_query(level="WARN", pattern="Timer|lag")
```

`level` is a **minimum** severity. `pattern` is a Java regular expression matched against the
message.

**There is no `logs_tail`.** "The last N lines" spends the whole budget on join messages on a busy
server. Search for what you are looking for.

## Exceptions — read the groups, then dig into one

```
exceptions_recent(limit=10)        # no stacks; occurrence count + first-seen time
exceptions_recent(hash="...")      # that one, with its full stack
```

The same exception ten thousand times is one line. That is why counting and reading are separated.

## Asking for the current state — `state_query`

Ask the server instead of inferring from events. **Confusing test results are almost always a
disagreement about state.**

```jsonc
{"kind": "player", "target": "Tester1", "permissions": ["essentials.fly"]}
{"kind": "block",  "world": "world", "x": 10, "y": 63, "z": 20}
```

A `kind="player"` response carries `name`, `uuid`, `online`, `address`, `gameMode`, `op`, `world`,
`x`/`y`/`z`, `permissions`.

- `uuid` — what permissions key on. A bot's is derived from its name, so it is the same every time
- `address` — the IP the server attributed to this connection. For a bot that is the injected value,
  which makes this **the only place to confirm the injection worked**
- `permissions` — answers only what you asked. Permissions cannot be enumerated, only tested

## Reading a menu GUI — `state_query kind="inventory"`

**This is the only way to read the contents of a menu a plugin opened.** Those items exist only in
the virtual inventory held by the open view — not in the player's NBT, not in an event payload.
`/data get entity` will not show them either.

```jsonc
{"kind": "inventory", "target": "Tester1"}                     // the open menu
{"kind": "inventory", "target": "Tester1", "which": "player"}  // their own inventory
```

```jsonc
{
  "view": "CHEST",          // CRAFTING / CREATIVE / PLAYER = no menu open
  "title": "§aShop",
  "size": 27,
  "occupiedSlots": 2,
  "items": [
    {"slot": 11, "material": "EMERALD", "amount": 1,
     "displayName": "§aBuy", "lore": ["§7Costs 10"], "enchanted": false,
     "customModelData": 1,
     "modelData": {"floats": [1.0], "flags": [], "strings": ["icon_a"], "colors": ["#FF8800"]}}
  ],
  "truncated": false
}
```

### CustomModelData — two forms

A resource-pack menu distinguishes its icons with this. Same material and same name can still be a
completely different button, so **checking only material and name misses icon bugs.**

| Field | What |
|---|---|
| `customModelData` | The integer form. What `setCustomModelData(1)` put there |
| `modelData` | The component added in 1.21.4 — `floats` / `flags` / `strings` / `colors` |

**They are two views of the same thing.** On 1.21.8, `setCustomModelData(1)` actually writes
`floats: [1.0]`.

**`customModelData` is lossy.** It truncates the component's first float to an integer, so `2.5`
arrives as `2` — meaning a `2.0` button and a `2.5` button are indistinguishable through it. And
**string keys are invisible to it entirely.** String keys are what modern packs mostly use, and the
integer view makes them look absent.

Check `customModelData` if you set an integer, and `modelDataString` if you use string keys:

```json
{"slot": 7, "material": "PAPER", "customModelData": 1}
{"slot": 8, "material": "PAPER", "modelDataString": "icon_a"}
```

- **Empty slots are omitted.** A 54-slot menu is mostly air, and listing it all only eats budget.
  `size` and `occupiedSlots` describe the whole thing, so "slot 22 is empty" is still knowable — if
  it is not in the list, it is empty
- **Colour codes are preserved in `§` form.** Whether a menu rendered correctly includes its
  colours. Ignore them if you want to; strip them and you cannot get them back
- A `view` of `CRAFTING` / `CREATIVE` / `PLAYER` **means no menu is open.** Creative shows
  `CREATIVE` — all three mean "their own screen"

## What the server cannot see — `bot_inspect`

`state_query` reads the **server-side** Bukkit inventory. But a plugin drawing its GUI with
ProtocolLib or packetevents leaves the server inventory empty and **sends item packets to the client
only.** Then:

```
state_query  →  occupiedSlots: 0   ← the server believes it is an empty chest
the real player →  a full menu      ← only the client received it
```

`bot_inspect` returns **what the bot's client was actually sent**:

```jsonc
{
  "menu": {"containerId": 1, "title": "Shop"},
  "items": [
    {"slot": 7, "itemId": 983, "amount": 1, "name": "Test",
     "customModelData": "1.0", "lore": "line one | line two"}
  ],
  "messages": [
    {"sequence": 7, "timestamp": 1787540400123, "text": "multiplayer.player.joined"},
    {"sequence": 8, "timestamp": 1787540400523, "text": "[action bar] You lack permission"}
  ],
  "messageCursor": "messages/7b46f73d:9",
  "messagesDropped": 0,
  "bossBars": [{"title": "Event ends in 4:12", "progress": 0.7, "color": "PURPLE"}],
  "scoreboard": {"title": "Server", "lines": ["Money: 1,200", "Region: spawn"]},
  "health": 20.0, "food": 20,
  "experienceLevel": 3, "totalExperience": 27, "experienceProgress": 0.4,
  "effects": ["speed:1:120"]
}
```

**Items come back with names.** Use `state_query` when you need the server-side inventory — but
only when the server really holds that inventory. Name, lore and CustomModelData arrive as
components, so both sides show them.

## A live view — `bot_view`

`bot_view` starts an optional localhost-only viewer for a connected bot. It does not start a second
Minecraft client or expose a port beyond `127.0.0.1`:

```json
{"name":"Tester1","what":"world","mode":"first_person"}
{"name":"Tester1","what":"inventory"}
{"name":"Tester1","stop":"true"}
```

The same bot reuses its URL. The world view uses the optional prismarine asset; the inventory view
is a lightweight live page showing the open menu. A session reset or bot despawn closes the port.
An installation that never calls `bot_view` does not install or download the viewer asset.
The current npx native viewer asset is Windows x64; on Linux or macOS, point
`VITAMINMCP_VIEWER_PATH` at a local sidecar.

### The rest of the screen

Everything a server draws on a player that never reaches the server's own view is here.

| Field | What |
|---|---|
| `messages` | timestamped chat as received, plus action bar, title and subtitle with a location prefix |
| `messageCursor` / `messagesDropped` | where this bot's message stream stands, and records permanently lost from the requested window |
| `bossBars` | boss bars on screen now, with `progress` (0..1) and `color` |
| `scoreboard` | the sidebar: `title` and `lines`, highest score first — the order the client draws |
| `health` / `food` | current client-side health and hunger values |
| `experienceLevel` / `totalExperience` / `experienceProgress` | the level, total points and progress bar the client received |
| `effects` | active effects as `name:amplifier:duration` strings |

The split is deliberate. **Messages are things that were said; boss bars and scoreboards are things
that are showing.** A refusal is a message and is gone a moment later; a scoreboard holds a
player's live state — money, region, quest progress — for as long as they are online, and asking
"what does it say now" is a different question from "what was I told".

Chat is returned as received, with no location prefix. The `[action bar]`, `[title]` and
`[subtitle]` prefixes matter because "above the hotbar, briefly" and "in chat, persistently" are
different claims about what a person would actually notice, and a test asserting on a refusal
usually cares which.

Only the sidebar slot is followed. The player-list and below-name slots hold numbers rather than
the lines anyone writes a test about.

**Lines come back as the player reads them**, which takes some assembling. A sidebar line's
"entry" is a key, not text: servers register one blank-looking entry per line — a colour code,
since entries have to be unique — and put the words in that entry's *team* prefix and suffix. Read
the scores alone and a fifteen-line scoreboard arrives as `["§e", "§d", "§c", …]`, which looks like
data and is not. The prefix and suffix are joined back on, and the entry's formatting codes
dropped, so what you get is the line as drawn. Blank spacer lines stay blank rather than
disappearing, because their position is part of the layout.

## What the server told the player — `messages`

**A plugin's refusal is usually one message and nothing else.** No exception, no console log, no
event. So from the agent's side alone, *"refused for lack of permission"* and *"silently did
nothing"* look identical.

Check `bot_inspect`'s `messages`, or use the scenario step:

```json
{"action": "assert_message", "bot": "Tester1", "contains": "permission"}
```

Each message has a monotonically increasing `sequence`, the `timestamp` when it reached the bot's
client in epoch milliseconds, and its `text`. At most 100 messages are retained per bot. To isolate
the reply to one action, call `bot_inspect` immediately before it and save `messageCursor`, then
pass that value as `cursor` on the next call. The second call returns messages from that position
onward while the menu, boss bars, scoreboard and other fields still describe the current screen.

```text
bot_inspect({"name":"Tester1"})
→ save "messageCursor":"messages/7b46f73d:9"

run the command or interaction

bot_inspect({"name":"Tester1","cursor":"messages/7b46f73d:9"})
→ "messages":[{"sequence":9,"timestamp":1787540400923,"text":"reply"}]
```

`messageCursor` is opaque and always returned, including when no message matched. Do not construct
it from the bot name or interpret its stream id. It belongs to this live bot stream: another
session or runner, a runner restart, and even a replacement bot with the same name all have a
different id and reject the stale cursor. `messagesDropped` counts messages requested by the cursor
that have already fallen out of the 100-message window; a nonzero value means the answer is
incomplete and paging cannot recover those records.

## Response budget

Every query tool has a cap (200 records / 50KB by default). The exact value is stated in each tool's
description. When output is cut, the response says so:

- `truncated: true` — cut for budget
- `nextCursor` — pass this as `cursor` to continue
- `dropped` — records **gone for good** from the ring buffer. Continuing will not recover these

---

# B. Testing with bots

## `session_start` — always first

**For a server on this machine, it takes no arguments at all.**

```jsonc
{}
```

The agent writes its host, both ports and its token to `~/.vitaminmcp/agents/<port>.properties`
while it is running, and this reads them. The response says which under `resolvedFrom`, so a
session that connected to something unexpected says so rather than looking like a working one.

Anything passed wins over the file, so a detail that differs is the only one worth writing:

```jsonc
{
  "port": 25577,          // a proxy in front of the Minecraft port the agent knows about
  "mcpPort": 25585        // which agent, when several run here
}
```

With more than one agent on this machine and no `mcpPort`, this is an error that lists them.
A proxied network is several servers and there is no right guess between them.

Omit `runnerJar` and it looks for the runner next to `mcp-server.jar`, or wherever
`VITAMINMCP_RUNNER_JAR` says. There is one, whatever versions are supported: it carries a backend
per protocol and picks the right one by asking the server what it speaks, so there is nothing here
to get wrong. Installed through npm, it may still be downloading — the call waits for it rather
than failing, and only a call that needs bots waits at all.

**For a server on another machine** none of that applies: a token minted here says nothing about a
server elsewhere and is not sent there, so `host` and `token` are required. The agent prints a
block to paste, in its startup log:

```jsonc
{
  "host": "203.0.113.10", "mcpPort": 25585, "tls": "true",
  "token": "YLwNyFij...",
  "tlsFingerprint": "sha256:ffb61d8f...f163",   // when self-signed
  "port": 25565
}
```

`tlsFingerprint` pins trust to **that one certificate**. Nothing has to be installed on the client,
and it is not the same as turning verification off — it is narrower than CA verification (a CA
vouches for everything it signs; a fingerprint vouches for one certificate). Omit it for a real
certificate.

Call `server_info` once right here. If the host, port or token is wrong, this is where it says so,
instead of blowing up inside some unrelated tool later. The response also returns the current
session roster; sessions whose bot runner has exited are removed before that list is returned.

The response also carries `agentTools` — **the real parameters of the proxied tools.** mcp-server
publishes its tool list at startup, when no agent is attached yet, so it cannot state their
parameters there. Even *which* tools exist depends on the agent (read-only means no `command_exec`
at all). So the definitions come from the side that has the implementation.

When calling a proxied tool, **pass parameters flat, at the top level.** Do not wrap them:

```jsonc
{"kind": "player", "target": "Tester1"}                     // ✓
{"arguments": "{\"kind\":\"player\"}"}                       // ✗
```

`session_reset` disconnects every bot while keeping the connection. **Call it between independent
tests** or one inherits the previous test's players.

> It does not roll back world state. A scenario that depends on the world has to create that state
> itself.

## Several servers at once — a proxied network

A BungeeCord network is several servers, each with its own agent. Open one session per server and
they coexist; starting one never disturbs another, which matters because **closing a session
disconnects its bots.**

```jsonc
session_start {"session": "lobby",    "port": 25577, "mcpPort": 25585, "token": "..."}
session_start {"session": "survival", "port": 25577, "mcpPort": 25586, "token": "..."}
```

`port` is the **proxy's** port in both — that is where a real player connects, and bots are real
players. What distinguishes the sessions is `mcpPort`: the agent inside each backend server.

Every other tool then takes `session`:

```jsonc
bot_spawn   {"session": "lobby", "name": "Tester1"}
command_exec {"session": "lobby", "command": "send Tester1 survival"}
state_query  {"session": "survival", "kind": "player", "target": "Tester1"}
```

Omit `session` and it resolves only while **one** session is open. With several it is an error
naming them, rather than a guess — the sessions differ by which backend they observe, so picking
one for you would send a command to the wrong server and report success.

`session_reset {"session": "lobby", "close": "true"}` ends one session and frees the name. Sessions
each hold a bot runner process, so close the ones you are done with.

> Tool calls are served one at a time. Sessions **coexist**, but a 30-second spawn on one blocks a
> call to another until it returns.

## Driving it step by step

```
bot_spawn {"name": "Tester1"}
```

Offline authentication is the default. The name is the identity and the UUID derives from it, so
`Tester1` is the same player today as yesterday and permission-dependent behaviour reproduces. The
response is the handle name, actual player name, UUID and where it landed.

Omit `clientIp` for an ordinary login. If the test needs the server to attribute the connection to
a chosen address — IP bans, per-IP connection limits or geo logic — pass `clientIp` and set the test
server's `spigot.yml` `settings.bungeecord` to `true`; that opts into the forwarding handshake.

For an `online-mode=true` server, authenticate a dedicated Microsoft account instead:

```json
bot_spawn {"name":"RealProfileName", "auth":"microsoft", "account":"qa-primary"}
```

On the first call, open the returned device-login URL and enter its code. Authentication continues
in the runner; after completing it, repeat the same `bot_spawn` call. `account` is a local token
cache key and defaults to `name`. Cached tokens live under `~/.vitaminmcp/accounts`, or the
directory named by `VITAMINMCP_ACCOUNTS_DIR` when the MCP server started. No password or access
token belongs in a tool call. `clientIp` forwarding is available only to offline bots.

Both bot modes are rejected while the agent is `read-only: true`; enable writes and restart the
server before spawning or running a scenario.

After that, use the proxied agent tools directly. `wait_for`, `state_query` and `events_query` all
go to the session's server — the only one open, or the one `session` names.

## All at once — `bot_run_scenario`

A scenario is a JSON array of steps. **It stops at the first failure** — running later steps from a
state the scenario never described makes those failures meaningless.

```json
[
  {"action": "spawn",        "bot": "Tester1"},
  {"action": "console",      "command": "op Tester1"},
  {"action": "assert_player","bot": "Tester1", "op": true},
  {"action": "break_block",  "bot": "Tester1", "x": 10, "y": 63, "z": 20},
  {"action": "wait_for",     "condition": "block_is", "x": 10, "y": 63, "z": 20,
                             "material": "AIR"},
  {"action": "assert_event", "eventType": "BlockBreakEvent", "player": "Tester1"}
]
```

### Step reference

| action | Required | Optional |
|---|---|---|
| `spawn` | `bot` | `clientIp` |
| `despawn` | `bot` | |
| `move_to` | `bot`, `x`, `y`, `z` | `mode`: `path` (default) or `teleport`; `timeoutMillis` (or `timeout`) for path movement |
| `break_block` | `bot`, `x`, `y`, `z` | |
| `use_block` | `bot`, `x`, `y`, `z` | `face` (default `UP`). Waits for the target block to reach the client, then right-clicks it |
| `use_entity` | `bot`, `x`, `y`, `z` | `radius` (default 2), `entityType`. Right-click the nearest entity — an NPC, a villager |
| `attack_entity` | `bot`, `x`, `y`, `z` | `radius` (default 2), `entityType` |
| `hold_item` | `bot`, `slot` | hotbar slot 0..8 |
| `drop_item` | `bot` | `count` (default: whole held stack) |
| `place_block` | `bot`, `x`, `y`, `z` | `face` (default `up`); places the held item against the reference block |
| `jump` | `bot` | |
| `sneak` / `sprint` | `bot` | `state`: `on` or `off` |
| `look_at` | `bot`, `x`, `y`, `z` | |
| `assert_reachable` | `x`, `y`, `z` | `bot` (optional existing bot), `reachable` (default `true`), `timeoutMillis` |
| `command` | `bot`, `command` | a command typed by the bot |
| `chat` | `bot`, `message` | |
| `console` | `command` | a command typed by the console (via `command_exec`) |
| `click_slot` | `bot`, `slot` | `click`: `left` (default) / `right` / `shift_left` / `shift_right` |
| `close_menu` | `bot` | |
| `wait_for` | `condition` | per-condition parameters, `timeoutMillis` |
| `assert_block` | `x`, `y`, `z`, `material` | `world` (default `"world"`) |
| `assert_player` | `bot` | `online`, `gameMode`, `op`, `timeoutMillis` |
| `assert_event` | `eventType` | `player`, `sinceSequence`, `timeoutMillis` |
| `assert_inventory` | `bot` | `title`, `size`, `which`, `slots[]` (below) |
| `assert_message` | `bot`, `contains` | whether what the server told that bot contains this string |

Entries in `assert_inventory`'s `slots[]`:

| Field | Meaning |
|---|---|
| `slot` | which slot to check (required) |
| `material` | expected item. Case-insensitive |
| `name` | whether the display name contains this string. **Compared with colour codes ignored**, so `"Buy"` matches `§aBuy` |
| `amount` | count |
| `lore` | whether this string appears somewhere in the lore |
| `customModelData` | integer CustomModelData |
| `modelDataString` | whether the component's `strings` contains this value (for string-key packs) |
| `empty` | `true` requires the slot to be empty |

A few things to know:

- **`console` calls `command_exec`.** With the agent on `read-only: true`, this step fails.
- **`assert_player` waits rather than reads.** Values like `op` change asynchronously — `/op` takes
  effect only after the name resolves to a UUID, so reading immediately races the previous command
  and fails for the wrong reason.
- **`assert_event`'s reference point is the start of the scenario.** Leave out `sinceSequence` and
  that is automatic. The thing you are verifying usually happened in the previous step, not after
  this one.
- The default wait is 15 seconds; the `wait_for` tool's own default is 10.

---

# B-2. Testing a menu GUI

The flow is: type a command, a menu opens, check it was drawn correctly.

```json
[
  {"action": "spawn",    "bot": "Tester1"},
  {"action": "command",  "bot": "Tester1", "command": "shop"},
  {"action": "wait_for", "condition": "inventory_open", "name": "Tester1", "title": "Shop"},
  {"action": "assert_inventory", "bot": "Tester1", "size": 27, "slots": [
      {"slot": 11, "material": "EMERALD", "name": "Buy",   "lore": "Costs 10"},
      {"slot": 15, "material": "BARRIER", "name": "Close", "amount": 3},
      {"slot": 13, "empty": true}
  ]},
  {"action": "click_slot", "bot": "Tester1", "slot": 11},
  {"action": "assert_event", "eventType": "InventoryClickEvent", "player": "Tester1"},
  {"action": "close_menu", "bot": "Tester1"}
]
```

**Do not leave out the `wait_for`.** A menu does not open synchronously with the command — the
plugin may take a tick, or wait on a database. Read immediately and you read the player's own
screen and report "the menu is empty", which has the same symptoms as a menu that failed to fill.

If the plugin **opens the menu first and fills it later**, `inventory_open` is not enough. Wait for
the button itself:

```json
{"action": "wait_for", "condition": "inventory_contains",
 "name": "Tester1", "material": "EMERALD", "slot": 11}
```

On failure you get what was actually there:

```
slot 11 expected DIAMOND but held EMERALD
slot 11 is empty, expected DIAMOND
expected the title to contain 'Shop' but it was '§cError'
no menu is open for Tester1 — the view is CREATIVE. If a command should have opened one,
wait_for inventory_open first.
```

## Right-clicking an NPC

A shop or quest giver on an NPC reacts to `PlayerInteractEntityEvent` and to nothing else. Standing
next to it and running its command is a different code path, and it is the path a right-click bug
hides behind.

```json
{"action": "use_entity", "bot": "Tester1", "x": 120, "y": 64, "z": -40,
 "entityType": "PLAYER", "radius": 2},
{"action": "wait_for",   "condition": "inventory_open", "name": "Tester1", "title": "Shop"}
```

**The NPC is named by where it stands.** The protocol addresses entities by a numeric id the server
invents, which never leaves the connection, so there is nothing stable for a scenario to have been
written against. The runner tracks what the server spawned for this bot and picks the nearest match.

- `radius` defaults to 2 and should stay small. A generous radius does not fail when the
  coordinates are wrong — it quietly right-clicks a different entity.
- `entityType` narrows the match, and is worth setting for a Citizens NPC: those are `PLAYER`
  entities, and a `PLAYER` filter skips the mobs that may be standing around it.
- **The bot has to be close enough to have been sent the entity.** Outside its view distance the
  server never spawns it client-side, so it cannot be found however right the coordinates are.
  `move_to` first if the NPC is far from where the bot spawned.

When nothing matches, the failure lists what is actually nearby rather than only saying no:

```
no PLAYER within 2.0 blocks of 120.0 64.0 -40.0. Nearby: VILLAGER at 121.5 64.0 -39.5
(1.6 away); PLAYER at 118.0 64.0 -44.0 (4.5 away)
```

That distinguishes wrong coordinates from a radius too tight from an NPC that was never in view.

> `INTERACT_AT` and then `INTERACT` are sent, which is what a vanilla client sends for one right
> click. Paper turns them into `PlayerInteractAtEntityEvent` and `PlayerInteractEntityEvent`, so a
> bot's right click produces the same pair of events a player's does. Anything less does not: NPC
> plugins are commonly driven by the `AT` variant or by a packet listener expecting it, and sending
> only `INTERACT` fires an event while the NPC does nothing.

## Opening a chest directly

To open a container GUI without a plugin, right-click it with `use_block`.

The runner waits until the target block is present in the bot's client world before sending the
interaction packet. This matters when a preceding console command just placed or changed the
block; the server can answer that command before its block update reaches the client.

```json
{"action": "console",   "command": "setblock 10 64 20 chest"},
{"action": "use_block", "bot": "Tester1", "x": 10, "y": 64, "z": 20}
```

> **A chest will not open with an opaque block directly above it.** That is a game rule and not a
> problem with this harness, but the symptom looks like "the menu code is broken". Clear the block
> above to `air` first.

---

# C. `wait_for` — why not to sleep

**There is no `sleep` step, and there will not be one.** Have it and it will get used — it is the
shortest way past a timing problem — and every scenario that used it is calibrated to the machine of
whoever wrote it. Right on an idle server, wrong on a busy one. That is the entire mechanism by
which flaky tests are made.

Instead, **name the thing you are waiting for.** The agent checks every tick inside the server and
answers the moment it becomes true. One request, and nothing can slip through between two polls.

| condition | Parameters |
|---|---|
| `ticks` | `count` |
| `block_is` / `block_is_not` | `material`, `x`, `y`, `z`, `world` |
| `event` | `eventType`, `player`, `sinceSequence` |
| `player_online` / `player_offline` | `name` |
| `player_near` | `name`, `x`, `y`, `z`, `distance` |
| `player_state` | `name`, plus whichever of `online` / `gameMode` / `op` to check |
| `inventory_open` | `name`, `title` (substring, colour ignored) |
| `inventory_contains` | `name`, `material`, `slot`, `which` |
| `log_matches` | `pattern` (Java regex), `level` (minimum severity, optional) |

**`log_matches` is for work that changes nothing you can see.** A plugin loading a player's data
asynchronously is the usual case: no block moves, no menu opens, no event you can name fires — the
only signal it finished is the line it logs. Without this the alternative is `ticks`, which is a
sleep wearing another name, and it will be calibrated to whichever machine wrote it.

```json
{"action": "wait_for", "condition": "log_matches",
 "pattern": "Loaded affinity player data for 889dcaa5", "timeoutMillis": 15000}
```

Only lines written **during the wait** count, so a match is something that just happened rather than
a leftover from the previous run.

`timeoutMillis` defaults to 10000, capped at 60000.

**A timeout does not come back empty-handed.** A snapshot of the events and logs from that moment
rides along, and the reason is usually in there.

---

# D. Changing the server — `command_exec`

It has to be `read-only: false` in `config.yml` before it **appears in the tool list at all.** It is
not restricted; it is absent.

```jsonc
{"command": "give Tester1 diamond 1", "as": "Tester1"}   // omit `as` for the console
```

`dispatched` in the response means "a handler accepted it", not that it succeeded. **The real answer
is in `output`** — plenty of commands succeed formally while reporting failure in their output.

**`dispatched: false` always carries a `reason`**, because on its own it is indistinguishable from a
command that ran and did nothing — both are `false` with an empty `output`. The reason says which of
the two things happened:

```jsonc
// a vanilla command as a player who is not op
{"dispatched": false,
 "reason": "Nothing ran: 'list' was refused before it executed, because Tester1 does not have
            minecraft.command.list. ..."}

// a command that does not exist
{"dispatched": false,
 "reason": "Nothing ran: this server has no command named 'lst'. ..."}
```

**`as` runs vanilla commands too.** `/list`, `/say`, `/tp` and the rest are matched by the server's
own dispatcher against that player's permissions rather than by a plugin, so this is how you test a
permission check on one. **Whether a non-op is refused depends on the Paper version** — measured
across the matrix, 1.21.8 gates `/list` behind `minecraft.command.list` and refuses, 1.21.1 does
not. Op the player, or use the console, where the command has to run regardless. Paper tells the
refused player nothing at all, not even "unknown command", which is why the reason above is
assembled by the agent rather than read out of the server's reply.

**A command run as a player answers that player, not the console**, so `output` is usually empty even
when it worked. The reply reached the client: read it with `bot_inspect`'s `messages`.

---

# E. Reading a failure

`bot_run_scenario` tells you which step died and why.

```jsonc
{
  "passed": false,
  "steps": [
    {"step": 1, "action": "spawn", "passed": true,  "detail": "Tester1 joined at ..."},
    {"step": 2, "action": "break_block", "passed": false,
     "detail": "...", "evidence": "events=[...] logs=[...]"}
  ]
}
```

`evidence` is the events and logs **from the moment of failure**. Ask separately later and the
server state has already moved on, so it is attached rather than left for another tool call.

When the cause is not visible there, dig in this order:

1. `state_query` — do the bot and the server agree about position, gamemode and permissions
2. `exceptions_recent` — is a plugin failing quietly
3. `server_info`'s `eventsDropped` — was it simply never seen

---

# F. Common mistakes

| Symptom | Cause |
|---|---|
| Bot connection refused with `did you forget to enable BungeeCord in spigot.yml?` | The server is not `online-mode=false` + `bungeecord: true` ([README](../README.md) §2) |
| `Microsoft login required` | Open the URL, enter the device code, finish login, then repeat the same `bot_spawn` call |
| Microsoft login owns a different profile | `name` must be the authenticated Minecraft Java profile name; keep `account` as the cache alias |
| Bot actions are unavailable in read-only mode | Set `read-only: false` in the agent config and restart; bot joins and actions change server state |
| `err startup ... unsupported server version` | The Node runner has no minecraft-data entry for what this server speaks. Add the version to the compatibility matrix only after a live verification. |
| Events are not captured | The type is on the high-frequency list. Name it in `types`, and enable `capture-high-frequency` if needed |
| `command_exec` is missing | `read-only: true` (the default). `session_start`'s `agentTools` lists the tools that actually exist |
| A proxied tool refuses with something like `... needs 'kind'` | Parameters were wrapped. Pass them flat, at the top level |
| `presented a certificate that no trusted authority signed` | Self-signed server. Pass the `tlsFingerprint` from the startup log |
| `did not present the pinned certificate` | The certificate was regenerated. Take the new fingerprint from the log |
| The menu reads empty | No `wait_for inventory_open`. Or check `view` — `CREATIVE`/`CRAFTING` means it never opened |
| The menu opened but `occupiedSlots: 0` | It may be a packet-drawn GUI. Use `bot_inspect` to see what the client received |
| A command silently does nothing | Check `bot_inspect`'s `messages` — the refusal is in there |
| A chest will not open | An opaque block sits directly above it (a game rule) |
| `use_entity` reports no entity there | Either the coordinates are off, or the bot is too far away to have been sent the entity at all. The failure lists what is nearby — `move_to` first if the list is empty |
| `click_slot` fails with `has no menu open` | Clicked before it opened. `wait_for inventory_open` first |
| The bot connected but nothing works | It has not landed. `bot_spawn` waits for that, but when driving manually the ground under it may still be air |
| It breaks from the second run onward | State from the previous run survived. Use `session_reset`, and if the scenario depends on the world, have it create that state |

---

Design rationale is in [design.md](design.md).

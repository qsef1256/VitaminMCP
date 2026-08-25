# VitaminMCP design notes

Design decisions and their reasons, for an MCP server plus protocol-bot test harness aimed at
Minecraft plugin automation.

This document holds the **why**. Rules and invariants are in `CONTRIBUTING.md`.

---

## 1. The problem this project solves

There are two goals, and they are not equally important.

1. **Automated plugin testing** — verify plugin behaviour from a real player's point of view,
   multiplayer scenarios included, by attaching several bots.
2. **Server observability** — expose logs, event history and exceptions over MCP so an LLM can query
   them directly. Useful for debugging production servers, not just test environments.

The second looks like a component of the first, but **its standalone value may be greater.** Drop
one plugin into a development server and "look at why it died last night" starts working. So the
agent plugin is designed from the start to stand on its own.

---

## 2. Driving the client: why not an NMS fake player

Injecting a fake `ServerPlayer` and a fake `Connection` inside the server looks simpler at a glance.
It is not adopted.

- `ServerPlayer` and `Connection` signatures change per version, so maintenance cost never stops
- A fake connection does not actually put packets on the wire. The login flow, inventory GUI
  synchronisation and plugin message channels all drop out of verification
- It misses exactly the areas where bugs concentrate: packet round-trips, and the state a client
  actually observes

Instead we use **bots that connect over the real protocol**. They take the real path from login
through packets, and the world state a bot observes *is* "what the player sees", which makes the
meaning of a test unambiguous.

The bot implementation is Node.js with mineflayer, minecraft-data and mineflayer-pathfinder. The
Java MCP side communicates with it only through the version-neutral runner protocol.

### Limits (know them going in)

Resource packs, client rendering and shaders cannot be verified this way. Only that the relevant
packet was sent.

---

## 3. Handling online mode

A server with `online-mode=true` will not accept an arbitrary bot, because of session verification.
Three layers of response.

### 3.1 Default strategy — offline player identity, forwarding opt-in

Plugin logic barely depends on online mode. Only four things actually differ.

| | online-mode=true | online-mode=false |
|---|---|---|
| Source of the UUID | issued by Mojang | MD5 of `OfflinePlayer:<name>` |
| textures property | signed by Mojang | absent |
| Connection encryption | on | off |
| Chat signing (1.19+) | can be enforced | absent |

For ordinary tests, the Node bot connects normally with `online-mode=false`. Reusing the same bot
name gives the same deterministic offline UUID, so a test does not need proxy forwarding merely to
keep a bot identity stable.

When a test specifically needs an arbitrary UUID, skin properties or a spoofed client address, it
opts into the forwarding handshake by passing `clientIp` and enabling BungeeCord parsing on that
test server.

Put the backend on `online-mode=false` with `settings.bungeecord: true` in `spigot.yml`, and
appending the following to the handshake packet's server address field is enough to inject an
arbitrary premium UUID and signed skin properties:

```
<host>\0<clientIP>\0<uuid>\0<properties-json>
```

It reproduces a premium environment without authenticating. Because the UUID can be fixed,
permission and LuckPerms tests become reproducible.

In an environment using Velocity modern forwarding there is an HMAC signature as well, but a test
environment holds the secret, so the same approach works.

### 3.2 When online mode itself is under test — our own Yggdrasil

Running `authlib-injector` with `Drasl` (a Yggdrasil-compatible API server) lets the server keep
`online-mode=true` and authenticate normally with self-issued accounts. Encryption, session
verification and profile signing all genuinely run, so things like chat signing get verified too.

**Careful**: launcher, client, server and bot must **all point at the same Yggdrasil API.** One
mismatch and authentication fails.

This path is introduced when it becomes necessary. It is out of the initial scope.

### 3.3 Real accounts — final smoke only

Mineflayer uses Microsoft device-code authentication for `online-mode=true` smoke tests. The first
spawn returns the URL and code while authentication continues in the runner; retrying the spawn
after sign-in reuses the private local token cache. The tool never accepts a password or access
token. Account cost and automation-flag risk still make this a dedicated-account, final-staging
path rather than the default for a large test matrix.

---

## 4. Supporting every version — per-version code (revised 2026-07-30)

> **Revised.** The original design inserted a ViaProxy translation layer. Raising the floor to
> 1.21.8 removed its premise, so it is dropped. The original argument is preserved in §4.3.

### 4.1 Why Via was removed

Via's value was "the bot knows one protocol, and Via upstream carries the rest". That calculation
holds when the supported range is wide, like 1.13 through latest.

With the floor at 1.21.8 the real matrix became a narrow band: **1.21.8 through latest.** There is
almost no protocol difference across it — 1.21.7 and 1.21.8, for instance, **share protocol number
772**. Putting a translation layer over a range with nothing to translate buys nothing and takes on
all of §4.3's risk.

### 4.2 What we do instead

**Write and maintain per-version code where versions actually differ.** Branch only at the points
where the protocol genuinely diverges; where it does not, one implementation covers the range.

- The bot asks the server for its protocol and selects the matching minecraft-data entry
- Servers are started natively (§15)
- When a version really diverges, that is when a branch gets written. No abstracting in advance

The `bot-via` module was left empty at the time of this revision and has since been removed
entirely. If a real demand for versions below the floor appears, Via gets re-examined then — and at
that point the cost of lowering the floor (§5.4) has to be counted too.

### 4.4 Historical: Java runner and per-protocol backends (retired in Stage 9)

> Everything in this section is the superseded Java/MCProtocolLib design. It is retained only to
> explain the migration and the decisions that led to the Node runner.

> **Revised.** §4.2 originally shipped one `bot-runner-<protocol>` **jar** per protocol, each its
> own process. The processes were right; the jars were not.

Two things went wrong with a jar per protocol.

- **The caller had to pick one.** `session_start` looked for a runner beside the server jar and
  refused to guess when it found more than one — correct, and unusable the moment a second protocol
  existed. Nothing in the install knew which one the server spoke.
- **The runner source was copied per version.** 1620 lines of it, most of which is not
  protocol-specific at all: bookkeeping of which bots exist, the scoreboard, the container, the line
  protocol's own switch statement.

What replaced it is **one `bot-runner.jar` carrying a backend per protocol as an embedded
resource.** At startup it pings the server — a status handshake needs no protocol library and has
not changed since 1.7 — reads the protocol out of the reply, and loads that backend in a
parent-last class loader.

**The isolation is unchanged in strength.** Two MCProtocolLib builds cannot share a *class path*,
because every build occupies the same package names. They can share a *process*: a class loader is
what separates them, and only one backend is ever loaded per process because a process serves one
server. The child process remains, and it is still the crash boundary and still the cleanup.

**Two boundaries, deliberately different.** The process boundary stays the text line protocol —
it has to be serialized anyway, and killing the process is what guarantees no protocol state
survives a `session_reset`. The class loader boundary is a Java interface, `bot.spi.BotBackend`,
because there is nothing to serialize and a type contract buys three things text cannot: the
dispatch stops being per-version and is compiled once, a backend that misses a command does not
compile, and the escaping question at that seam disappears.

**The SPI names no protocol library type.** That is not a restriction to design around — the line
protocol already carries every command as text, which proves a library-free signature exists for
each. A test asserts it, because the failure it prevents is a runtime linkage error rather than a
compile one.

**Backends share source and override by file.** `bot/backends/shared` is not a module: it has no
build of its own and is compiled *into* each backend, against that backend's library. A version
that differs drops its own copy of the file that differs, at the same path. Measured across 1.21 to
1.21.8, what actually differs is five small files — `PlayerSync`, `SessionFactory`, `EntitySync`,
`ItemText`, `BlockUse` — and 1.21.5 onward needs none of them.

An interface with a subclass per version would work too, inside a library band. It is not used
because it requires deciding the seams in advance and materialising every seam in every backend,
including the ones where nothing differs; under file override a backend that differs nowhere is an
empty directory. Promote a seam to a base class when two backends have actually diverged at the
same point — that is §4.2 applied one level down.

### 4.3 The original argument (preserved)

Via translation is not lossless. It can mask packet-level bugs specific to an old version, or
introduce problems that were not there. So even the original design assumed "the two or three
versions we actually guarantee get double-checked natively". Now that **the entire range is
native**, that risk disappears wholesale — the largest side benefit of dropping Via.

---

## 5. Supported floor: 1.21+ (third revision 2026-08-01)

> **Revision history.**
> 1. Originally **1.13**. The judgement was that flattening (the `Material` enum and `ItemStack`
>    data handling) is the real boundary, which is still true in itself. But **a live startup showed
>    that the JVM constraint bites first** (§5.1).
> 2. → **1.18**. Dropped to Java 17 bytecode, giving up 1.13–1.17.
> 3. → **1.21.8**. See §5.2.
> 4. → **1.21** (current). See §5.6.

### 5.6 Why it went back down to 1.21

Bot backends for protocols 767 to 771 brought the matrix down to 1.21.1, and the agent passed on
every one of them — but only *empirically*. Compiled against `paper-api:1.21.8`, nothing stopped a
call to API those older servers do not have; the first sign would have been a `NoSuchMethodError`
on somebody's server.

Lowering `FLOOR` is what turns that into a compile error, which is what §5.4 said the mechanism was
for. It cost nothing in bytecode — 1.21 is already Java 21, so `release` stays 21 — and it found
exactly one offender: `ItemMeta.getCustomModelDataComponent()`, which is 1.21.4 API. That now goes
through reflection like `getTPS` beside it, so one jar still covers the range and still reports the
field on the versions that have it.

**The floor is where the agent is verified, not where the bots stop.** Below 1.21 the cost is §5.4's
table again, and nothing has asked for it.

### 5.1 Why 1.13 was impossible

A plugin jar loads into whatever JVM the server chose. Required JVM per Minecraft version:

| Server version | Required JVM |
|---|---|
| 1.13 – 1.16.5 | Java 8+ |
| 1.17 | Java 16+ |
| 1.18 – 1.20.4 | Java 17+ |
| 1.20.5+ | Java 21+ |

Put an agent compiled with Java 21 into Paper 1.13.2 and it dies like this:

```
UnsupportedClassVersionError: VitaminMcpPlugin has been compiled by a more recent
version of the Java Runtime (class file version 65.0), this version of the Java
Runtime only recognizes class file versions up to 55.0
```

So **Java 21 and a 1.13 floor were never compatible.** One of three had to be chosen:

1. Drop to Java 8/11 bytecode and keep 1.13 — giving up records, pattern matching and switch
   expressions entirely
2. Drop to Java 17 and floor at 1.18 — keeping records and instanceof patterns, giving up only Java
   21 switch patterns
3. Keep Java 21 — the floor rises to 1.20.5

The reasoning is the same one that picked 1.13 in the first place — cost against benefit:

- This agent barely touches `Material` or `ItemStack`. It reads event class names, player names and
  block coordinates. **The flattening argument that justified a 1.13 floor hardly applies to this
  module.**
- 1.13–1.17 servers are rare in practice and getting rarer
- Option 1's cost (stripping records from every module) is out of proportion to what it buys

### 5.2 Why 1.21.8 and not 1.18

Right after dropping to 1.18, it turned out the actual target in production is 1.21.8. The floor
follows.

Since 1.20.5 requires Java 21, **raising the floor to 1.21.8 removes the `--release 17`
constraint.** Everything given up in the move to Java 17 (switch pattern matching, record patterns)
comes straight back. So this revision trades supported range for the removal of a code constraint.

The cost of giving up 1.18–1.20.4 is zero unless there is a plan to use that range. If a plan
appears, go back to `--release 17` — all that is lost then is Java 21 syntax, and the place to
revert is the single `vitaminmcp.server-jvm-target`.

### 5.3 Consequences

- The `agent-legacy` adapter module is unnecessary in its entirety
- A single jar works across the whole supported range on the Bukkit/Paper API alone
- `io.papermc.paper:paper-api` compiles against the floor (1.21.8) — API added later is simply not
  on the classpath, so it cannot be used by accident
- Servers below 1.21.7 are out of scope
- `agent-*` and `contract` state `--release 21` **explicitly**. It currently equals the toolchain
  value but means something different: the toolchain is what we compile with, `release` is what the
  server can load. If the toolchain moves to 25, the agent stays loadable
- The remaining modules (bot, orchestrator, testkit, mcp-server) run on our JVM and are unaffected

### 5.4 To lower the floor again

Everything floor-related derives from **one place — `FLOOR` in
`build-logic/.../SupportedVersions.kt`.** The `paper-api` coordinate, `plugin.yml`'s `api-version`
and the `--release` value all come from it.

```kotlin
const val FLOOR = "1.21.8"   // the only line to change
```

The MC↔Java table is encoded, so **an impossible combination is rejected by the build.** Set
`FLOOR = "1.13.2"` and it derives `release 8`, then fails compilation with
`records are not supported in -source 8`. That combination used to build quietly and explode at
server startup with `UnsupportedClassVersionError`.

So the real cost of lowering the floor is only **removing syntax that Java version lacks**, and the
build says exactly how much of it there is.

| Floor | Derived release | What must go |
|---|---|---|
| 1.20.5+ | 21 | (nothing) |
| 1.18 – 1.20.4 | 17 | switch pattern matching, record patterns |
| 1.17 | 16 | the above plus sealed |
| 1.13 – 1.16.5 | 8 | **all records**, var, instanceof patterns — effectively a rewrite |

### 5.5 The remaining trap — what breaks on newer versions is not compilation

Raising the floor does not help: **forward compatibility still has to be maintained by hand.** The
canonical case is 1.21's `InventoryView`, which changed from an abstract class to an interface. A
direct call compiled against the older shape is frozen as `invokevirtual` and throws
`IncompatibleClassChangeError` on the newer version. The compiler catches nothing.

That is why `EventDetails` in `agent-core` calls only `PlayerEvent`, `BlockEvent` and `EntityEvent`
directly and routes the rest through reflection. Do not relax this because the floor went up.

**Measured (2026-07-29, Paper 1.21.8).** The inventory path was exercised for real — in a session
opening and clicking chests and crafting tables, `InventoryOpenEvent`, `InventoryClickEvent` and
`InventoryCloseEvent` all resolved their player correctly, with zero `IncompatibleClassChangeError`.
Those three call `getWhoClicked` / `getPlayer` reflectively, which is why the `InventoryView` change
does not reach them. The same code written as direct calls would have broken here.

---

## 6. Module structure

```
build-logic/         convention plugins
contract/            MCP tool schemas + DTOs. Pure Java, zero dependencies
agent/
  agent-core/        capture engine, state queries (Bukkit API)
  agent-mcp/         MCP server (the JDK's built-in HttpServer)
bot/
  bot-core/          runner handle, line protocol, handshake, server ping, and the
                     process boundary. No protocol library
  bot/bot-runner-node/ Node runner source and optional native SEA build
orchestrator/        native server startup / world reset / version matrix
testkit/             scenario runner, wait_for, assertions
mcp-server/          tool exposure + assembly (entry point)
```

### Why a monorepo

Bot, plugin and MCP share the same DTOs. Splitting the repository would create a permanent cost of
keeping the contract in sync.

### Dependency direction

```
mcp-server  → testkit → {bot-core, orchestrator, contract}
bot-core    → contract
agent-mcp   → agent-core → contract
```

The essential point is that **`mcp-server` does not compile against `agent-*`**. The agent is only
injected into a server as a jar at runtime, and the sole thing joining the two is `contract`. Hold
this and the agent can be split across versions without disturbing the modules above.

### shadow / relocate

`agent-*` runs as a plugin inside the server, so Netty, Jackson and Guava collide with the server
itself and with other plugins. **Relocate every dependency.** Netty especially — the server is using
it, and a missed relocation produces a crash that is hard to trace back.

---

## 7. The agent plugin as an MCP server

The agent **speaks MCP directly** rather than a bespoke RPC protocol. Two usage modes fall out
naturally.

- **Standalone mode** — install one plugin on a development or production server and let Claude
  connect directly. Almost no barrier to entry
- **Matrix mode** — the orchestrator bundles several servers' MCP endpoints behind tool namespaces
  and exposes them as one

### Transport

**The JDK's built-in `com.sun.net.httpserver`.** Bringing in Javalin or Undertow would grow the
relocation surface and bloat the jar. MCP streamable HTTP is fine on the built-in server, and with
zero dependencies the risk of collision disappears.

---

## 8. Event capture — volume is the biggest risk

This is where the design succeeds or fails.

`PlayerMoveEvent` fires around 20 times a second per player; `BlockPhysicsEvent` can fire thousands
of times per tick. Naively exposing `get_events()` **burns the context window immediately and leaves
the LLM useless.**

### Principles

1. **Capture everything, query from a whitelist.** High-frequency events (Move, BlockPhysics,
   ChunkLoad, entity movement) sit on a default-excluded list and come back only when explicitly
   requested
2. **Give the aggregate first.** Show counts by type via `events_summary`, then steer toward
   querying only the types that matter. This two-step structure is the core of it
3. **Nail a response token budget into the tool itself.** 200 records / 50KB by default, plus cursor
   pagination
4. **Ring buffer plus asynchronous serialization.** The MONITOR listener builds only a lightweight
   record and pushes it onto a lock-free queue; a separate thread serializes. Building JSON on the
   main thread kills TPS
5. **Expose a drop counter.** On ring buffer overflow the response must carry the drop count, or the
   LLM cannot tell that the data was truncated

### Implementing catch-all

Bukkit has no "subscribe to all events" API. The standard approach is scanning
`org.bukkit.event.Event` subclasses with ClassGraph and calling `registerEvent` dynamically.

- Register at `EventPriority.MONITOR` — observing the outcome after other plugins have handled it
- `ignoreCancelled = false` — **cancelled events matter most for debugging**

---

## 9. Log collection — no file tailing

Paper uses Log4j2. **Attaching a custom Appender** delivers level, logger name and throwable already
structured. The quality gap against regex-parsing a file is large.

### Exception grouping

Stack traces are stored separately; a list query returns only the first line and a stack hash. The
same exception repeating hundreds of times is common, so **folding it into `this exception ×342,
first seen at`** is what turns out to be most useful in practice.

The full stack trace is returned only on explicit request.

---

## 10. The MCP tool list

```
server_info()                              version, plugins, TPS, players online
events_summary(since, until)               counts by type ← always start here
events_query(types[], player?, cursor)     detail
logs_query(level, pattern, since, cursor)  regex search
exceptions_recent(limit)                   grouped exceptions ← most used in practice
state_query(kind, target)                  scoreboard / permissions / inventory
command_exec(cmd, as)                      console or player command (off by default)
```

### Design rules

- **No micro tools.** Dozens of fine-grained tools make an LLM worse, not better. Solve it with a
  parameter, and consider extending an existing tool before adding one
- **Never build `logs_tail(n)`.** Pattern search always beats "the last N lines", and a tail only
  consumes context
- **Put cursors in from the beginning.** `events_since(cursor)` / `logs_since(cursor)`. Adding them
  later means tearing everything apart

---

## 11. How bot scenarios are expressed

An `eval(js)` strategy is not available in Java. Of the two options, **the declarative action
sequence comes first.**

### Adopted: a declarative JSON DSL

```json
[
  {"action": "move_to", "x": 10, "y": 64, "z": 20},
  {"action": "click_slot", "slot": 3},
  {"wait_for": {"type": "inventory_contains", "item": "DIAMOND"}}
]
```

Easy for an LLM to generate, and on failure it shows plainly which step stopped.

### Escape hatch: a script engine

Embed Groovy or GraalJS in the bot context and handle only the complicated cases as scripts.

**Not included from the start.** It gets introduced once real cases that the declarative DSL cannot
cover have accumulated.

---

## 12. Determinism — tick synchronisation

Bot actions are asynchronous and the server runs at 20 TPS. Writing on top of `sleep(500)` produces
flaky tests.

- Put an **"advance N ticks, then respond" barrier** in the agent plugin
- Force every assertion through `wait_for(predicate, timeout)`
- Do not offer a fixed wait (sleep) in the scenario DSL — offer it and it will be used

---

## 13. State isolation

Reset the container and the world per test. Without it, failures nobody can trace accumulate.

- Keep a world template and restore it on every run
- Use fixed bot UUIDs — that is what makes permissions reproducible

---

## 14. Security

**Breach the MCP endpoint and console authority goes with it.** `command_exec` alone can grant op.
The design assumes installation on a production server.

- Default bind `127.0.0.1`; external exposure only through explicit configuration
- Token authentication is mandatory — with none configured, **refuse to start** (never warn and
  continue)
- **read-only is the default mode.** `command_exec` and other state-changing tools work only when
  explicitly enabled in config
- Do not relax any of these defaults for the convenience of a test

read-only mode alone is worth shipping independently. Do not blur that boundary.

### 14.1 Console activity log (2026-07-31)

Captured events and logs accumulate in **a buffer only the MCP client can read.** That made the
agent the one component on the server that operated without a trace — one line at plugin load, and
then nothing however much happened afterwards. Wrong default for software that can run console
commands.

It writes two lines per call. **One on arrival** (who, which tool, which arguments) and **one on
response** (how long it took, what came back). Two, because `wait_for` can hold a request for up to
a minute — log only on completion and the console is silent while it runs, leaving a stuck call
indistinguishable from no call at all.

Arguments and responses are truncated. The response budget is 50KB and the console is not where you
read it; the client already has the full payload.

Controlled by `activity-log: full | summary | off`. **Even at `off`, refused tokens and
state-changing tools are still logged.** Wanting a quiet console and giving up the record of what
the agent did to your server are different requests.

### 14.2 Self-signed certificates — revised to fingerprint pinning (2026-07-31)

The original policy was **"self-signed certificates are not supported"**, on the grounds that
supporting them *teaches every client to skip verification* — allow self-signed and clients end up
turning verification off, at which point having a certificate is pointless.

**That reasoning only holds when the client turns verification off.** Pin the fingerprint and it
inverts:

| | Trusts |
|---|---|
| Verification off | anything |
| CA verification | everything that CA signs |
| **Fingerprint pinning** | **that one certificate** |

Pinning is **narrower** than CA verification. So self-signed is allowed, but only reachable by
fingerprint.

- `session_start`'s `tlsFingerprint` — trusts that one certificate only
- With a pin, hostname verification is off. Name matching asks "was this issued for the host I
  typed", a pin asks "is this the exact certificate I was given" — the stronger question, and the
  only one a self-signed certificate can answer. Requiring both means matching SANs for no gain
- Connecting to a self-signed server without a fingerprint **fails.** "Skip verification" is not an
  option

**Why this changed.** A remote connection for a user without a domain took eight steps — keytool
twice, transferring the certificate, building a truststore, two `-D` flags on the MCP client. Four
of those came from the single problem of "make the client trust this certificate", and pinning
removes all four. The security properties are unchanged.

At startup the agent prints the block to paste into `session_start`
(host / port / token / fingerprint).

---

### 14.3 The token mints itself, and says where it is (2026-08-21)

**The rule is that the endpoint never opens unauthenticated. It was implemented as a refusal to
start, and those are not the same thing.**

What the refusal actually bought: a first start that fails, a token printed into a crash log, a
hand-copy into `config.yml`, a second start. Four steps to arrive at *a random secret nobody chose*
— which is what generating one produces directly. The secret is no weaker for having been written
by the plugin instead of pasted by a person; nobody was ever going to review it.

So an empty `auth-token` is now filled in and saved, and the start proceeds. The invariant is
unchanged and still enforced in `AgentSettings.validate()`: **no token, no endpoint.** Minting
happens before validation, and if the write fails it is skipped, so the refusal is still what
happens when a token cannot be had. An operator who sets their own token is unaffected.

**Why not hold the token in memory only.** It would change every restart, which makes it useless to
anything that has to be told it once, and it would never appear in the file an operator goes to
read. A token that cannot be looked up is worse than a token in a file that already had a place for
it.

**The handshake.** With the token generated rather than chosen, nothing on the client side knows it
— so the agent leaves it where a client on the same machine can look: host, both ports and token in
`~/.vitaminmcp/agents/<mcpPort>.properties`, written at startup and removed at shutdown
(`LocalHandshake`, in `contract` because it is by definition a thing two artifacts agree on).
`session_start` reads it, and takes no arguments at all for a local server.

This exposes nothing new. Anyone who can read that file is the user the server runs as, and that
user can already read `config.yml`, where the same token sits in the clear. Where the filesystem
can say so it is narrowed to `rw-------` anyway. `local-handshake: false` turns it off.

**Two limits, both deliberate.** The file is only consulted for a loopback host — a token minted
here says nothing about a server elsewhere, and quietly sending it there would turn a missing
argument into a leaked secret. And with several agents running, an unnamed `mcpPort` is an error
listing them rather than a pick: a proxied network is several servers, and guessing which one was
meant is how a test passes against the wrong backend.

---

## 15. Configuration and server startup (revised 2026-07-30)

The version matrix is `versions.yaml`, not code. Adding a version must be one configuration block
and nothing else.

```yaml
versions:
  - id: "1.21.8"
    paper: { version: "1.21.8", build: 60 }   # omit build for latest
  - id: "1.21.11"
    paper: { version: "1.21.11" }
```

**The protocol is deliberately absent from it.** The Node runner asks the server what it speaks
and selects the matching minecraft-data entry, so writing the number here would be a second place
for it to be wrong. A new version needs this block and a compatibility run.

### 15.1 Why not Docker (revised)

The original design used the `itzg/minecraft-server` Docker image. Dropped.

What startup actually needs is one thing: "bring up version X with a clean world", and that is
**download the jar from the PaperMC API and run it in a fresh directory.** Docker layers a daemon
and image layers on top, and when the development environment is Windows those layers are a WSL2 VM,
so world file I/O crosses a boundary and slows down. Measured, native startup is two to three
seconds.

Docker wins on exactly one point — parity with CI/Linux — and there is no such requirement now. If
one appears, add another implementation behind `ServerLauncher`. We do not build an abstraction
before there is a use for it.

### 15.2 Isolation

Keep a world template and restore it every run (§13). Native startup makes this simpler rather than
harder, because it is a directory copy. No Docker volume lifecycle to manage.

**This is genuinely needed.** During Stage 3 verification, a bot that an earlier diagnostic had
`op`ed persisted in `ops.json` and broke the scenario on its second run — exactly the "failures that
accumulate untraceably" that §13 warned about.

---

## 16. Distribution — how a stranger installs this (2026-08-21)

**The client half and the server half have opposite constraints, and one delivery mechanism was
being asked to cover both.**

The server half cannot be automated from here at all. It is a jar that goes in someone's `plugins/`
directory, on a machine this code may never see, and it stays a manual step. What it can stop being
is a *puzzle* — hence §14.3, and hence a `setup` MCP prompt: the client's own agent already has
shell access to wherever the server is, so the install is written down as instructions for it
rather than as a README section for a person. In Claude Code that surfaces as
`/mcp__vitaminmcp__setup`.

The client half was three jars, an absolute path and a `claude mcp add` line, none of which anyone
should be typing. It is now `npx -y vitaminmcp`.

### 16.1 Why npm, for a project with no JavaScript in it

The official MCP registry indexes npm, PyPI, NuGet, OCI and MCPB. **A jar on a GitHub release is
not one of them**, so "publish the jars and register them" was never available. Of what is:

| | Why not |
|---|---|
| MCPB bundle | No Java runtime type in the manifest spec; a `binary` bundle would have to carry a JVM per platform, and the artifact goes from 95MB to several hundred |
| OCI image | Requires Docker on the client machine to run an MCP server that already needs a JVM there. Two runtimes to have instead of one |
| PyPI / NuGet | The same wrapper, in a language even less related to this project |

npm wins on one thing the others do not have: `npx` is already how MCP clients launch local
servers, so the install line looks like every other install line. The package is a launcher and
nothing else — it finds a JDK, fetches the MCP server jar, and selects the Node runner.

### 16.2 The server jar is downloaded, not packaged

The Node runner source is staged separately and the Windows native fallback is a release asset, so
neither is mixed into the small MCP server jar or npm launcher.

So the package holds no jars; it downloads them from the release matching its own version, into
`~/.vitaminmcp/jars/<version>/`, and checks each against a SHA-256 **stamped into the package at
publish time**. That direction matters: a release asset can be replaced after the fact, a published
npm version cannot, so the immutable side is the one that gets to say what the bytes should be. The
checksum file names the version it was stamped for and the launcher refuses a mismatch, because the
one mistake this design invites — bumping the version without re-stamping — would otherwise
surface as a hash failure indistinguishable from a compromised download.

`mcp-server.jar` is small and nothing runs without it, so the launcher waits for it. With Node
available, no runner asset is downloaded; the Windows native asset is fetched only when Node is
not available and a bot session needs it.

The handoff is a file rename. Partial downloads use `.part`; `mcp-server` waits for the rename
rather than polling for a size, so it can never open a half-written asset.

**The native runner bundles only the supported versions of minecraft-data.** minecraft-data ships
every version of both editions — 426MB of JSON, 331MB of it Bedrock, which a Paper testing tool
can never reach. Its `data.js` reaches all of it through *static* `require()` calls hidden behind
lazy getters: free at runtime, but esbuild resolves each one while bundling and inlines the file.
That is how the runner asset went from an 88MB jar to a 564MB executable when the bot side moved to
mineflayer, and every user who has no Node installed downloads it.

`scripts/slim-minecraft-data.mjs` replaces the versions outside the supported line with a module
that throws, taking the asset to 134MB. The keep-set is **derived from `data.js`, not from
directory names**, because a version entry borrows files from older ones — 1.21.x reads out of
pc/1.16.1, pc/1.20, pc/1.20.2, pc/1.20.3 and pc/1.20.5 — so an obvious prune builds cleanly and
then fails on a bot that asks for a recipe. The supported line comes from `SupportedVersions.FLOOR`
rather than being written down a second time.

The server-list ping names its version for the same reason. It happens before anything knows what
the server speaks, so minecraft-protocol otherwise falls back to the newest version it has heard of
and loads that whole data set to send a handshake — which both re-introduced the largest single
version and made every startup pay for it.

### 16.3 Release order

Three publishes, none of them reversible, and each depends on the last:

1. **GitHub release** — the jars must be downloadable before anything can pin them
2. **npm** — pins those exact bytes; the registry checks the package exists and that its
   `mcpName` claims this server name
3. **MCP registry** — `server.json`, published with `mcp-publisher` under `io.github.Backas03/*`,
   which GitHub OIDC proves ownership of from CI

Every check that can fail runs before step 1. Nothing here can be unpublished.

---

## Open questions

- Naming rules for MCP tool namespaces (the per-server prefix format in matrix mode)
- Resource ceiling for multiple bot instances (how many concurrently is realistic — needs measuring)
- Criteria for deciding when to bring in our own Yggdrasil (3.2)
- Whether Via translation error can be detected automatically (currently native cross-checking, by
  hand)

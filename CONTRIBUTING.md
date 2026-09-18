# Contributing

**Thank you for being here.** Every contribution is appreciated — a pull request, a bug report, a
question that shows a document was unclear, or a note that something did not work on your server.
Small ones count: a typo fix, a sentence that reads better, a version this was never tried on.

There are very few rules. Open a pull request, describe what it does and why, and we will work
out the rest together. If something in this file blocks something reasonable, say so.

## Build and test

JDK 21 and Node/npm:

```bash
./gradlew build
cd bot/bot-runner-node && npm ci && npm test
```

`./gradlew build` needs no Minecraft server. Tests that need a real one are skipped unless asked
for:

```bash
./gradlew :testkit:test -Dvitaminmcp.liveServer=true -Dvitaminmcp.token=...
```

Paper 26.1 needs Java 25 while the build stays on 21, so pass `-Dvitaminmcp.serverJavaHome` when
the JDK that starts the server must differ from the one running the tests. A property only reaches
the test if it is listed in the module's `build.gradle.kts`; add a new one there too, or the live
test silently skips and the run looks green without having tested anything.

`./gradlew dist` assembles the three distributable artifacts into `build/dist/`.

## What the build checks for you

Two things are enforced by `./gradlew build`, so you will hear about them from Gradle rather than
from a reviewer:

- **Module dependencies flow one way** — `mcp-server → testkit → {bot-core, orchestrator,
  contract}`, `bot-core → contract`, `agent-mcp → agent-core → contract`. In particular
  `mcp-server` never compiles against `agent-*`; the agent is injected as a jar at runtime and
  the only thing joining the two is `contract`. The whitelist lives in
  [vitaminmcp.module-rules.gradle.kts](build-logic/src/main/kotlin/vitaminmcp.module-rules.gradle.kts).
- **`contract` has no external dependencies.** It is the shared vocabulary of two artifacts that
  ship separately, so anything it drags in, both sides inherit.

If a change needs either of these relaxed, open an issue first so the reason gets written down.

## Two things to keep in mind

- **Security defaults stay as they are.** The agent binds to `127.0.0.1`, never starts without a
  token, and is read-only until config says otherwise. Do not weaken any of those for the
  convenience of a test.
- **The version matrix is [versions.yaml](versions.yaml).** Adding a version means starting a
  server on it and confirming it works, not only editing the file. `CompatibilityLiveTest` is the
  gate. The protocol number is never written there; the runner asks the server.

## Where the reasons are

Why the project is built the way it is — the process boundaries, the Node runner, the response
budgets, the handshake trick that lets bots carry arbitrary UUIDs — is in
[docs/design.md](docs/design.md). When something looks arbitrary, it is usually explained there.

Commit messages in the `type(scope): subject` form are appreciated but not required. Write the body
for whoever hits the commit in `git blame` later.

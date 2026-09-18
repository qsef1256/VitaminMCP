# Publishing

Three things are published per release, in this order, and **none of them can be unpublished**:

1. the **GitHub release** — the agent jar, MCP server jar, five native Node runner assets and the
   optional Windows x64 viewer asset
2. the **npm package** `vitaminmcp` — the launcher, pinning the sha256 of those jars and assets
3. the **MCP registry** entry — `server.json`, pointing at that npm version

The order is forced. A package cannot pin bytes that are not downloadable yet, and the registry
rejects a `server.json` naming an npm package that does not exist or does not claim this server
name. Why it is built this way at all is [design.md §16](design.md).

[`.github/workflows/release.yml`](../.github/workflows/release.yml) does all three when a version
tag is pushed. What follows is the one-time setup it needs, and the first release, which is worth
doing by hand.

---

## One-time setup

### npm

The package name `vitaminmcp` is claimed by whoever publishes it first, so publish before
announcing anything.

**Publishing to npm needs a second factor, and the first publish is the awkward one.** npm requires
2FA to publish, no longer accepts new TOTP enrolments, and is restricting the tokens that used to
bypass 2FA. So:

- **A passkey or security key on the account.** Add one at
  `https://npmjs.com/settings/<user>/tfa`. On Windows, Windows Hello registers as one — no separate
  hardware key needed. An authenticator app is not an option any more
- **npm 11.5.1 or later, on Node 22.14 or later**, locally and in CI
- **The first publish is done by a person**, because the alternative to a token is trusted
  publishing and that cannot be configured for a package that does not exist yet

### After the first publish: trusted publishing

Set this up once and CI needs no npm credentials at all. On npmjs.com, package settings →
**Trusted Publisher** → GitHub Actions, with:

| | |
|---|---|
| Organization or user | `Backas03` |
| Repository | `VitaminMCP` |
| Workflow filename | `release.yml` — the name only, not the path |

The workflow already grants `id-token: write`, and the npm CLI prefers OIDC over a token wherever
it finds it. `NPM_TOKEN` stays as a fallback and can be deleted once a release has gone out this
way.

### Errors this produces, and what they actually mean

| What you see | What it is |
|---|---|
| `404 Not Found - PUT .../vitaminmcp` from CI | Not a missing package. npm answers an authorization failure with 404 so it does not disclose whether a package exists, and the one you are creating does not. The token cannot publish |
| `403 ... Two-factor authentication or granular access token with bypass 2fa enabled is required` | The honest version of the same thing, which you only get locally |
| `400 ... "otp" with value "[object Object]"` | An npm CLI bug on Windows: the one-time-password prompt passes an object through. Pass `--otp=<code>` on the command line instead, or upgrade npm |
| `404 ... Adding a new TOTP 2FA is no longer supported` | Register a passkey or security key instead |
| `400 ... NPM package ownership validation failed. Expected mcpName 'X', got 'x'` | From the MCP registry, not npm. `mcpName` is compared exactly, capitalisation included. A published npm version cannot be edited, so getting this wrong costs a patch release |

A granular token, if you use one at all, has to be scoped to **all packages** for a first publish.
Scoped to selected packages it cannot create one that is not in its list yet — which a new package
never is.

The workflow publishes with `--provenance`, which links the tarball to the workflow run that built
it. That needs `id-token: write`, which the workflow already declares.

### MCP registry

Nothing to register in advance. The namespace `io.github.Backas03/*` is proved by GitHub
authentication — interactively with `mcp-publisher login github`, and from CI with
`mcp-publisher login github-oidc`, which is why the workflow needs `id-token: write` for that too.

**The GitHub account's own capitalisation is part of the name.** The registry does not fold it, and
grants exactly `io.github.Backas03/*`, so a lowercase `server.json` is refused:

```
403 Forbidden — You do not have permission to publish this server.
You have permission to publish: io.github.Backas03/*
Attempting to publish: io.github.backas03/vitaminmcp
```

### npm ownership of the name

The registry checks that `npm/package.json` carries

```json
"mcpName": "io.github.Backas03/vitaminmcp"
```

which is what stops someone else's package from claiming this server name. Do not remove it.

---

## Cutting a release

### 1. Bump the version

The version lives in **one** place —
[`build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts`](../build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts).
Everything else copies it:

```bash
node npm/scripts/stamp-checksums.mjs --sync
```

That writes the version into `npm/package.json` and `server.json` (both places it appears there).
Commit all three files together. The release workflow refuses to build if any of them disagrees
with the tag, which is deliberate: a mismatch has to fail in the repository, not halfway through
publishing.

### 2. Tag it

```bash
git tag 2.0.0
git push origin 2.0.0
```

The workflow builds `dist`, creates the release with the agent/server jars, five native runner
assets and the optional Windows x64 viewer asset, then stamps the checksums from the
jars it just built, publishes to npm, and publishes `server.json`. Watch it — the first two steps
are irreversible before the third runs.

---

## The first release, by hand

Worth doing once rather than debugging the workflow against an unpublished name.

**Creating a release with `gh` pushes the tag, so the workflow runs too.** That is fine: each of
its three publish steps checks whether it has already happened and carries on rather than failing,
so whichever of you gets there second does nothing. The same guard makes a rerun safe after a
failure halfway down, which matters because nothing above the failure can be undone.

From a clean checkout at the commit you want released:

```bash
./gradlew dist
```

```bash
gh release create 2.0.0 --title 2.0.0 --generate-notes \
  build/dist/VitaminMCP.jar build/dist/mcp-server.jar \
  build/dist/runners/bot-runner-win-x64.exe \
  build/dist/runners/bot-runner-linux-x64 \
  build/dist/runners/bot-runner-linux-arm64 \
  build/dist/runners/bot-runner-darwin-x64 \
  build/dist/runners/bot-runner-darwin-arm64 \
  build/dist/assets/bot-runner-viewer-win-x64.tgz
```

```bash
cd npm && node scripts/stamp-checksums.mjs --tag 2.0.0 && npm publish --access public
```

`--tag` reads the hashes from the release you just created rather than from the jars on disk. Use
it whenever the release already exists: a shadow jar is not byte-reproducible, so the same source
built twice gives two different files, and only one of them is the one people will download.

`npm publish` asks for a one-time password here, which is why the first release is easier by hand
than through a token.

If npm succeeded but the registry step did not, finish it without cutting anything again:

```bash
gh workflow run Release --ref master -f version=2.0.0
```

Every step checks whether its own work is already done, so that publishes only what is missing.

Then install the publisher and claim the name. On Windows:

```powershell
Invoke-WebRequest -Uri "https://github.com/modelcontextprotocol/registry/releases/latest/download/mcp-publisher_windows_amd64.tar.gz" -OutFile mcp-publisher.tar.gz; tar xf mcp-publisher.tar.gz mcp-publisher.exe
```

```bash
mcp-publisher login github
```

```bash
mcp-publisher publish
```

`login github` opens a browser. `publish` reads `server.json` from the working directory; add
`--dry-run` first to validate without publishing.

Check it landed:

```bash
curl -s "https://registry.modelcontextprotocol.io/v0/servers?search=vitaminmcp"
```

---

## Checking the launcher without publishing

`npm/` can be exercised against a release that already exists, or against local jars:

```bash
node npm/bin/vitaminmcp.mjs --jars
```

Downloads what this version pins and prints where the jars went, without starting a server.
`VITAMINMCP_SERVER_JAR` and `VITAMINMCP_RUNNER_JAR` point it at jars you built instead, which is
how to run the launcher against unreleased changes:

```bash
VITAMINMCP_SERVER_JAR=$PWD/build/dist/mcp-server.jar \
VITAMINMCP_NODE_RUNNER=$PWD/npm/runner/runner.mjs \
  node npm/bin/vitaminmcp.mjs
```

`npm pack --dry-run` lists exactly what would be published. It should contain the launcher and
Node runner source, with no jars or platform binaries.

---

## What a user gets

| | |
|---|---|
| `claude mcp add vitaminmcp -- npx -y vitaminmcp` | from npm |
| the server's entry in a client's MCP catalogue | from the registry |
| `/mcp__vitaminmcp__setup` | from the server itself, once connected |
| `VitaminMCP.jar` for the Minecraft server | from the GitHub release, by hand or by that prompt |

The plugin stays a manual install. It goes on a machine none of the above can reach.

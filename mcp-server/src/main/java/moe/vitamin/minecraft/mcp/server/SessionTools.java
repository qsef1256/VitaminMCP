package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.ClientMessage;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.bot.spi.MenuItem;
import moe.vitamin.minecraft.mcp.contract.Cursor;
import moe.vitamin.minecraft.mcp.contract.LocalHandshake;
import moe.vitamin.minecraft.mcp.testkit.AgentClient;
import moe.vitamin.minecraft.mcp.testkit.ScenarioResult;

/** The tools this server exposes, and nothing else. */
final class SessionTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Agent tools passed straight through, in the order a caller usually needs them. */
    private static final List<String> PROXIED = List.of(
            "server_info", "events_summary", "events_query", "logs_query",
            "exceptions_recent", "state_query", "wait_for", "command_exec");

    /** Every open session, by name, in the order they were started. */
    private final java.util.Map<String, Session> sessions = new java.util.LinkedHashMap<>();

    ArrayNode listTools() {
        ArrayNode tools = MAPPER.createArrayNode();

        tools.add(tool("session_start",
                "Connect to a Minecraft server and its VitaminMCP agent. Call this first — "
                        + "every other tool needs it. Several sessions can be open at once, which "
                        + "is what a BungeeCord network needs: one per backend server, each with "
                        + "its own agent. Starting one never disturbs the others, so bots stay "
                        + "connected. Starting one that names the same server and agent as an open "
                        + "session replaces it. The response includes the server details, the real "
                        + "agent tool definitions and the current session roster; sessions whose "
                        + "runner process has exited are removed from that roster.",
                properties -> {
                    string(properties, "session",
                            "Name for this session, used by every other tool to say which server "
                                    + "it means — 'lobby', 'survival'. Defaults to "
                                    + "host:port@mcpPort.");
                    string(properties, "host",
                            "Server host. Omit for a server on this machine — the agent leaves "
                                    + "its host, ports and token where this tool reads them.");
                    number(properties, "port",
                            "Minecraft port bots connect to. On a proxied network this is the "
                                    + "proxy's port, since that is where a real player connects. "
                                    + "Omit for a server on this machine; 25565 otherwise.");
                    number(properties, "mcpPort",
                            "Agent's MCP port. Each backend server runs its own agent on its own "
                                    + "port, and that is what makes one session different from "
                                    + "another. Omit when only one agent runs on this machine; "
                                    + "name it to pick between several.");
                    string(properties, "token",
                            "The agent's auth-token from its config.yml. Omit for a server on "
                                    + "this machine — it is read from the agent's handshake, or "
                                    + "from VITAMINMCP_TOKEN. Required for a server anywhere "
                                    + "else, since nothing local can vouch for it.");
                    string(properties, "runnerJar",
                            "Path to a bot runner (Node script or platform executable). Optional: defaults to "
                            + "VITAMINMCP_RUNNER_JAR, or to the Node runner sitting "
                                    + "beside this server's own jar, which is where both "
                                    + "'gradlew dist' and the npm package put it.");
                    string(properties, "tls",
                            "'true' if the agent serves HTTPS. Required for any server that is "
                                    + "not on this machine — a remotely reachable agent refuses "
                                    + "to start without transport security.");
                    string(properties, "tlsFingerprint",
                            "SHA-256 of the agent's certificate, printed in its startup log. "
                                    + "Needed when the agent uses a self-signed certificate; "
                                    + "pins that exact certificate so nothing has to be "
                                    + "installed on this machine. Omit for a certificate signed "
                                    + "by a public authority.");
                }));

        tools.add(tool("session_reset",
                "Disconnect every bot, keeping the connection. Use between independent tests so "
                        + "one does not inherit the other's players. Pass close:true to end the "
                        + "session instead, which is the only way to release one you are done "
                        + "with.",
                properties -> {
                    session(properties);
                    string(properties, "close",
                            "'true' to close the session rather than reset it. Its bots "
                                    + "disconnect and the name becomes free again.");
                }));

        tools.add(tool("bot_spawn",
                "Connect a bot and wait until it is standing in the world with the ground "
                        + "beneath it loaded. Rejected while the connected agent is read-only. "
                        + "Offline auth is the default and derives the UUID from the name, so the same name "
                        + "is the same player every run and permission-dependent behaviour is "
                        + "reproducible — which also means THE SERVER REMEMBERS THEM. Inventory, "
                        + "position, advancements and anything a plugin stored against that UUID "
                        + "survive from earlier runs, so a bot you have used before is not a "
                        + "fresh player and 'it has the item' may be left over rather than just "
                        + "granted. Use an unused name when a first join is what is being tested, "
                        + "and clear what you left behind ('clear <name>' through command_exec). This means the CLIENT is ready, not that the SERVER will "
                        + "act on what the bot does: Paper drops a joining player's interactions "
                        + "for a few seconds, and plugins commonly hold them longer while they "
                        + "load that player's data. An action in that window is refused rather "
                        + "than lost — break_block says which — so check what it answered instead "
                        + "of assuming a spawn means ready.",
                properties -> {
                    session(properties);
                    string(properties, "name", "Bot name, at most 16 characters.");
                    string(properties, "clientIp",
                            "Optional spoofed address for the BungeeCord forwarding handshake. "
                                    + "Omit for a normal login; pass it only when the server has "
                                    + "bungeecord=true and the test needs an attributed IP.");
                    string(properties, "auth",
                            "offline (default) or microsoft. Microsoft authentication works with "
                                    + "online-mode=true. The first call returns a device login URL "
                                    + "and code; complete it and call bot_spawn again.");
                    string(properties, "account",
                            "Local cache key for a Microsoft account, defaulting to name. It may "
                                    + "be an email or a harmless alias and is never sent to the "
                                    + "Minecraft server. Reuse it to reuse the cached login.");
                }));

        tools.add(tool("bot_inspect",
                "What the bot's client was told, which the server cannot always be asked. Use "
                        + "when state_query reports an empty menu but a player would see a full "
                        + "one — a plugin drawing its GUI with packets leaves the server-side "
                        + "inventory empty. Also returns the messages the server sent this bot, "
                        + "which is where a refusal like 'you lack permission' appears; those "
                        + "never reach the console, so a declined command and one that did "
                        + "nothing look identical from the agent's side. 'items' IS THE OPEN "
                        + "MENU'S CONTENTS AND NOTHING ELSE — it is null when no menu is open, "
                        + "and never the player's own inventory, which is state_query "
                        + "kind='inventory' which='player'. Items are named the way the registry "
                        + "names them — 'minecraft:diamond_sword' — so they read the same as "
                        + "state_query's. 'messages' contains sequence, timestamp and text "
                        + "records. Chat text is returned as received; action bar, title and "
                        + "subtitle text is prefixed with where it appeared. Timestamp is when "
                        + "the text reached the client, in epoch milliseconds. At most 100 "
                        + "messages are retained per bot. To "
                        + "isolate one action's reply, call this before the command, save "
                        + "'messageCursor', then pass it back as 'cursor'. 'messageCursor' is "
                        + "always returned and is opaque: it belongs to this bot connection, so "
                        + "a cursor from another session, runner, or same-named replacement is "
                        + "rejected. 'messagesDropped' counts requested messages that fell out "
                        + "of the retained window, so a nonzero value means the answer is "
                        + "incomplete. Cursor filtering affects only messages; every other field "
                        + "is still the current client state. "
                        + "Also reports health, food, experience and active effects. "
                        + "'bossBars' and 'scoreboard' are on-screen state rather than messages: "
                        + "they persist, and a server's live view of a player — timers, money, "
                        + "region, quest progress — is usually drawn there and nowhere the agent "
                        + "can see.",
                properties -> {
                    session(properties);
                    string(properties, "name", "Bot name.");
                    string(properties, "cursor",
                            "A messageCursor from an earlier bot_inspect call for this same bot "
                                    + "connection. Only messages at or after that position are "
                                    + "returned.");
                }));

        tools.add(tool("bot_view",
                "Start or reuse a localhost-only live view for one bot. what='world' uses the "
                        + "optional prismarine viewer in first_person or third_person mode; "
                        + "what='inventory' shows the open client menu as a live page. The same "
                        + "bot always reuses its URL. Pass stop:true to close it. The viewer is "
                        + "optional and is not downloaded until this tool is first used.",
                properties -> {
                    session(properties);
                    string(properties, "name", "Bot name.");
                    string(properties, "what", "world (default) or inventory.");
                    string(properties, "mode", "first_person or third_person (world only).");
                    string(properties, "stop", "true to close the viewer for this bot.");
                }));

        tools.add(tool("bot_run_scenario",
                "Run a declarative scenario. Rejected while the connected agent is read-only. "
                        + "Steps: spawn, despawn, move_to, break_block, "
                        + "attack_entity, use_block, use_entity, hold_item, drop_item, "
                        + "place_block, jump, sneak, sprint, look_at, assert_reachable, "
                        + "command, chat, console, click_slot, "
                        + "close_menu, wait_for, assert_block, assert_player, assert_event, "
                        + "assert_inventory, assert_message. There is no sleep step — use "
                        + "wait_for and name what you are waiting for. move_to walks by default; "
                        + "use mode 'teleport' for setup placement, and timeoutMillis to bound a "
                        + "walk. To test a menu GUI: "
                        + "command, then wait_for inventory_open, then assert_inventory with the "
                        + "slots you expect. use_entity right-clicks an NPC or villager, named by "
                        + "the coordinates it stands at rather than by an entity id, since the id "
                        + "is the server's own and never visible here. On failure the response "
                        + "says which step failed, why, and what the server was doing at that "
                        + "moment.",
                properties -> {
                    session(properties);
                    string(properties, "scenario",
                            "JSON array of steps, e.g. "
                                    + "[{\"action\":\"spawn\",\"bot\":\"Tester1\"}]");
                }));

        for (String name : PROXIED) {
            tools.add(passthroughTool(name,
                    "Forwarded to the agent on the connected server. Call session_start first — "
                            + "its response lists this tool's parameters, as the agent defines "
                            + "them. Pass them as top-level properties, not wrapped. Add "
                            + "'session' to say which server, when more than one is open."));
        }
        return tools;
    }

    JsonNode call(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? MAPPER.createObjectNode() : arguments;

        return switch (name) {
            case "session_start" -> sessionStart(args);
            case "session_reset" -> sessionReset(args);
            case "bot_spawn" -> botSpawn(args);
            case "bot_inspect" -> botInspect(args);
            case "bot_view" -> botView(args);
            case "bot_run_scenario" -> runScenario(args);
            default -> {
                if (PROXIED.contains(name)) {
                    Session target = require(args);

                    ObjectNode forwarded = args instanceof ObjectNode object
                            ? object.deepCopy()
                            : MAPPER.createObjectNode();
                    forwarded.remove("session");
                    yield target.agent().call(name, forwarded);
                }
                throw new IllegalArgumentException("Unknown tool: " + name);
            }
        };
    }

    private JsonNode sessionStart(JsonNode args) {
        pruneDeadSessions();
        Connection connection = resolveConnection(args);
        String token = connection.token();

        String runnerJar = args.path("runnerJar").asText("");
        java.nio.file.Path runner = runnerJar.isBlank()
                ? runnerBesideThisJar()
                : java.nio.file.Path.of(runnerJar);

        String host = connection.host();
        int port = connection.port();
        int mcpPort = connection.mcpPort();

        String name = args.path("session").asText("");
        if (name.isBlank()) {
            name = host + ":" + port + "@" + mcpPort;
        }

        Session replaced = sessions.remove(name);
        if (replaced != null) {
            replaced.close();
        }

        Session started;
        try {
            started = new Session(host, port, mcpPort, token,
                    args.path("tls").asBoolean(false),
                    args.path("tlsFingerprint").asText(null),
                    runner);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not start the bot runner: " + e.getMessage(), e);
        }
        sessions.put(name, started);

        JsonNode info = started.agent().call("server_info", AgentClient.arguments());
        started.readOnly(info.path("readOnly").asBoolean(true));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("session", name);
        result.put("connected", started.describe());
        result.put("resolvedFrom", connection.source());
        result.set("server", info);

        result.set("agentTools", started.agent().listTools());
        result.set("sessions", roster());
        return result;
    }

    private JsonNode sessionReset(JsonNode args) {
        Session session = require(args);
        String name = nameOf(session);

        ObjectNode result = MAPPER.createObjectNode();
        if (args.path("close").asBoolean(false)) {
            sessions.remove(name);
            session.close();
            result.put("closed", name);
        } else {
            try {
                session.reset();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                        "Bots were disconnected, but the replacement runner did not start: "
                                + e.getMessage() + ". Call session_start again.", e);
            }
            result.put("reset", name);
            result.put("session", session.describe());
        }
        result.set("sessions", roster());
        return result;
    }

    /** What is open, so a caller never has to remember what it named things. */
    private ArrayNode roster() {
        pruneDeadSessions();
        ArrayNode open = MAPPER.createArrayNode();
        sessions.forEach((name, session) -> open.addObject()
                .put("session", name)
                .put("connected", session.describe()));
        return open;
    }

    /** Removes runner processes that exited without a matching session_reset/close call. */
    private void pruneDeadSessions() {
        java.util.Iterator<java.util.Map.Entry<String, Session>> iterator =
                sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, Session> entry = iterator.next();
            if (!entry.getValue().isRunning()) {
                iterator.remove();
                entry.getValue().close();
            }
        }
    }

    private String nameOf(Session session) {
        return sessions.entrySet().stream()
                .filter(entry -> entry.getValue() == session)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private JsonNode botSpawn(JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("bot_spawn needs 'name'.");
        }
        Session session = require(args);
        requireWritable(session.readOnly());
        refuseIfAlreadyOnline(session, name);

        try {
            BotRunner.BotHandle bot = session.bots().spawn(
                    name,
                    args.hasNonNull("clientIp") ? args.get("clientIp").asText() : null,
                    args.path("auth").asText("offline"),
                    args.path("account").asText(null));

            ObjectNode result = MAPPER.createObjectNode();
            result.put("name", name);
            result.put("playerName", bot.playerName());
            result.put("uuid", bot.uuid());
            result.put("x", bot.blockX());
            result.put("y", bot.blockY());
            result.put("z", bot.blockZ());

            // Gamemode belongs in the answer to "what did I just spawn". It changes how every
            // later observation reads — creative masks a full-inventory failure, and item grants
            // behave differently — and it used to be reachable only by inferring it from the
            // 'view' field of an inventory query, which is documented as being about menus.
            try {
                ObjectNode query = AgentClient.arguments();
                query.put("kind", "player");
                query.put("target", bot.playerName());
                JsonNode state = session.agent().call("state_query", query);
                result.put("gameMode", state.path("gameMode").asText(null));
                result.put("op", state.path("op").asBoolean(false));
            } catch (RuntimeException agentUnavailable) {

                result.putNull("gameMode");
            }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not spawn " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Refuses a name the server already has a player under.
     *
     * <p>Asked before connecting, because connecting is what does the damage. A bot's UUID is
     * derived from its name, so a second caller using the same name is the same player — and the
     * server resolves that by admitting the newcomer and kicking whoever held it. Two MCP sessions
     * driving one server hit this immediately, and the session that loses its bot is never told:
     * its {@code bot_spawn} had already returned success. Which side loses is a race, so the same
     * pair of sessions can behave differently run to run.
     *
     * <p>Checked here rather than left to the rejection, since by then someone has been evicted.
     * A server that cannot answer is not a reason to refuse — that failure belongs to the spawn,
     * which reports it far better than a pre-flight check could.
     */
    private void refuseIfAlreadyOnline(Session session, String name) {
        boolean online;
        try {
            ObjectNode query = MAPPER.createObjectNode();
            query.put("kind", "player");
            query.put("target", name);

            // AgentClient.call already unwraps the MCP envelope, so this is the payload itself.
            // Walking into content[0].text instead read nothing, defaulted to false, and let every
            // spawn through — a check that cannot fail is worse than none, because it looks like
            // one in the diff.
            online = session.agent().call("state_query", query).path("online").asBoolean(false);
        } catch (RuntimeException e) {
            return;
        }

        if (online) {
            throw new IllegalStateException("A player called " + name + " is already on the server,"
                    + " so spawning one would disconnect them. A bot's UUID is derived from its"
                    + " name, which makes two bots of the same name the same player — if another"
                    + " session is driving this server, give each session its own bot names."
                    + " Otherwise use session_reset, or wait for that player to leave.");
        }
    }

    private JsonNode botInspect(JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("bot_inspect needs 'name'.");
        }
        String rawCursor = args.path("cursor").asText("");
        try {
            ClientView view = new BotRunner.BotHandle(
                    require(args).bots(), name, 0, 0, 0).inspect();
            long cursor = parseMessageCursor(rawCursor, view.messageStreamId());

            ObjectNode result = MAPPER.createObjectNode();
            if (view.menu() == null) {
                result.putNull("menu");
            } else {
                ObjectNode menu = result.putObject("menu");
                menu.put("containerId", view.menu().containerId());
                menu.put("title", view.menu().title());
            }

            // Null rather than an empty array when nothing is open. 'items' has only ever meant
            // the open menu's contents, but an empty array next to a player holding a full
            // inventory reads as "this player has nothing" — a dogfooding round drew exactly that
            // conclusion about its own control subject and nearly went hunting for the wrong
            // bug.
            if (view.menu() == null) {
                result.putNull("items");
                result.put("itemsNote", "No menu is open, so there is nothing here. This field is "
                        + "only ever the open menu's contents — for what the player is carrying, "
                        + "ask state_query kind='inventory' which='player'.");
            } else {
                ArrayNode items = result.putArray("items");
                for (MenuItem item : view.items()) {
                    ObjectNode entry = items.addObject();
                    entry.put("slot", item.slot());
                    entry.put("itemId", item.itemId());
                    entry.put("amount", item.amount());
                    entry.put("name", item.name());
                    entry.put("customModelData", item.customModelData());
                    entry.put("lore", item.lore());
                }
            }

            putMessages(result, view, name, cursor);

            ArrayNode bossBars = result.putArray("bossBars");
            for (BossBar bar : view.bossBars()) {
                ObjectNode entry = bossBars.addObject();
                entry.put("title", bar.title());
                entry.put("progress", bar.progress());
                entry.put("color", bar.color());
            }

            if (view.scoreboard() == null) {
                result.putNull("scoreboard");
            } else {
                ObjectNode scoreboard = result.putObject("scoreboard");
                scoreboard.put("title", view.scoreboard().title());
                ArrayNode lines = scoreboard.putArray("lines");
                view.scoreboard().lines().forEach(lines::add);
            }
            if (view.health() != null) {
                result.put("health", view.health());
            } else {
                result.putNull("health");
            }
            if (view.food() != null) {
                result.put("food", view.food());
            } else {
                result.putNull("food");
            }
            if (view.experienceLevel() != null) {
                result.put("experienceLevel", view.experienceLevel());
            } else {
                result.putNull("experienceLevel");
            }
            if (view.totalExperience() != null) {
                result.put("totalExperience", view.totalExperience());
            } else {
                result.putNull("totalExperience");
            }
            if (view.experienceProgress() != null) {
                result.put("experienceProgress", view.experienceProgress());
            } else {
                result.putNull("experienceProgress");
            }
            ArrayNode effects = result.putArray("effects");
            view.effects().forEach(effects::add);
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not inspect " + name + ": " + e.getMessage(), e);
        }
    }

    /** Parses a cursor for the current opaque message stream; an omitted cursor starts at zero. */
    static long parseMessageCursor(String token, String streamId) {
        if (token == null || token.isBlank()) {
            return 0L;
        }
        return Cursor.parse(token, messageStream(streamId)).sequence();
    }

    /** Adds the bounded, cursor-filtered message stream and its completeness metadata. */
    static void putMessages(ObjectNode result, ClientView view, String name, long cursor) {
        long next = view.nextMessageSequence();
        if (cursor > next) {
            throw new IllegalArgumentException(
                    "Message cursor " + cursor + " is ahead of the next message sequence " + next
                            + " for bot '" + name + "'. Use messageCursor from this bot's current "
                            + "connection.");
        }

        long oldestRetained = view.messages().isEmpty()
                ? next
                : view.messages().get(0).sequence();
        long firstAvailable = Math.max(cursor, oldestRetained);

        ArrayNode messages = result.putArray("messages");
        for (ClientMessage message : view.messages()) {
            if (message.sequence() < firstAvailable) {
                continue;
            }
            ObjectNode entry = messages.addObject();
            entry.put("sequence", message.sequence());
            entry.put("timestamp", message.timestamp());
            entry.put("text", message.text());
        }

        result.put("messageCursor",
                new Cursor(messageStream(view.messageStreamId()), next).encode());
        result.put("messagesDropped", Math.max(0L, oldestRetained - cursor));
    }

    private static String messageStream(String streamId) {
        return "messages/" + streamId;
    }

    private JsonNode botView(JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("bot_view needs 'name'.");
        }
        try {
            BotRunner.BotHandle bot = new BotRunner.BotHandle(
                    require(args).bots(), name, 0, 0, 0);
            ObjectNode result = MAPPER.createObjectNode();
            if (args.path("stop").asBoolean(false)) {
                bot.stopView();
                result.put("stopped", true);
                return result;
            }
            String what = args.path("what").asText("world");
            String mode = args.path("mode").asText("third_person");
            result.put("url", bot.view(what, mode));
            result.put("what", what);
            result.put("mode", mode);
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not start view for " + name + ": "
                    + e.getMessage(), e);
        }
    }

    private JsonNode runScenario(JsonNode args) {
        String scenario = args.path("scenario").isTextual()
                ? args.path("scenario").asText()
                : args.path("scenario").toString();
        if (scenario.isBlank() || "null".equals(scenario)) {
            throw new IllegalArgumentException("bot_run_scenario needs 'scenario'.");
        }

        Session session = require(args);
        requireWritable(session.readOnly());
        ScenarioResult result = session.runner().run(scenario);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("passed", result.passed());
        response.put("summary", result.describe());

        ArrayNode steps = response.putArray("steps");
        for (ScenarioResult.StepResult step : result.steps()) {
            ObjectNode entry = steps.addObject();
            entry.put("step", step.index());
            entry.put("action", step.action());
            entry.put("passed", step.passed());
            entry.put("detail", step.detail());
            if (!step.evidence().isEmpty()) {

                entry.put("evidence", step.evidence());
            }
        }
        return response;
    }

    /** The documented read-only boundary also covers player joins and bot actions. */
    static void requireWritable(boolean readOnly) {
        if (readOnly) {
            throw new IllegalStateException(
                    "Bot actions are unavailable: this agent is running read-only. Set "
                            + "'read-only: false' in config.yml and restart the server to allow "
                            + "players or scenarios to change it.");
        }
    }

    /** Where a session is connecting, and how that was worked out. */
    private record Connection(String host, int port, int mcpPort, String token, String source) {}

    /**
     * Works out what to connect to from what the caller said, and what the machine already knows.
     *
     * <p>An agent on this machine writes its host, ports and token to a handshake file as it
     * starts, so for the common case — one server, running right here — none of it has to be
     * repeated to this tool. Anything the caller does pass wins over the file.
     *
     * <p>The file is only consulted for a local host. A token minted by the agent on this machine
     * says nothing about a server somewhere else, and quietly sending it there would turn a
     * missing argument into a leaked secret.
     */
    private static Connection resolveConnection(JsonNode args) {
        String host = args.path("host").asText("");
        boolean local = host.isBlank() || "127.0.0.1".equals(host) || "localhost".equals(host);

        String token = args.path("token").asText("");
        String source = "arguments";

        if (token.isBlank()) {
            String fromEnvironment = System.getenv("VITAMINMCP_TOKEN");
            if (fromEnvironment != null && !fromEnvironment.isBlank()) {
                token = fromEnvironment;
                source = "VITAMINMCP_TOKEN";
            }
        }

        LocalHandshake handshake = local ? handshakeFor(args, token.isBlank()) : null;
        if (handshake != null && token.isBlank()) {
            token = handshake.token();
            source = "the agent's handshake in " + LocalHandshake.directory();
        }

        if (token.isBlank()) {
            throw new IllegalArgumentException(local
                    ? "session_start found no agent on this machine. Either the server is not "
                            + "running, or its VitaminMCP plugin did not start — its console says "
                            + "which. For a server elsewhere, pass 'host' and 'token' (the "
                            + "auth-token in the agent's config.yml)."
                    : "session_start needs 'token' for a server on another machine — the agent "
                            + "refuses unauthenticated requests. It is the auth-token in the "
                            + "agent's config.yml.");
        }

        return new Connection(
                host.isBlank() ? (handshake == null ? "127.0.0.1" : handshake.host()) : host,
                args.has("port") ? args.path("port").asInt()
                        : (handshake == null ? 25565 : handshake.minecraftPort()),
                args.has("mcpPort") ? args.path("mcpPort").asInt()
                        : (handshake == null ? 25585 : handshake.mcpPort()),
                token,
                source);
    }

    /**
     * The handshake this call is about, or null when there is nothing to read.
     *
     * <p>With several agents running — a proxied network is several servers, one agent each —
     * there is no right guess, so an unnamed port is an error that lists them rather than a pick.
     * That only applies when the file is actually needed: a caller who supplied a token is asking
     * for defaults, not for a decision.
     */
    private static LocalHandshake handshakeFor(JsonNode args, boolean tokenNeeded) {
        if (args.has("mcpPort")) {
            return LocalHandshake.read(args.path("mcpPort").asInt()).orElse(null);
        }

        List<LocalHandshake> all = LocalHandshake.readAll();
        if (all.size() == 1) {
            return all.get(0);
        }
        if (all.size() > 1 && tokenNeeded) {
            throw new IllegalArgumentException(
                    "Several VitaminMCP agents are running on this machine, so 'mcpPort' says "
                            + "which one you mean: "
                            + all.stream().map(LocalHandshake::toString).toList()
                            + ". On a proxied network, open one session per backend.");
        }
        return null;
    }

    /** How long a runner still downloading is waited for before the caller is told. */
    private static final java.time.Duration RUNNER_DOWNLOAD_WAIT = java.time.Duration.ofMinutes(10);

    /** Finds the bot runner: named by the launcher, or sitting next to this server's own jar. */
    private static java.nio.file.Path runnerBesideThisJar() {
        String announced = System.getenv("VITAMINMCP_RUNNER_JAR");
        if (announced != null && !announced.isBlank()) {
            return awaitRunner(java.nio.file.Path.of(announced));
        }

        java.nio.file.Path here;
        try {
            here = java.nio.file.Path.of(SessionTools.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
        } catch (RuntimeException | java.net.URISyntaxException e) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': this server could not work out where its "
                            + "own jar is, so it cannot find the runner beside it.");
        }

        List<java.nio.file.Path> found = new java.util.ArrayList<>();
        try (var entries = java.nio.file.Files.list(here)) {
            entries.filter(path -> isRunnerFile(path.getFileName().toString())).forEach(found::add);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': could not look in " + here + " (" + e + ")");
        }

        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar' — the Node runner built for this server. "
                            + "Bots run in a child process. No Node runner was found in "
                            + here + ", so pass its path.");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': " + here + " holds more than one runner "
                            + found.stream().map(p -> p.getFileName().toString()).toList()
                            + ". Name the one that speaks this server's protocol.");
        }
        return found.get(0);
    }

    /**
     * Whether a filename is a supported Node runner.
     *
     * <p>Both spellings, because 'gradlew dist' stamps the version into the name and the release
     * artifact the npm package downloads does not.
     */
    private static boolean isRunnerFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("runner.mjs")
                || lower.equals("runner.js")
                || lower.equals("bot-runner-win-x64.exe");
    }

    /**
     * Waits for a runner that is still arriving.
     *
     * <p>The runner can be large, so the npm launcher fetches it in the background rather
     * than holding up a client that may never spawn a bot: this server starts answering while the
     * download runs, and only a call that actually needs bots waits for it. A partial file is
     * named {@code .part} and renamed when complete, so the wait is for a rename and never sees a
     * half-written jar.
     */
    private static java.nio.file.Path awaitRunner(java.nio.file.Path runner) {
        if (java.nio.file.Files.isRegularFile(runner)) {
            return runner;
        }

        java.nio.file.Path partial = runner.resolveSibling(runner.getFileName() + ".part");
        if (!java.nio.file.Files.exists(partial)) {
            throw new IllegalArgumentException(
                    "session_start needs 'runnerJar': VITAMINMCP_RUNNER_JAR names " + runner
                            + ", but nothing is there and no download is in progress.");
        }

        System.err.println("Waiting for the bot runner to finish downloading: " + runner);
        long deadline = System.nanoTime() + RUNNER_DOWNLOAD_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.isRegularFile(runner)) {
                return runner;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the bot runner download");
            }
        }
        throw new IllegalStateException(
                "The bot runner was still downloading after " + RUNNER_DOWNLOAD_WAIT.toMinutes()
                        + " minutes (" + partial + "). Delete that file and start again, or pass "
                        + "'runnerJar' pointing at a copy you already have.");
    }

    /** The session a call is about. */
    private Session require(JsonNode args) {
        pruneDeadSessions();
        String name = args.path("session").asText("");
        if (!name.isBlank()) {
            Session named = sessions.get(name);
            if (named == null) {
                throw new IllegalArgumentException(
                        "No session named '" + name + "'. Open: " + sessions.keySet());
            }
            return named;
        }
        if (sessions.isEmpty()) {
            throw new IllegalStateException("No session. Call session_start first.");
        }
        if (sessions.size() > 1) {
            throw new IllegalArgumentException(
                    "Several sessions are open " + sessions.keySet()
                            + " — pass 'session' to say which one this is for.");
        }
        return sessions.values().iterator().next();
    }

    void close() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }

    private static ObjectNode tool(
            String name, String description, java.util.function.Consumer<ObjectNode> properties) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        properties.accept(schema.putObject("properties"));
        schema.set("required", MAPPER.createArrayNode());
        return tool;
    }

    /** A tool whose arguments belong to something else. */
    private static ObjectNode passthroughTool(String name, String description) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", true);
        return tool;
    }

    /** Which server this call is for. */
    private static void session(ObjectNode properties) {
        string(properties, "session",
                "Which session, by the name session_start gave it. Optional while only one is "
                        + "open; required once there are several.");
    }

    private static void string(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    private static void number(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "integer");
        property.put("description", description);
    }
}

package moe.vitamin.minecraft.mcp.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.bot.spi.ClientMessage;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.orchestrator.ManagedServer;
import moe.vitamin.minecraft.mcp.orchestrator.PaperDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Every feature, on one version, against a server this test starts itself. */
@EnabledIfSystemProperty(named = "vitaminmcp.liveServer", matches = "true")
class CompatibilityLiveTest {

    private static final String VERSION = System.getProperty("vitaminmcp.version", "1.21.8");
    private static final int BUILD = Integer.getInteger("vitaminmcp.paperBuild", 0);
    private static final int EXPECTED_PROTOCOL = Integer.getInteger("vitaminmcp.protocol", 0);
    private static final int PORT = Integer.getInteger("vitaminmcp.port", 25810);
    private static final int AGENT_PORT = Integer.getInteger("vitaminmcp.mcpPort", 25811);

    /** Long enough for a first start that also generates a world. */
    private static final Duration STARTUP = Duration.ofMinutes(5);

    private final List<String> failures = new ArrayList<>();

    @Test
    void everyFeatureWorksOnThisVersion() throws Exception {
        Path agentJar = required("vitaminmcp.agentJar");
        Path runnerJar = required("vitaminmcp.runnerJar");
        Path javaHome = ServerJava.home();

        Path work = Files.createTempDirectory("vitaminmcp-compat-" + VERSION + "-");
        String token = token();

        Path paper = new PaperDownloader().fetch(VERSION, BUILD);
        System.out.println("[compat] " + VERSION + " -> " + paper.getFileName());

        try (ManagedServer server = new ManagedServer(work.resolve("server"), paper, PORT, AGENT_PORT)) {
            server.prepare(null, agentJar, token);
            server.start(javaHome, STARTUP);

            AgentClient agent = new AgentClient("127.0.0.1", AGENT_PORT, token);

            try (BotRunner bots = BotRunner.launch(runnerJar, "127.0.0.1", PORT)) {
                exercise(bots, agent);
            }
        } finally {
            deleteQuietly(work);
        }

        if (!failures.isEmpty()) {
            fail(failures.size() + " feature(s) failed on " + VERSION + ":\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    private void exercise(BotRunner bots, AgentClient agent) throws Exception {

        if (EXPECTED_PROTOCOL != 0 && bots.protocol() != EXPECTED_PROTOCOL) {
            fail("the runner loaded the backend for protocol " + bots.protocol()
                    + " but " + VERSION + " speaks " + EXPECTED_PROTOCOL);
        }
        System.out.println("[compat] backend protocol " + bots.protocol());

        long beforeJoin = eventSequence(agent);

        BotRunner.BotHandle bot = bots.spawn("Tester1");
        System.out.println("[compat] spawned at " + bot.x() + " " + bot.y() + " " + bot.z());
        assertTrue(bot.y() > 0, "the bot spawned at y=" + bot.y() + ", which is not in a world");

        int bx = bot.blockX();
        int by = bot.blockY();
        int bz = bot.blockZ();

        check("join event", () ->
                require(sawEvent(agent, "PlayerJoinEvent", "Tester1", beforeJoin),
                        "the agent never recorded PlayerJoinEvent for Tester1"));

        check("player state", () -> {
            JsonNode state = playerState(agent, "Tester1");
            require(state.path("online").asBoolean(), "the server does not have Tester1 online");
            require("CREATIVE".equals(state.path("gameMode").asText()),
                    "gameMode was " + state.path("gameMode").asText());
        });

        // Paper silently drops block interactions while it loads a joining player's data. The
        // window is about three seconds on 1.21.8, so this is the one honest fixed tick barrier in
        // the compatibility harness; a wait_for log or online check does not release it.
        check("join interaction lockout", () -> awaitInteractionUnlock(agent));

        check("op and deop", () -> {
            console(agent, "op Tester1");
            require(await(() -> playerState(agent, "Tester1").path("op").asBoolean()),
                    "op never took effect");
            console(agent, "deop Tester1");
            require(await(() -> !playerState(agent, "Tester1").path("op").asBoolean()),
                    "deop never took effect");
            console(agent, "op Tester1");
            require(await(() -> playerState(agent, "Tester1").path("op").asBoolean()),
                    "op never took effect the second time");
        });

        // /list is vanilla, so it never goes through a plugin's command handler. Running it as a
        // player is the check that the 'as' path reaches the server's own dispatcher at all.
        //
        // What a *non-op* gets is deliberately not asserted: Paper moved where vanilla commands
        // are gated, so 1.21.8 refuses one and 1.21.1 runs it, and pinning either answer here
        // would make this harness fail on half the matrix for a difference that is Paper's to
        // make. The invariant that does hold everywhere is that the answer explains itself.
        check("a vanilla command as a player", () -> {
            console(agent, "op Tester1");
            require(await(() -> playerState(agent, "Tester1").path("op").asBoolean()),
                    "op never took effect");

            JsonNode ran = as(agent, "list", "Tester1");
            require(ran.path("dispatched").asBoolean(),
                    "/list as an op player was not dispatched, so the player path never reaches "
                            + "the vanilla dispatcher: " + ran.path("reason").asText());
            require(ran.path("reason").isNull(),
                    "a dispatched command still carried a reason: " + ran.path("reason"));

            console(agent, "deop Tester1");
            require(await(() -> !playerState(agent, "Tester1").path("op").asBoolean()),
                    "deop never took effect");

            JsonNode asNonOp = as(agent, "list", "Tester1");
            boolean dispatched = asNonOp.path("dispatched").asBoolean();
            String reason = asNonOp.path("reason").asText("");
            System.out.println("[compat] /list as a non-op: dispatched=" + dispatched);

            if (dispatched) {
                require(asNonOp.path("reason").isNull(),
                        "a dispatched command still carried a reason: " + reason);
            } else {
                require(reason.startsWith("Nothing ran:"),
                        "a refusal that does not say nothing ran: " + reason);
                require(reason.contains("Tester1"),
                        "the refusal did not name the player it was about: " + reason);
                require(reason.contains("minecraft.command.list"),
                        "the refusal did not name the permission it was about: " + reason);
            }

            // Leave op on, which is what the rest of this run expects.
            console(agent, "op Tester1");
            require(await(() -> playerState(agent, "Tester1").path("op").asBoolean()),
                    "op never took effect the second time");
        });

        // Asked as the console on purpose. The console is allowed every command on every version,
        // so a failure there can only mean the command does not exist — the one place the two
        // causes of a false can be told apart without depending on a permission policy.
        check("an unknown command is not a refusal", () -> {
            JsonNode unknown = consoleResult(agent, "definitelynotacommand");
            require(!unknown.path("dispatched").asBoolean(),
                    "the server claims to have a command named definitelynotacommand");
            String reason = unknown.path("reason").asText("");
            require(reason.contains("no command named"),
                    "an unknown command was not reported as unknown: " + reason);
        });

        check("break a block", () -> {
            String before = block(agent, bx, by - 1, bz);
            require(!"AIR".equals(before),
                    "the bot is not standing on anything, so there is nothing to break");

            long since = eventSequence(agent);
            bot.breakBlock(bx, by - 1, bz);
            require(sawEvent(agent, "BlockBreakEvent", "Tester1", since),
                    "no BlockBreakEvent arrived. The block under the bot was " + before
                            + ", now " + block(agent, bx, by - 1, bz));
        });

        check("move", () -> {

            console(agent, "tp Tester1 " + (bx + 3) + " " + by + " " + bz);
            require(await(() -> Math.abs(playerState(agent, "Tester1").path("x").asDouble()
                    - (bx + 3)) < 2.0), "the server never moved the player");

            // This is the legacy move contract being compared here. Stage 4's walking contract is
            // exercised separately with an explicit mode; leaving this implicit would make the
            // old Java oracle and the Node runner test different behaviours.
            bot.moveTo(bx + 4.5, by, bz + 0.5, "teleport", 30_000);
            require(await(() -> Math.abs(playerState(agent, "Tester1").path("x").asDouble()
                    - (bx + 4.5)) < 1.5),
                    "the server did not accept the bot's own movement; it has the player at "
                            + playerState(agent, "Tester1").path("x").asDouble());
        });

        check("chat", () -> {
            long since = eventSequence(agent);
            bot.chat("compat-chat-" + System.nanoTime());
            require(sawEvent(agent, "PlayerCommandPreprocessEvent", "Tester1", since),
                    "the server never saw the bot say anything. Messages: " + messages(bot));
        });

        check("command", () -> {
            String marker = "compat-cmd-" + System.nanoTime();
            long messageCursor = bot.inspect().nextMessageSequence();
            long sentAt = System.currentTimeMillis();
            ScenarioResult assertion = new ScenarioRunner(bots, agent).run("""
                    [
                      {"action":"command","bot":"Tester1","command":"/say %s"},
                      {"action":"assert_message","bot":"Tester1","contains":"%s"}
                    ]
                    """.formatted(marker, marker));
            require(assertion.passed(),
                    "assert_message did not find the timestamped reply: " + assertion.describe());

            ClientMessage observed = message(bot, messageCursor, marker);
            require(observed != null,
                    "the bot's own command produced nothing it could see. Messages: "
                            + messages(bot));
            require(observed.sequence() >= messageCursor,
                    "the command reply preceded its cursor: " + observed);
            require(observed.timestamp() >= sentAt,
                    "the command reply was timestamped before it was sent: " + observed);
            System.out.println("[compat] command reply latency "
                    + (observed.timestamp() - sentAt) + "ms");
        });

        int cx = bx + 4;
        int cz = bz;

        check("open a container", () -> {
            console(agent, "setblock " + (cx + 1) + " " + by + " " + cz + " minecraft:chest");
            console(agent, "item replace block " + (cx + 1) + " " + by + " " + cz
                    + " container.0 with minecraft:diamond 3");

            bot.useBlock(cx + 1, by, cz, "UP");
            require(await(() -> menuOpen(bot)),
                    "the chest never opened on the client. The server says the block is "
                            + block(agent, cx + 1, by, cz));
        });

        check("read the open menu", () -> {
            require(menuOpen(bot), "no menu is open, so there is nothing to read");
            ClientView view = bot.inspect();
            require(view.menu() != null, "inspect reported no menu while menu() did");
            require(!view.items().isEmpty(),
                    "the client received no items for the open chest, which had a diamond in it");
            require(view.items().stream().anyMatch(item -> item.amount() == 3),
                    "no slot carried the 3 diamonds that were put in: " + view.items());
        });

        check("model data through the agent", () -> {
            String item = bots.protocol() >= 769
                    ? "minecraft:diamond[minecraft:custom_model_data={strings:[\"compat\"]}]"
                    : "minecraft:diamond[minecraft:custom_model_data=7]";
            console(agent, "item replace block " + (cx + 1) + " " + by + " " + cz
                    + " container.1 with " + item + " 1");

            ObjectNode query = AgentClient.arguments();
            query.put("kind", "inventory");
            query.put("target", "Tester1");
            query.put("which", "menu");
            require(await(() -> agent.call("state_query", query).toString().contains("modelData")
                            || agent.call("state_query", query).toString()
                                    .contains("customModelData")),
                    "the agent reported no model data for an item that has some: "
                            + agent.call("state_query", query));
        });

        check("click and close", () -> {
            bot.clickSlot(0, "left");
            bot.closeMenu();
            require(await(() -> !menuOpen(bot)), "the menu never closed");
        });

        check("boss bar", () -> {
            console(agent, "bossbar add compat {\"text\":\"Compat Bar\"}");
            console(agent, "bossbar set compat players Tester1");
            console(agent, "bossbar set compat visible true");
            require(await(() -> view(bot).bossBars().stream()
                            .anyMatch(bar -> bar.title().contains("Compat Bar"))),
                    "the boss bar never reached the client: " + view(bot).bossBars());
        });

        check("sidebar scoreboard", () -> {
            console(agent, "scoreboard objectives add compat dummy {\"text\":\"Compat Board\"}");
            console(agent, "scoreboard objectives setdisplay sidebar compat");
            console(agent, "scoreboard players set Tester1 compat 5");
            require(await(() -> view(bot).scoreboard() != null
                            && !view(bot).scoreboard().lines().isEmpty()),
                    "the sidebar never reached the client: " + view(bot).scoreboard());
            require(view(bot).scoreboard().title().contains("Compat Board"),
                    "the sidebar title was '" + view(bot).scoreboard().title() + "'");
        });

        check("right-click an entity", () -> {
            console(agent, "summon minecraft:armor_stand " + (cx + 1) + " " + by + " " + (cz + 1));
            require(await(() -> {
                try {
                    return !bot.useEntity(cx + 1, by, cz + 1, 3.0, null).isBlank();
                } catch (java.io.IOException notYet) {

                    return false;
                }
            }), "the bot was never told about the armour stand next to it");
        });

        check("three bots at once", () -> {
            bots.spawn("Tester2");
            bots.spawn("Tester3");
            for (String name : List.of("Tester1", "Tester2", "Tester3")) {
                require(playerState(agent, name).path("online").asBoolean(),
                        name + " is not online");
            }
        });

        check("despawn", () -> {
            long since = eventSequence(agent);
            bots.despawn("Tester3");
            require(sawEvent(agent, "PlayerQuitEvent", "Tester3", since),
                    "no PlayerQuitEvent after despawning Tester3");
        });

        check("no linkage errors on the server", () -> {
            ObjectNode arguments = AgentClient.arguments();
            arguments.put("limit", 50);
            String recent = agent.call("exceptions_recent", arguments).toString();
            for (String linkage : List.of("IncompatibleClassChangeError", "NoSuchMethodError",
                    "NoSuchFieldError", "NoClassDefFoundError", "AbstractMethodError")) {
                require(!recent.contains(linkage),
                        "the server logged a " + linkage + ": " + recent);
            }
        });
    }

    private interface Step {
        void run() throws Exception;
    }

    /** Runs one feature check, recording rather than throwing so the rest still run. */
    private void check(String name, Step step) {
        try {
            step.run();
            System.out.println("[compat] ok    " + name);
        } catch (Exception | AssertionError e) {
            System.out.println("[compat] FAIL  " + name + ": " + e.getMessage());
            failures.add(name + ": " + e.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Polls until something the server was asked to do has been observed. */
    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException retry) {

            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Waits out Paper's post-join interaction lock, which is independent of player_online. */
    private static void awaitInteractionUnlock(AgentClient agent) {
        ObjectNode wait = AgentClient.arguments();
        wait.put("condition", "ticks");
        wait.put("count", 80);
        wait.put("timeoutMillis", 10_000);
        require(agent.call("wait_for", wait).path("matched").asBoolean(),
                "the server did not advance through the post-join interaction lockout");
    }

    private static boolean menuOpen(BotRunner.BotHandle bot) {
        try {
            return bot.menu() != null;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static List<String> messages(BotRunner.BotHandle bot) {
        return view(bot).messages().stream().map(ClientMessage::text).toList();
    }

    /** One retained message at or beyond a cursor, or {@code null} while it has not arrived. */
    private static ClientMessage message(BotRunner.BotHandle bot, long cursor, String contains) {
        return view(bot).messages().stream()
                .filter(candidate -> candidate.sequence() >= cursor)
                .filter(candidate -> candidate.text().contains(contains))
                .findFirst()
                .orElse(null);
    }

    /** The client's view, or an empty one. */
    private static ClientView view(BotRunner.BotHandle bot) {
        try {
            return bot.inspect();
        } catch (java.io.IOException e) {
            return new ClientView(null, List.of(), List.of(), List.of(), null);
        }
    }

    /** Whether the agent recorded {@code eventType} for {@code player} after {@code since}. */
    private static boolean sawEvent(
            AgentClient agent, String eventType, String player, long since) {
        ObjectNode wait = AgentClient.arguments();
        wait.put("condition", "event");
        wait.put("eventType", eventType);
        wait.put("player", player);
        wait.put("sinceSequence", since);
        wait.put("timeoutTicks", 200);
        return agent.call("wait_for", wait).path("matched").asBoolean();
    }

    private static void console(AgentClient agent, String command) {
        consoleResult(agent, command);
    }

    /** The same, for a caller that cares what came back. */
    private static JsonNode consoleResult(AgentClient agent, String command) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("command", command);
        return agent.call("command_exec", arguments);
    }

    /** A command run as a player rather than as the console. */
    private static JsonNode as(AgentClient agent, String command, String player) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("command", command);
        arguments.put("as", player);
        return agent.call("command_exec", arguments);
    }

    private static JsonNode playerState(AgentClient agent, String name) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("kind", "player");
        arguments.put("target", name);
        return agent.call("state_query", arguments);
    }

    private static String block(AgentClient agent, int x, int y, int z) {
        ObjectNode arguments = AgentClient.arguments();
        arguments.put("kind", "block");
        arguments.put("world", "world");
        arguments.put("x", x);
        arguments.put("y", y);
        arguments.put("z", z);
        return agent.call("state_query", arguments).path("block").asText();
    }

    /** Where the event log is now, so a wait cannot miss what happens next. */
    private static long eventSequence(AgentClient agent) {
        String cursor = agent.call("server_info", AgentClient.arguments())
                .path("latestEventCursor").asText("");
        int colon = cursor.indexOf(':');
        return colon < 0 ? 0 : Long.parseLong(cursor.substring(colon + 1));
    }

    private static Path required(String property) {
        Path path = Path.of(System.getProperty(property, ""));
        assertTrue(Files.exists(path), "pass -D" + property + "=<path>");
        return path;
    }

    private static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Best-effort cleanup. */
    private static void deleteQuietly(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException stillInUse) {

                }
            });
        } catch (java.io.IOException e) {
            System.err.println("could not clean " + root + ": " + e.getMessage());
        }
    }
}

package moe.vitamin.minecraft.mcp.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.bot.spi.ClientMessage;

/** Runs a declarative scenario against a server. */
public final class ScenarioRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Long enough for a real condition, short enough that a wedged one fails the run. */
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(15);

    /** How often a bot is asked again whether the message it is waiting for has arrived. */
    private static final long MESSAGE_POLL_MILLIS = 100;

    /** Long enough for a short walk, but finite so an impossible route names its failure. */
    private static final long DEFAULT_MOVE_TIMEOUT_MILLIS = Duration.ofSeconds(30).toMillis();

    private final BotRunner bots;
    private final AgentClient agent;

    public ScenarioRunner(BotRunner bots, AgentClient agent) {
        this.bots = bots;
        this.agent = agent;
    }

    public ScenarioResult run(String scenarioJson) {
        JsonNode steps;
        try {
            steps = MAPPER.readTree(scenarioJson);
        } catch (IOException e) {
            return new ScenarioResult(false, List.of(ScenarioResult.StepResult.failed(
                    0, "parse", "the scenario is not valid JSON: " + e.getMessage(), "")));
        }
        if (!steps.isArray()) {
            return new ScenarioResult(false, List.of(ScenarioResult.StepResult.failed(
                    0, "parse", "a scenario is an array of steps", "")));
        }

        long scenarioStart = currentEventSequence();

        List<ScenarioResult.StepResult> results = new ArrayList<>();
        int index = 0;

        for (JsonNode step : steps) {
            index++;
            String action = step.path("action").asText("");
            if (action.isEmpty()) {
                results.add(ScenarioResult.StepResult.failed(
                        index, "?", "step has no 'action'", step.toString()));
                return new ScenarioResult(false, results);
            }

            // Where the streams stand before this step, so a failure can be shown the window it
            // happened in rather than everything the server has retained.
            Moment before = moment();

            try {
                results.add(execute(index, action, step, scenarioStart, before));
            } catch (RuntimeException e) {

                results.add(ScenarioResult.StepResult.failed(
                        index, action, String.valueOf(e.getMessage()), snapshot(before)));
                return new ScenarioResult(false, results);
            }

            if (!results.get(results.size() - 1).passed()) {
                return new ScenarioResult(false, results);
            }
        }
        return new ScenarioResult(true, results);
    }

    private ScenarioResult.StepResult execute(
            int index, String action, JsonNode step, long scenarioStart, Moment before) {
        return switch (action) {
            case "spawn" -> {
                String name = required(step, "bot");
                try {

                    BotRunner.BotHandle bot = bots.spawn(
                            name,
                            step.hasNonNull("clientIp") ? step.get("clientIp").asText() : null,
                            step.path("auth").asText("offline"),
                            step.path("account").asText(null));
                    yield ScenarioResult.StepResult.ok(index, action,
                            bot.playerName() + " joined at " + bot.x() + ", " + bot.y()
                                    + ", " + bot.z());
                } catch (java.io.IOException e) {

                    throw new IllegalStateException(
                            "could not spawn " + name + ": " + e.getMessage(), e);
                }
            }

            case "despawn" -> {
                try {
                    bots.despawn(required(step, "bot"));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(String.valueOf(e.getMessage()), e);
                }
                yield ScenarioResult.StepResult.ok(index, action, "disconnected");
            }

            case "move_to" -> {
                String mode = step.path("mode").asText("path");
                long timeout = step.has("timeoutMillis")
                        ? step.path("timeoutMillis").asLong()
                        : step.path("timeout").asLong(DEFAULT_MOVE_TIMEOUT_MILLIS);
                act(step, bot -> bot.moveTo(
                        step.path("x").asDouble(), step.path("y").asDouble(),
                        step.path("z").asDouble(), mode, timeout));
                yield ScenarioResult.StepResult.ok(index, action,
                        "arrived using " + mode.toLowerCase(java.util.Locale.ROOT));
            }

            case "break_block" -> {
                String[] outcome = new String[1];
                act(step, bot -> outcome[0] = bot.breakBlock(
                        step.path("x").asInt(), step.path("y").asInt(), step.path("z").asInt()));
                yield ScenarioResult.StepResult.ok(index, action, outcome[0]);
            }

            case "attack_entity" -> {
                String[] hit = new String[1];
                act(step, bot -> hit[0] = bot.attackEntity(
                        step.path("x").asDouble(), step.path("y").asDouble(),
                        step.path("z").asDouble(), step.path("radius").asDouble(2.0),
                        step.path("entityType").asText(null)));
                yield ScenarioResult.StepResult.ok(index, action,
                        "sent to entity " + (hit[0] == null ? "" : hit[0]));
            }

            case "hold_item" -> {
                act(step, bot -> bot.holdItem(step.path("slot").asInt()));
                yield ScenarioResult.StepResult.ok(index, action, "holding hotbar slot "
                        + step.path("slot").asInt());
            }

            case "drop_item" -> {
                act(step, bot -> bot.dropItem(step.hasNonNull("count")
                        ? step.path("count").asInt() : null));
                yield ScenarioResult.StepResult.ok(index, action, "dropped");
            }

            case "place_block" -> {
                act(step, bot -> bot.placeBlock(
                        step.path("x").asInt(), step.path("y").asInt(), step.path("z").asInt(),
                        step.path("face").asText("up")));
                yield ScenarioResult.StepResult.ok(index, action, "placed");
            }

            case "jump" -> {
                act(step, BotRunner.BotHandle::jump);
                yield ScenarioResult.StepResult.ok(index, action, "jumped");
            }

            case "sneak", "sprint" -> {
                boolean state = step.path("state").asBoolean(
                        "on".equalsIgnoreCase(step.path("state").asText("on")));
                act(step, bot -> {
                    if ("sneak".equals(action)) bot.sneak(state);
                    else bot.sprint(state);
                });
                yield ScenarioResult.StepResult.ok(index, action, state ? "on" : "off");
            }

            case "look_at" -> {
                act(step, bot -> bot.lookAt(step.path("x").asDouble(), step.path("y").asDouble(),
                        step.path("z").asDouble()));
                yield ScenarioResult.StepResult.ok(index, action, "looking");
            }

            case "assert_reachable" -> {
                long timeout = step.has("timeoutMillis")
                        ? step.path("timeoutMillis").asLong() : 5_000L;
                String botName = step.path("bot").asText("");
                boolean reachable;
                try {
                    reachable = bots.assertReachable(botName, step.path("x").asDouble(),
                            step.path("y").asDouble(), step.path("z").asDouble(), timeout);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(String.valueOf(e.getMessage()), e);
                }
                boolean expected = step.path("reachable").asBoolean(true);
                yield reachable == expected
                        ? ScenarioResult.StepResult.ok(index, action,
                                reachable ? "reachable" : "not reachable")
                        : ScenarioResult.StepResult.failed(index, action,
                                "expected reachable=" + expected + " but pathfinder returned "
                                        + reachable,
                                // Restating the assertion told a round nothing it did not already
                                // know. What it needed was what 'reachable' means here
                                // (dogfood/JOURNAL.md, 2026-08-23).
                                "This asks whether the bot can WALK there, not whether it is "
                                        + "within arm's reach. A block in mid-air, behind a gap, "
                                        + "or with no standable surface beside it is unreachable "
                                        + "however close it is. For 'can the bot act on this "
                                        + "block', move the bot next to it and try the action.");
            }

            case "command" -> {
                act(step, bot -> bot.command(required(step, "command")));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "chat" -> {
                act(step, bot -> bot.chat(required(step, "message")));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "console" -> {
                ObjectNode arguments = AgentClient.arguments();
                arguments.put("command", required(step, "command"));
                JsonNode result = agent.call("command_exec", arguments);
                boolean dispatched = result.path("dispatched").asBoolean();
                yield ScenarioResult.StepResult.ok(index, action,
                        "dispatched=" + dispatched
                                + (dispatched ? "" : " (" + result.path("reason").asText() + ")")
                                + " output=" + result.path("output"));
            }

            case "wait_for" -> {
                JsonNode result = agent.call("wait_for", waitArguments(step));
                yield result.path("matched").asBoolean()
                        ? ScenarioResult.StepResult.ok(index, action,
                                "matched after " + result.path("ticksObserved").asInt() + " ticks")
                        : ScenarioResult.StepResult.failed(index, action,
                                "timed out waiting for " + result.path("condition").asText(),
                                "events=" + result.path("recentEvents")
                                        + " logs=" + result.path("recentLogs"));
            }

            case "assert_block" -> {
                ObjectNode arguments = AgentClient.arguments();
                arguments.put("kind", "block");
                arguments.put("world", step.path("world").asText("world"));
                arguments.put("x", step.path("x").asInt());
                arguments.put("y", step.path("y").asInt());
                arguments.put("z", step.path("z").asInt());
                String actual = agent.call("state_query", arguments).path("block").asText();
                String expected = required(step, "material").toUpperCase(java.util.Locale.ROOT);
                yield actual.equals(expected)
                        ? ScenarioResult.StepResult.ok(index, action, expected)
                        : ScenarioResult.StepResult.failed(index, action,
                                "expected " + expected + " but found " + actual, snapshot(before));
            }

            case "assert_player" -> {

                ObjectNode arguments = waitArguments(step);
                arguments.put("condition", "player_state");
                arguments.put("name", required(step, "bot"));

                JsonNode result = agent.call("wait_for", arguments);
                if (result.path("matched").asBoolean()) {
                    yield ScenarioResult.StepResult.ok(index, action, "as expected");
                }

                ObjectNode query = AgentClient.arguments();
                query.put("kind", "player");
                query.put("target", required(step, "bot"));
                JsonNode actual = agent.call("state_query", query);
                yield ScenarioResult.StepResult.failed(index, action,
                        "player state never matched", actual.toString());
            }

            case "use_block" -> {
                act(step, bot -> bot.useBlock(
                        step.path("x").asInt(), step.path("y").asInt(), step.path("z").asInt(),
                        step.path("face").asText(null)));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "use_entity" -> {

                String[] hit = new String[1];
                act(step, bot -> hit[0] = bot.useEntity(
                        step.path("x").asDouble(), step.path("y").asDouble(),
                        step.path("z").asDouble(),
                        step.path("radius").asDouble(2.0),
                        step.path("entityType").asText(null)));
                yield ScenarioResult.StepResult.ok(index, action,
                        hit[0] == null || hit[0].isBlank()
                                ? "sent"
                                : "sent to entity " + hit[0]);
            }

            case "click_slot" -> {
                act(step, bot -> bot.clickSlot(
                        step.path("slot").asInt(), step.path("click").asText("left")));
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "close_menu" -> {
                act(step, BotRunner.BotHandle::closeMenu);
                yield ScenarioResult.StepResult.ok(index, action, "sent");
            }

            case "assert_inventory" -> {
                ObjectNode query = AgentClient.arguments();
                query.put("kind", "inventory");
                query.put("target", required(step, "bot"));
                query.put("which", step.path("which").asText("menu"));
                JsonNode actual = agent.call("state_query", query);

                yield checkInventory(index, action, step, actual);
            }

            // Waits rather than checking once. A plugin that answers from an async task — a
            // leaderboard, a lookup, anything database-backed — replies a beat after the command,
            // and a bare assert here failed on all of them while reporting "nothing said to X",
            // which reads as "it never replied" rather than "not yet" (dogfood/JOURNAL.md,
            // 2026-08-23). `wait_for` cannot cover this: a message to a client is not something
            // the server-side agent can see.
            case "assert_message" -> {

                String bot = required(step, "bot");
                String wanted = required(step, "contains");
                long timeout = step.has("timeoutMillis")
                        ? step.path("timeoutMillis").asLong()
                        : DEFAULT_WAIT.toMillis();

                List<ClientMessage> received = List.of();
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
                try {
                    do {
                        received = new BotRunner.BotHandle(bots, bot, 0, 0, 0).inspect().messages();
                        if (received.stream()
                                .anyMatch(message -> message.text().contains(wanted))) {
                            yield ScenarioResult.StepResult.ok(index, action, "said to " + bot);
                        }
                        Thread.sleep(MESSAGE_POLL_MILLIS);
                    } while (System.nanoTime() < deadline);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(String.valueOf(e.getMessage()), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                yield ScenarioResult.StepResult.failed(index, action,
                        "nothing said to " + bot + " contained '" + wanted + "' within "
                                + timeout + "ms",
                        String.join(" | ", received.stream().map(ClientMessage::text).toList()));
            }

            case "assert_event" -> {
                ObjectNode arguments = waitArguments(step);
                arguments.put("condition", "event");
                if (!arguments.has("sinceSequence")) {
                    arguments.put("sinceSequence", scenarioStart);
                }
                JsonNode result = agent.call("wait_for", arguments);
                yield result.path("matched").asBoolean()
                        ? ScenarioResult.StepResult.ok(index, action, "seen")
                        : ScenarioResult.StepResult.failed(index, action,
                                "no " + step.path("eventType").asText() + " was recorded",
                                "events=" + result.path("recentEvents"));
            }

            case "sleep" -> throw new IllegalArgumentException(
                    "there is no sleep step. Use wait_for and name what you are waiting for — a "
                            + "fixed wait is right on the machine that wrote it and wrong "
                            + "everywhere else.");

            default -> throw new IllegalArgumentException("unknown action '" + action + "'");
        };
    }

    /** Checks a menu against what the step said should be in it. */
    private ScenarioResult.StepResult checkInventory(
            int index, String action, JsonNode step, JsonNode snapshot) {

        String view = snapshot.path("view").asText();
        if (step.hasNonNull("title") || "menu".equals(step.path("which").asText("menu"))) {

            if (!moe.vitamin.minecraft.mcp.contract.InventorySnapshot.isMenu(view)) {
                return ScenarioResult.StepResult.failed(index, action,
                        "no menu is open for " + step.path("bot").asText()
                                + " — the view is " + view + ". If a command should have opened "
                                + "one, wait_for inventory_open first.",
                        snapshot.toString());
            }
        }

        String wantedTitle = step.path("title").asText(null);
        if (wantedTitle != null && !plain(snapshot.path("title").asText("")).contains(wantedTitle)) {
            return ScenarioResult.StepResult.failed(index, action,
                    "expected the title to contain '" + wantedTitle + "' but it was '"
                            + snapshot.path("title").asText("") + "'",
                    snapshot.toString());
        }

        if (step.hasNonNull("size") && snapshot.path("size").asInt() != step.path("size").asInt()) {
            return ScenarioResult.StepResult.failed(index, action,
                    "expected " + step.path("size").asInt() + " slots but the menu has "
                            + snapshot.path("size").asInt(),
                    snapshot.toString());
        }

        int checked = 0;
        for (JsonNode expected : step.path("slots")) {
            int slot = expected.path("slot").asInt();
            JsonNode actual = slotIn(snapshot, slot);
            checked++;

            if (expected.path("empty").asBoolean(false)) {
                if (actual != null) {
                    return ScenarioResult.StepResult.failed(index, action,
                            "expected slot " + slot + " to be empty but it held "
                                    + actual.path("material").asText(),
                            snapshot.toString());
                }
                continue;
            }

            if (actual == null) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " is empty, expected "
                                + expected.path("material").asText("something"),
                        snapshot.toString());
            }

            String material = expected.path("material").asText(null);
            if (material != null
                    && !material.equalsIgnoreCase(actual.path("material").asText())) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected " + material.toUpperCase(java.util.Locale.ROOT)
                                + " but held " + actual.path("material").asText(),
                        snapshot.toString());
            }

            String name = expected.path("name").asText(null);
            if (name != null && !plain(actual.path("displayName").asText("")).contains(name)) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected a name containing '" + name + "' but it was '"
                                + actual.path("displayName").asText("") + "'",
                        snapshot.toString());
            }

            if (expected.hasNonNull("amount")
                    && actual.path("amount").asInt() != expected.path("amount").asInt()) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected " + expected.path("amount").asInt()
                                + " of them but found " + actual.path("amount").asInt(),
                        snapshot.toString());
            }

            String lore = expected.path("lore").asText(null);
            if (lore != null && !plain(actual.path("lore").toString()).contains(lore)) {
                return ScenarioResult.StepResult.failed(index, action,
                        "slot " + slot + " expected lore containing '" + lore + "' but it was "
                                + actual.path("lore"),
                        snapshot.toString());
            }

            String modelString = expected.path("modelDataString").asText(null);
            if (modelString != null) {
                JsonNode strings = actual.path("modelData").path("strings");
                boolean found = false;
                for (JsonNode candidate : strings) {
                    found = found || candidate.asText().equals(modelString);
                }
                if (!found) {
                    return ScenarioResult.StepResult.failed(index, action,
                            "slot " + slot + " expected model data string '" + modelString
                                    + "' but the item has " + (strings.isMissingNode()
                                            ? "no custom_model_data component" : strings.toString()),
                            snapshot.toString());
                }
            }

            if (expected.hasNonNull("customModelData")) {
                JsonNode found = actual.path("customModelData");
                if (found.isNull() || found.isMissingNode()
                        || found.asInt() != expected.path("customModelData").asInt()) {

                    return ScenarioResult.StepResult.failed(index, action,
                            "slot " + slot + " expected customModelData "
                                    + expected.path("customModelData").asInt() + " but "
                                    + (found.isNull() || found.isMissingNode()
                                            ? "the item has none" : "it was " + found.asInt()),
                            snapshot.toString());
                }
            }
        }

        return ScenarioResult.StepResult.ok(index, action,
                view + " '" + snapshot.path("title").asText("") + "', "
                        + checked + " slot(s) as expected");
    }

    /** The listed item at a slot, or {@code null} — empty slots are omitted from the snapshot. */
    private static JsonNode slotIn(JsonNode snapshot, int slot) {
        for (JsonNode item : snapshot.path("items")) {
            if (item.path("slot").asInt() == slot) {
                return item;
            }
        }
        return null;
    }

    /** Drops legacy colour codes. */
    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    /** Copies a step's parameters into wait_for arguments, dropping the ones it does not take. */
    private ObjectNode waitArguments(JsonNode step) {
        ObjectNode arguments = AgentClient.arguments();
        step.fields().forEachRemaining(field -> {
            if (field.getKey().equals("action") || field.getKey().equals("bot")) {
                return;
            }
            arguments.set(field.getKey(), field.getValue());
        });
        if (!arguments.has("timeoutMillis")) {
            arguments.put("timeoutMillis", DEFAULT_WAIT.toMillis());
        }
        return arguments;
    }

    /** Runs an action against a named bot, turning a runner failure into a step failure. */
    private void act(JsonNode step, BotAction action) {
        String name = required(step, "bot");
        if (!bots.bots().contains(name)) {
            throw new IllegalStateException(
                    "no bot named " + name + " — spawn it before acting with it");
        }
        try {
            action.perform(new BotRunner.BotHandle(bots, name, 0, 0, 0));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        }
    }

    @FunctionalInterface
    private interface BotAction {
        void perform(BotRunner.BotHandle bot) throws java.io.IOException;
    }

    private static String required(JsonNode step, String field) {
        String value = step.path(field).asText("");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("step needs '" + field + "'");
        }
        return value;
    }

    /** Where the event stream stands right now, so assertions can be scoped to this run. */
    private long currentEventSequence() {
        try {
            String cursor = agent.call("server_info", AgentClient.arguments())
                    .path("latestEventCursor").asText("");
            return cursor.contains(":")
                    ? Long.parseLong(cursor.substring(cursor.indexOf(':') + 1))
                    : 0;
        } catch (RuntimeException e) {

            return 0;
        }
    }

    /** Server state at the moment of an unexpected failure. */
    /** Where the event and log streams stood at some instant. */
    private record Moment(String events, String logs) {}

    private Moment moment() {
        try {
            JsonNode info = agent.call("server_info", AgentClient.arguments());
            return new Moment(
                    info.path("latestEventCursor").asText(""),
                    info.path("latestLogCursor").asText(""));
        } catch (RuntimeException unreachable) {

            return new Moment("", "");
        }
    }

    /**
     * What the server did during the step that just failed.
     *
     * <p>This used to be {@code events_summary} with no window, which is every event the agent
     * still holds, counted by type and sorted by count. On a server that had been up for a while
     * that meant the evidence was led by whatever ambient event was most frequent, and the one
     * type that mattered sat far down a list with a count that silently included the scenario's
     * own earlier, successful attempts — so a failed run could produce evidence that read like a
     * success (dogfood/JOURNAL.md, 2026-08-23).
     *
     * <p>So it is the individual events and log lines from this step's own window, which is what
     * "what the server was doing at that moment" was always supposed to mean. Unfiltered by
     * player on purpose: the cause of a bot's step failing is often something that happened to
     * nobody in particular.
     */
    private String snapshot(Moment before) {
        try {
            ObjectNode events = AgentClient.arguments();
            events.put("cursor", before.events());
            events.put("limit", 25);

            ObjectNode logs = AgentClient.arguments();
            logs.put("cursor", before.logs());
            logs.put("limit", 15);

            return "events during this step=" + agent.call("events_query", events).path("items")
                    + " logs=" + agent.call("logs_query", logs).path("items");
        } catch (RuntimeException e) {
            return "(could not read server state: " + e.getMessage() + ")";
        }
    }
}

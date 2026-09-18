package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import moe.vitamin.minecraft.mcp.agent.core.AgentQueries;
import moe.vitamin.minecraft.mcp.agent.core.SequencedRingBuffer;
import moe.vitamin.minecraft.mcp.contract.Cursor;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import moe.vitamin.minecraft.mcp.contract.ExceptionGroup;
import moe.vitamin.minecraft.mcp.contract.InventorySnapshot;
import moe.vitamin.minecraft.mcp.contract.LogEntry;
import moe.vitamin.minecraft.mcp.contract.LogLevel;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import moe.vitamin.minecraft.mcp.contract.Sequenced;
import moe.vitamin.minecraft.mcp.contract.WaitCondition;

/** The tools this agent exposes, and what they do. */
final class AgentTools {

    private final AgentQueries capture;
    private final ObjectMapper mapper;
    private final ResponseBudget budget;
    private final boolean readOnly;

    /**
     * Ceiling on a caller-supplied regex, so a pathological pattern cannot be huge as well as slow.
     */
    private static final int MAX_PATTERN_LENGTH = 500;

    AgentTools(AgentQueries capture, ObjectMapper mapper, ResponseBudget budget, boolean readOnly) {
        this.capture = capture;
        this.mapper = mapper;
        this.budget = budget;
        this.readOnly = readOnly;
    }

    ArrayNode listTools() {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool("server_info",
                "Server implementation, version, TPS, online players, installed plugins, and "
                        + "capture statistics. Start here.",
                schema -> {}));

        tools.add(tool("events_summary",
                "Counts captured events by type over a time window. ALWAYS call this before "
                        + "events_query — it stays small however busy the server is, and says "
                        + "which types are worth querying in detail.",
                properties -> {
                    numberProperty(properties, "from",
                            "Window start, epoch milliseconds. Omit for everything retained.");
                    numberProperty(properties, "to",
                            "Window end, epoch milliseconds. Omit for 'up to now'.");
                }));

        tools.add(tool("events_query",
                "Reads individual captured events. High-frequency types (PlayerMoveEvent, "
                        + "BlockPhysicsEvent, ChunkLoadEvent, entity movement) are excluded "
                        + "unless you name them in 'types' explicitly. Page with 'cursor'.",
                properties -> {
                    arrayProperty(properties, "types",
                            "Event type simple names, e.g. ['BlockBreakEvent']. Naming a "
                                    + "high-frequency type is what opts into it.");
                    stringProperty(properties, "player", "Restrict to one player name.");
                    stringProperty(properties, "cursor", "Resume token from a previous response.");
                    numberProperty(properties, "limit",
                            "Maximum records, capped at " + budget.maxItems() + ".");
                }));

        tools.add(tool("logs_query",
                "Searches captured server logs by severity and regular expression; there is no "
                        + "'last N lines'. The buffer starts when the agent attaches, so a "
                        + "startup line written before that may be absent.",
                properties -> {
                    enumProperty(properties, "level", "Minimum severity.",
                            List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));
                    stringProperty(properties, "pattern",
                            "Java regular expression matched against the message.");
                    stringProperty(properties, "cursor", "Resume token from a previous response.");
                    numberProperty(properties, "limit",
                            "Maximum records, capped at " + budget.maxItems() + ".");
                }));

        tools.add(tool("exceptions_recent",
                "Distinct exceptions, most recently seen first, collapsed with occurrence "
                        + "counts and first-seen times; stack traces are omitted — pass 'hash' "
                        + "to fetch one. THIS COVERS THE CURRENT SERVER RUN ONLY: the record "
                        + "starts empty at every boot, so an empty answer means 'not since this "
                        + "server started', never 'this has never happened'.",
                properties -> {
                    numberProperty(properties, "limit", "Maximum groups.");
                    stringProperty(properties, "hash",
                            "Fetch this one exception including its full stack trace.");
                }));

        tools.add(tool("state_query",
                "Reads current server state. kind='player' needs 'target', optionally "
                        + "'permissions'. kind='block' needs x, y, z, optionally 'world'. "
                        + "kind='inventory' needs 'target' and reads the menu that player has "
                        + "open — THE ONLY PLACE A PLUGIN GUI'S CONTENTS EXIST. Slots report "
                        + "material, amount, name, lore and customModelData (what a resource "
                        + "pack draws — same-material buttons can still be different icons); "
                        + "empty slots are omitted; a 'view' of CRAFTING or CREATIVE means no "
                        + "menu is open. kind='plugin' needs 'target' and answers its commands "
                        + "with the permission gating each, its declared permissions, and its "
                        + "LIVE loaded config — often not what the file in a repository says; "
                        + "secret-looking values read '(redacted)'. A command often carries no "
                        + "'permission' because its plugin checks a node in code instead — read "
                        + "the 'permissions' list too before concluding a command is ungated.",
                properties -> {
                    enumProperty(properties, "kind", "What to read.",
                            List.of("player", "block", "inventory", "plugin"));
                    stringProperty(properties, "target",
                            "Player name for kind='player'/'inventory'; plugin name for "
                                    + "kind='plugin'.");
                    arrayProperty(properties, "permissions",
                            "Permission nodes to test. They can only be tested, not listed.");
                    stringProperty(properties, "world", "World name, for kind='block'.");
                    numberProperty(properties, "x", "Block X, for kind='block'.");
                    numberProperty(properties, "y", "Block Y, for kind='block'.");
                    numberProperty(properties, "z", "Block Z, for kind='block'.");
                    enumProperty(properties, "which",
                            "For kind='inventory': the open GUI (default), or the player's own "
                                    + "inventory.",
                            List.of("menu", "player"));
                    numberProperty(properties, "limit",
                            "For kind='inventory': most slots to list, capped at "
                                    + budget.maxItems() + ".");
                }));

        tools.add(tool("wait_for",
                "Blocks until a condition holds, then returns. USE THIS INSTEAD OF WAITING OR "
                        + "RETRYING YOURSELF — a fixed wait is right on an idle server and wrong "
                        + "on a busy one. On timeout the response carries the events and log "
                        + "lines from that moment. Wait for inventory_open before reading a "
                        + "menu — opening one is not synchronous with the command that caused "
                        + "it; use log_matches for async work that changes nothing observable. "
                        + "Chat, action bar and title NEVER reach the server-side agent, so no "
                        + "condition here can see them — assert those on the bot side "
                        + "(assert_message, bot_inspect).",
                properties -> {
                    enumProperty(properties, "condition",
                            "What to wait for. Parameters by condition: ticks (count); "
                                    + "block_is / block_is_not (world, x, y, z, material); "
                                    + "event (eventType, player, sinceSequence); "
                                    + "player_online / player_offline (name); "
                                    + "player_state (name, plus online / gameMode / op values); "
                                    + "player_near (name, x, y, z, distance); "
                                    + "inventory_open (name, title); "
                                    + "inventory_contains (name, material, slot, which); "
                                    + "log_matches (pattern, level).",
                            List.of(WaitCondition.TICKS,
                                    WaitCondition.BLOCK_IS, WaitCondition.BLOCK_IS_NOT,
                                    WaitCondition.EVENT,
                                    WaitCondition.PLAYER_ONLINE, WaitCondition.PLAYER_OFFLINE,
                                    WaitCondition.PLAYER_STATE, WaitCondition.PLAYER_NEAR,
                                    WaitCondition.INVENTORY_OPEN,
                                    WaitCondition.INVENTORY_CONTAINS,
                                    WaitCondition.LOG_MATCHES));
                    numberProperty(properties, "timeoutMillis",
                            "How long to wait before giving up. Default 10000, capped at 60000.");
                    stringProperty(properties, "eventType", "For condition='event'.");
                    stringProperty(properties, "player", "Player name, where the condition takes one.");
                    stringProperty(properties, "name", "Player name, for player_* conditions.");
                    stringProperty(properties, "material",
                            "For block_is / block_is_not / inventory_contains.");
                    stringProperty(properties, "title",
                            "For inventory_open: substring of the menu title, colour codes "
                                    + "ignored. Omit to accept any menu.");
                    numberProperty(properties, "slot",
                            "For inventory_contains: check this slot only. Omit to accept the "
                                    + "material anywhere.");
                    enumProperty(properties, "which",
                            "For inventory_contains: the open menu (default) or the player's "
                                    + "own inventory.",
                            List.of("menu", "player"));
                    stringProperty(properties, "world", "World name. Defaults to the main world.");
                    numberProperty(properties, "x", "Coordinate, where the condition takes one.");
                    numberProperty(properties, "y", "Coordinate, where the condition takes one.");
                    numberProperty(properties, "z", "Coordinate, where the condition takes one.");
                    numberProperty(properties, "count", "Ticks to advance, for condition='ticks'.");
                    numberProperty(properties, "distance", "Radius, for condition='player_near'.");
                    numberProperty(properties, "sinceSequence",
                            "For condition='event': only count events at or after this sequence. "
                                    + "Omit to count only events that happen during the wait.");
                    stringProperty(properties, "pattern",
                            "For condition='log_matches': a Java regular expression. Only lines "
                                    + "written during the wait count.");
                    enumProperty(properties, "level",
                            "For condition='log_matches': minimum severity. Omit to match any.",
                            List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));
                }));

        if (!readOnly) {
            tools.add(tool("command_exec",
                    "Runs a command, as the console by default. This CHANGES the server. "
                            + "Returns whether a handler accepted it plus whatever it logged — "
                            + "often where the real answer is. 'dispatched': false always "
                            + "carries a 'reason'. WITH 'as', THIS RESPONSE CANNOT TELL YOU "
                            + "WHETHER THE COMMAND WORKED: the reply goes to that player, so a "
                            + "refusal, an opened menu and a pending async reply all look like "
                            + "'dispatched': true with empty 'output' — read what the player "
                            + "was sent with bot_inspect before concluding anything. 'as' also "
                            + "skips PlayerCommandPreprocessEvent, so have a bot send the "
                            + "command itself when that listener is under test.",
                    properties -> {
                        stringProperty(properties, "command",
                                "The command, with or without a leading slash.");
                        stringProperty(properties, "as",
                                "Player name to run as. Omit to run as the console. Vanilla "
                                        + "commands may be gated behind minecraft.command.<name> "
                                        + "for a non-op player — op the player or use the "
                                        + "console where the command must run regardless.");
                    }));
        }

        return tools;
    }

    /** Whether a tool changes the server rather than only reading it. */
    boolean changesState(String toolName) {
        return "command_exec".equals(toolName);
    }

    /** Runs one tool. */
    JsonNode call(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;

        return switch (name) {
            case "server_info" -> serverInfo();
            case "events_summary" -> eventsSummary(args);
            case "events_query" -> eventsQuery(args);
            case "logs_query" -> logsQuery(args);
            case "exceptions_recent" -> exceptionsRecent(args);
            case "state_query" -> stateQuery(args);
            case "command_exec" -> commandExec(args);
            case "wait_for" -> waitFor(args);
            default -> throw new ToolException("Unknown tool: " + name);
        };
    }

    private JsonNode serverInfo() {
        ObjectNode result = mapper.valueToTree(capture.serverInfo());
        result.set("capture", mapper.valueToTree(capture.captureStatus()));
        result.put("readOnly", readOnly);

        result.put("latestEventCursor", capture.latestEventCursor());
        result.put("latestLogCursor", capture.latestLogCursor());
        return result;
    }

    private JsonNode eventsSummary(JsonNode args) {
        long from = args.path("from").asLong(0);
        long to = args.path("to").asLong(0);
        return mapper.valueToTree(capture.summarize(from, to));
    }

    private JsonNode eventsQuery(JsonNode args) {
        Set<String> types = stringSet(args.path("types"));
        String player = text(args.path("player"));
        String cursor = text(args.path("cursor"));
        int limit = budget.clampLimit(args.path("limit").asInt(0));

        SequencedRingBuffer.Batch<EventRecord> batch;
        try {
            batch = capture.queryEvents(cursor, types, player, limit);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        return page(batch, Cursor.EVENTS);
    }

    private JsonNode logsQuery(JsonNode args) {
        LogLevel level = parseLevel(text(args.path("level")));
        Pattern pattern = parsePattern(text(args.path("pattern")));
        String cursor = text(args.path("cursor"));
        int limit = budget.clampLimit(args.path("limit").asInt(0));

        SequencedRingBuffer.Batch<LogEntry> batch;
        try {
            batch = capture.queryLogs(cursor, level, pattern, limit);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        return page(batch, Cursor.LOGS);
    }

    private JsonNode exceptionsRecent(JsonNode args) {
        String hash = text(args.path("hash"));
        if (hash != null) {
            ExceptionGroup group = capture.exceptionByHash(hash);
            if (group == null) {
                throw new ToolException("No exception is recorded under hash: " + hash);
            }
            return mapper.valueToTree(group);
        }

        int limit = budget.clampLimit(args.path("limit").asInt(0));
        ObjectNode result = mapper.createObjectNode();
        result.set("items", mapper.valueToTree(capture.recentExceptions(limit)));
        return result;
    }

    /** Longest a caller may block a request thread. */
    private static final long MAX_WAIT_MILLIS = 60_000;

    private JsonNode waitFor(JsonNode args) {
        String type = text(args.path("condition"));
        if (type == null) {
            throw new ToolException("wait_for needs 'condition'.");
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        args.fields().forEachRemaining(field -> {
            if (field.getKey().equals("condition") || field.getKey().equals("timeoutMillis")) {
                return;
            }
            JsonNode value = field.getValue();
            if (value.isNumber()) {
                parameters.put(field.getKey(), value.numberValue());
            } else if (value.isBoolean()) {
                parameters.put(field.getKey(), value.booleanValue());
            } else if (value.isTextual()) {
                parameters.put(field.getKey(), value.asText());
            }
        });

        long millis = args.path("timeoutMillis").asLong(10_000);
        if (millis < 1) {
            throw new ToolException("timeoutMillis must be positive.");
        }

        millis = Math.min(millis, MAX_WAIT_MILLIS);

        try {
            return mapper.valueToTree(capture.waitFor(
                    new WaitCondition(type, parameters), java.time.Duration.ofMillis(millis)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
    }

    private JsonNode stateQuery(JsonNode args) {
        String kind = text(args.path("kind"));
        if (kind == null) {
            throw new ToolException("state_query needs 'kind': 'player' or 'block'.");
        }

        return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
            case "player" -> {
                String target = text(args.path("target"));
                if (target == null) {
                    throw new ToolException("state_query kind='player' needs 'target'.");
                }
                yield mapper.valueToTree(
                        capture.playerState(target, stringSet(args.path("permissions"))));
            }
            case "inventory" -> {
                String target = text(args.path("target"));
                if (target == null) {
                    throw new ToolException("state_query kind='inventory' needs 'target'.");
                }

                boolean openMenu = !"player".equalsIgnoreCase(args.path("which").asText("menu"));

                InventorySnapshot snapshot = capture.inventory(
                        target, openMenu, budget.clampLimit(args.path("limit").asInt(0)));
                if (snapshot == null) {
                    throw new ToolException("No such online player: " + target);
                }
                yield mapper.valueToTree(snapshot);
            }

            case "plugin" -> {
                String target = text(args.path("target"));
                if (target == null) {
                    throw new ToolException("state_query kind='plugin' needs 'target'.");
                }
                moe.vitamin.minecraft.mcp.contract.PluginDetail detail =
                        capture.pluginDetail(target, budget.maxItems());
                if (detail == null) {
                    throw new ToolException("No plugin named " + target
                            + ". server_info lists what is installed.");
                }
                yield mapper.valueToTree(detail);
            }

            case "block" -> {
                ObjectNode result = mapper.createObjectNode();
                int x = args.path("x").asInt();
                int y = args.path("y").asInt();
                int z = args.path("z").asInt();
                String world = text(args.path("world"));
                moe.vitamin.minecraft.mcp.contract.BlockState state =
                        capture.blockAt(world, x, y, z);
                if (state == null) {
                    throw new ToolException("No such world: " + world);
                }
                // state.world() rather than the argument, which is null whenever the caller left
                // it to the default and would make the answer unable to say what it read.
                result.put("world", state.world());
                result.put("x", state.x());
                result.put("y", state.y());
                result.put("z", state.z());
                result.put("block", state.block());
                yield result;
            }
            default -> throw new ToolException(
                    "Unknown kind '" + kind + "'. Expected 'player' or 'block'.");
        };
    }

    private JsonNode commandExec(JsonNode args) {

        if (readOnly) {
            throw new ToolException(
                    "command_exec is unavailable: this agent is running read-only. Set "
                            + "'read-only: false' in config.yml to allow it.");
        }

        String command = text(args.path("command"));
        if (command == null) {
            throw new ToolException("command_exec needs 'command'.");
        }

        String normalised = command.startsWith("/") ? command.substring(1) : command;
        if (normalised.isBlank()) {
            throw new ToolException("command_exec was given an empty command.");
        }

        try {
            return mapper.valueToTree(capture.executeCommand(
                    normalised, text(args.path("as")), java.time.Duration.ofSeconds(10)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
    }

    /** Serializes a batch, stopping at whichever limit is reached first. */
    private <T extends Sequenced> ObjectNode page(SequencedRingBuffer.Batch<T> batch, String stream) {
        ArrayNode items = mapper.createArrayNode();
        List<T> source = batch.items();

        int consumedBytes = 0;
        int included = 0;
        for (T record : source) {
            JsonNode node = mapper.valueToTree(record);
            int size = node.toString().length();
            if (included > 0 && consumedBytes + size > budget.maxBytes()) {
                break;
            }
            items.add(node);
            consumedBytes += size;
            included++;
        }

        boolean cutByBytes = included < source.size();
        boolean truncated = cutByBytes || !batch.exhausted();

        String nextCursor = null;
        if (cutByBytes) {
            nextCursor = new Cursor(stream, source.get(included - 1).sequence() + 1).encode();
        } else if (!batch.exhausted()) {
            nextCursor = new Cursor(stream, batch.nextSequence()).encode();
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        result.put("nextCursor", nextCursor);
        result.put("truncated", truncated);

        result.put("dropped", batch.dropped());
        return result;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** An array argument, however the caller managed to express it. */
    private Set<String> stringSet(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String raw = node.asText().trim();
            if (raw.isEmpty()) {
                return null;
            }
            if (raw.startsWith("[")) {
                try {
                    return stringSet(mapper.readTree(raw));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException(
                            "Could not read as a list: " + raw, e);
                }
            }

            Set<String> parsed = new LinkedHashSet<>();
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    parsed.add(value);
                }
            }
            return parsed.isEmpty() ? null : parsed;
        }
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        node.forEach(element -> {
            String value = text(element);
            if (value != null) {
                values.add(value);
            }
        });
        return values.isEmpty() ? null : values;
    }

    private static LogLevel parseLevel(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LogLevel.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown log level '" + raw
                    + "'. Expected one of TRACE, DEBUG, INFO, WARN, ERROR.");
        }
    }

    private static Pattern parsePattern(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length() > MAX_PATTERN_LENGTH) {
            throw new ToolException("Pattern is longer than " + MAX_PATTERN_LENGTH + " characters.");
        }
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regular expression: " + e.getDescription());
        }
    }

    private ObjectNode tool(String name, String description, java.util.function.Consumer<ObjectNode> properties) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.accept(props);
        schema.set("required", mapper.createArrayNode());
        return tool;
    }

    private static void stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    private static void numberProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "integer");
        property.put("description", description);
    }

    private static void arrayProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "array");
        property.put("description", description);
        property.putObject("items").put("type", "string");
    }

    private void enumProperty(ObjectNode properties, String name, String description, List<String> values) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
        ArrayNode allowed = property.putArray("enum");
        new ArrayList<>(values).forEach(allowed::add);
    }

    /** A tool-level failure: reported back to the caller as content, not as a transport error. */
    static final class ToolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }
    }
}

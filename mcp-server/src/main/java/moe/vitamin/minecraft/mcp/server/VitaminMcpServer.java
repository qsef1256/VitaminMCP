package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** The MCP server a client such as Claude Code launches. */
public final class VitaminMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    /** This build, from the jar manifest, or "dev" when running from classes rather than a jar. */
    private static final String VERSION = version();

    private static String version() {
        String declared = VitaminMcpServer.class.getPackage().getImplementationVersion();
        return declared == null ? "dev" : declared;
    }

    private final SessionTools tools = new SessionTools();
    private final SetupPrompts prompts = new SetupPrompts(VERSION);

    public static void main(String[] args) throws IOException {
        new VitaminMcpServer().run();
    }

    private void run() throws IOException {

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        System.err.println("VitaminMCP server ready on stdio");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            ObjectNode response = handle(line);
            if (response != null) {
                out.println(response);
            }
        }

        tools.close();
    }

    private ObjectNode handle(String line) {
        JsonNode request;
        try {
            request = MAPPER.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "Malformed JSON");
        }

        JsonNode id = request.has("id") ? request.get("id") : null;
        String method = request.path("method").asText("");
        JsonNode params = request.has("params") ? request.get("params") : MAPPER.createObjectNode();

        if (id == null || id.isNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> success(id, initialize(params));
                case "ping" -> success(id, MAPPER.createObjectNode());
                case "tools/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("tools", tools.listTools());
                    yield success(id, result);
                }
                case "tools/call" -> success(id, callTool(params));
                case "prompts/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("prompts", prompts.list());
                    yield success(id, result);
                }
                case "prompts/get" -> success(id, prompts.get(params));
                default -> error(id, -32601, "Unknown method: " + method);
            };
        } catch (RuntimeException e) {

            System.err.println("Error handling " + method + ": " + e);
            return error(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", negotiateProtocolVersion(
                params.path("protocolVersion").asText(PROTOCOL_VERSION)));
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        capabilities.putObject("prompts");

        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "VitaminMCP");
        info.put("version", VERSION);

        result.put("instructions",
                "Drives a Minecraft server for plugin testing: real bots connect over the "
                        + "protocol while an agent inside the server reports what happened. "
                        + "Call session_start first with the agent's token. Then either spawn "
                        + "bots and act step by step, or hand bot_run_scenario a whole scenario "
                        + "— it reports which step failed and what the server was doing at that "
                        + "moment. Prefer wait_for over waiting yourself; there is no sleep. "
                        + "On this machine session_start needs no arguments — the agent leaves "
                        + "its host, ports and token where it reads them. If no agent answers, "
                        + "the server has not got the plugin yet: the 'setup' prompt installs "
                        + "it.");
        return result;
    }

    static String negotiateProtocolVersion(String requested) {
        return SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
    }

    /** Runs a tool. */
    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        ObjectNode result = MAPPER.createObjectNode();

        try {
            JsonNode payload = tools.call(name, params.get("arguments"));
            result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", pretty(payload));
            result.put("isError", false);
        } catch (RuntimeException e) {
            result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", String.valueOf(e.getMessage()));
            result.put("isError", true);
        }
        return result;
    }

    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(node);
        }
    }

    private static ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}

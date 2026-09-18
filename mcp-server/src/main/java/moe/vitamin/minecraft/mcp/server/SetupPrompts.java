package moe.vitamin.minecraft.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The prompts this server offers, which a client surfaces as commands.
 *
 * <p>Installing the agent is the one part of this that a tool cannot do: it happens on the server,
 * before anything here can connect to it. So it is written down as a prompt instead — in Claude
 * Code that is {@code /mcp__vitaminmcp__setup} — and the client's own agent does the work, with
 * this server's tools to check it afterwards.
 */
final class SetupPrompts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RELEASES =
            "https://github.com/Backas03/VitaminMCP/releases";

    private final String version;

    SetupPrompts(String version) {
        this.version = version;
    }

    /** Every prompt, as {@code prompts/list} answers it. */
    ArrayNode list() {
        ArrayNode prompts = MAPPER.createArrayNode();

        ObjectNode setup = prompts.addObject();
        setup.put("name", "setup");
        setup.put("title", "Install VitaminMCP on a Minecraft server");
        setup.put("description",
                "Walks through putting the agent plugin on a Paper server and connecting to it: "
                        + "the jar, the restart, the server settings bots need, and the first "
                        + "session. Run this once per server.");
        ArrayNode arguments = setup.putArray("arguments");
        argument(arguments, "serverDirectory",
                "Path to the Minecraft server directory — the one holding server.properties. "
                        + "Omit and you will be asked for it.");
        argument(arguments, "bots",
                "'no' to install for inspection only, which changes nothing about the server. "
                        + "Anything else, or omitted, also covers the settings bots need.");

        return prompts;
    }

    /** One prompt, filled in, as {@code prompts/get} answers it. */
    ObjectNode get(JsonNode params) {
        String name = params.path("name").asText("");
        if (!"setup".equals(name)) {
            throw new IllegalArgumentException("Unknown prompt: " + name);
        }

        JsonNode arguments = params.path("arguments");
        String directory = arguments.path("serverDirectory").asText("");
        boolean bots = !"no".equalsIgnoreCase(arguments.path("bots").asText(""));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("description", "Install and connect the VitaminMCP agent");
        result.putArray("messages").addObject()
                .put("role", "user")
                .putObject("content")
                .put("type", "text")
                .put("text", setupText(directory, bots));
        return result;
    }

    private String setupText(String directory, boolean bots) {
        StringBuilder text = new StringBuilder();

        text.append("Set up the VitaminMCP agent on my Minecraft server, then connect to it.\n\n");

        if (directory.isBlank()) {
            text.append("First ask me where the server directory is — the one holding "
                    + "server.properties — and do not guess at it.\n\n");
        } else {
            text.append("The server directory is: ").append(directory).append("\n\n");
        }

        text.append("""
                Work through these in order, checking each before moving on, and stop and tell me \
                if one of them does not hold.

                1. Check the server is Paper 1.21 or later, or a fork of it such as Purpur. \
                Anything older will not load the agent at all, and anything that is not \
                Paper-based has no plugin API for it. Check the Java on that machine is 21 or \
                later too.

                2. Put the agent jar in the server's plugins/ directory. Download \
                VitaminMCP.jar for version %s from %s/tag/%s — ask me before downloading \
                anything, and tell me the URL you are using. If a copy is already there, check \
                its version rather than replacing it.

                3. Restart the server, then read its console. The plugin writes an auth token \
                into plugins/VitaminMCP/config.yml by itself on the first start and reports the \
                port it is listening on. If instead it refused to start, read the reason it \
                gave — it says exactly what to change — and tell me before changing anything.
                """.formatted(version, RELEASES, version));

        if (bots) {
            text.append("""

                    4. Bots change server state, so ask before setting read-only: false in \
                    plugins/VitaminMCP/config.yml, and restart after changing it. Skip this for a \
                    server that only needs inspecting — events, logs, exceptions and live state \
                    all work without bots.

                    5. Ask which bot identity the test needs. Prefer Microsoft device-code auth \
                    for a server that must keep online-mode=true; its first bot_spawn returns a \
                    URL and code, and the same call is retried after browser sign-in. The default \
                    offline bot requires online-mode=false and must never be enabled on a server \
                    reachable from the internet, because anyone who can open a socket can then \
                    impersonate anyone.

                    6. Call session_start with no arguments. On this machine it finds the host, \
                    both ports and the token by itself. Then report the server version, the TPS \
                    and the plugins it found.

                    7. Spawn one bot with the chosen auth mode to prove the path end to end, then \
                    disconnect it with session_reset.
                    """);
        } else {
            text.append("""

                    4. Call session_start with no arguments. On this machine it finds the host, \
                    both ports and the token by itself. Then report the server version, the TPS \
                    and the plugins it found.

                    Leave the server's own settings alone: an inspection-only install changes \
                    nothing about the server, and read-only is the agent's default, so it cannot \
                    alter it either.
                    """);
        }

        return text.toString();
    }

    private static void argument(ArrayNode arguments, String name, String description) {
        ObjectNode argument = arguments.addObject();
        argument.put("name", name);
        argument.put("description", description);
        argument.put("required", false);
    }
}

package moe.vitamin.minecraft.mcp.server;

import moe.vitamin.minecraft.mcp.bot.core.BotRunner;
import moe.vitamin.minecraft.mcp.testkit.AgentClient;
import moe.vitamin.minecraft.mcp.testkit.ScenarioRunner;

/** One connected server, and the bots currently on it. */
final class Session {

    private final String host;
    private final int port;
    private final AgentClient agent;
    private final java.nio.file.Path runnerJar;

    /** Defaults closed until session_start has read the agent's declared mode. */
    private boolean readOnly = true;

    /** Replaced by {@link #reset()}, which restarts the process rather than reusing it. */
    private BotRunner bots;

    Session(String host, int port, int mcpPort, String token, boolean tls, String tlsFingerprint,
            java.nio.file.Path runnerJar)
            throws java.io.IOException {
        this.host = host;
        this.port = port;
        this.agent = new AgentClient(host, mcpPort, token, tls, tlsFingerprint);
        this.runnerJar = runnerJar;
        this.bots = BotRunner.launch(runnerJar, host, port);
    }

    AgentClient agent() {
        return agent;
    }

    BotRunner bots() {
        return bots;
    }

    boolean readOnly() {
        return readOnly;
    }

    void readOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /** Whether the child runner still exists. */
    boolean isRunning() {
        return bots.isRunning();
    }

    ScenarioRunner runner() {
        return new ScenarioRunner(bots, agent);
    }

    String describe() {
        return host + ":" + port + " (" + bots.bots().size() + " bots)";
    }

    /** Disconnects every bot but keeps the session. */
    void reset() throws java.io.IOException {
        bots.close();
        bots = BotRunner.launch(runnerJar, host, port);
    }

    void close() {
        bots.close();
    }
}

package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import moe.vitamin.minecraft.mcp.agent.core.ActivityLogging;
import moe.vitamin.minecraft.mcp.agent.core.AgentSettings;
import moe.vitamin.minecraft.mcp.agent.core.CaptureService;
import moe.vitamin.minecraft.mcp.agent.core.OAuthSettings;
import moe.vitamin.minecraft.mcp.agent.core.TlsSettings;
import moe.vitamin.minecraft.mcp.contract.LocalHandshake;
import moe.vitamin.minecraft.mcp.contract.ResponseBudget;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: reads the configuration, starts capture, opens the MCP endpoint. */
public final class VitaminMcpPlugin extends JavaPlugin {

    private CaptureService capture;
    private McpHttpServer mcpServer;

    /** The port whose handshake this instance owns, or 0 when it published none. */
    private int handshakePort;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        if (!config.getBoolean("enabled", true)) {
            getLogger().info("Disabled in config.yml; not starting.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        mintTokenIfMissing(config);

        AgentSettings settings = readSettings(config);

        try {
            settings.validate();
        } catch (IllegalStateException e) {

            getLogger().severe(e.getMessage());
            if (!settings.hasAuthToken()) {
                getLogger().severe("Suggested token (paste into config.yml): " + generateToken());
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        capture = new CaptureService(this, settings);
        capture.start();
        logCaptureState(settings);

        // Nulls are omitted from serialized records — an absent field and a null field read the
        // same to a client, and the nulls were costing lines in every query page. Fields whose
        // null is meaningful are written as explicit tree nulls, which inclusion does not touch.
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        AgentTools tools = new AgentTools(
                capture, mapper, readBudget(config), settings.readOnly());
        mcpServer = new McpHttpServer(
                settings, tools, mapper, getLogger(), getPluginMeta().getVersion());

        try {
            mcpServer.start();
            publishHandshake(config, settings);
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not open the MCP endpoint on "
                    + settings.bindAddress() + ":" + settings.port(), e);
            capture.stop();
            capture = null;
            mcpServer = null;
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (handshakePort > 0) {
            LocalHandshake.remove(handshakePort);
            handshakePort = 0;
        }
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
            getLogger().info("MCP endpoint closed.");
        }
        if (capture != null) {
            Map<String, Object> status = capture.captureStatus();
            capture.stop();
            capture = null;
            getLogger().info("Capture stopped after " + status.get("eventsCaptured")
                    + " events and " + status.get("logsCaptured") + " log entries.");
        }
    }

    /** Says on the console what is being captured and what is not. */
    private void logCaptureState(AgentSettings settings) {
        Map<String, Object> status = capture.captureStatus();
        getLogger().info("Capturing " + status.get("eventTypesRegistered") + " event types into a "
                + settings.eventBufferSize() + "-record buffer; log capture "
                + (Boolean.TRUE.equals(status.get("logCaptureActive"))
                        ? "attached (" + settings.logBufferSize() + " records)"
                        : "UNAVAILABLE, so logs_query will stay empty") + ".");

        if (!settings.captureHighFrequency()) {
            getLogger().info("High-frequency events are not being captured "
                    + "(capture-high-frequency: false in config.yml).");
        }
        if (settings.activityLog() != ActivityLogging.FULL) {
            getLogger().info("Activity logging is set to '"
                    + settings.activityLog().name().toLowerCase(java.util.Locale.ROOT)
                    + "'; refused tokens and state-changing calls are still logged.");
        }
    }

    /** The response ceiling, from config.yml or the shipped default. */
    private static ResponseBudget readBudget(FileConfiguration config) {
        return new ResponseBudget(
                config.getInt("max-response-items", ResponseBudget.DEFAULT.maxItems()),
                config.getInt("max-response-bytes", ResponseBudget.DEFAULT.maxBytes()));
    }

    private AgentSettings readSettings(FileConfiguration config) {
        return new AgentSettings(
                config.getString("bind-address", AgentSettings.DEFAULT_BIND_ADDRESS),
                config.getInt("port", AgentSettings.DEFAULT_PORT),
                config.getString("auth-token", ""),
                config.getBoolean("read-only", true),
                config.getInt("event-buffer-size", 100_000),
                config.getInt("log-buffer-size", 20_000),
                config.getInt("max-exception-groups", 1_000),
                config.getBoolean("capture-high-frequency", false),
                stringList(config, "extra-high-frequency"),
                stringList(config, "reinstate-types"),
                stringList(config, "scan-packages"),
                readOAuth(config),
                readTls(config),
                ActivityLogging.parse(config.getString("activity-log", "full")));
    }

    /** Reads the OAuth block. */
    private static OAuthSettings readOAuth(FileConfiguration config) {
        if (!config.getBoolean("oauth.enabled", false)) {
            return OAuthSettings.disabled();
        }
        return new OAuthSettings(
                true,
                config.getString("oauth.issuer", ""),
                config.getString("oauth.introspection-url", ""),
                config.getString("oauth.client-id", ""),
                config.getString("oauth.client-secret", ""),
                config.getString("oauth.resource-url", ""),
                stringList(config, "oauth.required-scopes"));
    }

    /** Reads the TLS block. */
    private static TlsSettings readTls(FileConfiguration config) {
        return new TlsSettings(
                config.getBoolean("tls.enabled", false),
                config.getString("tls.keystore", ""),
                config.getString("tls.keystore-password", ""),
                config.getBoolean("tls.terminated-upstream", false));
    }

    private static List<String> stringList(FileConfiguration config, String path) {
        return config.isList(path) ? List.copyOf(config.getStringList(path)) : List.of();
    }

    /**
     * Puts a token in config.yml when there is none, so a first start succeeds.
     *
     * <p>Refusing to start unauthenticated is the invariant (design.md §14); making an operator
     * hand-copy the token out of a crash log was never part of it. What is required is that the
     * endpoint never opens without a secret, and minting one satisfies that at least as well as
     * refusing does — the endpoint that comes up is authenticated either way.
     *
     * <p>The refusal is kept for the case that matters: if the token cannot be written, this
     * returns having changed nothing and {@code validate()} still stops the start. A token held
     * only in memory would be a token nobody can use and that changes every restart.
     */
    private void mintTokenIfMissing(FileConfiguration config) {
        if (!config.getString("auth-token", "").isBlank()) {
            return;
        }

        String token = generateToken();
        try {
            config.set("auth-token", token);
            saveConfig();
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not write a generated token to config.yml", e);
            return;
        }

        getLogger().info("No auth token was configured, so one was generated and written to "
                + "config.yml: " + token);
        // Plain ASCII on purpose: a Windows console renders an em dash in this line as '?', and
        // this is a line an operator has to read to get their bearings.
        getLogger().info("A client on this machine does not need it: session_start finds it "
                + "itself. Copy it only for a client somewhere else.");
    }

    /**
     * Leaves the connection details where a client on this machine can find them.
     *
     * <p>Turns the four things session_start had to be told into none of them. Off by
     * {@code local-handshake: false} for anyone who would rather nothing were written outside the
     * server directory.
     */
    private void publishHandshake(FileConfiguration config, AgentSettings settings) {
        if (!config.getBoolean("local-handshake", true)) {
            return;
        }

        try {
            new LocalHandshake(
                    advertisedHost(settings),
                    settings.port(),
                    getServer().getPort(),
                    settings.authToken(),
                    getPluginMeta().getVersion(),
                    getServer().getVersion()).write();
            handshakePort = settings.port();
        } catch (IOException | RuntimeException e) {

            // Nothing about the endpoint depends on this file; a client can always be told the
            // details instead. So it is worth a line on the console and no more.
            getLogger().log(Level.WARNING,
                    "Could not write the local handshake to " + LocalHandshake.directory()
                            + "; session_start on this machine will need the token passing in", e);
        }
    }

    /** The address a client on this machine should dial to reach this endpoint. */
    private static String advertisedHost(AgentSettings settings) {
        String bind = settings.bindAddress();
        return bind.isBlank() || "0.0.0.0".equals(bind) || "::".equals(bind)
                ? LocalHandshake.LOCAL_HOST
                : bind;
    }

    /** A token an operator can paste, so refusing to start still leaves an obvious next step. */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

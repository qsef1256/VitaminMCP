package moe.vitamin.minecraft.mcp.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import moe.vitamin.minecraft.mcp.agent.core.AgentSettings;

/** Serves MCP over HTTP using the JDK's built-in {@link HttpServer}. */
public final class McpHttpServer {

    /** The single MCP endpoint. */
    private static final String ENDPOINT = "/mcp";

    /** RFC 9728 discovery path. */
    private static final String PROTECTED_RESOURCE_METADATA =
            "/.well-known/oauth-protected-resource";

    /** Revision implemented here. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** Revisions accepted from a client. */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    /** Ceiling on a request body. */
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final AgentSettings settings;
    private final AgentTools tools;
    private final ObjectMapper mapper;
    private final Logger logger;
    private final String serverVersion;
    private final BearerTokenVerifier tokens;
    private final ActivityLog activity;

    /**
     * Threads serving requests that answer immediately.
     *
     * <p>Small on purpose: every one of these returns in milliseconds, so more would buy nothing.
     * What used to make four too few was {@code wait_for} sitting in this pool for up to a minute
     * at a time — four of those and every other client, in every other session, queued behind
     * them. Those now run somewhere else.
     */
    private static final int REQUEST_THREADS = 8;

    /**
     * How many {@code wait_for} calls may be in flight at once.
     *
     * <p>Bounded rather than unbounded because this endpoint can be exposed to a network, and an
     * unbounded pool would let anyone holding the token turn a minute-long wait into as many
     * threads as they cared to ask for. Past the limit a caller is refused outright — a wait that
     * silently queued would report the timeout it never actually spent waiting for.
     */
    private static final int WAIT_THREADS = 32;

    private HttpServer server;
    private ExecutorService executor;

    /** Where a wait blocks, so it never occupies a thread another session needs. */
    private ExecutorService waits;

    public McpHttpServer(
            AgentSettings settings,
            AgentTools tools,
            ObjectMapper mapper,
            Logger logger,
            String serverVersion) {
        this.settings = settings;
        this.tools = tools;
        this.mapper = mapper;
        this.logger = logger;
        this.serverVersion = serverVersion;
        this.tokens = new BearerTokenVerifier(settings.authToken(), settings.oauth(), logger);
        this.activity = new ActivityLog(logger, settings.activityLog());
    }

    public void start() throws IOException {
        settings.validate();

        InetSocketAddress address =
                new InetSocketAddress(InetAddress.getByName(settings.bindAddress()), settings.port());
        server = settings.tls().enabled()
                ? createHttpsServer(address)
                : HttpServer.create(address, 0);
        server.createContext(ENDPOINT, this::handle);

        server.createContext(PROTECTED_RESOURCE_METADATA, this::describeProtectedResource);

        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newFixedThreadPool(REQUEST_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "VitaminMCP-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        // A SynchronousQueue rather than the unbounded one a fixed pool would get: with no queue
        // to absorb it, submitting past the limit is rejected there and then, which is the whole
        // point — the caller learns it was refused instead of waiting for a turn.
        AtomicInteger waitNumber = new AtomicInteger();
        waits = new java.util.concurrent.ThreadPoolExecutor(
                0, WAIT_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable, "VitaminMCP-wait-" + waitNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        server.start();

        logger.info("MCP endpoint listening on " + scheme() + "://" + settings.bindAddress() + ":"
                + boundPort() + ENDPOINT + (settings.readOnly() ? " (read-only)" : ""));

        if (settings.isExternallyReachable()) {
            logger.warning("The MCP endpoint is reachable from outside this machine. Anyone "
                    + "holding the token can read server internals"
                    + (settings.readOnly() ? "" : " and run console commands")
                    + ". Keep the token secret and rotate it if it leaks.");
            if (settings.tls().terminatedUpstream()) {
                logger.warning("tls.terminated-upstream is set, so this agent is serving plain "
                        + "HTTP and trusting whatever is in front of it to terminate TLS. If "
                        + "nothing is, the token is crossing the network in clear text.");
            }
            logConnectionDetails();
        }
    }

    /** Prints what a client needs, in the shape it needs it. */
    private void logConnectionDetails() {
        StringBuilder block = new StringBuilder("Connect with session_start:")
                .append(System.lineSeparator())
                .append("  \"host\": \"").append(reachableHost()).append("\",")
                .append(" \"mcpPort\": ").append(settings.port()).append(",")
                .append(" \"tls\": \"").append(settings.tls().enabled()).append("\",")
                .append(System.lineSeparator())
                .append("  \"token\": \"").append(settings.authToken()).append("\"");

        certificateFingerprint().ifPresent(fingerprint -> block
                .append(",").append(System.lineSeparator())
                .append("  \"tlsFingerprint\": \"sha256:").append(fingerprint).append("\""));

        logger.info(block.toString());
    }

    /** A host a client could actually dial. */
    private String reachableHost() {
        if (!"0.0.0.0".equals(settings.bindAddress()) && !"::".equals(settings.bindAddress())) {
            return settings.bindAddress();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            return "<this server's address>";
        }
    }

    /** SHA-256 of the certificate being served, as lowercase hex, if TLS is on. */
    private java.util.Optional<String> certificateFingerprint() {
        if (!settings.tls().enabled()) {
            return java.util.Optional.empty();
        }
        try {
            char[] password = settings.tls().keystorePassword().toCharArray();
            java.security.KeyStore keystore = java.security.KeyStore.getInstance("PKCS12");
            try (var in = java.nio.file.Files.newInputStream(
                    java.nio.file.Path.of(settings.tls().keystore()))) {
                keystore.load(in, password);
            }
            java.util.Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                java.security.cert.Certificate certificate =
                        keystore.getCertificate(aliases.nextElement());
                if (certificate != null) {
                    byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(certificate.getEncoded());
                    StringBuilder hex = new StringBuilder(digest.length * 2);
                    for (byte b : digest) {
                        hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                .append(Character.forDigit(b & 0xF, 16));
                    }
                    return java.util.Optional.of(hex.toString());
                }
            }
            return java.util.Optional.empty();
        } catch (java.security.GeneralSecurityException | IOException e) {

            logger.warning("Could not read the certificate to print its fingerprint: " + e);
            return java.util.Optional.empty();
        }
    }

    /** An HTTPS server backed by the configured keystore. */
    private HttpServer createHttpsServer(InetSocketAddress address) throws IOException {
        try {
            char[] password = settings.tls().keystorePassword().toCharArray();
            java.security.KeyStore keystore = java.security.KeyStore.getInstance("PKCS12");
            try (var in = java.nio.file.Files.newInputStream(
                    java.nio.file.Path.of(settings.tls().keystore()))) {
                keystore.load(in, password);
            }

            var keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keystore, password);

            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(keyManagers.getKeyManagers(), null, null);

            com.sun.net.httpserver.HttpsServer https =
                    com.sun.net.httpserver.HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl));
            return https;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Could not load the TLS keystore at "
                    + settings.tls().keystore() + ": " + e.getMessage(), e);
        }
    }

    /** The scheme this endpoint is reachable on, for messages and metadata. */
    private String scheme() {
        return settings.tls().enabled() ? "https" : "http";
    }

    /** The port actually bound, which differs from the configured one when that was 0. */
    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (waits != null) {
            waits.shutdownNow();
            waits = null;
        }
    }

    /**
     * Serves one request, unless it is a wait — those are finished by another thread.
     *
     * <p>Not try-with-resources any more. A handed-off wait answers long after this method
     * returns, so closing the exchange here would cut the caller off mid-request; {@code
     * handedOff} is what says who owns it.
     */
    private void handle(HttpExchange exchange) throws IOException {
        String client = clientOf(exchange);
        boolean handedOff = false;
        try {
            String refusal = tokens.refuse(exchange.getRequestHeaders().getFirst("Authorization"));
            if (refusal != null) {
                activity.refused(client, refusal);

                exchange.getResponseHeaders().add("WWW-Authenticate",
                        "Bearer error=\"invalid_token\", error_description=\"" + refusal
                                + "\", resource_metadata=\"" + scheme() + "://" + settings.bindAddress()
                                + ":" + boundPort() + PROTECTED_RESOURCE_METADATA + "\"");
                respond(exchange, 401,
                        "{\"error\":\"unauthorized\",\"reason\":\"" + refusal + "\"}");
                return;
            }

            String method = exchange.getRequestMethod();
            switch (method) {
                case "POST" -> handedOff = handlePost(exchange, client);

                case "GET" -> {
                    activity.malformed(client, "GET, but this endpoint accepts POST only");
                    respond(exchange, 405, "{\"error\":\"this endpoint accepts POST only\"}");
                }

                case "DELETE" -> respond(exchange, 204, "");
                default -> {
                    activity.malformed(client, "unsupported HTTP method " + method);
                    respond(exchange, 405, "{\"error\":\"unsupported method\"}");
                }
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Unhandled error serving an MCP request from " + client, e);
            safelyRespond(exchange, 500, "{\"error\":\"internal error\"}");
        } finally {
            if (!handedOff) {
                exchange.close();
            }
        }
    }

    /** @return {@code true} when a wait took the exchange and will close it itself */
    private boolean handlePost(HttpExchange exchange, String client) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) {
            activity.malformed(client, "the request body exceeded " + MAX_REQUEST_BYTES + " bytes");
            respond(exchange, 413, "{\"error\":\"request too large\"}");
            return false;
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (IOException e) {
            activity.malformed(client, "malformed JSON");
            writeJson(exchange, 400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Malformed JSON"));
            return false;
        }

        if (payload == null || !payload.isObject()) {
            String problem = payload != null && payload.isArray()
                    ? "Batched requests are not supported by this protocol revision"
                    : "Expected a JSON-RPC object";
            activity.malformed(client, problem);
            writeJson(exchange, 400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, problem));
            return false;
        }

        JsonRpc.Request request = new JsonRpc.Request(
                payload.has("id") ? payload.get("id") : null,
                payload.path("method").asText(""),
                payload.has("params") ? payload.get("params") : mapper.createObjectNode());

        if (request.isNotification()) {

            activity.notification(client, request.method());
            dispatch(request);

            respond(exchange, 202, "");
            return false;
        }

        if (blocks(request)) {
            return handOffToWaitPool(exchange, client, request);
        }

        serve(exchange, client, request);
        return false;
    }

    /**
     * Whether this call will sit on its thread rather than answering.
     *
     * <p>{@code wait_for} is the only tool that blocks by design — it holds its thread for up to a
     * minute while a condition comes true, which is exactly what makes it the one call that must
     * not be served from the pool everything else shares.
     */
    private static boolean blocks(JsonRpc.Request request) {
        return "tools/call".equals(request.method())
                && "wait_for".equals(request.params().path("name").asText(""));
    }

    /**
     * Hands a wait to its own pool, or refuses it.
     *
     * <p>The exchange goes with it and is answered from there, so the request thread is free again
     * immediately — which is the whole point: before this, four concurrent waits held every thread
     * the server had and each further request, from any session, queued behind them for as long as
     * a wait had left to run.
     *
     * @return {@code true} if the wait pool took it, meaning it now owns the exchange
     */
    private boolean handOffToWaitPool(HttpExchange exchange, String client, JsonRpc.Request request)
            throws IOException {
        try {
            waits.execute(() -> {
                try {
                    serve(exchange, client, request);
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Unhandled error serving a wait from " + client, e);
                    safelyRespond(exchange, 500, "{\"error\":\"internal error\"}");
                } finally {
                    exchange.close();
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            String problem = WAIT_THREADS + " wait_for calls are already running, which is the "
                    + "limit. Retry when one finishes, or shorten the timeouts you are asking for.";
            activity.malformed(client, problem);
            writeJson(exchange, 429, JsonRpc.error(request.id(), JsonRpc.INTERNAL_ERROR, problem));
            return false;
        }
    }

    /** Runs a request and writes what it produced. */
    private void serve(HttpExchange exchange, String client, JsonRpc.Request request)
            throws IOException {
        ActivityLog.Call call = begin(client, request);
        ObjectNode response = dispatch(request);
        record(call, response);

        if (response == null) {
            respond(exchange, 202, "");
            return;
        }
        writeJson(exchange, 200, response);
    }

    /** The caller's address, which is what distinguishes a local client from a remote one. */
    private static String clientOf(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null
                ? "unknown"
                : remote.getAddress().getHostAddress() + ":" + remote.getPort();
    }

    /** Opens the console record for a call. */
    private ActivityLog.Call begin(String client, JsonRpc.Request request) {
        boolean isToolCall = "tools/call".equals(request.method());
        String toolName = isToolCall ? request.params().path("name").asText("") : null;
        JsonNode arguments = isToolCall ? request.params().get("arguments") : request.params();

        return activity.begin(
                client, request.method(), toolName, arguments, tools.changesState(toolName));
    }

    /** Closes that record with whatever the caller was sent. */
    private static void record(ActivityLog.Call call, ObjectNode response) {
        if (response == null) {
            call.succeeded(null);
            return;
        }
        JsonNode error = response.get("error");
        if (error != null) {
            call.failed(error.path("message").asText(""));
            return;
        }

        JsonNode result = response.path("result");
        String text = result.path("content").path(0).path("text").asText(null);
        if (result.path("isError").asBoolean()) {
            call.failed(text == null ? result.toString() : text);
            return;
        }

        call.succeeded(text == null ? result.toString() : text);
    }

    private ObjectNode dispatch(JsonRpc.Request request) {
        if (request.method().isEmpty()) {
            return JsonRpc.error(request.id(), JsonRpc.INVALID_REQUEST, "Missing 'method'");
        }

        if (request.isNotification()) {
            return null;
        }

        try {
            return switch (request.method()) {
                case "initialize" -> JsonRpc.success(request.id(), initialize(request.params()));
                case "ping" -> JsonRpc.success(request.id(), mapper.createObjectNode());
                case "tools/list" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.set("tools", tools.listTools());
                    yield JsonRpc.success(request.id(), result);
                }
                case "tools/call" -> JsonRpc.success(request.id(), callTool(request.params()));
                default -> JsonRpc.error(request.id(), JsonRpc.METHOD_NOT_FOUND,
                        "Unknown method: " + request.method());
            };
        } catch (IllegalArgumentException e) {
            return JsonRpc.error(request.id(), JsonRpc.INVALID_PARAMS, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error handling " + request.method(), e);
            return JsonRpc.error(request.id(), JsonRpc.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText(PROTOCOL_VERSION);
        String negotiated =
                SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);

        result.putObject("capabilities").putObject("tools");

        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "VitaminMCP");
        info.put("version", serverVersion);

        result.put("instructions",
                "Observability for a running Minecraft server. Call server_info first to see "
                        + "what this server is and how much has been captured. For events, call "
                        + "events_summary before events_query — the summary is small no matter "
                        + "how busy the server is and tells you which types to ask for. Every "
                        + "query response carries 'truncated' (page again with nextCursor) and "
                        + "'dropped' (records lost to buffer overflow and gone for good); a "
                        + "non-zero 'dropped' means what you are seeing is incomplete.");
        return result;
    }

    /** Runs a tool and wraps the outcome as MCP content. */
    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Missing tool name");
        }

        ObjectNode result = mapper.createObjectNode();
        try {
            JsonNode payload = tools.call(name, params.get("arguments"));
            result.putArray("content")
                    .addObject()
                    .put("type", "text")
                    .put("text", writeText(payload));
            result.put("isError", false);
        } catch (AgentTools.ToolException e) {
            result.putArray("content")
                    .addObject()
                    .put("type", "text")
                    .put("text", e.getMessage());
            result.put("isError", true);
        }
        return result;
    }

    /** Compact on purpose: the reader is a model, and pretty-printing only costs its context. */
    private String writeText(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return payload.toString();
        }
    }

    /** Serves the metadata that tells a client how to authenticate (RFC 9728). */
    private void describeProtectedResource(HttpExchange exchange) throws IOException {
        try (exchange) {
            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("resource", settings.oauth().resourceUrl().isEmpty()
                    ? scheme() + "://" + settings.bindAddress() + ":" + boundPort() + ENDPOINT
                    : settings.oauth().resourceUrl());

            if (settings.oauth().enabled()) {
                metadata.putArray("authorization_servers").add(settings.oauth().issuer());
                if (!settings.oauth().requiredScopes().isEmpty()) {
                    var scopes = metadata.putArray("scopes_supported");
                    settings.oauth().requiredScopes().forEach(scopes::add);
                }
            }
            metadata.putArray("bearer_methods_supported").add("header");

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, metadata.toString());
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
            return body.length > MAX_REQUEST_BYTES ? null : body;
        }
    }

    private void writeJson(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        respond(exchange, status, body.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void safelyRespond(HttpExchange exchange, int status, String body) {
        try {
            respond(exchange, status, body);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not write the error response", e);
        }
    }
}

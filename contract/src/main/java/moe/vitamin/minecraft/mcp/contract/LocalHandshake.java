package moe.vitamin.minecraft.mcp.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What an agent leaves behind so a client on the same machine does not have to be told the token.
 *
 * <p>The agent writes one of these per MCP port when it starts; {@code session_start} reads it when
 * the caller omitted the connection details. It only ever helps a client running as the same OS
 * user on the same machine — which is the local development case, and the only one where the file
 * is readable at all. A remote server is still connected to by hand.
 *
 * <p>This is deliberately not new exposure: anyone who can read this file can already read
 * {@code plugins/VitaminMCP/config.yml}, where the same token sits in the clear.
 *
 * <p>Java {@link Properties} rather than JSON, because {@code contract} takes no dependencies
 * and a hand-rolled parser is a worse thing to own than a boring format.
 */
public record LocalHandshake(
        String host,
        int mcpPort,
        int minecraftPort,
        String token,
        String version,
        String server) {

    /** Loopback — the only host an agent can truthfully advertise about itself. */
    public static final String LOCAL_HOST = "127.0.0.1";

    private static final String SUFFIX = ".properties";

    public LocalHandshake {
        host = host == null || host.isBlank() ? LOCAL_HOST : host;
        token = Objects.requireNonNull(token, "token");
        version = version == null ? "" : version;
        server = server == null ? "" : server;

        if (token.isBlank()) {
            throw new IllegalArgumentException("A handshake without a token is of no use");
        }
        if (mcpPort < 1 || mcpPort > 65535) {
            throw new IllegalArgumentException("mcpPort out of range: " + mcpPort);
        }
        if (minecraftPort < 1 || minecraftPort > 65535) {
            throw new IllegalArgumentException("minecraftPort out of range: " + minecraftPort);
        }
    }

    /**
     * Where handshakes live: {@code $VITAMINMCP_HOME/agents}, or {@code ~/.vitaminmcp/agents}.
     *
     * <p>The same root the npm wrapper caches jars in, so one variable moves everything.
     */
    public static Path directory() {
        String home = System.getenv("VITAMINMCP_HOME");
        Path root = home == null || home.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".vitaminmcp")
                : Path.of(home);
        return root.resolve("agents");
    }

    /** The file an agent on {@code mcpPort} writes. */
    public static Path fileFor(int mcpPort) {
        return directory().resolve(mcpPort + SUFFIX);
    }

    /** Writes this handshake, readable only by the user running the server where that is enforced. */
    public void write() throws IOException {
        Path file = fileFor(mcpPort);
        Files.createDirectories(file.getParent());

        Properties properties = new Properties();
        properties.setProperty("host", host);
        properties.setProperty("mcp-port", Integer.toString(mcpPort));
        properties.setProperty("minecraft-port", Integer.toString(minecraftPort));
        properties.setProperty("token", token);
        properties.setProperty("version", version);
        properties.setProperty("server", server);

        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "VitaminMCP agent — written at startup, removed at shutdown. "
                    + "Contains the auth token; do not share.");
        }
        restrictToOwner(file);
    }

    /** Reads the handshake for one MCP port, or empty when no agent left one. */
    public static Optional<LocalHandshake> read(int mcpPort) {
        return readFile(fileFor(mcpPort));
    }

    /** Every handshake on this machine, ordered by MCP port. */
    public static List<LocalHandshake> readAll() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<LocalHandshake> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .forEach(file -> readFile(file).ifPresent(found::add));
        } catch (IOException e) {
            return List.of();
        }
        found.sort((left, right) -> Integer.compare(left.mcpPort(), right.mcpPort()));
        return List.copyOf(found);
    }

    /** Removes the handshake for one MCP port. Silent when there is none. */
    public static void remove(int mcpPort) {
        try {
            Files.deleteIfExists(fileFor(mcpPort));
        } catch (IOException e) {
            // A stale handshake is a failed connection with a clear message, not a reason to
            // fail shutdown.
        }
    }

    private static Optional<LocalHandshake> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }

        try {
            return Optional.of(new LocalHandshake(
                    properties.getProperty("host", LOCAL_HOST),
                    Integer.parseInt(properties.getProperty("mcp-port", "")),
                    Integer.parseInt(properties.getProperty("minecraft-port", "")),
                    properties.getProperty("token", ""),
                    properties.getProperty("version", ""),
                    properties.getProperty("server", "")));
        } catch (IllegalArgumentException e) {

            // A truncated or hand-edited file is the same as no file: the caller falls back to
            // being told the details, which is the path that always works.
            return Optional.empty();
        }
    }

    /**
     * Narrows the file to its owner where the filesystem can say so.
     *
     * <p>On Windows there is no POSIX view; a file under the user's profile already carries an
     * owner-scoped ACL, so there is nothing to tighten.
     */
    private static void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows, or a filesystem without POSIX permissions.
        }
    }

    /** How this handshake would be described on a console, token withheld. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s:%d (Minecraft %d)%s",
                host, mcpPort, minecraftPort, server.isBlank() ? "" : " — " + server);
    }
}

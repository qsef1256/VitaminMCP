package moe.vitamin.minecraft.mcp.bot.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.ClientMessage;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import moe.vitamin.minecraft.mcp.bot.spi.MenuItem;
import moe.vitamin.minecraft.mcp.bot.spi.OpenMenu;
import moe.vitamin.minecraft.mcp.bot.spi.Scoreboard;

/** A bot runner process, and the bots inside it. */
public final class BotRunner implements AutoCloseable {

    /** How long a single command may take before the runner is presumed wedged. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    private final Process process;
    private final BufferedWriter toRunner;
    private final BufferedReader fromRunner;
    private final List<String> live = new ArrayList<>();
    private int protocol;

    private BotRunner(Process process) {
        this.process = process;
        this.toRunner = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromRunner = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Launches the runner and waits until it is ready. */
    public static BotRunner launch(Path runnerPath, String host, int port)
            throws IOException {
        Objects.requireNonNull(runnerPath, "runnerPath");

        Process process = new ProcessBuilder(commandFor(runnerPath, host, port))

                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        BotRunner runner = new BotRunner(process);
        String ready = runner.fromRunner.readLine();
        String[] fields = RunnerProtocol.decode(ready == null ? "" : ready);
        if (fields.length == 0 || !RunnerProtocol.READY.equals(fields[0])) {
            runner.close();

            throw new IOException("The bot runner did not start. It said: " + ready);
        }

        runner.protocol = fields.length > 1 ? Integer.parseInt(fields[1]) : 0;
        return runner;
    }

    /** The protocol the loaded backend speaks, or 0 if the runner did not say. */
    public int protocol() {
        return protocol;
    }

    /**
     * How to start this runner, decided by what it is.
     *
     * The runner is a separate process precisely so the bot implementation can be swapped without
     * anything above this line noticing, and a JavaScript runner is that swap. Both are launched
     * the same way and answer the same protocol, so which one is in use is a path and nothing
     * more — which is what lets the two be run against the same server on the same afternoon.
     */
    static List<String> commandFor(Path runner, String host, int port) {
        String path = runner.toAbsolutePath().toString();
        List<String> command = new ArrayList<>();

        if (isScript(runner)) {
            command.add(node());
            command.add(path);
        } else {
            command.add(path);
        }

        command.add(host);
        command.add(String.valueOf(port));
        return command;
    }

    /** How many fields an {@code inspect} reply carries, message sequence and stream id included. */
    private static final int INSPECT_FIELDS = 17;

    /** Decodes one {@code inspect} reply from the runner line protocol. */
    static ClientView parseInspect(String[] reply) {
        // Said plainly, because the runner is replaceable: VITAMINMCP_RUNNER_JAR can point at any
        // runner, and one built before message cursors existed answers with a shorter reply. Left
        // to the field reads below that arrives as an index out of bounds naming nothing.
        if (reply.length < INSPECT_FIELDS) {
            throw new IllegalStateException(
                    "This runner answered inspect with " + reply.length + " fields; "
                            + INSPECT_FIELDS + " are expected. It predates message timestamps and "
                            + "cursors — use the runner that ships with this version.");
        }

        int containerId = Integer.parseInt(reply[2]);

        List<MenuItem> items = new ArrayList<>();
        for (String record : RunnerProtocol.records(reply[4])) {
            String[] parts = RunnerProtocol.fields(record);
            items.add(new MenuItem(
                    Integer.parseInt(parts[0]), parts[1],
                    Integer.parseInt(parts[2]), parts[3], parts[4], parts[5]));
        }

        List<ClientMessage> messages = new ArrayList<>();
        for (String record : RunnerProtocol.records(reply[5])) {
            String[] parts = RunnerProtocol.fields(record);
            messages.add(new ClientMessage(
                    Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]));
        }

        List<BossBar> bossBars = new ArrayList<>();
        for (String record : RunnerProtocol.records(reply[6])) {
            String[] parts = RunnerProtocol.fields(record);
            bossBars.add(new BossBar(parts[0], Float.parseFloat(parts[1]), parts[2]));
        }

        return new ClientView(
                containerId < 0 ? null : new OpenMenu(containerId, reply[3]),
                List.copyOf(items),
                List.copyOf(messages),
                Long.parseLong(reply[15]),
                reply[16],
                List.copyOf(bossBars),
                reply[7].isEmpty() ? null : new Scoreboard(
                        reply[7], List.of(RunnerProtocol.records(reply[8]))),
                reply.length > 9 && !reply[9].isEmpty() ? Float.parseFloat(reply[9]) : null,
                reply.length > 10 && !reply[10].isEmpty() ? Integer.parseInt(reply[10]) : null,
                reply.length > 11 && !reply[11].isEmpty() ? Integer.parseInt(reply[11]) : null,
                reply.length > 12 && !reply[12].isEmpty() ? Integer.parseInt(reply[12]) : null,
                reply.length > 13 && !reply[13].isEmpty() ? Float.parseFloat(reply[13]) : null,
                reply.length > 14 ? List.of(RunnerProtocol.records(reply[14])) : List.of());
    }

    private static boolean isScript(Path runner) {
        String name = runner.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mjs") || name.endsWith(".js");
    }

    /**
     * The node to run a script runner with.
     *
     * {@code VITAMINMCP_NODE} wins for the same reason {@code JAVA_HOME} does on the other side: a
     * machine with several runtimes usually means one of them was chosen deliberately, and PATH is
     * the one nobody remembers setting.
     */
    private static String node() {
        String configured = System.getenv("VITAMINMCP_NODE");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "node.exe"
                : "node";
    }

    /** Connects a bot and waits until it is standing in the world. */
    public BotHandle spawn(String name) throws IOException {
        return spawn(name, null);
    }

    /** Connects a bot that claims to be connecting from a particular address. */
    public BotHandle spawn(String name, String clientIp) throws IOException {
        return spawn(name, clientIp, "offline", null);
    }

    /** Connects an offline bot or a Microsoft-authenticated Minecraft account. */
    public BotHandle spawn(String name, String clientIp, String auth, String account)
            throws IOException {
        String[] reply = send(RunnerProtocol.SPAWN, name, clientIp == null ? "" : clientIp,
                auth == null ? "offline" : auth, account == null ? "" : account);
        String playerName = reply.length > 5 && !reply[5].isBlank() ? reply[5] : name;
        String uuid = reply.length > 6 && !reply[6].isBlank()
                ? reply[6]
                : BotIdentity.offlineUuid(name).toString();
        if ("microsoft".equalsIgnoreCase(auth) && reply.length <= 6) {
            throw new IOException(
                    "This bot runner predates Microsoft authentication; use the runner that "
                            + "ships with this MCP server version.");
        }
        live.add(name);
        return new BotHandle(this, name,
                Double.parseDouble(reply[2]), Double.parseDouble(reply[3]),
                Double.parseDouble(reply[4]), playerName, uuid);
    }

    public void despawn(String name) throws IOException {
        send(RunnerProtocol.DESPAWN, name);
        live.remove(name);
    }

    public boolean isRunning() {
        return process.isAlive();
    }

    /** Bots currently connected through this runner. */
    public List<String> bots() {
        return List.copyOf(live);
    }

    /** Asks the Node runner whether a loaded route exists without moving a bot. */
    public boolean assertReachable(String name, double x, double y, double z, long timeoutMillis)
            throws IOException {
        String[] reply = send(RunnerProtocol.ASSERT_REACHABLE, name == null ? "" : name,
                String.valueOf(x), String.valueOf(y), String.valueOf(z),
                String.valueOf(timeoutMillis));
        return reply.length > 2 && Boolean.parseBoolean(reply[2]);
    }

    /** Sends one command and returns its reply fields. */
    synchronized String[] send(String... command) throws IOException {
        if (!process.isAlive()) {
            throw new IOException("The bot runner has exited");
        }
        toRunner.write(RunnerProtocol.encode(command));
        toRunner.newLine();
        toRunner.flush();

        String line = readWithTimeout();
        String[] reply = RunnerProtocol.decode(line);
        if (reply.length > 0 && RunnerProtocol.ERROR.equals(reply[0])) {

            throw new IOException(reply.length > 2 ? reply[2] : "the runner reported an error");
        }
        return reply;
    }

    /** Reads one reply, giving up rather than blocking forever. */
    private String readWithTimeout() throws IOException {
        long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (fromRunner.ready()) {
                String line = fromRunner.readLine();
                if (line == null) {
                    throw new IOException("The bot runner closed its output");
                }
                return line;
            }
            if (!process.isAlive()) {
                throw new IOException("The bot runner exited while a command was in flight");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for the bot runner", e);
            }
        }
        throw new IOException("The bot runner did not answer within " + COMMAND_TIMEOUT);
    }

    @Override
    public void close() {
        try {
            if (process.isAlive()) {
                toRunner.write(RunnerProtocol.encode(RunnerProtocol.SHUTDOWN));
                toRunner.newLine();
                toRunner.flush();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            process.destroyForcibly();
            live.clear();
        }
    }

    /** One bot, addressed by name through its runner. */
    public record BotHandle(
            BotRunner runner, String name, double x, double y, double z,
            String playerName, String uuid) {

        public BotHandle(BotRunner runner, String name, double x, double y, double z) {
            this(runner, name, x, y, z, name, BotIdentity.offlineUuid(name).toString());
        }

        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }

        public void moveTo(double x, double y, double z) throws IOException {
            runner.send(RunnerProtocol.MOVE, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z));
        }

        /** Moves with the Node runner's path/teleport mode and arrival timeout. */
        public void moveTo(double x, double y, double z, String mode, long timeoutMillis)
                throws IOException {
            if (!"path".equalsIgnoreCase(mode) && !"teleport".equalsIgnoreCase(mode)) {
                throw new IOException("Unknown movement mode '" + mode + "'. Use path or teleport.");
            }
            if (timeoutMillis <= 0) {
                throw new IOException("Movement timeout must be positive: " + timeoutMillis + "ms");
            }
            runner.send(RunnerProtocol.MOVE, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    mode.toLowerCase(java.util.Locale.ROOT), String.valueOf(timeoutMillis));
        }

        /** Right-clicks the nearest entity to a point — an NPC, a villager, an armour stand. */
        public String useEntity(double x, double y, double z, double radius, String type)
                throws IOException {
            String[] reply = runner.send(RunnerProtocol.USE_ENTITY, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    String.valueOf(radius), type == null ? "" : type);

            return reply.length > 2 ? reply[2] : "";
        }

        public String attackEntity(double x, double y, double z, double radius, String type)
                throws IOException {
            String[] reply = runner.send(RunnerProtocol.ATTACK_ENTITY, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    String.valueOf(radius), type == null ? "" : type);
            return reply.length > 2 ? reply[2] : "";
        }

        public void holdItem(int slot) throws IOException {
            runner.send(RunnerProtocol.HOLD_ITEM, name, String.valueOf(slot));
        }

        public void dropItem(Integer count) throws IOException {
            runner.send(RunnerProtocol.DROP_ITEM, name, count == null ? "" : String.valueOf(count));
        }

        public void placeBlock(int x, int y, int z, String face) throws IOException {
            runner.send(RunnerProtocol.PLACE_BLOCK, name, String.valueOf(x), String.valueOf(y),
                    String.valueOf(z), face == null ? "up" : face);
        }

        public void jump() throws IOException {
            runner.send(RunnerProtocol.JUMP, name);
        }

        public void sneak(boolean state) throws IOException {
            runner.send(RunnerProtocol.SNEAK, name, state ? "on" : "off");
        }

        public void sprint(boolean state) throws IOException {
            runner.send(RunnerProtocol.SPRINT, name, state ? "on" : "off");
        }

        public void lookAt(double x, double y, double z) throws IOException {
            runner.send(RunnerProtocol.LOOK_AT, name, String.valueOf(x), String.valueOf(y),
                    String.valueOf(z));
        }

        public boolean assertReachable(double x, double y, double z, long timeoutMillis)
                throws IOException {
            return runner.assertReachable(name, x, y, z, timeoutMillis);
        }

        /** Starts or reuses a localhost world/inventory viewer for this bot. */
        public String view(String what, String mode) throws IOException {
            String[] reply = runner.send(RunnerProtocol.VIEW, name,
                    what == null ? "world" : what,
                    mode == null ? "third_person" : mode, "false");
            return reply.length > 2 ? reply[2] : "";
        }

        public void stopView() throws IOException {
            runner.send(RunnerProtocol.VIEW, name, "world", "third_person", "true");
        }

        /**
         * Breaks a block and reports what became of the dig.
         *
         * <p>Not {@code void}: the runner waits for the server to acknowledge the block action,
         * so the answer separates a dig something cancelled from one that never arrived. Those
         * used to be the same silence.
         */
        public String breakBlock(int x, int y, int z) throws IOException {
            String[] reply = runner.send(RunnerProtocol.BREAK, name,
                    String.valueOf(x), String.valueOf(y), String.valueOf(z));
            return reply.length > 2 ? reply[2] : "sent";
        }

        public void command(String command) throws IOException {
            runner.send(RunnerProtocol.COMMAND, name, command);
        }

        public void chat(String message) throws IOException {
            runner.send(RunnerProtocol.CHAT, name, message);
        }

        /** Right-clicks a block. */
        public void useBlock(int x, int y, int z, String face) throws IOException {
            runner.send(RunnerProtocol.USE, name, String.valueOf(x), String.valueOf(y),
                    String.valueOf(z), face == null ? "" : face);
        }

        /** Clicks a slot in the menu the bot has open. */
        public void clickSlot(int slot, String click) throws IOException {
            runner.send(RunnerProtocol.CLICK, name, String.valueOf(slot),
                    click == null || click.isBlank() ? "left" : click);
        }

        public void closeMenu() throws IOException {
            runner.send(RunnerProtocol.CLOSE_MENU, name);
        }

        /** The menu the client has been told about, or {@code null} if none. */
        public OpenMenu menu() throws IOException {
            String[] reply = runner.send(RunnerProtocol.MENU, name);
            int containerId = Integer.parseInt(reply[2]);
            return containerId < 0 ? null : new OpenMenu(containerId, reply[3]);
        }

        /** What the client was told, which the server cannot always be asked. */
        public ClientView inspect() throws IOException {
            return parseInspect(runner.send(RunnerProtocol.INSPECT, name));
        }

        /** The bot's position now, which may differ from where it spawned. */
        public double[] position() throws IOException {
            String[] reply = runner.send(RunnerProtocol.POSITION, name);
            return new double[] {
                Double.parseDouble(reply[2]), Double.parseDouble(reply[3]),
                Double.parseDouble(reply[4])
            };
        }
    }

}

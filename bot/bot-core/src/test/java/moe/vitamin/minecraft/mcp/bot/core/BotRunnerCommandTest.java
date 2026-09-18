package moe.vitamin.minecraft.mcp.bot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotRunnerCommandTest {

    @Test
    void launchesWindowsSeaDirectly() throws IOException {
        assertEquals(
                List.of(
                        absolute("C:/runners/bot-runner-win-x64.exe"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/bot-runner-win-x64.exe"),
                        "127.0.0.1",
                        25565));
    }

    @Test
    void launchesUnixSeaNamesDirectly() throws IOException {
        assertEquals(
                List.of(
                        absolute("C:/runners/bot-runner-linux-x64"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/bot-runner-linux-x64"),
                        "127.0.0.1",
                        25565));
    }

    @Test
    void launchesTheNodeScriptThroughNode() throws IOException {
        assertEquals(
                List.of(
                        BotRunner.node(),
                        absolute("C:/runners/runner.mjs"),
                        "127.0.0.1",
                        "25565"),
                BotRunner.commandFor(
                        Path.of("C:/runners/runner.mjs"),
                        "127.0.0.1",
                        25565));
    }

    @Test
    void appendsAnExplicitMinecraftProtocol() throws IOException {
        assertEquals(
                List.of(
                        BotRunner.node(),
                        absolute("C:/runners/runner.mjs"),
                        "127.0.0.1",
                        "25577",
                        "772"),
                BotRunner.commandFor(
                        Path.of("C:/runners/runner.mjs"),
                        "127.0.0.1",
                        25577,
                        772));
    }

    @Test
    void refusesAJarWithoutLettingTheOperatingSystemExplainIt() {
        IOException refused = assertThrows(IOException.class, () -> BotRunner.commandFor(
                Path.of("C:/vitaminmcp/bot-runner.jar"),
                "127.0.0.1",
                25565));

        assertTrue(refused.getMessage().contains("bot-runner-win-x64.exe"), refused.getMessage());
        assertTrue(refused.getMessage().contains("runner.mjs"), refused.getMessage());
    }

    private static String absolute(String path) {
        return Path.of(path).toAbsolutePath().toString();
    }
}

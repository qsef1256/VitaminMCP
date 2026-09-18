package moe.vitamin.minecraft.mcp.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

/** The JVM Paper is started with. Paper 26.1 needs Java 25 while the tests run on 21. */
final class ServerJava {

    private ServerJava() {
    }

    static Path home() {
        String configured = System.getProperty("vitaminmcp.serverJavaHome");
        Path home = Path.of(configured != null ? configured : System.getProperty("java.home"));
        assertTrue(Files.isDirectory(home.resolve("bin")),
                "vitaminmcp.serverJavaHome is not a JDK or JRE: " + home);
        return home;
    }
}

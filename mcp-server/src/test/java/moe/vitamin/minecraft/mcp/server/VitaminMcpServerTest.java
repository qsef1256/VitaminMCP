package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VitaminMcpServerTest {

    @Test
    void echoesSupportedProtocolVersions() {
        assertEquals("2025-03-26",
                VitaminMcpServer.negotiateProtocolVersion("2025-03-26"));
    }

    @Test
    void countersAnUnsupportedProtocolVersionWithItsLatestSupportedVersion() {
        assertEquals("2025-06-18",
                VitaminMcpServer.negotiateProtocolVersion("2025-11-25"));
    }
}

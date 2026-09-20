package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VitaminMcpHttpServerTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-06-18","capabilities":{},
              "clientInfo":{"name":"test","version":"1"}}}
            """;

    private final HttpClient client = HttpClient.newHttpClient();
    private VitaminMcpHttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws Exception {
        server = new VitaminMcpHttpServer(0);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void isolatesClientsAndClosesOnlyTheDeletedSession() throws Exception {
        HttpResponse<String> first = post(INITIALIZE, null, null);
        HttpResponse<String> second = post(INITIALIZE, null, null);
        String firstSession = first.headers().firstValue(VitaminMcpHttpServer.SESSION_HEADER)
                .orElseThrow();
        String secondSession = second.headers().firstValue(VitaminMcpHttpServer.SESSION_HEADER)
                .orElseThrow();

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertNotEquals(firstSession, secondSession);
        assertEquals(2, server.sessionCount());
        assertEquals(200, post(toolsList(), firstSession, null).statusCode());

        HttpRequest delete = HttpRequest.newBuilder(endpoint)
                .header(VitaminMcpHttpServer.SESSION_HEADER, firstSession)
                .DELETE()
                .build();
        assertEquals(204, client.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, server.sessionCount());
        assertEquals(404, post(toolsList(), firstSession, null).statusCode());
        assertEquals(200, post(toolsList(), secondSession, null).statusCode());
    }

    @Test
    void refusesBrowserOrigins() throws Exception {
        HttpResponse<String> response = post(INITIALIZE, null, "https://example.com");

        assertEquals(403, response.statusCode());
        assertEquals(0, server.sessionCount());
    }

    private HttpResponse<String> post(String body, String session, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header(VitaminMcpHttpServer.SESSION_HEADER, session);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String toolsList() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
    }
}

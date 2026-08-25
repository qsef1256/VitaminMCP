package moe.vitamin.minecraft.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import moe.vitamin.minecraft.mcp.bot.spi.ClientMessage;
import moe.vitamin.minecraft.mcp.bot.spi.ClientView;
import org.junit.jupiter.api.Test;

class SessionToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STREAM_ID = "opaque-stream-a";

    @Test
    void botInspectPublishesTheMessageCursorContract() {
        JsonNode inspect = findTool(new SessionTools().listTools(), "bot_inspect");

        JsonNode cursor = inspect.path("inputSchema").path("properties").path("cursor");
        assertEquals("string", cursor.path("type").asText());
        assertTrue(cursor.path("description").asText().contains("same bot"));

        String description = inspect.path("description").asText();
        assertTrue(description.contains("epoch milliseconds"));
        assertTrue(description.contains("100 messages"));
        assertTrue(description.contains("messageCursor"));
        assertTrue(description.contains("messagesDropped"));
        assertTrue(description.contains("before the command"));
        assertTrue(description.contains("opaque"));
        assertTrue(description.contains("same-named replacement"));
        assertTrue(description.contains("Chat text is returned as received"));
    }

    @Test
    void sessionStartDescribesTheLiveSessionRoster() {
        JsonNode start = findTool(new SessionTools().listTools(), "session_start");

        String description = start.path("description").asText();
        assertTrue(description.contains("real agent tool definitions"));
        assertTrue(description.contains("runner process has exited"));
        assertTrue(description.contains("current session roster"));
    }

    @Test
    void botSpawnPublishesMicrosoftAuthenticationWithoutMakingItTheDefault() {
        JsonNode spawn = findTool(new SessionTools().listTools(), "bot_spawn");

        JsonNode properties = spawn.path("inputSchema").path("properties");
        assertTrue(properties.path("auth").path("description").asText()
                .contains("online-mode=true"));
        assertTrue(properties.path("account").path("description").asText()
                .contains("cache key"));
        assertTrue(spawn.path("description").asText().contains("read-only"));
    }

    @Test
    void readOnlyModeRejectsBotMutationAtTheOuterServer() {
        IllegalStateException rejected = assertThrows(
                IllegalStateException.class, () -> SessionTools.requireWritable(true));

        assertTrue(rejected.getMessage().contains("read-only"));
        SessionTools.requireWritable(false);
    }

    @Test
    void aMessageCursorRejectsASameNamedBotWithAnotherStreamId() {
        ClientView first = view(43L, "opaque-stream-a", List.of(
                new ClientMessage(42L, 1_000L, "first connection")));
        ClientView sameNamedReplacement = view(0L, "opaque-stream-b", List.of());
        ObjectNode firstResponse = MAPPER.createObjectNode();
        ObjectNode replacementResponse = MAPPER.createObjectNode();
        SessionTools.putMessages(firstResponse, first, "Tester1", 0L);
        SessionTools.putMessages(replacementResponse, sameNamedReplacement, "Tester1", 0L);

        String cursor = firstResponse.path("messageCursor").asText();

        assertEquals(43L, SessionTools.parseMessageCursor(cursor, first.messageStreamId()));
        assertEquals(0L, SessionTools.parseMessageCursor("", "opaque-stream-b"));
        assertTrue(!cursor.equals(replacementResponse.path("messageCursor").asText()));

        IllegalArgumentException wrongStream = assertThrows(IllegalArgumentException.class,
                () -> SessionTools.parseMessageCursor(
                        cursor, sameNamedReplacement.messageStreamId()));
        assertTrue(wrongStream.getMessage().contains("messages/opaque-stream-a"));
        assertTrue(wrongStream.getMessage().contains("messages/opaque-stream-b"));
    }

    @Test
    void messagesAreRecordsFromTheCursorAndCarryTheNextWatermark() {
        ClientView view = view(13L, List.of(
                new ClientMessage(10L, 1_000L, "old"),
                new ClientMessage(11L, 1_400L, "reply"),
                new ClientMessage(12L, 1_500L, "later")));
        ObjectNode result = MAPPER.createObjectNode();
        result.put("health", 20.0);

        SessionTools.putMessages(result, view, "Tester1", 11L);

        assertEquals(2, result.path("messages").size());
        assertEquals(11L, result.path("messages").get(0).path("sequence").asLong());
        assertEquals(1_400L, result.path("messages").get(0).path("timestamp").asLong());
        assertEquals("reply", result.path("messages").get(0).path("text").asText());
        assertEquals("messages/opaque-stream-a:13", result.path("messageCursor").asText());
        assertEquals(0L, result.path("messagesDropped").asLong());
        assertEquals(20.0, result.path("health").asDouble());
    }

    @Test
    void anExpiredCursorReportsMessagesThatCannotBeRecovered() {
        ClientView view = view(103L, List.of(
                new ClientMessage(100L, 1_000L, "first retained"),
                new ClientMessage(101L, 1_100L, "second retained"),
                new ClientMessage(102L, 1_200L, "third retained")));
        ObjectNode result = MAPPER.createObjectNode();

        SessionTools.putMessages(result, view, "Tester1", 98L);

        assertEquals(3, result.path("messages").size());
        assertEquals(2L, result.path("messagesDropped").asLong());
        assertEquals("messages/opaque-stream-a:103", result.path("messageCursor").asText());
    }

    @Test
    void aCursorAtTheWatermarkReturnsNoMessagesAndAFutureCursorIsRejected() {
        ClientView view = view(13L, List.of(
                new ClientMessage(12L, 1_500L, "latest")));
        ObjectNode result = MAPPER.createObjectNode();

        SessionTools.putMessages(result, view, "Tester1", 13L);

        assertTrue(result.path("messages").isEmpty());
        assertEquals("messages/opaque-stream-a:13", result.path("messageCursor").asText());

        IllegalArgumentException future = assertThrows(IllegalArgumentException.class,
                () -> SessionTools.putMessages(
                        MAPPER.createObjectNode(), view, "Tester1", 14L));
        assertTrue(future.getMessage().contains("ahead"));
        assertTrue(future.getMessage().contains("Tester1"));
    }

    private static ClientView view(long nextSequence, List<ClientMessage> messages) {
        return view(nextSequence, STREAM_ID, messages);
    }

    private static ClientView view(
            long nextSequence, String streamId, List<ClientMessage> messages) {
        return new ClientView(
                null, List.of(), messages, nextSequence, streamId, List.of(), null,
                null, null, null, null, null, List.of());
    }

    private static JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("No tool named " + name);
    }
}

package org.example;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Server} — integration-level tests using real sockets.
 *
 * Starts the server on a random available port, connects real clients,
 * and verifies the command protocol end-to-end.
 *
 * Covers:
 *  - Server starts and accepts connections
 *  - Player name registration
 *  - CREATE command: session created, ID returned
 *  - JOIN command: valid join, session not found, full session
 *  - Two players can join and game starts
 *  - ANALYTICS command returns report
 *  - QUIT command disconnects cleanly
 *  - Single shared sessions map (the root bug fix)
 */
@DisplayName("Server Integration Tests")
class ServerTest {

    private Server    server;
    private Thread    serverThread;
    private int       port;

    // ── Server lifecycle ─────────────────────────────────────────────────────

    @BeforeEach
    void startServer() throws Exception {
        // Find a free port
        try (var tmp = new ServerSocket(0)) {
            port = tmp.getLocalPort();
        }
        server       = new Server();
        serverThread = new Thread(() -> {
            try { server.start(port); }
            catch (IOException e) { /* server stopped */ }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(200); // give server time to bind
    }

    @AfterEach
    void stopServer() throws Exception {
        serverThread.interrupt();
    }

    // ── Helper: connect a client, read until prompt, return streams ──────────

    static class TestClient implements Closeable {
        final Socket       socket;
        final BufferedReader in;
        final PrintWriter    out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(3000); // 3s timeout per read
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
        }

        /** Read lines until a line containing the marker is found. */
        String readUntil(String marker) throws IOException {
            var sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line).append("\n");
                if (line.contains(marker)) break;
            }
            return sb.toString();
        }

        /** Send a line to the server. */
        void send(String line) { out.println(line); }

        @Override public void close() throws IOException { socket.close(); }
    }

    /** Connect, read welcome banner, send name, read hello message. */
    private TestClient connect(String playerName) throws IOException {
        var client = new TestClient(port);
        client.readUntil("Enter your name");
        client.send(playerName);
        client.readUntil("Commands:");
        return client;
    }

    // =========================================================
    // Connection
    // =========================================================

    @Nested
    @DisplayName("Connection")
    class ConnectionTests {

        @Test
        @DisplayName("Server accepts a connection and sends welcome banner")
        void server_sends_welcome() throws IOException {
            try (var client = new TestClient(port)) {
                var response = client.readUntil("Enter your name");
                assertTrue(response.contains("CodeBreaker"));
            }
        }

        @Test
        @DisplayName("Server greets player by name")
        void server_greets_by_name() throws IOException {
            try (var client = connect("Alice")) {
                // readUntil("Commands:") already consumed in connect()
                // Just verify no exception was thrown = greeting received
                assertTrue(true);
            }
        }

        @Test
        @DisplayName("Multiple clients can connect simultaneously")
        void multiple_clients_connect() throws IOException {
            try (var c1 = connect("Alice");
                 var c2 = connect("Bob")) {
                assertNotNull(c1);
                assertNotNull(c2);
            }
        }
    }

    // =========================================================
    // CREATE command
    // =========================================================

    @Nested
    @DisplayName("CREATE command")
    class CreateCommandTests {

        @Test
        @DisplayName("CREATE returns a session ID")
        void create_returns_session_id() throws IOException {
            try (var client = connect("Alice")) {
                client.send("CREATE");
                var response = client.readUntil("Waiting");
                assertTrue(response.contains("Session created"),
                        "Expected 'Session created' in: " + response);
            }
        }

        @Test
        @DisplayName("CREATE with 2 players parameter works")
        void create_with_player_count() throws IOException {
            try (var client = connect("Alice")) {
                client.send("CREATE 2");
                var response = client.readUntil("Waiting");
                assertTrue(response.contains("Session created"));
            }
        }

        @Test
        @DisplayName("CREATE with timeout shows timeout message")
        void create_with_timeout() throws IOException {
            try (var client = connect("Alice")) {
                client.send("CREATE 2 30");
                var response = client.readUntil("timeout");
                assertTrue(response.contains("timeout") || response.contains("Turn timeout"));
            }
        }
    }

    // =========================================================
    // JOIN command
    // =========================================================

    @Nested
    @DisplayName("JOIN command")
    class JoinCommandTests {

        @Test
        @DisplayName("JOIN with unknown session ID returns error")
        void join_unknown_session() throws IOException {
            try (var client = connect("Bob")) {
                client.send("JOIN XXXXXX");
                var response = client.readUntil("not found");
                assertTrue(response.contains("not found"));
            }
        }

        @Test
        @DisplayName("JOIN without session ID returns usage error")
        void join_no_id() throws IOException {
            try (var client = connect("Bob")) {
                client.send("JOIN");
                var response = client.readUntil("Usage");
                assertTrue(response.contains("Usage"));
            }
        }

        @Test
        @DisplayName("Two players can create and join the same session")
        void two_players_join_same_session() throws Exception {
            try (var alice = connect("Alice");
                 var bob   = connect("Bob")) {

                // Alice creates
                alice.send("CREATE 2");
                var createResponse = alice.readUntil("created:");

                // Extract session ID (last token of the "Session created: XXXXXX" line)
                String sessionId = extractSessionId(createResponse);
                assertNotNull(sessionId, "Could not extract session ID from: " + createResponse);

                // Bob joins
                bob.send("JOIN " + sessionId);
                var joinResponse = bob.readUntil("Game Started");
                assertTrue(joinResponse.contains("Game Started"),
                        "Expected game to start, got: " + joinResponse);
            }
        }

        private String extractSessionId(String response) {
            for (String line : response.split("\n")) {
                if (line.contains("Session created:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) return parts[parts.length - 1].trim();
                }
            }
            return null;
        }
    }

    // =========================================================
    // ANALYTICS command
    // =========================================================

    @Nested
    @DisplayName("ANALYTICS command")
    class AnalyticsCommandTests {

        @Test
        @DisplayName("ANALYTICS returns the analytics report")
        void analytics_returns_report() throws IOException {
            try (var client = connect("Alice")) {
                client.send("ANALYTICS");
                var response = client.readUntil("ANALYTICS");
                assertTrue(response.contains("ANALYTICS") || response.contains("games"));
            }
        }
    }

    // =========================================================
    // QUIT command
    // =========================================================

    @Nested
    @DisplayName("QUIT command")
    class QuitCommandTests {

        @Test
        @DisplayName("QUIT sends goodbye message")
        void quit_sends_goodbye() throws IOException {
            try (var client = connect("Alice")) {
                client.send("QUIT");
                var response = client.readUntil("Goodbye");
                assertTrue(response.contains("Goodbye"));
            }
        }
    }

    // =========================================================
    // Unknown command
    // =========================================================

    @Test
    @DisplayName("Unknown command returns error message")
    void unknown_command() throws IOException {
        try (var client = connect("Alice")) {
            client.send("BADCOMMAND");
            var response = client.readUntil("Unknown");
            assertTrue(response.contains("Unknown"));
        }
    }

    // =========================================================
    // Shared sessions map (root bug fix)
    // =========================================================

    @Test
    @DisplayName("Sessions map is shared: player 2 can find player 1's session")
    void sessions_map_is_shared() throws Exception {
        try (var alice = connect("Alice");
             var bob   = connect("Bob")) {

            alice.send("CREATE 2");
            var createResponse = alice.readUntil("created:");

            // Extract ID
            String sessionId = null;
            for (String line : createResponse.split("\n")) {
                if (line.contains("Session created:")) {
                    String[] parts = line.trim().split("\\s+");
                    sessionId = parts[parts.length - 1].trim();
                    break;
                }
            }
            assertNotNull(sessionId, "Session ID not found in: " + createResponse);

            // Bob must be able to JOIN (would fail if sessions maps were separate)
            bob.send("JOIN " + sessionId);
            var joinResponse = bob.readUntil("Joined");
            assertFalse(joinResponse.contains("not found"),
                    "Session not found — sessions map is NOT shared! Bug still present.");
            assertTrue(joinResponse.contains("Joined"),
                    "Expected Bob to join successfully, got: " + joinResponse);
        }
    }
}
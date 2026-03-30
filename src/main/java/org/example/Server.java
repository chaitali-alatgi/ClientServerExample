package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Integer.parseInt;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Server {
    private static final int PORT = 8080;
    private final Map<String, Session> sessions  = new ConcurrentHashMap<>();
    private final GameAnalytics            analytics = new GameAnalytics();


    record ClientContext(Socket socket, int id, String label) {
        ClientContext(Socket socket, int id) {
            this(socket, id, "Client #" + id);
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PORT;
        new Server().start(port);
    }

    void start(int port) throws IOException {
        try (var executor   = Executors.newVirtualThreadPerTaskExecutor();
             var serverSocket = new ServerSocket(port)) {
            while (!serverSocket.isClosed()) {
                var socket = serverSocket.accept();
                executor.submit(() -> handleClient(socket));

            }
        }
    }

     private void handleClient(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println("""
                    ╔══════════════════════════════════════════╗
                    ║     CodeBreaker Multiplayer Server       ║
                    ╚══════════════════════════════════════════╝
                    Enter your name:""");
            var name = in.readLine();
            if (name == null || name.isBlank()) name = "Player-" + socket.getPort();
            var player = new Session.Player(name, socket, in, out);
            out.println("""
                    Hello, %s!
                    Commands:
                      CREATE [players] [timeoutSecs]  – create a session
                      JOIN <sessionId>                – join a session
                      ANALYTICS                       – view server stats
                      QUIT                            – exit""".formatted(name));

            var session = waitForSession(player, in, out);
            if (session == null) return;

            // 3. Run the game loop
            int playerIndex = session.getPlayers().indexOf(player);
            gameLoop(player, playerIndex, session, in, out);

        } catch (SocketException e) {
            System.out.println("[-] Client disconnected: " + socket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("[!] Connection error: " + e.getMessage());
        }
    }

    private Session waitForSession(Session.Player player, BufferedReader in, PrintWriter out)
            throws IOException {

        while (true) {
            var line = in.readLine();
            if (line == null || "QUIT".equalsIgnoreCase(line)) {
                out.println("Goodbye!");
                return null;
            }

            if ("ANALYTICS".equalsIgnoreCase(line)) {
                out.println(analytics.getReport());
                continue;
            }

            var parts = line.trim().split("\\s+");
            var cmd = parts[0].toUpperCase();
            switch (cmd) {
                case "CREATE" -> {
                    // CREATE [players] [timeoutSecs]
                    int numPlayers = parts.length > 1 ? parseInt(parts[1], 2) : 2;
                    long timeoutSecs = parts.length > 2 ? parseInt(parts[2], 0) : 0;

                    var id = newSessionId();
                    var session = new Session(id, numPlayers, timeoutSecs, analytics);
                    session.addPlayer(player);
                    sessions.put(id, session);
                    out.println("✅ Session created: " + id);
                    out.println("⏳ Waiting for %d more player(s) to JOIN %s … %d"
                            .formatted(numPlayers - 1, id, timeoutSecs));
                    if (timeoutSecs > 0)
                        out.println("⏱  Turn timeout: " + timeoutSecs + "s");

                    System.out.printf("[+] Session %s created by %s (%d players, %ds timeout)%n",
                            id, player.name(), numPlayers, timeoutSecs);
                    if (session.isFull()) {
                        session.startGame();
                    }
                    // Block until all players join (or creator quits)
                    return waitForAllPlayers(session, in, out);
                }

                case "JOIN" -> {
                    if (parts.length < 2) {
                        out.println("⚠  Usage: JOIN <sessionId>");
                        continue;
                    }
                    var id = parts[1].toUpperCase();
                    var session = sessions.get(id);

                    if (session == null) {
                        out.println("⚠  Session not found: " + id);
                        continue;
                    }
                    if (session.isFull()) {
                        out.println("⚠  Session " + id + " is full.");
                        continue;
                    }
                    if (session.isGameOver()) {
                        out.println("⚠  Session " + id + " already ended.");
                        continue;
                    }

                    session.addPlayer(player);
                    out.println("✅ Joined session: " + id);
                    System.out.printf("[+] %s joined session %s (%d/%d)%n",
                            player.name(), id, session.getPlayers().size(), session.getMaxPlayers());

                    // If all players are now in, start the game
                    if (session.isFull()) {
                        session.startGame();
                    } else {
                        out.println("⏳ Waiting for %d more player(s)…"
                                .formatted(session.getMaxPlayers() - session.getPlayers().size()));
                        return waitForAllPlayers(session, in, out);
                    }
                    return session;
                }

                default -> out.println("⚠  Unknown command. Use CREATE, JOIN, ANALYTICS, or QUIT.");
            }
        }
    }
    /**
     * Blocks the calling thread until the session is full (all players joined)
     * or the waiting player types QUIT.
     */
    private Session waitForAllPlayers(Session session,
                                          BufferedReader in, PrintWriter out)
            throws IOException {
        try {
            while (!session.isFull()) {
                if (in.ready()) {
                    var line = in.readLine();
                    if (line != null && "QUIT".equalsIgnoreCase(line)) {
                        sessions.remove(session.getSessionId());
                        session.shutdown();
                        out.println("Session cancelled.");
                        return null;
                    }
                }
                Thread.sleep(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return session;
    }

    private void gameLoop(Session.Player player, int playerIndex,
                          Session session, BufferedReader in, PrintWriter out)
            throws IOException {

        while (true) {
            var line = in.readLine();
            if (line == null) {                           // client disconnected
                session.broadcast("⚠  " + player.name() + " disconnected.");
                break;
            }
            if (line.isBlank()) continue;

            switch (line.toUpperCase()) {

                case "QUIT" -> {
                    session.broadcast("⚠  " + player.name() + " left the game.");
                    out.println("Goodbye, " + player.name() + "!");
                    System.out.printf("[-] %s quit session %s%n",
                            player.name(), session.getSessionId());
                    session.shutdown();
                    sessions.remove(session.getSessionId());
                    return;
                }

                case "RESTART" -> {
                    if (!session.isGameOver()) {
                        out.println("⚠  Game is still in progress.");
                    } else {
                        session.restart();
                    }
                }

                case "ANALYTICS" -> out.println(analytics.getReport()); // Bonus 3

                default -> session.makeGuess(playerIndex, line);
            }
        }
    }

    private String newSessionId() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private int parseInt(String s, int defaultValue) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private void printBanner(int port) {
        System.out.printf("""
                ╔══════════════════════════════════════════╗
                ║     CodeBreaker Multiplayer Server       ║
                ║     JDK 21+  •  Virtual Threads         ║
                ╚══════════════════════════════════════════╝
                  Listening on port %d
                  Features: N-player • Turn timeout • Analytics
                %n""", port);
    }

}

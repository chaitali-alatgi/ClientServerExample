package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class Client {
    private static final String HOST = "localhost";
    private static final int PORT = 8080;
    private static final int NUM_CLIENTS = 1;

    /**
     * Signals both threads to stop. volatile ensures cross-thread visibility.
     */
    private volatile boolean running = true;

    record ClientResult(int id, boolean success, String message) {
    }

    public static void main(String[] args) throws IOException {
        var host = args.length > 0 ? args[0] : HOST;
        var port = args.length > 1 ? Integer.parseInt(args[1]) : PORT;
        new Client().runClient(host, port);
    }

//        // newVirtualThreadPerTaskExecutor() — stable since Java 21
//        // Creates one lightweight virtual thread per submitted task.
//        // No need to size a pool; JVM schedules them on carrier threads.
//        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
//
//            // Submit all clients and collect Futures
//            List<Future<ClientResult>> futures = IntStream
//                    .rangeClosed(1, NUM_CLIENTS)
//                    .mapToObj(id -> executor.submit(() -> runClient(id)))
//                    .toList();
//
//            // Executor is AutoCloseable in Java 19+:
//            // closing it blocks until all submitted tasks complete — acts like join()
//            // (the try-with-resources close() is called here automatically)
//
//            System.out.println("\n── Results ──────────────────────────────────");
//            for (var future : futures) {
//                try {
//                    // Pattern matching switch over ClientResult (stable Java 21)
//                    var result = future.get();
//                    var status = switch (result) {
//                        case ClientResult r when r.success() -> "✓ OK  ";
//                        case ClientResult r -> "✗ FAIL";
//                    };
//                    System.out.printf("%s  Client #%d — %s%n",
//                            status, result.id(), result.message());
//                } catch (Exception e) {
//                    System.err.println("Failed to get result: " + e.getMessage());
//                }
//            }
//        }
//
//        System.out.println("\n=== All " + NUM_CLIENTS + " clients finished ===");
////            try (
////                    Socket socket = new Socket(HOST, PORT);
////                    BufferedReader in      = new BufferedReader(new InputStreamReader(socket.getInputStream()));
////                    PrintWriter out     = new PrintWriter(socket.getOutputStream(), true);
////                    Scanner        scanner = new Scanner(System.in)
////            ) {
////                out.println("Hello World!");
////                Thread  clientThread = new Thread(() -> {
////                    try {
////                        String serverResponse;
////                        while ((serverResponse = in.readLine()) != null) {
////                            out.println("Server response: " + serverResponse);
////                        }
////                    } catch (IOException e) {
////                        e.printStackTrace();
////                    }
////
////                });
////                clientThread.setDaemon(true);
////                clientThread.start();
////
////                while (scanner.hasNextLine()) {
////                    String userInput = scanner.nextLine();
////                    out.println(userInput);
////
////                    if ("quit".equalsIgnoreCase(userInput.trim())) {
////                        System.out.println("Disconnecting...");
////                        break;
////                    }
////                }
////            } catch(IOException ioe){
////                ioe.printStackTrace();
////            }
//
//    }

    private void runClient(String host, int port) {
        try (
                var socket = new Socket(host, port);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                var out = new PrintWriter(socket.getOutputStream(), true);
                var scanner = new Scanner(System.in);
        ) {
            System.out.println("Connected!\n");
            socket.setSoTimeout(0); // no read timeout — server controls pacing
            var readerThread = Thread.ofVirtual()
                    .name("server-reader")
                    .start(() -> {
                        try {
                            String line;
                            while (running && (line = in.readLine()) != null) {
                                System.out.println(line);
                            }
                        } catch (SocketException e) {
                            if (running) System.out.println("\n[Disconnected from server]");
                        } catch (IOException e) {
                            if (running) System.err.println("Read error: " + e.getMessage());
                        } finally {
                            running = false; // unblock main thread if server drops
                        }
                    });

            // ── Main thread: keyboard → server ───────────────────────────────
            while (running && scanner.hasNextLine()) {
                var input = scanner.nextLine().trim();
                if (input.isBlank()) continue;

                out.println(input);

                if ("QUIT".equalsIgnoreCase(input)) {
                    running = false;
                    break;
                }
            }

            running = false;
            readerThread.join(1_000); // wait up to 1s for clean reader shutdown

        } catch (ConnectException e) {
            System.err.printf("❌ Could not connect to %s:%d — is the server running?%n",
                    host, port);
        } catch (IOException | InterruptedException e) {
            System.err.println("Connection error: " + e.getMessage());
        }

        System.out.println("Goodbye!");
    }
}

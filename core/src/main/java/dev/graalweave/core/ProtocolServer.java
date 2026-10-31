package dev.graalweave.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProtocolServer implements AutoCloseable {
    private final RuntimeService runtime;
    private final ServerSocket server;
    private final ExecutorService connections = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("graalweave-io-", 0).factory());
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Path discovery;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile boolean closed;

    public ProtocolServer(RuntimeService runtime) throws IOException {
        this.runtime = runtime;
        Path directory = runtime.instance.resolve("graalweave");
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) { lockChannel.close(); throw new IOException("GraalWeave is already running for this instance"); }
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        discovery = directory.resolve("connection.json");
        Path temp = directory.resolve("connection.json.tmp");
        Files.writeString(temp, Json.GSON.toJson(Json.object("protocol", 1, "host", "127.0.0.1", "port", server.getLocalPort(),
                "pid", ProcessHandle.current().pid(), "instance", runtime.instance.toString())));
        Files.move(temp, discovery, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        connections.execute(this::accept);
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                sockets.add(socket);
                connections.execute(() -> serve(socket));
            } catch (IOException e) { if (!closed) System.err.println("GraalWeave accept: " + e); }
        }
    }

    private void serve(Socket socket) {
        Set<Session> attached = ConcurrentHashMap.newKeySet();
        try (socket;
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            java.util.function.Consumer<JsonObject> send = message -> {
                synchronized (writer) {
                    try { writer.write(Json.GSON.toJson(message)); writer.newLine(); writer.flush(); }
                    catch (IOException ignored) { /* Detachment cleanup is handled by the reader. */ }
                }
            };
            String line;
            while ((line = reader.readLine()) != null) {
                String id = null;
                try {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    id = Json.string(request, "id", UUID.randomUUID().toString());
                    request.addProperty("id", id);
                    String op = Json.string(request, "op", "execute");
                    String sessionName = Json.string(request, "session", null);
                    JsonObject response = Json.object("id", id, "type", "result");
                    switch (op) {
                        case "status" -> response.add("data", runtime.status());
                        case "sessions" -> response.add("data", runtime.list());
                        case "close" -> runtime.closeSession(sessionName);
                        case "reset" -> runtime.resetSession(sessionName);
                        case "interrupt" -> runtime.find(sessionName).interrupt();
                        case "input" -> runtime.find(sessionName).input(Json.string(request, "text", null));
                        case "complete" -> {
                            var session = runtime.session(sessionName);
                            String responseId = id;
                            session.complete(Json.string(request, "text", "")).whenComplete((values, error) -> {
                                if (error != null) send.accept(Json.object("id", responseId, "type", "error", "message", error.getMessage()));
                                else send.accept(Json.object("id", responseId, "type", "result", "data", values));
                            });
                            continue;
                        }
                        case "execute" -> {
                            Session session = runtime.session(sessionName);
                            attached.add(session);
                            session.submit(request, send);
                            continue;
                        }
                        default -> throw new IllegalArgumentException("Unknown operation: " + op);
                    }
                    send.accept(response);
                } catch (Exception e) { send.accept(Json.object("id", id, "type", "error", "message", e.toString())); }
            }
        } catch (IOException ignored) {
            // A closed client socket ends its attachment, not persistent sessions.
        } finally {
            sockets.remove(socket);
            attached.forEach(s -> {
                if (!s.persistent) runtime.remove(s);
                else s.input(null);
            });
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        server.close();
        for (Socket socket : sockets) socket.close();
        connections.shutdownNow();
        Files.deleteIfExists(discovery);
        lock.release();
        lockChannel.close();
    }
}

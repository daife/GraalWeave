package dev.graalweave.core;

import com.google.gson.*;
import org.graalvm.polyglot.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class RuntimeService implements AutoCloseable {
    final Path instance;
    final GameAccess game;
    final ApiExplorer api;
    final Engine engine = Engine.newBuilder("python").build();
    final Object tickMonitor = new Object();
    long tick;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RuntimeService(Path instance, GameAccess game) {
        this.instance = instance.toAbsolutePath().normalize();
        this.game = game;
        this.api = new ApiExplorer(game);
    }
    public Session session(String name) {
        boolean persistent = name != null && !name.isBlank();
        String actual = persistent ? name : "temporary-" + UUID.randomUUID();
        return sessions.computeIfAbsent(actual, n -> new Session(this, n, persistent));
    }
    public Session find(String name) {
        Session s = sessions.get(name);
        if (s == null) throw new IllegalArgumentException("Unknown session: " + name);
        return s;
    }
    void remove(Session session) {
        sessions.remove(session.name, session);
        session.close();
    }
    public void closeSession(String name) { remove(find(name)); }
    public void resetSession(String name) { closeSession(name); session(name); }
    /** Run startup files sequentially without blocking the game thread. */
    public void runStartup(Consumer<JsonObject> sink) throws IOException {
        Path directory = Files.createDirectories(instance.resolve("graalweave/startup"));
        List<Path> scripts;
        try (var files = Files.list(directory)) {
            scripts = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".py"))
                    .filter(path -> !path.getFileName().toString().equals("__init__.py"))
                    .sorted().toList();
        }
        runStartup(scripts.iterator(), sink);
    }
    private void runStartup(Iterator<Path> scripts, Consumer<JsonObject> sink) {
        if (closed || !scripts.hasNext()) return;
        Path script = scripts.next();
        Session session = session("startup/" + script.getFileName());
        session.submit(Json.object("mode", "file", "filename", script.toString(),
                "cwd", script.getParent().toString()), message -> {
            message.addProperty("script", script.getFileName().toString());
            sink.accept(message);
            String type = Json.string(message, "type", "");
            if (type.equals("input")) session.input(null);
            if (type.equals("done")) runStartup(scripts, sink);
        });
    }
    public JsonArray list() {
        var result = new JsonArray();
        sessions.values().forEach(s -> result.add(Json.object("name", s.name, "persistent", s.persistent, "execution", s.execution.get())));
        return result;
    }
    public JsonObject status() {
        return Json.object("protocol", 1, "pid", ProcessHandle.current().pid(), "instance", instance.toString(),
                "graalpy", engine.getLanguages().get("python").getVersion(), "java", System.getProperty("java.version"), "sessions", list());
    }
    public void tick() {
        synchronized (tickMonitor) { tick++; tickMonitor.notifyAll(); }
        event("tick");
    }
    public void event(String name) { sessions.values().forEach(s -> s.event(name)); }
    @Override public void close() {
        closed = true;
        sessions.values().forEach(Session::close);
        sessions.clear();
        synchronized (tickMonitor) { tickMonitor.notifyAll(); }
        engine.close();
    }
}

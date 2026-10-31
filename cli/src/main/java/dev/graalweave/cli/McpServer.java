package dev.graalweave.cli;

import com.google.gson.*;
import dev.graalweave.core.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A stdio MCP adapter. The game remains the sole owner of Python sessions. */
final class McpServer {
    private final Path instance;
    private final Path settingsFile;
    private volatile Path workingDirectory;
    private final JsonObject tool = TOOL.deepCopy();
    private final BufferedReader input;
    private final PrintWriter output;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Execution> running = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private static final JsonObject TOOL = JsonParser.parseString("""
            {
              "name": "graalweave",
              "inputSchema": {
                "type": "object",
                "properties": {
                  "code": {"type": "string", "description": "Python source, including multiline code."},
                  "script": {"type": "string", "description": "Python file path on this machine."},
                  "args": {"type": "array", "items": {"type": "string"}, "description": "Script arguments, available as sys.argv[1:]."},
                  "session": {"type": "string", "minLength": 1, "default": "agent"},
                  "cwd": {"type": "string", "description": "Set and persist the working directory immediately; subsequent calls may omit it. Can be used alone. Prefer an absolute path; relative paths resolve against the current working directory. Relative script paths resolve here; code imports from here, script imports from its own directory."},
                  "action": {"type": "string", "enum": ["run", "status", "sessions", "interrupt", "close", "reset"], "default": "run"}
                },
                "additionalProperties": false
              }
            }
            """).getAsJsonObject();
    static {
        TOOL.addProperty("description", """
                Run Python in the running Minecraft 26.3 JVM (GraalPy). Supply code or script, not both; no shell. print() returns output; input() gets EOF.
                Variables/imports persist in session 'agent'; one request per session. action: run (default), status, sessions, interrupt, close, reset. Close when finished to release subscriptions.
                Use the workspace for reusable Python modules, packages, and task data. Import a module once, then call its functions in later requests. cwd/__init__.py runs in session globals before the first execution only (also for CLI). Changing cwd does not rerun it; reset starts fresh. An initializer error fails that request without automatic retry.
                from graalweave import api, client, ticks, events; import java
                Discover Java APIs: api.mods() lists loaded mod IDs; api.classes('keyword', mod='id') finds binary class names (Outer$Inner); api.inspect('full.Class', query='member', kind='method', exact=True) shows overloads. classes/inspect paginate via next_offset; use offset to continue, detail=True for full metadata. help(api) shows signatures.
                Access game objects only in short client.call(fn) callbacks. Use ticks.wait(n) outside them. Players/worlds may be None before joining; reacquire them after changing worlds.
                Example (in a world): mc = java.type('net.minecraft.client.Minecraft').getInstance(); print(client.call(lambda: {'pos': str(mc.player.position()), 'health': mc.player.getHealth()}))
                events.subscribe('join', fn) also accepts 'leave'/'tick'. Callbacks run on the session worker after the current script finishes; subscription.close() removes the subscription.
                """.strip());
    }

    McpServer(Path instance) throws IOException {
        this.instance = instance;
        Path defaultDirectory = Files.createDirectories(instance.resolve("graalweave/workspace"));
        settingsFile = instance.resolve("graalweave/mcp-settings.json");
        if (!Files.exists(settingsFile)) Files.writeString(settingsFile, "{\"cwd\":\"workspace\"}\n");
        JsonObject settings = JsonParser.parseString(Files.readString(settingsFile)).getAsJsonObject();
        String configured = Json.string(settings, "cwd", "workspace");
        if (configured.isBlank()) throw new IllegalArgumentException("Configured cwd must not be blank");
        workingDirectory = Files.createDirectories(settingsFile.getParent().resolve(configured).toAbsolutePath().normalize());
        tool.addProperty("description", "The working directory at this MCP launch is \"" + workingDirectory
                + "\". The default working directory is \"" + defaultDirectory
                + "\". Configuration is read once at MCP startup from \"" + settingsFile
                + "\". Passing cwd updates memory and saves the configuration immediately; this launch description changes only after restart.\n"
                + "Startup scripts: place .py files directly in \"" + instance.resolve("graalweave/startup").toAbsolutePath().normalize()
                + "\". This path is independent of MCP cwd and shell pwd. Scripts run once per game launch with that directory as cwd. Output goes to the game log; use join subscriptions for world-dependent work.\n"
                + TOOL.get("description").getAsString());
        input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    }

    private synchronized Path changeDirectory(String value) throws IOException {
        if (value.isBlank()) throw new IllegalArgumentException("cwd must not be blank");
        Path directory = Files.createDirectories(workingDirectory.resolve(value).toAbsolutePath().normalize());
        Files.writeString(settingsFile, Json.GSON.toJson(Json.object("cwd", directory.toString())) + "\n");
        workingDirectory = directory;
        return directory;
    }

    void run() throws IOException {
        try {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject request;
                try { request = JsonParser.parseString(line).getAsJsonObject(); }
                catch (RuntimeException e) { error(JsonNull.INSTANCE, -32700, "Invalid JSON-RPC message"); continue; }
                JsonElement id = request.get("id");
                String method = Json.string(request, "method", "");
                JsonObject params = request.has("params") && request.get("params").isJsonObject()
                        ? request.getAsJsonObject("params") : new JsonObject();
                if (id == null) {
                    if (method.equals("notifications/cancelled") && params.has("requestId")) {
                        Execution execution = running.get(params.get("requestId").toString());
                        if (execution != null) workers.execute(execution::cancel);
                    }
                    continue;
                }
                switch (method) {
                    case "initialize" -> {
                        String version = Json.string(params, "protocolVersion", "2025-11-25");
                        if (!Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25").contains(version)) version = "2025-11-25";
                        result(id, Json.object("protocolVersion", version, "capabilities", Json.object("tools", new JsonObject()),
                                "serverInfo", Json.object("name", "graalweave", "version", "0.1.0")));
                    }
                    case "ping" -> result(id, new JsonObject());
                    case "tools/list" -> result(id, Json.object("tools", List.of(tool)));
                    case "tools/call" -> {
                        if (!Json.string(params, "name", "").equals("graalweave")) { error(id, -32602, "Unknown tool"); continue; }
                        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                                ? params.getAsJsonObject("arguments") : new JsonObject();
                        var execution = new Execution(arguments);
                        if (running.putIfAbsent(id.toString(), execution) != null) { error(id, -32600, "Request ID is already active"); continue; }
                        workers.execute(() -> {
                            try {
                                JsonObject data = execution.call();
                                String status = Json.string(data, "status", "ok");
                                boolean failed = !status.equals("ok") && !(status.equals("exit") && data.get("exitCode").getAsInt() == 0);
                                result(id, toolResult(data, failed));
                            } catch (Exception e) {
                                result(id, toolResult(Json.object("message", e.getMessage()), true));
                            } finally { running.remove(id.toString(), execution); }
                        });
                    }
                    default -> error(id, -32601, "Unknown method: " + method);
                }
            }
        } finally {
            closed = true;
            running.values().forEach(Execution::cancel);
            workers.shutdownNow();
        }
    }

    private static JsonObject toolResult(JsonObject data, boolean failed) {
        var content = new ArrayList<JsonObject>();
        if (data.has("stdout")) content.add(textContent(data.get("stdout").getAsString()));
        if (data.has("stderr")) content.add(textContent("stderr:\n" + data.get("stderr").getAsString()));
        if (data.has("message")) content.add(textContent(data.get("message").getAsString()));
        if (data.has("exitCode") && data.get("exitCode").getAsInt() != 0)
            content.add(textContent("Exit code: " + data.get("exitCode").getAsInt()));
        if (data.has("data")) content.add(textContent(Json.GSON.toJson(data.get("data"))));
        if (data.has("cwd")) content.add(textContent(data.get("cwd").getAsString()));
        return Json.object("content", content, "isError", failed);
    }
    private static JsonObject textContent(String text) {
        return Json.object("type", "text", "text", text.replaceAll("\r+\n", "\n"));
    }
    private void result(JsonElement id, JsonObject data) { send(Json.object("jsonrpc", "2.0", "id", id, "result", data)); }
    private void error(JsonElement id, int code, String message) { send(Json.object("jsonrpc", "2.0", "id", id, "error", Json.object("code", code, "message", message))); }
    private synchronized void send(JsonObject message) { if (!closed) output.println(Json.GSON.toJson(message)); }

    private final class Execution {
        private final JsonObject args;
        private volatile Connection connection;
        private volatile String activeSession;
        private volatile boolean cancelled;
        Execution(JsonObject args) { this.args = args; }

        JsonObject call() throws IOException {
            validate();
            String action = Json.string(args, "action", "run");
            String session = Json.string(args, "session", "agent");
            if (!action.equals("run")) {
                if (!Set.of("status", "sessions", "interrupt", "close", "reset").contains(action))
                    throw new IllegalArgumentException("Unknown action: " + action);
                JsonObject reply = Connection.request(instance, Json.object("op", action, "session", session));
                return reply.has("data") ? Json.object("status", "ok", "data", reply.get("data")) : Json.object("status", "ok");
            }
            if (args.has("code") && args.has("script") || !args.has("code") && !args.has("script") && !args.has("cwd"))
                throw new IllegalArgumentException("Provide code or script, or cwd alone");
            Path cwd = args.has("cwd") ? changeDirectory(args.get("cwd").getAsString()) : workingDirectory;
            if (!args.has("code") && !args.has("script")) return Json.object("status", "ok", "cwd", cwd.toString());
            JsonObject request = Json.object("op", "execute", "session", session, "cwd", cwd.toString(),
                    "args", args.has("args") ? args.get("args") : new JsonArray());
            if (args.has("code")) {
                request.addProperty("mode", "code"); request.add("code", args.get("code"));
            } else {
                request.addProperty("mode", "file"); request.addProperty("filename", cwd.resolve(args.get("script").getAsString()).normalize().toString());
            }
            StringBuilder stdout = new StringBuilder(), stderr = new StringBuilder();
            try (var link = new Connection(instance)) {
                connection = link;
                if (cancelled) throw new IOException("Tool call cancelled");
                link.send(request);
                while (true) {
                    JsonObject message = link.receive();
                    switch (Json.string(message, "type", "")) {
                        case "started" -> {
                            activeSession = message.get("session").getAsString();
                            if (cancelled) cancel();
                        }
                        case "output" -> (Json.string(message, "stream", "stdout").equals("stderr") ? stderr : stdout).append(message.get("text").getAsString());
                        case "subscription_error" -> stderr.append(Json.string(message, "message", "")).append('\n');
                        case "input" -> Connection.request(instance, Json.object("op", "input", "session", activeSession, "text", null));
                        case "done" -> {
                            JsonObject data = Json.object("status", Json.string(message, "status", "error"));
                            if (!stdout.isEmpty()) data.addProperty("stdout", stdout.toString());
                            if (!stderr.isEmpty()) data.addProperty("stderr", stderr.toString());
                            for (String key : List.of("message", "exitCode")) if (message.has(key)) data.add(key, message.get(key));
                            return data;
                        }
                        case "error" -> throw new IOException(Json.string(message, "message", "Execution failed"));
                    }
                }
            } finally { connection = null; activeSession = null; }
        }

        private void validate() {
            for (var entry : args.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if (!TOOL.getAsJsonObject("inputSchema").getAsJsonObject("properties").has(key))
                    throw new IllegalArgumentException("Unknown argument: " + key);
                if (key.equals("args")) {
                    if (!value.isJsonArray()) throw new IllegalArgumentException("args must be a string array");
                    for (JsonElement item : value.getAsJsonArray()) requireString(key, item);
                } else requireString(key, value);
            }
            if (Json.string(args, "session", "agent").isBlank()) throw new IllegalArgumentException("session must not be blank");
        }
        private void requireString(String key, JsonElement value) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
        }
        void cancel() {
            cancelled = true;
            String session = activeSession;
            if (session != null) {
                try { Connection.request(instance, Json.object("op", "interrupt", "session", session)); }
                catch (IOException e) { System.err.println("MCP interrupt: " + e.getMessage()); }
            }
            Connection link = connection;
            if (link != null) try { link.close(); } catch (IOException ignored) {}
        }
    }
}

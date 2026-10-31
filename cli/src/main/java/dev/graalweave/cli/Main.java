package dev.graalweave.cli;

import com.google.gson.*;
import dev.graalweave.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.*;

public final class Main {
    private Path instance = Path.of(".").toAbsolutePath().normalize();
    private String session;
    private volatile String activeSession;
    private LineReader lineReader;
    private Terminal terminal;

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        int status;
        try { status = new Main().run(new ArrayList<>(List.of(args))); }
        catch (Exception e) { System.err.println("GraalWeave: " + e.getMessage()); status = 1; }
        if (status != 0) System.exit(status);
    }

    private int run(List<String> args) throws Exception {
        while (!args.isEmpty()) {
            if (args.getFirst().equals("--instance")) { args.removeFirst(); instance = Path.of(take(args, "instance directory")).toAbsolutePath().normalize(); }
            else if (args.getFirst().equals("--session")) { args.removeFirst(); session = take(args, "session name"); }
            else break;
        }
        String command = args.isEmpty() ? "help" : args.removeFirst();
        switch (command) {
            case "help", "--help", "-h" -> {
                System.out.println("""
                        GraalWeave [--instance DIR] [--session NAME] COMMAND
                          status | sessions
                          run FILE [ARGS...] | run - [ARGS...] | - [ARGS...]
                          -c CODE [ARGS...] | -m MODULE [ARGS...]
                          repl
                          mcp
                          close NAME | reset NAME | interrupt NAME
                          env pip [ARGS...] | env rebuild
                        Options precede COMMAND. Scripts run in the Minecraft process.
                        run - (or -) reads UTF-8 source from stdin; input() then receives EOF.
                        """);
                return 0;
            }
            case "env" -> { return environment(args); }
            case "mcp" -> { new McpServer(instance).run(); return 0; }
            case "status", "sessions" -> {
                System.out.println(Json.GSON.toJson(Connection.request(instance, Json.object("op", command)).get("data")));
                return 0;
            }
            case "close", "reset", "interrupt" -> {
                String name = args.isEmpty() ? session : args.removeFirst();
                if (name == null) throw new IllegalArgumentException("Session name required");
                Connection.request(instance, Json.object("op", command, "session", name));
                return 0;
            }
            case "-", "run", "-c", "-m", "repl" -> {
                if (command.equals("-") || command.equals("run") && !args.isEmpty() && args.getFirst().equals("-")) {
                    if (command.equals("run")) args.removeFirst();
                    String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                    if (source.startsWith("\uFEFF")) source = source.substring(1);
                    JsonObject request = Json.object("op", "execute", "mode", "code", "code", source,
                            "filename", "<stdin>", "session", session, "args", args,
                            "cwd", Path.of(".").toAbsolutePath().normalize().toString());
                    Object nativeHandler = org.jline.utils.Signals.register("INT", this::interrupt);
                    try { return exitCode(execute(request)); }
                    finally { org.jline.utils.Signals.unregister("INT", nativeHandler); }
                }
                try (Terminal term = TerminalBuilder.builder().system(true).nativeSignals(true)
                        .encoding(StandardCharsets.UTF_8).build()) {
                    terminal = term;
                    lineReader = LineReaderBuilder.builder().terminal(term).parser(new DefaultParser().eofOnUnclosedQuote(false)
                                    .eofOnUnclosedBracket())
                            .completer((reader, line, candidates) -> {
                                if (session == null) return;
                                try {
                                    var response = Connection.request(instance, Json.object("op", "complete", "session", session, "text", line.word()));
                                    for (JsonElement value : response.getAsJsonArray("data")) candidates.add(new Candidate(value.getAsString()));
                                } catch (IOException ignored) { /* Completion must not disrupt editing. */ }
                            }).variable(LineReader.HISTORY_FILE, instance.resolve("graalweave/history"))
                            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true).build();
                    term.handle(Terminal.Signal.INT, signal -> interrupt());
                    Object nativeHandler = org.jline.utils.Signals.register("INT", () -> term.raise(Terminal.Signal.INT));
                    try {
                        if (command.equals("repl")) return repl();
                        String target = take(args, "script, code or module");
                        JsonObject request = Json.object("op", "execute", "session", session, "args", args,
                                "cwd", Path.of(".").toAbsolutePath().normalize().toString());
                        switch (command) {
                            case "run" -> { request.addProperty("mode", "file"); request.addProperty("filename", Path.of(target).toAbsolutePath().normalize().toString()); }
                            case "-c" -> { request.addProperty("mode", "code"); request.addProperty("code", target); }
                            case "-m" -> { request.addProperty("mode", "module"); request.addProperty("module", target); }
                        }
                        return exitCode(execute(request));
                    } finally {
                        org.jline.utils.Signals.unregister("INT", nativeHandler);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private int environment(List<String> args) throws Exception {
        String op = take(args, "pip or rebuild");
        switch (op) {
            case "pip" -> { return PythonEnvironment.pip(instance, args); }
            case "rebuild" -> {
                Path discovery = instance.resolve("graalweave/connection.json");
                if (Files.exists(discovery)) throw new IllegalStateException("Stop Minecraft before rebuilding its Python environment");
                Path venv = PythonEnvironment.root(instance).resolve("venv");
                InstanceInstallation.recycle(venv);
                PythonEnvironment.initialize(instance);
            }
            default -> throw new IllegalArgumentException("Unknown env command: " + op);
        }
        System.out.println(PythonEnvironment.root(instance));
        return 0;
    }

    private int repl() throws IOException {
        if (session == null) session = "repl";
        terminal.writer().println("GraalWeave Python — session " + session + " (Ctrl+D exits; session remains available)");
        terminal.writer().flush();
        StringBuilder source = new StringBuilder();
        try (var connection = new Connection(instance)) {
            while (true) {
                String line;
                try { line = lineReader.readLine(source.isEmpty() ? ">>> " : "... "); }
                catch (UserInterruptException e) { source.setLength(0); continue; }
                catch (EndOfFileException e) { return 0; }
                if (!source.isEmpty()) source.append('\n');
                source.append(line);
                JsonObject request = Json.object("op", "execute", "mode", "repl", "code", source.toString(),
                        "session", session, "filename", "<stdin>", "cwd", Path.of(".").toAbsolutePath().normalize().toString());
                JsonObject result = execute(connection, request);
                String status = Json.string(result, "status", "error");
                if (status.equals("exit")) return exitCode(result);
                if (!status.equals("incomplete")) source.setLength(0);
            }
        }
    }

    private JsonObject execute(JsonObject request) throws IOException {
        try (var connection = new Connection(instance)) { return execute(connection, request); }
    }

    private JsonObject execute(Connection connection, JsonObject request) throws IOException {
        Attributes saved = terminal == null ? null : terminal.getAttributes();
        if (terminal != null) {
            Attributes executing = new Attributes(saved);
            executing.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            executing.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            executing.setLocalFlag(Attributes.LocalFlag.ISIG, true);
            terminal.setAttributes(executing);
            terminal.resume();
        }
        try {
            connection.send(request);
            while (true) {
                JsonObject message = connection.receive();
                switch (Json.string(message, "type", "")) {
                    case "started" -> activeSession = message.get("session").getAsString();
                    case "output" -> {
                        PrintStream stream = Json.string(message, "stream", "stdout").equals("stderr") ? System.err : System.out;
                        stream.print(message.get("text").getAsString()); stream.flush();
                    }
                    case "input" -> {
                        String value = null;
                        try { if (lineReader != null) value = lineReader.readLine(""); }
                        catch (EndOfFileException e) { value = null; }
                        catch (UserInterruptException e) { interrupt(); continue; }
                        // Use another connection so stdin cannot be confused with execution events.
                        Connection.request(instance, Json.object("op", "input", "session", activeSession, "text", value));
                    }
                    case "subscription_error" -> System.err.println("Subscription removed: " + Json.string(message, "message", ""));
                    case "done" -> {
                        if (message.has("message")) System.err.println(message.get("message").getAsString());
                        return message;
                    }
                    case "error" -> throw new IOException(Json.string(message, "message", "Execution failed"));
                }
            }
        } finally {
            activeSession = null;
            if (terminal != null) terminal.setAttributes(saved);
        }
    }

    private void interrupt() {
        String name = activeSession;
        if (name == null) return;
        Thread.ofPlatform().daemon().start(() -> {
            try { Connection.request(instance, Json.object("op", "interrupt", "session", name)); }
            catch (IOException e) { System.err.println("Interrupt: " + e.getMessage()); }
        });
    }

    private static int exitCode(JsonObject result) {
        return switch (Json.string(result, "status", "error")) {
            case "ok", "incomplete" -> 0;
            case "exit" -> result.get("exitCode").getAsInt();
            case "interrupted" -> 130;
            default -> 1;
        };
    }
    private static String take(List<String> args, String what) {
        if (args.isEmpty()) throw new IllegalArgumentException("Expected " + what);
        return args.removeFirst();
    }
}

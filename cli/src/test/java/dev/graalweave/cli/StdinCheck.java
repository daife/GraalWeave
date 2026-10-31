package dev.graalweave.cli;

import com.google.gson.*;
import dev.graalweave.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;

/** Exercise the actual CLI subprocess with redirected stdin and no terminal. */
public final class StdinCheck {
    public static void main(String[] args) throws Exception {
        Path instance = Files.createTempDirectory("GraalWeave stdin 中文 ");
        System.out.println("Integration environment: " + instance);
        if (args.length == 0) PythonEnvironment.initialize(instance);
        else preparePackaged(instance, Path.of(args[0]));
        var game = new GameAccess() {
            public boolean isMainThread() { return true; }
            public void execute(Runnable action) { action.run(); }
            public ClassLoader classLoader() { return getClass().getClassLoader(); }
            public List<ApiExplorer.Mod> mods() { return List.of(); }
        };
        try (var runtime = new RuntimeService(instance, game); var server = new ProtocolServer(runtime)) {
            check(instance, List.of("--session", "stdin", "run", "-", "中文"), "\uFEFF" + """
                    import sys
                    assert sys.argv == ['<stdin>', '中文']
                    text = '''双引号 " 单引号 ' 反斜线 \\\\path'''
                    assert '双引号 "' in text and "单引号 '" in text and text.endswith('\\\\path')
                    values = []
                    for i in range(3):
                        values.append(i)
                    try:
                        input()
                    except EOFError:
                        pass
                    else:
                        raise AssertionError('stdin source must be followed by EOF')
                    print('STDIN OK')
                    """, 0, "STDIN OK", "");
            check(instance, List.of("--session", "stdin", "-"), "assert values == [0, 1, 2]\nraise SystemExit(7)", 7, "", "");
            check(instance, List.of("run", "-"), "", 0, "", "");
            check(instance, List.of("-"), "raise ValueError('中文')", 1, "", "File \"<stdin>\", line 1");
            checkMcp(instance, runtime);
        }
        System.out.println("PASS: stdin UTF-8/BOM, quotes, multiline, argv, input EOF, session, exit status, traceback");
    }

    private static void preparePackaged(Path instance, Path mod) throws Exception {
        Path directory = instance.resolve("graalweave");
        try (var jar = FileSystems.newFileSystem(mod)) {
            Path libraries = jar.getPath("/META-INF/jars");
            InstanceInstallation.prepare(instance, libraries);
            Path ready = directory.resolve("python/venv/.graalweave-ready");
            var created = Files.getLastModifiedTime(ready);
            Path userScript = directory.resolve("workspace/task.py");
            Files.writeString(userScript, "print('keep me')\n");
            Path settings = directory.resolve("mcp-settings.json");
            Files.writeString(settings, "{\"cwd\":\"workspace\"}\n");
            Path stale = directory.resolve("client/lib/obsolete-runtime.jar");
            Files.writeString(stale, "old runtime");
            Path oldLib = Files.createDirectories(directory.resolve("python/lib"));
            Files.writeString(oldLib.resolve("old.jar"), "old runtime");
            InstanceInstallation.prepare(instance, libraries);
            if (Files.exists(stale) || Files.exists(oldLib)
                    || !Files.readString(userScript).equals("print('keep me')\n")
                    || !Files.readString(settings).equals("{\"cwd\":\"workspace\"}\n")
                    || !Files.getLastModifiedTime(ready).equals(created))
                throw new AssertionError("upgrade must preserve user files and venv, and remove old libraries");
        }
        Path output = instance.resolve("launcher.txt");
        var launcher = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "& $env:GRAALWEAVE_TEST_LAUNCHER --help; exit $LASTEXITCODE");
        launcher.environment().put("GRAALWEAVE_TEST_LAUNCHER", directory.resolve("client/bin/graalweave.bat").toString());
        launcher.environment().put("JAVA_HOME", "invalid-path-for-test");
        var process = launcher.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0
                || !Files.readString(output).contains("env pip") || Files.readString(output).contains("env init"))
            throw new AssertionError("extracted launcher failed: " + Files.readString(output));
        System.out.println("PASS: packaged JAR bootstrap, offline venv, repeat startup, old library cleanup, standalone launcher");
    }

    private static ArrayList<String> command(Path instance) throws IOException {
        Path config = instance.resolve("graalweave/mcp.json");
        if (Files.exists(config)) {
            JsonObject server = JsonParser.parseString(Files.readString(config)).getAsJsonObject()
                    .getAsJsonObject("mcpServers").getAsJsonObject("graalweave");
            var command = new ArrayList<String>();
            command.add(server.get("command").getAsString());
            server.getAsJsonArray("args").forEach(arg -> command.add(arg.getAsString()));
            if (!command.removeLast().equals("mcp")) throw new AssertionError("MCP entry point");
            return command;
        }
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "--instance", instance.toString()));
    }

    private static void checkMcp(Path instance, RuntimeService runtime) throws Exception {
        var command = command(instance);
        command.add("mcp");
        var process = new ProcessBuilder(command).redirectError(instance.resolve("mcp-stderr.txt").toFile()).start();
        try (var writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
             var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var reads = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.function.Supplier<JsonObject> receive = () -> {
                try { return JsonParser.parseString(reads.submit(reader::readLine).get(45, TimeUnit.SECONDS)).getAsJsonObject(); }
                catch (Exception e) { throw new AssertionError("MCP did not return valid JSON", e); }
            };
            writer.println(Json.object("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Json.object("protocolVersion", "2025-11-25")));
            if (!receive.get().getAsJsonObject("result").get("protocolVersion").getAsString().equals("2025-11-25")) throw new AssertionError("initialize");
            writer.println(Json.object("jsonrpc", "2.0", "method", "notifications/initialized"));
            writer.println(Json.object("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
            JsonArray tools = receive.get().getAsJsonObject("result").getAsJsonArray("tools");
            if (tools.size() != 1) throw new AssertionError("one tool");
            String launchDescription = tools.get(0).getAsJsonObject().get("description").getAsString();
            if (!launchDescription.contains(instance.resolve("graalweave/workspace").toString())) throw new AssertionError("default cwd discovery");
            java.util.function.BiFunction<Integer, JsonObject, JsonObject> call = (id, args) -> {
                writer.println(Json.object("jsonrpc", "2.0", "id", id, "method", "tools/call", "params", Json.object("name", "graalweave", "arguments", args)));
                JsonObject result = receive.get().getAsJsonObject("result");
                return result;
            };
            JsonObject reply = call.apply(3, Json.object("code", "message = '''中文 ' \" $ ` \\\\path'''\nprint(message)\ntry:\n input()\nexcept EOFError:\n pass"));
            if (reply.get("isError").getAsBoolean() || !reply.toString().contains("中文")) throw new AssertionError(reply);
            reply = call.apply(4, Json.object("code", "assert message.startswith('中文'); print('persistent')"));
            if (reply.get("isError").getAsBoolean() || !reply.toString().contains("persistent")) throw new AssertionError(reply);
            if (!reply.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().equals("persistent\n"))
                throw new AssertionError("stdout must be direct text: " + reply);
            reply = call.apply(40, Json.object("code", "import sys\nprint('line1\\r\\nline2')\nprint('warning', file=sys.stderr)"));
            JsonArray content = reply.getAsJsonArray("content");
            if (content.size() != 2 || !content.get(0).getAsJsonObject().get("text").getAsString().equals("line1\nline2\n")
                    || !content.get(1).getAsJsonObject().get("text").getAsString().equals("stderr:\nwarning\n"))
                throw new AssertionError("stdout/stderr: " + reply);
            Path script = instance.resolve("graalweave/workspace/MCP 脚本.py");
            Files.writeString(script, "import sys\nfrom pathlib import Path\nassert Path.cwd() == Path(__file__).parent\nassert sys.argv[1:] == ['中文']\nprint('file OK')\n");
            reply = call.apply(5, Json.object("script", script.getFileName().toString(), "args", List.of("中文")));
            if (reply.get("isError").getAsBoolean() || !reply.toString().contains("file OK")) throw new AssertionError(reply);
            if (!call.apply(6, Json.object("code", "raise ValueError('中文错误')")).get("isError").getAsBoolean()) throw new AssertionError("script failure");
            if (!call.apply(7, Json.object("code", "pass", "script", "x.py")).get("isError").getAsBoolean()) throw new AssertionError("ambiguous input");
            writer.println(Json.object("jsonrpc", "2.0", "id", 8, "method", "tools/call", "params", Json.object("name", "graalweave", "arguments", Json.object("code", "while True: pass"))));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (runtime.list().asList().stream().noneMatch(e -> e.getAsJsonObject().get("name").getAsString().equals("agent")
                    && !e.getAsJsonObject().get("execution").isJsonNull()) && System.nanoTime() < deadline) Thread.sleep(10);
            writer.println(Json.object("jsonrpc", "2.0", "id", 9, "method", "ping"));
            if (receive.get().get("id").getAsInt() != 9) throw new AssertionError("execution blocked MCP input");
            writer.println(Json.object("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Json.object("requestId", 8)));
            reply = receive.get();
            if (reply.get("id").getAsInt() != 8 || !reply.getAsJsonObject("result").get("isError").getAsBoolean()) throw new AssertionError(reply);
            if (call.apply(10, Json.object("code", "assert message.startswith('中文')")).get("isError").getAsBoolean()) throw new AssertionError("session after cancel");
            if (call.apply(11, Json.object("action", "close")).get("isError").getAsBoolean()) throw new AssertionError("close");
            Path changed = instance.resolve("自定义 workspace");
            if (call.apply(12, Json.object("cwd", changed.toString())).get("isError").getAsBoolean()) throw new AssertionError("set cwd");
            Path settings = instance.resolve("graalweave/mcp-settings.json");
            if (!JsonParser.parseString(Files.readString(settings)).getAsJsonObject().get("cwd").getAsString().equals(changed.toString()))
                throw new AssertionError("cwd not saved");
            Files.writeString(settings, "{\"cwd\":\"after-restart\"}\n");
            reply = call.apply(13, Json.object("code", "from pathlib import Path\nassert Path.cwd() == Path(" + Json.GSON.toJson(changed.toString()) + ")"));
            if (reply.get("isError").getAsBoolean()) throw new AssertionError("cwd must persist in memory: " + reply);
            writer.println(Json.object("jsonrpc", "2.0", "id", 14, "method", "tools/list"));
            if (!receive.get().getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject().get("description").getAsString().equals(launchDescription))
                throw new AssertionError("launch description changed");
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        var restarted = new ProcessBuilder(command).redirectError(instance.resolve("mcp-stderr.txt").toFile()).start();
        try {
            try (var writer = new PrintWriter(new OutputStreamWriter(restarted.getOutputStream(), StandardCharsets.UTF_8), true)) {
                writer.println(Json.object("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
            }
            if (!restarted.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("MCP restart timed out");
            String discovery = new String(restarted.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String description = JsonParser.parseString(discovery).getAsJsonObject().getAsJsonObject("result")
                    .getAsJsonArray("tools").get(0).getAsJsonObject().get("description").getAsString();
            if (!description.contains(instance.resolve("graalweave/after-restart").toString())) throw new AssertionError("config not reloaded on restart");
        } finally { if (restarted.isAlive()) restarted.destroyForcibly(); }
        System.out.println("PASS: MCP discovery, cwd persistence/restart, code/file UTF-8, session, EOF, errors, concurrent ping, cancellation/reuse");
    }

    private static void check(Path instance, List<String> args, String source, int status, String stdout, String stderr) throws Exception {
        var command = command(instance);
        command.addAll(args);
        Path out = instance.resolve("stdout.txt"), err = instance.resolve("stderr.txt");
        var process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            try (var input = process.getOutputStream()) { input.write(source.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new AssertionError("stdin CLI timed out");
            String output = Files.readString(out), errors = Files.readString(err);
            if (process.exitValue() != status || !output.strip().equals(stdout) || !errors.contains(stderr)
                    || stderr.isEmpty() && !errors.isBlank())
                throw new AssertionError("exit=" + process.exitValue() + " stdout=" + output + " stderr=" + errors);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}

package dev.graalweave.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import org.graalvm.python.embedding.GraalPyResources;

/** A writable environment created at its final installation path, never a relocated venv. */
public final class PythonEnvironment {
    private static final String VERSION = "25.4.4.1.1";
    private PythonEnvironment() {}
    public static Path root(Path instance) { return instance.resolve("graalweave/python").toAbsolutePath().normalize(); }

    public static Context.Builder builder(Path instance) {
        Path root = root(instance);
        if (!Files.isRegularFile(root.resolve("venv/.graalweave-ready")))
            throw new IllegalStateException("Python environment missing; start Minecraft to initialize GraalWeave");
        return GraalPyResources.contextBuilder(root).allowAllAccess(true).allowHostAccess(JavaAssignments.HOST_ACCESS);
    }

    public static void initialize(Path instance) throws IOException {
        Path root = root(instance);
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("src/graalweave"));
        for (String name : List.of("__init__.py", "_interop.py")) {
            try (var source = PythonEnvironment.class.getResourceAsStream("/python/graalweave/" + name)) {
                Files.copy(Objects.requireNonNull(source), root.resolve("src/graalweave/" + name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path ready = root.resolve("venv/.graalweave-ready");
        writeLauncher(root);
        if (Files.exists(ready) && Files.readString(ready).equals(VERSION)) return;
        InstanceInstallation.recycle(root.resolve("venv"));
        try {
            var process = new ProcessBuilder(root.resolve("graalpy.exe").toString(), "-m", "venv", root.resolve("venv").toString()).inheritIO();
            process.environment().put("PIP_NO_INDEX", "1");
            process.environment().put("PIP_GRAALPY_PATCHES_URL", "disabled");
            int status = process.start().waitFor();
            if (status != 0) throw new IOException("GraalPy venv creation failed: " + status);
            Files.writeString(ready, VERSION);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    public static void writeLauncher(Path root) throws IOException {
        // Reuse extracted libraries instead of the Minecraft launch classpath.
        Path lib = root.getParent().resolve("client/lib");
        if (!Files.isDirectory(lib)) {
            // Standalone core integration checks have no installed client.
            lib = Files.createDirectories(root.resolve("lib"));
            for (Path jar : runtimeJars()) Files.copy(jar, lib.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        String java = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
        Path launcher = root.resolve("graalpy.exe");
        try (var source = PythonEnvironment.class.getClassLoader().getResourceAsStream(
                "META-INF/resources/windows/amd64/Lib/venv/scripts/nt/venvlauncher.exe")) {
            Files.copy(Objects.requireNonNull(source, "GraalPy Windows launcher resource"), launcher, StandardCopyOption.REPLACE_EXISTING);
        }
        String command = "\"" + java + "\" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -cp \"" + lib + "\\*"
                + "\" com.oracle.graal.python.shell.GraalPythonMain";
        Files.writeString(root.resolve("pyvenv.cfg"), "venvlauncher_command = " + command + "\nbase-executable = " + launcher + "\n");
    }

    private static List<Path> runtimeJars() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(Path::of).filter(p -> p.toString().endsWith(".jar")).toList();
    }

    public static int pip(Path instance, List<String> arguments) throws IOException, InterruptedException {
        Path root = root(instance);
        initialize(instance);
        var command = new ArrayList<>(List.of(root.resolve("venv/Scripts/python.exe").toString(), "-m", "pip"));
        command.addAll(arguments);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }
}

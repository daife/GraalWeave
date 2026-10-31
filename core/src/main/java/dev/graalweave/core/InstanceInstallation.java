package dev.graalweave.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Materialize the standalone tools from the mod's bundled JARs. */
public final class InstanceInstallation {
    private InstanceInstallation() {}

    public static void prepare(Path instance, Path bundledLibraries) throws IOException {
        Path directory = instance.toAbsolutePath().normalize().resolve("graalweave");
        Path client = directory.resolve("client");
        Path lib = Files.createDirectories(client.resolve("lib"));
        Set<String> names = new HashSet<>();
        try (var jars = Files.list(bundledLibraries)) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                String name = jar.getFileName().toString();
                names.add(name);
                Path target = lib.resolve(name);
                if (!Files.exists(target) || Files.mismatch(jar, target) != -1)
                    Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (names.isEmpty()) throw new IOException("No bundled GraalWeave libraries found");
        try (var installed = Files.list(lib)) {
            for (Path old : installed.filter(p -> !names.contains(p.getFileName().toString())).toList()) recycle(old);
        }
        // Earlier installers kept a second set of runtime JARs here.
        recycle(directory.resolve("python/lib"));
        Files.createDirectories(directory.resolve("startup"));
        Files.createDirectories(directory.resolve("workspace"));
        Path bin = Files.createDirectories(client.resolve("bin"));
        Files.writeString(client.resolve("java-home.txt"), System.getProperty("java.home") + "\r\n");
        Files.writeString(bin.resolve("graalweave.bat"), """
                @echo off
                setlocal
                chcp 65001 >nul
                set "APP_HOME=%~dp0.."
                set /p GW_JAVA_HOME=<"%APP_HOME%\\java-home.txt"
                "%GW_JAVA_HOME%\\bin\\java.exe" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -cp "%APP_HOME%\\lib\\*" dev.graalweave.cli.Main --instance "%APP_HOME%\\..\\.." %*
                exit /b %errorlevel%
                """.replace("\n", "\r\n"));
        recycle(bin.resolve("graalweave"));
        String java = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
        List<String> args = List.of("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-cp",
                lib + "\\*", "dev.graalweave.cli.Main", "--instance", directory.getParent().toString(), "mcp");
        Files.writeString(directory.resolve("mcp.json"), Json.GSON.toJson(Json.object("mcpServers",
                Json.object("graalweave", Json.object("command", java, "args", args)))) + "\n");
        PythonEnvironment.initialize(instance);
    }

    public static void recycle(Path path) throws IOException {
        if (!Files.exists(path)) return;
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName Microsoft.VisualBasic
                $path = $env:GRAALWEAVE_RECYCLE_PATH
                if ([IO.Directory]::Exists($path)) {
                    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($path, 'OnlyErrorDialogs', 'SendToRecycleBin')
                } else {
                    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($path, 'OnlyErrorDialogs', 'SendToRecycleBin')
                }
                """);
        process.environment().put("GRAALWEAVE_RECYCLE_PATH", path.toAbsolutePath().toString());
        process.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            if (process.start().waitFor() != 0) throw new IOException("Cannot recycle " + path);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}

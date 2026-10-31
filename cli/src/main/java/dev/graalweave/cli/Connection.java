package dev.graalweave.cli;

import com.google.gson.*;
import dev.graalweave.core.Json;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class Connection implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;

    Connection(Path instance) throws IOException {
        Path file = instance.resolve("graalweave/connection.json");
        if (!Files.isRegularFile(file)) throw new IOException("No running GraalWeave instance: " + file);
        JsonObject info = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (info.get("protocol").getAsInt() != 1) throw new IOException("Unsupported GraalWeave protocol");
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", info.get("port").getAsInt()), 3000);
        socket.setTcpNoDelay(true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }
    synchronized void send(JsonObject request) throws IOException {
        writer.write(Json.GSON.toJson(request)); writer.newLine(); writer.flush();
    }
    JsonObject receive() throws IOException {
        String line = reader.readLine();
        if (line == null) throw new EOFException("Game connection closed");
        return JsonParser.parseString(line).getAsJsonObject();
    }
    static JsonObject request(Path instance, JsonObject request) throws IOException {
        try (var connection = new Connection(instance)) {
            connection.send(request);
            JsonObject result = connection.receive();
            if (Json.string(result, "type", "").equals("error")) throw new IOException(Json.string(result, "message", "Request failed"));
            return result;
        }
    }
    @Override public void close() throws IOException { socket.close(); }
}

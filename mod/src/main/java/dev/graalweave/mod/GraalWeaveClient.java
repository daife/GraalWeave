package dev.graalweave.mod;

import dev.graalweave.core.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GraalWeaveClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("GraalWeave");
    private RuntimeService runtime;
    private ProtocolServer server;

    @Override public void onInitializeClient() {
        runtime = new RuntimeService(FabricLoader.getInstance().getGameDir(), new GameAccess() {
            @Override public boolean isMainThread() { return Minecraft.getInstance().isSameThread(); }
            @Override public void execute(Runnable action) { Minecraft.getInstance().execute(action); }
            @Override public ClassLoader classLoader() { return GraalWeaveClient.class.getClassLoader(); }
            @Override public java.util.List<ApiExplorer.Mod> mods() {
                return FabricLoader.getInstance().getAllMods().stream().map(mod -> new ApiExplorer.Mod(
                        mod.getMetadata().getId(), mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(),
                        mod.getRootPaths())).toList();
            }
        });
        try {
            server = new ProtocolServer(runtime);
            LOG.info("GraalWeave is listening on loopback; connection file: {}/graalweave/connection.json", FabricLoader.getInstance().getGameDir());
        } catch (Exception e) { runtime.close(); throw new IllegalStateException("Cannot start GraalWeave", e); }
        ClientTickEvents.END_CLIENT_TICK.register(client -> runtime.tick());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.event("join"));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runtime.event("leave"));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                runtime.runStartup(message -> {
                    String script = Json.string(message, "script", "startup");
                    switch (Json.string(message, "type", "")) {
                        case "started" -> LOG.info("Running startup script: {}", script);
                        case "output" -> {
                            String text = Json.string(message, "text", "").stripTrailing();
                            if (Json.string(message, "stream", "").equals("stderr")) LOG.error("[{}] {}", script, text);
                            else LOG.info("[{}] {}", script, text);
                        }
                        case "done" -> {
                            String status = Json.string(message, "status", "");
                            if (!status.equals("ok") && !(status.equals("exit") && message.get("exitCode").getAsInt() == 0))
                                LOG.warn("Startup script {} finished: {}", script, message);
                        }
                        case "error", "subscription_error" -> LOG.warn("Startup script {}: {}", script, message);
                    }
                });
            } catch (java.io.IOException e) { LOG.error("Cannot load GraalWeave startup scripts", e); }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try { server.close(); } catch (Exception e) { LOG.warn("Closing GraalWeave listener", e); }
            runtime.close();
        });
    }
}

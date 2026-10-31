package dev.graalweave.core;

/** The core can also run without Minecraft for integration checks. */
public interface GameAccess {
    boolean isMainThread();
    void execute(Runnable action);
    ClassLoader classLoader();
    java.util.List<ApiExplorer.Mod> mods();
}

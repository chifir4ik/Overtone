package dev.musicplayer;

import java.nio.file.Path;

/** Каталоги игры. Загрузчик (Fabric/NeoForge) задаёт их один раз при старте. */
public final class Env {
    private Env() {}

    private static Path config;
    private static Path game;

    public static void init(Path configDir, Path gameDir) {
        config = configDir;
        game = gameDir;
    }

    public static Path configDir() { return config; }
    public static Path gameDir() { return game; }
}

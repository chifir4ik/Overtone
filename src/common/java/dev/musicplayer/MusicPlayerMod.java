package dev.musicplayer;

import dev.musicplayer.config.HudConfig;
import dev.musicplayer.hud.CoverArt;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.controller.PlayerController;

import java.nio.file.Path;

/** Общая часть запуска; загрузчики вызывают init и подключают события игры. */
public final class MusicPlayerMod {
    private MusicPlayerMod() {}

    /** ID остаётся прежним, чтобы не потерять конфиги, кэш и настройки клавиш пользователей. */
    public static final String MOD_ID = "musicplayer";
    public static final String NAME = "Overtone";
    public static final String AUTHORS = "chifir4ik, PabJlo";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NAME);

    public static void init(Path configDir, Path gameDir) {
        Env.init(configDir, gameDir);
        LOGGER.info("{} by {}", NAME, AUTHORS);
        HudConfig.get();
        Hotkeys.create();
        dev.musicplayer.discord.DiscordPresence.start();
    }

    /** options.txt к этому моменту уже прочитан. */
    public static void onClientStarted() { Hotkeys.onClientStarted(); }

    public static void onClientTick() {
        CoverArt.tick();
        Hotkeys.tick();
        PlayerController.I.tickPersist();
        dev.musicplayer.viz.Visualizer.tick();
    }
}

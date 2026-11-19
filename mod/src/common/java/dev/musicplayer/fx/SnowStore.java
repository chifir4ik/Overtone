package dev.musicplayer.fx;

import dev.musicplayer.Env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Сохраняет снег (шапку и снеговиков) между запусками игры: config/overtone-snow.txt, два раздела: [player] и [settings]. */
public final class SnowStore {
    private SnowStore() {}

    private static boolean loaded;

    private static Path file() {
        Path dir = Env.configDir();
        return dir == null ? null : dir.resolve("overtone-snow.txt");
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            List<String> player = new ArrayList<>(), settings = new ArrayList<>(), cur = null;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.equals("[player]")) cur = player;
                else if (line.equals("[settings]")) cur = settings;
                else if (cur != null) cur.add(line);
            }
            SnowFx.PLAYER.restore(player);
            SnowFx.SETTINGS.restore(settings);
        } catch (IOException | RuntimeException ignored) {
            // нет файла или он повреждён: снег начнётся заново
        }
    }

    public static synchronized void save() {
        Path f = file();
        if (f == null) return;
        try {
            String text = "[player]\n" + SnowFx.PLAYER.dump() + "[settings]\n" + SnowFx.SETTINGS.dump();
            Path tmp = f.resolveSibling("overtone-snow.txt.part");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // не записалось: в следующий раз сохранится
        }
    }
}

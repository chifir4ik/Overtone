package dev.musicplayer.font;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.musicplayer.Env;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.LyricFont;
import dev.musicplayer.config.PlayerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Подключает выбранный шрифт к игре. Игра читает шрифты только из ресурсов, поэтому выбранный файл копируется в маленький
 * служебный пак «OvertoneFont» (папка resourcepacks), и он включается в список паков. Пока выбран обычный шрифт,
 * пака нет вовсе: он удаляется и выключается, а ресурспаки игрока ни с чем не пересекаются (у пака свой шрифт musicplayer:lyrics_user).
 */
public final class FontPack {
    private FontPack() {}

    public static final String DIR = "OvertoneFont";
    private static final String ID = "file/" + DIR;

    public enum State { IDLE, WORKING, OK, ERROR }

    private static volatile State state = State.IDLE;
    private static volatile String message = "";

    public static State state() { return state; }

    public static String message() { return message; }

    /** Параметры растеризации так, как их понимает игра (провайдер ttf). */
    public record Params(float size, float oversample, float shiftX, float shiftY) {}

    private static Path packDir() { return Env.gameDir().resolve("resourcepacks").resolve(DIR); }

    /** Выбрать шрифт из каталога: файл уходит в пак, пак включается, ресурсы перезагружаются. */
    public static void use(FontCatalog.Entry entry, Params p) {
        if (state == State.WORKING) return;
        state = State.WORKING;
        message = "";
        Thread t = new Thread(() -> {
            try {
                write(entry, p);
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> enable(mc, entry.id(), p));
            } catch (Throwable e) {
                fail(e);
            }
        }, "Overtone-fontpack");
        t.setDaemon(true);
        t.start();
    }

    /** Вернуть обычный шрифт: пак выключается и удаляется. */
    public static void useDefault() {
        if (state == State.WORKING) return;
        state = State.WORKING;
        message = "";
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> disable(mc));
    }

    private static void write(FontCatalog.Entry entry, Params p) throws IOException {
        Path dir = packDir();
        Path fontDir = dir.resolve("assets").resolve(MusicPlayerMod.MOD_ID).resolve("font");
        Files.createDirectories(fontDir);
        Path tmp = fontDir.resolve("lyrics_user.ttf.part");
        Files.copy(entry.file(), tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, fontDir.resolve("lyrics_user.ttf"), StandardCopyOption.REPLACE_EXISTING);

        JsonObject ttf = new JsonObject();
        ttf.addProperty("type", "ttf");
        ttf.addProperty("file", MusicPlayerMod.MOD_ID + ":lyrics_user.ttf");
        ttf.addProperty("size", p.size());
        ttf.addProperty("oversample", p.oversample());
        JsonArray shift = new JsonArray();
        shift.add(p.shiftX());
        shift.add(p.shiftY());
        ttf.add("shift", shift);
        ttf.addProperty("skip", "");
        // чего нет в выбранном шрифте (символы, иероглифы), добирается из обычного шрифта игры вместе с его ресурспаками
        JsonObject vanilla = new JsonObject();
        vanilla.addProperty("type", "reference");
        vanilla.addProperty("id", "minecraft:default");
        JsonArray providers = new JsonArray();
        providers.add(ttf);
        providers.add(vanilla);
        JsonObject root = new JsonObject();
        root.add("providers", providers);
        Files.writeString(fontDir.resolve("lyrics_user.json"), root.toString(), StandardCharsets.UTF_8);

        // диапазон версий с запасом: пак содержит только шрифт и должен подходить любой версии игры
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Overtone: шрифт текста песен (создаётся модом, можно не трогать)");
        pack.addProperty("pack_format", 46);
        JsonObject range = new JsonObject();
        range.addProperty("min_inclusive", 0);
        range.addProperty("max_inclusive", 999);
        pack.add("supported_formats", range);
        pack.addProperty("min_format", 0);
        pack.addProperty("max_format", 999);
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        Files.writeString(dir.resolve("pack.mcmeta"), meta.toString(), StandardCharsets.UTF_8);
    }

    private static void enable(Minecraft mc, String fontId, Params p) {
        try {
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            if (!repo.getAvailableIds().contains(ID)) throw new IOException("pack not found: " + ID);
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            if (!selected.contains(ID)) selected.add(ID);
            repo.setSelected(selected);
            if (!mc.options.resourcePacks.contains(ID)) mc.options.resourcePacks.add(ID);
            mc.options.save();
            PlayerConfig c = PlayerConfig.get();
            c.lyricFont = fontId;
            c.lyricFontSize = p.size();
            c.lyricFontOversample = p.oversample();
            c.lyricFontShiftX = p.shiftX();
            c.lyricFontShiftY = p.shiftY();
            c.save();
            reload(mc);
        } catch (Throwable e) {
            fail(e);
        }
    }

    private static void disable(Minecraft mc) {
        try {
            PlayerConfig c = PlayerConfig.get();
            c.lyricFont = "";
            c.save();
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            boolean was = selected.remove(ID);
            if (was) repo.setSelected(selected);
            boolean listed = mc.options.resourcePacks.remove(ID);
            if (listed) mc.options.save();
            if (was || listed) {
                reload(mc);   // шрифт ещё сидит в загруженных ресурсах; файлы удалим после перезагрузки
            } else {
                LyricFonts.changed();
                state = State.OK;
            }
            Thread t = new Thread(FontPack::cleanup, "Overtone-fontpack-clean");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            fail(e);
        }
    }

    private static void reload(Minecraft mc) {
        mc.reloadResourcePacks().whenComplete((v, e) -> mc.execute(() -> {
            LyricFonts.changed();
            if (e != null) fail(e);
            else if (!PlayerConfig.get().lyricFont.isEmpty() && !LyricFont.exists()) {
                state = State.ERROR;
                message = "pack";
            } else {
                state = State.OK;
            }
        }));
    }

    private static void cleanup() {
        try {
            Thread.sleep(4000);   // даём игре закончить перезагрузку: она ещё может держать файл открытым
            Path dir = packDir();
            if (!Files.isDirectory(dir) || !PlayerConfig.get().lyricFont.isEmpty()) return;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        } catch (Exception ignored) {
            // лишняя папка пака не мешает
        }
    }

    private static void fail(Throwable e) {
        MusicPlayerMod.LOGGER.warn("Failed to apply lyrics font", e);
        message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        state = State.ERROR;
    }
}

package dev.musicplayer.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import java.util.Map;

/**
 * Горячие клавиши. Источник правды — обычные KeyMapping (options.txt): видны в ванильном меню управления
 * и сохраняются сразу при смене. Раньше они дублировались в musicplayer-player.json, и хранилища расходились.
 */
public final class Hotkeys {
    private Hotkeys() {}

    public enum Action {
        PREV("Предыдущий трек", 44, "key.musicplayer.prev"),        // ,
        NEXT("Следующий трек", 46, "key.musicplayer.next"),         // .
        PAUSE("Пауза / старт", 47, "key.musicplayer.pause"),        // /
        VOL_UP("Громкость +", 61, "key.musicplayer.vol_up"),        // =
        VOL_DOWN("Громкость −", 45, "key.musicplayer.vol_down"),    // -
        WORLD_LYRICS("Летающий текст вкл/выкл", 74, "key.musicplayer.world_lyrics");   // J

        public final String label;
        public final int defaultKey;
        final String translationKey;
        Action(String label, int defaultKey, String translationKey) {
            this.label = label; this.defaultKey = defaultKey; this.translationKey = translationKey;
        }
    }

    public static final float VOLUME_STEP = 0.05f;
    private static final int UNBOUND = -1;
    private static final KeyMapping[] MAPPINGS = new KeyMapping[Action.values().length];

    public static void register() {
        for (Action a : Action.values()) {
            MAPPINGS[a.ordinal()] = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    a.translationKey, InputConstants.Type.KEYBOARD, a.defaultKey, KeyMapping.Category.MISC));
        }
    }

    /**
     * Вызывать, когда клиент полностью запущен (options.txt уже прочитан): один раз переносит клавиши
     * из старого musicplayer-player.json в KeyMapping.
     */
    public static void migrateLegacy() {
        PlayerConfig pc = PlayerConfig.get();
        if (pc.keys == null || pc.keys.isEmpty()) return;
        for (Map.Entry<String, Integer> e : pc.keys.entrySet()) {
            Action a;
            try { a = Action.valueOf(e.getKey()); } catch (Exception ex) { continue; }
            KeyMapping km = MAPPINGS[a.ordinal()];
            if (km == null || e.getValue() == null) continue;
            km.setKey(e.getValue() == UNBOUND ? InputConstants.UNKNOWN : InputConstants.Type.KEYBOARD.getOrCreate(e.getValue()));
        }
        pc.keys.clear();
        pc.save();
        KeyMapping.resetMapping();
        save();
    }

    public static void tick() {
        boolean inWorld = Minecraft.getInstance().gui.screen() == null;
        for (Action a : Action.values()) {
            KeyMapping km = MAPPINGS[a.ordinal()];
            if (km == null) continue;
            while (km.consumeClick()) if (inWorld) perform(a);
        }
    }

    /** Действие, привязанное к нажатой клавише, или null. Нужно окну плеера, где KeyMapping не срабатывают. */
    public static Action transportFor(KeyEvent event) {
        for (Action a : Action.values()) {
            KeyMapping km = MAPPINGS[a.ordinal()];
            if (km != null && !km.isUnbound() && km.matches(event)) return a;
        }
        return null;
    }

    public static void perform(Action a) {
        if (a == Action.WORLD_LYRICS) {   // это просто настройка, вход в аккаунт не нужен
            HudConfig c = HudConfig.get();
            c.worldLyrics = !c.worldLyrics;
            c.save();
            WorldLyricsRenderer.toast(c.worldLyrics ? "Летающий текст: вкл" : "Летающий текст: выкл");
            return;
        }
        PlayerController ctl = PlayerController.I;
        if (!ctl.yandex.isLoggedIn()) return;
        switch (a) {
            case PREV -> ctl.prev();
            case NEXT -> ctl.next();
            case PAUSE -> ctl.togglePause();
            case VOL_UP -> { ctl.changeVolume(VOLUME_STEP); showVolume(); }
            case VOL_DOWN -> { ctl.changeVolume(-VOLUME_STEP); showVolume(); }
            default -> { }
        }
    }

    /** Плашка громкости нужна в мире; в окне плеера есть свой ползунок. */
    private static void showVolume() {
        if (Minecraft.getInstance().gui.screen() == null) VolumeRenderer.poke();
    }

    /** Назначает клавишу (-1 — снять). Если она уже занята другим действием мода, то у того она снимается. */
    public static void setKey(Action a, int code) {
        KeyMapping km = MAPPINGS[a.ordinal()];
        if (km == null) return;
        InputConstants.Key key = code == UNBOUND ? InputConstants.UNKNOWN : InputConstants.Type.KEYBOARD.getOrCreate(code);
        if (code != UNBOUND) {
            for (Action other : Action.values()) {
                KeyMapping o = MAPPINGS[other.ordinal()];
                if (other != a && o != null && !o.isUnbound() && o.saveString().equals(key.getName()))
                    o.setKey(InputConstants.UNKNOWN);
            }
        }
        km.setKey(key);
        KeyMapping.resetMapping();
        save();
    }

    public static void resetAll() {
        for (Action a : Action.values()) {
            KeyMapping km = MAPPINGS[a.ordinal()];
            if (km != null) km.setKey(km.getDefaultKey());
        }
        KeyMapping.resetMapping();
        save();
    }

    /** Сразу пишет options.txt — иначе клавиша сохранилась бы только при нормальном выходе из игры. */
    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.save();
    }

    public static String keyName(Action a) {
        KeyMapping km = MAPPINGS[a.ordinal()];
        if (km == null || km.isUnbound()) return "не назначено";
        return km.getTranslatedKeyMessage().getString();
    }
}

package dev.musicplayer.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.KeyInput;
import dev.musicplayer.compat.Keys;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import dev.musicplayer.ui.HudConfigScreen;
import dev.musicplayer.ui.PlayerScreen;
import dev.musicplayer.util.T;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Горячие клавиши. Источник правды — обычные KeyMapping (options.txt): они видны в ванильном меню
 * управления в своей категории и сохраняются сразу при смене.
 *
 * Клавиши подобраны так, чтобы существовали на клавиатурах 60–75%: никаких цифрового блока, F-ряда и стрелок.
 */
public final class Hotkeys {
    private Hotkeys() {}

    public enum Action {
        OPEN_PLAYER("open", InputConstants.KEY_M),
        TOGGLE_HUD("toggle_hud", InputConstants.UNKNOWN.getValue()),
        HUD_SETTINGS("hud_settings", InputConstants.KEY_K),
        PREV("prev", InputConstants.KEY_LBRACKET),
        NEXT("next", InputConstants.KEY_RBRACKET),
        PAUSE("pause", InputConstants.KEY_SEMICOLON),
        VOL_UP("vol_up", InputConstants.KEY_EQUALS),
        VOL_DOWN("vol_down", InputConstants.KEY_MINUS),
        WORLD_LYRICS("world_lyrics", InputConstants.KEY_J);

        public final int defaultKey;
        final String translationKey;
        Action(String id, int defaultKey) { this.defaultKey = defaultKey; this.translationKey = "key.musicplayer." + id; }

        public String label() { return Component.translatable(translationKey).getString(); }

        /** Транспорт работает и в окне плеера, и на экранах меню, где KeyMapping сами не срабатывают. */
        public boolean isTransport() { return this == PREV || this == NEXT || this == PAUSE || this == VOL_UP || this == VOL_DOWN; }
    }

    public static final float VOLUME_STEP = 0.05f;
    /** Поднимать при смене умолчаний: клавиши игроков сбрасываются на новые один раз. */
    private static final int KEYS_VERSION = 3;
    private static final int UNBOUND = -1;
    private static final KeyMapping[] MAPPINGS = new KeyMapping[Action.values().length];

    /** Создаёт KeyMapping; регистрирует их загрузчик (см. {@link #mappings()}). */
    public static void create() {
        for (Action a : Action.values())
            MAPPINGS[a.ordinal()] = Keys.create(a.translationKey, a.defaultKey);
    }

    public static KeyMapping[] mappings() { return MAPPINGS.clone(); }

    /**
     * Вызывать, когда клиент полностью запущен (options.txt уже прочитан): переносит клавиши из очень старого
     * musicplayer-player.json и один раз сбрасывает прежние умолчания на новые.
     */
    public static void onClientStarted() {
        PlayerConfig pc = PlayerConfig.get();
        boolean dirty = false;
        if (pc.keys != null && !pc.keys.isEmpty()) {
            for (Map.Entry<String, Integer> e : pc.keys.entrySet()) {
                Action a;
                try { a = Action.valueOf(e.getKey()); } catch (Exception ex) { continue; }
                KeyMapping km = MAPPINGS[a.ordinal()];
                if (km == null || e.getValue() == null) continue;
                km.setKey(e.getValue() == UNBOUND ? InputConstants.UNKNOWN : Keys.key(e.getValue()));
            }
            pc.keys.clear();
            dirty = true;
        }
        if (pc.keysVersion < KEYS_VERSION) {
            for (Action a : Action.values()) {
                KeyMapping km = MAPPINGS[a.ordinal()];
                boolean reset = pc.keysVersion < 2 || a == Action.TOGGLE_HUD;
                if (km != null && reset) km.setKey(km.getDefaultKey());
            }
            pc.keysVersion = KEYS_VERSION;
            dirty = true;
        }
        if (dirty) {
            pc.save();
            KeyMapping.resetMapping();
            save();
        }
    }

    public static void tick() {
        boolean inWorld = Game.screen() == null;
        for (Action a : Action.values()) {
            KeyMapping km = MAPPINGS[a.ordinal()];
            if (km == null) continue;
            while (km.consumeClick()) if (inWorld) perform(a, null);
        }
    }

    /** Действие, привязанное к нажатой клавише, или null. Нужно экранам, где KeyMapping не срабатывают. */
    public static Action actionFor(KeyInput event) {
        for (Action a : Action.values()) {
            KeyMapping km = MAPPINGS[a.ordinal()];
            if (km != null && !km.isUnbound() && event.matches(km)) return a;
        }
        return null;
    }

    /**
     * Обработка клавиши на экране главного меню, паузы или отключения: плеер доступен и вне мира.
     * Возвращает true, если клавиша обработана.
     */
    public static boolean handleOnMenu(Screen screen, KeyInput event) {
        Action a = actionFor(event);
        if (a == null || a == Action.TOGGLE_HUD || a == Action.WORLD_LYRICS) return false;
        perform(a, screen);
        return true;
    }

    /** menu — экран, с которого пришла клавиша (null — игра без экрана). Окна мода открываются поверх него. */
    public static void perform(Action a, Screen menu) {
        switch (a) {
            case OPEN_PLAYER -> Game.open(new PlayerScreen(menu));
            case HUD_SETTINGS -> Game.open(new HudConfigScreen(menu));
            case TOGGLE_HUD -> {
                HudConfig c = HudConfig.get();
                c.master = !c.master;
                c.save();
            }
            case WORLD_LYRICS -> {
                HudConfig c = HudConfig.get();
                c.worldLyrics = !c.worldLyrics;
                c.save();
                WorldLyricsRenderer.toast(T.get(c.worldLyrics ? "toast.world_on" : "toast.world_off"));
            }
            default -> transport(a);
        }
    }

    private static void transport(Action a) {
        PlayerController ctl = PlayerController.I;
        if (!ctl.engine().isLoggedIn()) return;
        switch (a) {
            case PREV -> ctl.prev();
            case NEXT -> ctl.next();
            case PAUSE -> ctl.togglePause();
            case VOL_UP -> { ctl.changeVolume(VOLUME_STEP); poke(); }
            case VOL_DOWN -> { ctl.changeVolume(-VOLUME_STEP); poke(); }
            default -> { }
        }
    }

    /** Плашка громкости нужна только в мире; в окнах есть свои ползунки. */
    private static void poke() {
        if (Game.screen() == null) VolumeRenderer.poke();
    }

    /** Назначает клавишу (-1 — снять). Если она уже занята другим действием мода, то у того она снимается. */
    public static void setKey(Action a, int code) {
        KeyMapping km = MAPPINGS[a.ordinal()];
        if (km == null) return;
        InputConstants.Key key = code == UNBOUND ? InputConstants.UNKNOWN : Keys.key(code);
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

    public static void reset(Action a) {
        KeyMapping km = MAPPINGS[a.ordinal()];
        if (km == null) return;
        setKey(a, km.getDefaultKey().getValue());
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
        if (km == null || km.isUnbound()) return T.get("keys.unbound");
        return km.getTranslatedKeyMessage().getString();
    }

    public static boolean isDefault(Action a) {
        KeyMapping km = MAPPINGS[a.ordinal()];
        return km != null && km.isDefault();
    }
}

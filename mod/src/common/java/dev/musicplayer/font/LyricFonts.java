package dev.musicplayer.font;

import dev.musicplayer.compat.LyricFont;
import dev.musicplayer.config.PlayerConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Какой шрифт у текста песен. Пока в каталоге выбран «Minecraft» (по умолчанию), {@link #active()} ложно, и весь текст
 * рисуется ровно как раньше обычным шрифтом игры, который меняют ресурспаками.
 */
public final class LyricFonts {
    private LyricFonts() {}

    private static volatile int stamp;
    private static volatile long checkedAt;
    private static volatile boolean present;

    /** Меняется при каждой смене шрифта: кэши раскладки текста по нему сбрасываются. */
    public static int stamp() { return stamp; }

    public static void changed() {
        stamp++;
        checkedAt = 0;
    }

    /** true — выбран свой шрифт и он реально загружен (его пак мог быть выключен или удалён). */
    public static boolean active() {
        String id = PlayerConfig.get().lyricFont;
        if (id == null || id.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (now - checkedAt > 1000) {
            present = LyricFont.exists();
            checkedAt = now;
        }
        return present;
    }

    public static Component component(String value) {
        return active() ? LyricFont.text(value) : Component.literal(value);
    }

    public static FormattedCharSequence seq(String value) {
        return active() ? LyricFont.text(value).getVisualOrderText() : FormattedCharSequence.forward(value, Style.EMPTY);
    }

    public static int width(Font font, String value) {
        return active() ? font.width(seq(value)) : font.width(value);
    }
}

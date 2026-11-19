package dev.musicplayer.compat;

import dev.musicplayer.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Шрифт текста песен, выбранный игроком в каталоге: id шрифта и проверка, что он загружен. Тип id зависит от версии игры. */
public final class LyricFont {
    private LyricFont() {}

    private static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "lyrics_user"));
    private static final Identifier JSON = Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "font/lyrics_user.json");

    public static Component text(String value) {
        return Component.literal(value).withStyle(s -> s.withFont(FONT));
    }

    /** Есть ли сейчас в загруженных ресурсах описание этого шрифта. */
    public static boolean exists() {
        try {
            return Minecraft.getInstance().getResourceManager().getResource(JSON).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}

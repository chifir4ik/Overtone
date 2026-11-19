package dev.musicplayer.compat;

import com.mojang.blaze3d.platform.InputConstants;
import dev.musicplayer.MusicPlayerMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class Keys {
    private Keys() {}

    public static final int ENTER = InputConstants.KEY_RETURN;
    public static final int KP_ENTER = InputConstants.KEY_NUMPADENTER;
    public static final int ESCAPE = InputConstants.KEY_ESCAPE;
    public static final int DELETE = InputConstants.KEY_DELETE;
    public static final int BACKSPACE = InputConstants.KEY_BACKSPACE;
    public static final int SPACE = InputConstants.KEY_SPACE;
    public static final int LEFT = InputConstants.KEY_LEFT;
    public static final int RIGHT = InputConstants.KEY_RIGHT;
    public static final int UP = InputConstants.KEY_UP;
    public static final int DOWN = InputConstants.KEY_DOWN;

    public static InputConstants.Key key(int code) { return InputConstants.Type.KEYSYM.getOrCreate(code); }

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "main"));

    /** NeoForge требует зарегистрировать категорию отдельно. */
    public static KeyMapping.Category category() { return CATEGORY; }

    public static KeyMapping create(String translationKey, int defaultKey) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
    }
}

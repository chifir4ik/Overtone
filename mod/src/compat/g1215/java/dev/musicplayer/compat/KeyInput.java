package dev.musicplayer.compat;

import net.minecraft.client.KeyMapping;

/** Нажатие клавиши в версионно-независимом виде. */
public final class KeyInput {
    private final int key, scan, mods;

    public KeyInput(int key, int scan, int mods) { this.key = key; this.scan = scan; this.mods = mods; }

    public int key() { return key; }
    public int scan() { return scan; }
    public int mods() { return mods; }

    public boolean matches(KeyMapping km) { return km.matches(key, scan); }
}

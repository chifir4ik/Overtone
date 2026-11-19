package dev.musicplayer.compat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

/** Нажатие клавиши в версионно-независимом виде. */
public final class KeyInput {
    private final KeyEvent event;

    public KeyInput(KeyEvent event) { this.event = event; }

    public KeyEvent raw() { return event; }
    public int key() { return event.key(); }

    public boolean matches(KeyMapping km) { return km.matches(event); }
}

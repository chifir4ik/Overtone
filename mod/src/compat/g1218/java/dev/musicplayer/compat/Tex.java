package dev.musicplayer.compat;

import net.minecraft.resources.ResourceLocation;

/** Зарегистрированная текстура; тип идентификатора зависит от версии игры. */
public final class Tex {
    public final ResourceLocation id;

    public Tex(ResourceLocation id) { this.id = id; }
}

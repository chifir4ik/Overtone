package dev.musicplayer.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class CameraPos {
    private CameraPos() {}

    /** Позиция камеры главного вида; null, если рендерер ещё не создан. */
    public static Vec3 get() {
        var renderer = Minecraft.getInstance().gameRenderer;
        return renderer == null ? null : renderer.getMainCamera().position();
    }
}

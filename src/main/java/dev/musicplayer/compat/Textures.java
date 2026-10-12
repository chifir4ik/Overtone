package dev.musicplayer.compat;

import com.mojang.blaze3d.platform.NativeImage;
import dev.musicplayer.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** ARGB-пиксели -> зарегистрированная текстура игры. Только из рендер-потока. */
public final class Textures {
    private Textures() {}

    public static Identifier upload(String name, int[] argb, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        try {
            NativeImage image = new NativeImage(size, size, false);
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    image.setPixel(x, y, argb[y * size + x]);
            DynamicTexture texture = new DynamicTexture(() -> name, image);
            Identifier id = Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, name);
            mc.getTextureManager().register(id, texture);
            return id;
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.warn("Не удалось загрузить обложку в текстуру", e);
            return null;
        }
    }

    public static void release(Identifier id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.getTextureManager().release(id);
    }
}

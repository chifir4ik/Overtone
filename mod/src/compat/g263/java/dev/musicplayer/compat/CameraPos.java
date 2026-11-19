package dev.musicplayer.compat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

public final class CameraPos {
    private CameraPos() {}

    private static Method cameraMethod;
    private static boolean resolved;

    /** Позиция камеры главного вида; null, если найти не удалось. Имя метода менялось между версиями, поэтому ищем по типу. */
    public static Vec3 get() {
        GameRenderer renderer = Minecraft.getInstance().gameRenderer;
        if (renderer == null) return null;
        try {
            if (!resolved) {
                resolved = true;
                for (Method m : GameRenderer.class.getMethods()) {
                    if (m.getParameterCount() == 0 && Camera.class.isAssignableFrom(m.getReturnType())
                            && (m.getName().equals("getMainCamera") || m.getName().equals("mainCamera"))) {
                        cameraMethod = m;
                        break;
                    }
                }
                if (cameraMethod == null) {
                    for (Method m : GameRenderer.class.getMethods()) {
                        if (m.getParameterCount() == 0 && Camera.class.isAssignableFrom(m.getReturnType())) {
                            cameraMethod = m;
                            break;
                        }
                    }
                }
            }
            if (cameraMethod != null) return ((Camera) cameraMethod.invoke(renderer)).position();
        } catch (Throwable ignored) {
        }
        return null;
    }
}

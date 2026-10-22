package dev.musicplayer.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * До 26.x текст в мире рисуется сразу в общий буфер и сбрасывается в конце кадра.
 * <p>
 * Игра сортирует квады полупрозрачного текста по расстоянию до камеры. Тень сдвинута на пиксель вбок, и у
 * повёрнутого текста этот сдвиг может приблизить её к камере сильнее, чем ванильные 0.03 по глубине отодвигают
 * её назад, — тогда тень рисуется после текста и ложится сверху. Поэтому сначала уходят в буфер все тени и он
 * сбрасывается, и только потом рисуется сам текст.
 */
public final class WorldTextImpl extends WorldText {
    private static final int LIGHT = 0xF000F0;       // полная яркость независимо от освещения мира
    private static final float SHADOW_BACK = 0.5f;   // на сколько тень отодвинута от зрителя, в единицах текста

    private record Front(FormattedCharSequence seq, float x, float y, int argb, Font.DisplayMode mode, Matrix4f pose) {}

    private final MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    private final List<Front> fronts = new ArrayList<>();

    @Override
    public void text(PoseStack pose, float x, float y, FormattedCharSequence seq,
                     boolean shadow, boolean seeThrough, int argb) {
        Font font = Minecraft.getInstance().font;
        Font.DisplayMode mode = seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        if (!shadow) {
            font.drawInBatch(seq, x, y, argb, false, pose.last().pose(), buffers, mode, 0, LIGHT);
            return;
        }
        int dark = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
        pose.pushPose();
        pose.translate(0f, 0f, -towardViewer(pose.last().pose()) * SHADOW_BACK);
        font.drawInBatch(seq, x + 1f, y + 1f, dark, false, pose.last().pose(), buffers, mode, 0, LIGHT);
        pose.popPose();
        fronts.add(new Front(seq, x, y, argb, mode, new Matrix4f(pose.last().pose())));
    }

    /** +1, если ось Z текста смотрит на камеру (камера в начале координат этого пространства), иначе -1. */
    private static float towardViewer(Matrix4f m) {
        Vector3f origin = m.transformPosition(0f, 0f, 0f, new Vector3f());
        Vector3f ahead = m.transformPosition(0f, 0f, 1f, new Vector3f());
        return ahead.length() < origin.length() ? 1f : -1f;
    }

    @Override public void rotateY(PoseStack pose, float rad) { pose.mulPose(new Quaternionf().rotationY(rad)); }
    @Override public void rotateX(PoseStack pose, float rad) { pose.mulPose(new Quaternionf().rotationX(rad)); }
    @Override public void rotateZ(PoseStack pose, float rad) { pose.mulPose(new Quaternionf().rotationZ(rad)); }

    @Override
    public void flush() {
        buffers.endBatch();
        if (fronts.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        for (Front f : fronts)
            font.drawInBatch(f.seq, f.x, f.y, f.argb, false, f.pose, buffers, f.mode, 0, LIGHT);
        fronts.clear();
        buffers.endBatch();
    }
}

package dev.musicplayer.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.musicplayer.font.LyricFonts;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector3f;

/** Приёмник текста в мире на один кадр. Способ рисования зависит от версии игры. */
public abstract class WorldText {
    public abstract void text(PoseStack pose, float x, float y, FormattedCharSequence seq,
                              boolean shadow, boolean seeThrough, int argb);

    /**
     * Текст с тенью. С обычным шрифтом всё как раньше (тень делает сама игра). У выбранного шрифта буквы толстые, и
     * встроенная тень игры ложится поверх них, поэтому тень рисуется отдельным слоем, сдвинутым вглубь от зрителя.
     */
    public final void shadowed(PoseStack pose, float x, float y, FormattedCharSequence seq,
                               boolean shadow, boolean seeThrough, int argb) {
        if (!shadow || !LyricFonts.active()) {
            text(pose, x, y, seq, shadow, seeThrough, argb);
            return;
        }
        // куда смотрит +Z текста относительно зрителя: к нему (тогда тень уводим в -Z) или от него
        org.joml.Matrix4f m = pose.last().pose();
        Vector3f at = m.transformPosition(new Vector3f(0f, 0f, 0f));
        Vector3f dir = m.transformDirection(new Vector3f(0f, 0f, 1f));
        float toViewer = -(dir.x * at.x + dir.y * at.y + dir.z * at.z);
        float back = toViewer > 0f ? -0.6f : 0.6f;   // вглубь: против взгляда зрителя
        int dark = (argb & 0xFF000000) | (((argb >> 16 & 255) >> 2) << 16) | (((argb >> 8 & 255) >> 2) << 8) | ((argb & 255) >> 2);
        pose.pushPose();
        pose.translate(0f, 0f, back);
        text(pose, x + 1f, y + 1f, seq, false, seeThrough, dark);
        pose.popPose();
        text(pose, x, y, seq, false, seeThrough, argb);
    }

    public abstract void rotateY(PoseStack pose, float rad);
    public abstract void rotateZ(PoseStack pose, float rad);
    /** Наклон вокруг горизонтальной оси: буквы, лежащие на блоках, повёрнуты плашмя. */
    public void rotateX(PoseStack pose, float rad) {}

    /** Вызывается после последнего слова кадра. */
    public void flush() {}
}

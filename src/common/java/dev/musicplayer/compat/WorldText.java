package dev.musicplayer.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.FormattedCharSequence;

/** Приёмник текста в мире на один кадр. Способ рисования зависит от версии игры. */
public abstract class WorldText {
    public abstract void text(PoseStack pose, float x, float y, FormattedCharSequence seq,
                              boolean shadow, boolean seeThrough, int argb);

    public abstract void rotateY(PoseStack pose, float rad);
    public abstract void rotateZ(PoseStack pose, float rad);
    /** Наклон вокруг горизонтальной оси: буквы, лежащие на блоках, повёрнуты плашмя. */
    public void rotateX(PoseStack pose, float rad) {}

    /** Вызывается после последнего слова кадра. */
    public void flush() {}
}

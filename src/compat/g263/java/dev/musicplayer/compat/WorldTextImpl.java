package dev.musicplayer.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

public final class WorldTextImpl extends WorldText {
    private static final int LIGHT = 0xF000F0;   // полная яркость независимо от освещения мира

    private final SubmitNodeCollector out;

    public WorldTextImpl(SubmitNodeCollector out) { this.out = out; }

    @Override
    public void text(PoseStack pose, float x, float y, FormattedCharSequence seq,
                     boolean shadow, boolean seeThrough, int argb) {
        Font.DisplayMode mode = seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        out.submitText(pose, x, y, seq, shadow, mode, LIGHT, argb, 0, 0);
    }

    @Override public void rotateY(PoseStack pose, float rad) { pose.mulPose(new Matrix4f().rotationY(rad)); }
    @Override public void rotateX(PoseStack pose, float rad) { pose.mulPose(new Matrix4f().rotationX(rad)); }
    @Override public void rotateZ(PoseStack pose, float rad) { pose.mulPose(new Matrix4f().rotationZ(rad)); }
}

package dev.musicplayer.mixin;

import dev.musicplayer.cinema.Cinematic;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Отдаёт камеру режиму «кино»: где она стоит и куда смотрит. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void overtone$cinematic(Level level, Entity entity, boolean isDetached, boolean thirdPersonReverse,
                                    float partialTick, CallbackInfo ci) {
        Cinematic.View v = Cinematic.frame(partialTick);
        if (v != null) {
            // detached нужен, чтобы мир рисовал тело самого игрока
            detached = true;
            setPosition(v.pos());
            setRotation(v.yaw(), v.pitch());
        }
    }
}

package dev.musicplayer.mixin;

import dev.musicplayer.cinema.Cinematic;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Угол обзора для режима «кино». */
@Mixin(Camera.class)
public abstract class FovMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void overtone$fov(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (Cinematic.active()) {
            cir.setReturnValue(Cinematic.fov(cir.getReturnValue()));
        }
    }
}

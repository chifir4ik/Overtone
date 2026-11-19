package dev.musicplayer.mixin;

import dev.musicplayer.compat.TabBadge;
import dev.musicplayer.share.ShareClient;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Наушники после ника у тех, у кого стоит Overtone, — как значок у Figura. Ширину колонки игра посчитает сама. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void overtone$badge(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        UUID id = TabBadge.id(info);
        if (!ShareClient.I.hasOvertone(id)) return;
        cir.setReturnValue(Component.empty().append(cir.getReturnValue()).append(" ")
                .append(TabBadge.badge(ShareClient.I.badgeColor(id))));
    }
}

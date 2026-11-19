package dev.musicplayer.neoforge;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.SharePayload;
import dev.musicplayer.share.ServerShare;
import dev.musicplayer.share.ShareNet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Канал «наушника» нужен с обеих сторон, поэтому это отдельная точка входа без привязки к клиенту. */
@Mod(MusicPlayerMod.MOD_ID)
public final class OvertoneNeoForgeShare {
    public OvertoneNeoForgeShare(IEventBus modBus) {
        // optional: без него ванильные клиенты не зашли бы на сервер с модом, а мод — на ванильный сервер
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").optional()
                .playBidirectional(SharePayload.TYPE, SharePayload.CODEC, (payload, ctx) -> {
                    if (ctx.flow() == PacketFlow.SERVERBOUND) {
                        if (ctx.player() instanceof ServerPlayer p) ServerShare.receive(p, payload.data());
                    } else {
                        ShareNet.received(payload.data());
                    }
                }));
        ServerShare.setSender((player, data) -> {
            if (player.connection.hasChannel(SharePayload.TYPE)) PacketDistributor.sendToPlayer(player, new SharePayload(data));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) ServerShare.quit(p);
        });
    }
}

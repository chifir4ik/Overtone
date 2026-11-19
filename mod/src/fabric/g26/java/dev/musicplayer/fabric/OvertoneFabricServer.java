package dev.musicplayer.fabric;

import dev.musicplayer.compat.SharePayload;
import dev.musicplayer.share.ServerShare;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Общая часть: работает и на выделенном сервере, и в одиночной игре (её сервер внутри клиента). */
public final class OvertoneFabricServer implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(SharePayload.TYPE, SharePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SharePayload.TYPE, SharePayload.CODEC);
        ServerShare.setSender((player, data) -> {
            if (ServerPlayNetworking.canSend(player, SharePayload.TYPE)) ServerPlayNetworking.send(player, new SharePayload(data));
        });
        ServerPlayNetworking.registerGlobalReceiver(SharePayload.TYPE, (payload, ctx) -> ServerShare.receive(ctx.player(), payload.data()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerShare.quit(handler.player));
    }
}

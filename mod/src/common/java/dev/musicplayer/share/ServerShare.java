package dev.musicplayer.share;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.BiConsumer;

/** «Наушник» на серверах Fabric/NeoForge и в одиночной игре, открытой по LAN: то же, что делает плагин для Paper. */
public final class ServerShare {
    private ServerShare() {}

    private static volatile BiConsumer<ServerPlayer, byte[]> sender = (p, d) -> { };
    private static MinecraftServer server;
    private static ShareRelay relay;

    public static void setSender(BiConsumer<ServerPlayer, byte[]> send) { sender = send; }

    public static void receive(ServerPlayer player, byte[] data) {
        ShareRelay r = relayFor(player.level().getServer());
        if (r != null) r.receive(peer(player), data);
    }

    public static void quit(ServerPlayer player) {
        MinecraftServer s = player.level().getServer();
        if (s == server && relay != null) relay.quit(player.getUUID());
    }

    /** У каждого запуска мира свой список: после выхода в меню и нового входа старые связи не тянутся. */
    private static ShareRelay relayFor(MinecraftServer s) {
        if (s == null) return null;
        if (s != server) {
            server = s;
            relay = new ShareRelay(ServerShare::lookup);
        }
        return relay;
    }

    private static ShareRelay.Peer lookup(UUID id) {
        MinecraftServer s = server;
        ServerPlayer p = s == null ? null : s.getPlayerList().getPlayer(id);
        return p == null ? null : peer(p);
    }

    private static ShareRelay.Peer peer(ServerPlayer p) {
        return new ShareRelay.Peer() {
            @Override public UUID id() { return p.getUUID(); }
            @Override public String name() { return p.getName().getString(); }
            @Override public Object world() { return p.level(); }
            @Override public double x() { return p.getX(); }
            @Override public double y() { return p.getY(); }
            @Override public double z() { return p.getZ(); }
            @Override public void send(byte[] data) { sender.accept(p, data); }
        };
    }
}

package dev.overtone.share;

import dev.musicplayer.share.ShareProto;
import dev.musicplayer.share.ShareRelay;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;

/**
 * Сервер только пересылает сообщения между игроками с Overtone и следит, чтобы никто не спамил.
 * Музыку он не трогает и не видит ничего, кроме того, что игроки и так показывают друг другу.
 */
public final class OvertoneSharePlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private ShareRelay relay;

    @Override
    public void onEnable() {
        relay = new ShareRelay(this::peer);
        getServer().getMessenger().registerIncomingPluginChannel(this, ShareProto.CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ShareProto.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (ShareProto.CHANNEL.equals(channel)) relay.receive(new BukkitPeer(player), message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        relay.quit(event.getPlayer().getUniqueId());
    }

    private ShareRelay.Peer peer(UUID id) {
        Player p = getServer().getPlayer(id);
        return p == null ? null : new BukkitPeer(p);
    }

    private final class BukkitPeer implements ShareRelay.Peer {
        private final Player player;
        private final Location at;

        BukkitPeer(Player player) {
            this.player = player;
            this.at = player.getLocation();
        }

        @Override public UUID id() { return player.getUniqueId(); }
        @Override public String name() { return player.getName(); }
        @Override public Object world() { return at.getWorld(); }
        @Override public double x() { return at.getX(); }
        @Override public double y() { return at.getY(); }
        @Override public double z() { return at.getZ(); }

        @Override
        public void send(byte[] data) {
            if (player.isOnline()) player.sendPluginMessage(OvertoneSharePlugin.this, ShareProto.CHANNEL, data);
        }
    }
}

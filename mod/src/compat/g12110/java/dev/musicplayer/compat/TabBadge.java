package dev.musicplayer.compat;

import dev.musicplayer.MusicPlayerMod;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Значок наушников в табе: свой шрифт из одного символа, цвет задаётся как у текста. */
public final class TabBadge {
    private TabBadge() {}

    private static final FontDescription FONT = new FontDescription.Resource(ResourceLocation.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "share"));

    public static UUID id(PlayerInfo info) { return info.getProfile().id(); }

    public static Component badge(int rgb) {
        return Component.literal("\uE900").withStyle(s -> s.withFont(FONT).withColor(rgb));
    }
}

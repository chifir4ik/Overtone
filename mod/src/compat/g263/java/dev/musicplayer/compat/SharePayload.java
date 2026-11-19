package dev.musicplayer.compat;

import dev.musicplayer.share.ShareProto;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сырые байты канала overtone:share: так же их шлёт и плагин для Paper. */
public record SharePayload(byte[] data) implements CustomPacketPayload {
    public static final Type<SharePayload> TYPE = new Type<>(Identifier.parse(ShareProto.CHANNEL));

    public static final StreamCodec<FriendlyByteBuf, SharePayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBytes(p.data),
            buf -> {
                int n = buf.readableBytes();
                if (n > ShareProto.MAX_PACKET) {
                    buf.skipBytes(n);
                    return new SharePayload(new byte[0]);
                }
                byte[] b = new byte[n];
                buf.readBytes(b);
                return new SharePayload(b);
            });

    @Override
    public Type<SharePayload> type() { return TYPE; }
}

package dev.musicplayer.local;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;

/** Теги и длительность FLAC, Ogg Vorbis и MP4. Читаются только заголовки, чтобы большая библиотека индексировалась быстро. */
final class ContainerTags {
    private static final int MAX_META = 24 << 20;

    private ContainerTags() {}

    record Info(long durationMs, TagReader.Tags tags) {
        static final Info NONE = new Info(0, TagReader.Tags.EMPTY);
    }

    static Info read(Path file, String ext) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return switch (ext) {
                case "flac" -> flac(ch);
                case "ogg", "oga" -> ogg(ch);
                case "m4a", "mp4", "m4b" -> mp4(ch);
                default -> Info.NONE;
            };
        } catch (Exception e) {
            return Info.NONE;
        }
    }

    private static Info flac(FileChannel ch) throws IOException {
        long p = 0;
        byte[] head = bytes(ch, 0, 10);
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            p = 10 + (((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F));
            if ((head[5] & 0x10) != 0) p += 10;
        }
        byte[] magic = bytes(ch, p, 4);
        if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') return Info.NONE;
        p += 4;
        long duration = 0;
        Comments c = new Comments();
        boolean last = false;
        while (!last) {
            byte[] h = bytes(ch, p, 4);
            last = (h[0] & 0x80) != 0;
            int type = h[0] & 0x7F;
            int len = ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            p += 4;
            if (type == 0 && len >= 18) {
                byte[] si = bytes(ch, p, 18);
                long v = 0;
                for (int i = 0; i < 8; i++) v = (v << 8) | (si[10 + i] & 0xFF);
                long rate = v >>> 44, total = v & 0xFFFFFFFFFL;
                if (rate > 0) duration = total * 1000 / rate;
            } else if (type == 4 && len <= MAX_META) {
                c.parse(bytes(ch, p, len), 0);
            } else if (type == 6 && len <= MAX_META && c.cover == null) {
                c.cover = picture(bytes(ch, p, len));
            }
            p += len;
        }
        return new Info(duration, c.tags());
    }

    /** Формат общий для FLAC и METADATA_BLOCK_PICTURE в Ogg. */
    private static byte[] picture(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.getInt();
        skip(bb);
        skip(bb);
        bb.position(bb.position() + 16);
        int n = bb.getInt();
        if (n <= 0 || n > bb.remaining()) return null;
        byte[] img = new byte[n];
        bb.get(img);
        return img;
    }

    private static void skip(ByteBuffer bb) {
        int len = bb.getInt();
        bb.position(bb.position() + len);
    }

    private static Info ogg(FileChannel ch) throws IOException {
        OggPackets r = new OggPackets(ch);
        byte[] ident = r.next();
        if (ident == null || ident.length < 16 || ident[0] != 1 || !"vorbis".equals(new String(ident, 1, 6, StandardCharsets.US_ASCII)))
            return Info.NONE;
        long rate = (ident[12] & 0xFFL) | (ident[13] & 0xFFL) << 8 | (ident[14] & 0xFFL) << 16 | (ident[15] & 0xFFL) << 24;
        Comments c = new Comments();
        byte[] comment = r.next();
        if (comment != null && comment.length > 7 && comment[0] == 3) c.parse(comment, 7);
        long granule = lastGranule(ch);
        return new Info(rate > 0 && granule > 0 ? granule * 1000 / rate : 0, c.tags());
    }

    /** granule последней страницы = число сэмплов */
    private static long lastGranule(FileChannel ch) throws IOException {
        long size = ch.size();
        int tail = (int) Math.min(size, 128 * 1024);
        byte[] b = bytes(ch, size - tail, tail);
        for (int i = b.length - 14; i >= 0; i--) {
            if (b[i] != 'O' || b[i + 1] != 'g' || b[i + 2] != 'g' || b[i + 3] != 'S') continue;
            long g = 0;
            for (int k = 7; k >= 0; k--) g = (g << 8) | (b[i + 6 + k] & 0xFF);
            if (g > 0) return g;
        }
        return 0;
    }

    private static final class OggPackets {
        private final FileChannel ch;
        private long pos;
        private final ByteArrayOutputStream cur = new ByteArrayOutputStream();
        private byte[] segs = new byte[0];
        private int segAt;
        private long dataAt;

        OggPackets(FileChannel ch) { this.ch = ch; }

        byte[] next() throws IOException {
            cur.reset();
            while (true) {
                if (segAt >= segs.length && !page()) return null;
                while (segAt < segs.length) {
                    int len = segs[segAt++] & 0xFF;
                    if (cur.size() + len > MAX_META) return null;
                    cur.write(bytes(ch, dataAt, len));
                    dataAt += len;
                    if (len < 255) return cur.toByteArray();
                }
            }
        }

        private boolean page() throws IOException {
            if (pos + 27 > ch.size()) return false;
            byte[] h = bytes(ch, pos, 27);
            if (h[0] != 'O' || h[1] != 'g' || h[2] != 'g' || h[3] != 'S') return false;
            int n = h[26] & 0xFF;
            segs = bytes(ch, pos + 27, n);
            segAt = 0;
            dataAt = pos + 27 + n;
            long body = 0;
            for (byte s : segs) body += s & 0xFF;
            pos = dataAt + body;
            return true;
        }
    }

    private static Info mp4(FileChannel ch) throws IOException {
        Comments c = new Comments();
        long[] dur = {0};
        atoms(ch, 0, ch.size(), c, dur, 0);
        return new Info(dur[0], c.tags());
    }

    private static void atoms(FileChannel ch, long from, long to, Comments c, long[] dur, int depth) throws IOException {
        long p = from;
        while (p + 8 <= to && depth < 8) {
            byte[] h = bytes(ch, p, 8);
            long size = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (size == 1) {
                byte[] big = bytes(ch, p + 8, 8);
                size = ByteBuffer.wrap(big).getLong();
                hdr = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < hdr || p + size > to) return;
            long body = p + hdr, end = p + size;
            switch (type) {
                case "moov", "udta", "ilst" -> atoms(ch, body, end, c, dur, depth + 1);
                case "meta" -> atoms(ch, body + 4, end, c, dur, depth + 1);   // version + flags
                case "mvhd" -> {
                    byte[] m = bytes(ch, body, (int) Math.min(32, end - body));
                    ByteBuffer bb = ByteBuffer.wrap(m);
                    int version = m[0];
                    long scale, length;
                    if (version == 1) { bb.position(20); scale = bb.getInt() & 0xFFFFFFFFL; length = bb.getLong(); }
                    else { bb.position(12); scale = bb.getInt() & 0xFFFFFFFFL; length = bb.getInt() & 0xFFFFFFFFL; }
                    if (scale > 0) dur[0] = length * 1000 / scale;
                }
                case "©nam", "©ART", "aART", "©alb", "©lyr", "covr" -> {
                    if (end - body > MAX_META) break;
                    byte[] v = itemData(bytes(ch, body, (int) (end - body)));
                    if (v == null) break;
                    String s = new String(v, StandardCharsets.UTF_8);
                    switch (type) {
                        case "©nam" -> c.title = s;
                        case "©ART" -> c.artist = s;
                        case "aART" -> { if (c.artist.isEmpty()) c.artist = s; }
                        case "©alb" -> c.album = s;
                        case "©lyr" -> c.lyrics = s;
                        default -> c.cover = v;
                    }
                }
                default -> { }
            }
            p = end;
        }
    }

    private static byte[] itemData(byte[] b) {
        if (b.length < 16) return null;
        int size = ByteBuffer.wrap(b, 0, 4).getInt();
        if (!"data".equals(new String(b, 4, 4, StandardCharsets.ISO_8859_1)) || size < 16 || size > b.length) return null;
        byte[] v = new byte[size - 16];
        System.arraycopy(b, 16, v, 0, v.length);
        return v;
    }

    private static final class Comments {
        String title = "", artist = "", album = "", lyrics = "";
        byte[] cover;

        void parse(byte[] b, int at) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                bb.position(at);
                skip(bb);
                int n = bb.getInt();
                for (int i = 0; i < n && bb.remaining() >= 4; i++) {
                    int len = bb.getInt();
                    if (len < 0 || len > bb.remaining()) return;
                    String kv = new String(b, bb.position(), len, StandardCharsets.UTF_8);
                    bb.position(bb.position() + len);
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String k = kv.substring(0, eq).toUpperCase(Locale.ROOT), v = kv.substring(eq + 1);
                    switch (k) {
                        case "TITLE" -> { if (title.isEmpty()) title = v; }
                        case "ARTIST" -> artist = artist.isEmpty() ? v : artist + ", " + v;
                        case "ALBUMARTIST" -> { if (artist.isEmpty()) artist = v; }
                        case "ALBUM" -> { if (album.isEmpty()) album = v; }
                        case "LYRICS", "UNSYNCEDLYRICS" -> { if (lyrics.isEmpty()) lyrics = v; }
                        case "METADATA_BLOCK_PICTURE" -> {
                            if (cover == null) {
                                try { cover = picture(Base64.getMimeDecoder().decode(v)); } catch (RuntimeException ignored) { }
                            }
                        }
                        default -> { }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        TagReader.Tags tags() { return new TagReader.Tags(title.trim(), artist.trim(), album.trim(), cover, lyrics); }
    }

    private static byte[] bytes(FileChannel ch, long at, int n) throws IOException {
        if (n < 0 || at < 0) throw new IOException("bad offset");
        ByteBuffer bb = ByteBuffer.allocate(n);
        while (bb.hasRemaining()) {
            int r = ch.read(bb, at + bb.position());
            if (r < 0) throw new EOFException();
        }
        return bb.array();
    }
}

package dev.musicplayer.local;

import dev.musicplayer.jlayer.Bitstream;
import dev.musicplayer.jlayer.BitstreamException;
import dev.musicplayer.jlayer.Header;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Теги ID3v2 / ID3v1 и длительность аудиофайла. Всё, чего не удалось прочитать, остаётся пустым. */
final class TagReader {
    private TagReader() {}

    record Tags(String title, String artist, String album, byte[] cover, String lyrics) {
        static final Tags EMPTY = new Tags("", "", "", null, "");
    }

    private static final Charset CP1251 = Charset.forName("windows-1251");
    private static final int MAX_TAG_BYTES = 24 << 20;

    static Tags read(Path file) {
        try {
            Tags v2 = readV2(file);
            if (v2 != null) return v2;
            return readV1(file);
        } catch (Exception e) {
            return Tags.EMPTY;
        }
    }

    static long durationMs(Path file, boolean mp3) {
        try {
            return mp3 ? mp3Duration(file) : pcmDuration(file);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long mp3Duration(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Bitstream bs = new Bitstream(in);
            double ms = 0;
            int bad = 0;
            while (bad < 50) {
                try {
                    Header h = bs.readFrame();
                    if (h == null) break;
                    ms += h.msPerFrame();
                    bs.closeFrame();
                } catch (BitstreamException e) {
                    bad++;
                    try { bs.closeFrame(); } catch (Throwable ignored) {}
                }
            }
            try { bs.close(); } catch (BitstreamException ignored) {}
            return (long) ms;
        }
    }

    private static long pcmDuration(Path file) throws Exception {
        AudioFileFormat f = AudioSystem.getAudioFileFormat(file.toFile());
        long frames = f.getFrameLength();
        float rate = f.getFormat().getFrameRate();
        return frames <= 0 || rate <= 0 ? 0 : (long) (frames * 1000.0 / rate);
    }

    private static Tags readV2(Path file) throws IOException {
        byte[] tag;
        int version, flags;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(10);
            if (head.length < 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') return null;
            version = head[3] & 0xFF;
            flags = head[5] & 0xFF;
            int size = synchsafe(head, 6);
            if (size <= 0 || size > MAX_TAG_BYTES || version < 2 || version > 4) return null;
            tag = in.readNBytes(size);
        }
        if ((flags & 0x80) != 0 && version < 4) tag = undoUnsync(tag);

        int pos = 0;
        if ((flags & 0x40) != 0 && version >= 3 && tag.length >= 4)
            pos = version == 4 ? synchsafe(tag, 0) : int32(tag, 0) + 4;

        int idLen = version == 2 ? 3 : 4, headLen = version == 2 ? 6 : 10;
        String title = "", artist = "", album = "", lyrics = "";
        byte[] cover = null;
        while (pos + headLen <= tag.length) {
            String id = new String(tag, pos, idLen, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break;
            int size = version == 2 ? int24(tag, pos + 3) : version == 4 ? synchsafe(tag, pos + 4) : int32(tag, pos + 4);
            int formatFlags = version == 2 ? 0 : ((tag[pos + 8] & 0xFF) << 8) | (tag[pos + 9] & 0xFF);
            int start = pos + headLen, end = start + size;
            if (size < 0 || end > tag.length) break;
            pos = end;

            boolean unreadable = version == 3 ? (formatFlags & 0x00C0) != 0 : version == 4 && (formatFlags & 0x000C) != 0;
            if (unreadable) continue;
            byte[] body = java.util.Arrays.copyOfRange(tag, start, end);
            if (version == 4 && (formatFlags & 0x0001) != 0 && body.length > 4) body = java.util.Arrays.copyOfRange(body, 4, body.length);
            if (version == 4 && (formatFlags & 0x0002) != 0) body = undoUnsync(body);

            switch (id) {
                case "TIT2", "TT2" -> title = text(body);
                case "TPE1", "TP1" -> artist = text(body);
                case "TALB", "TAL" -> album = text(body);
                case "USLT", "ULT" -> { if (lyrics.isEmpty()) lyrics = lyricsText(body); }
                case "APIC", "PIC" -> { if (cover == null) cover = picture(body, id.equals("PIC")); }
                default -> { }
            }
        }
        return new Tags(title, artist, album, cover, lyrics);
    }

    private static Tags readV1(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 128) return Tags.EMPTY;
        byte[] t = new byte[128];
        try (var ch = Files.newByteChannel(file)) {
            ch.position(size - 128);
            ByteBuffer bb = ByteBuffer.wrap(t);
            while (bb.hasRemaining() && ch.read(bb) > 0) { /* дочитываем до конца блока */ }
        }
        if (t[0] != 'T' || t[1] != 'A' || t[2] != 'G') return Tags.EMPTY;
        return new Tags(v1Field(t, 3, 30), v1Field(t, 33, 30), v1Field(t, 63, 30), null, "");
    }

    private static String v1Field(byte[] b, int from, int len) {
        int end = from;
        while (end < from + len && b[end] != 0) end++;
        return decode(java.util.Arrays.copyOfRange(b, from, end), 0).trim();
    }

    private static String text(byte[] body) {
        if (body.length < 2) return "";
        String s = decode(java.util.Arrays.copyOfRange(body, 1, body.length), body[0] & 0xFF);
        int nul = s.indexOf('\0');
        return (nul >= 0 ? s.substring(0, nul) : s).trim();
    }

    /** enc, язык (3 байта), описание с терминатором, затем сам текст. */
    private static String lyricsText(byte[] body) {
        if (body.length < 5) return "";
        int enc = body[0] & 0xFF;
        int at = skipTerminated(body, 4, enc);
        return at >= body.length ? "" : decode(java.util.Arrays.copyOfRange(body, at, body.length), enc).trim();
    }

    private static byte[] picture(byte[] body, boolean v22) {
        if (body.length < 8) return null;
        int enc = body[0] & 0xFF;
        int at = 1;
        if (v22) at += 3;
        else {
            while (at < body.length && body[at] != 0) at++;
            at++;
        }
        at++;                                    // тип картинки
        at = skipTerminated(body, at, enc);
        if (at >= body.length) return null;
        return java.util.Arrays.copyOfRange(body, at, body.length);
    }

    private static int skipTerminated(byte[] b, int from, int enc) {
        boolean wide = enc == 1 || enc == 2;
        int i = from;
        while (i < b.length) {
            if (!wide && b[i] == 0) return i + 1;
            if (wide && i + 1 < b.length && b[i] == 0 && b[i + 1] == 0) return i + 2;
            i += wide ? 2 : 1;
        }
        return b.length;
    }

    private static String decode(byte[] b, int enc) {
        return switch (enc) {
            case 1 -> new String(b, StandardCharsets.UTF_16);
            case 2 -> new String(b, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, StandardCharsets.UTF_8);
            default -> new String(b, looksLikeCp1251(b) ? CP1251 : StandardCharsets.ISO_8859_1);
        };
    }

    /**
     * Старые русские теги часто лежат в windows-1251, хотя объявлены как Latin-1. Латинские буквы с диакритикой
     * стоят в словах по одной, кириллица идёт подряд — по серии из трёх байтов выше 0x7F их и различаем.
     */
    private static boolean looksLikeCp1251(byte[] b) {
        int run = 0;
        for (byte x : b) {
            if (x < 0) { if (++run >= 3) return true; } else run = 0;
        }
        return false;
    }

    /** Текст из файла .lrc/.txt: UTF-8, а если не получилось — windows-1251. */
    static String decodeText(byte[] raw) {
        int skip = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, skip, raw.length - skip)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, skip, raw.length - skip, CP1251);
        }
    }

    private static int synchsafe(byte[] b, int at) {
        return ((b[at] & 0x7F) << 21) | ((b[at + 1] & 0x7F) << 14) | ((b[at + 2] & 0x7F) << 7) | (b[at + 3] & 0x7F);
    }

    private static int int32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static int int24(byte[] b, int at) {
        return ((b[at] & 0xFF) << 16) | ((b[at + 1] & 0xFF) << 8) | (b[at + 2] & 0xFF);
    }

    private static byte[] undoUnsync(byte[] b) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) {
            out.write(b[i]);
            if ((b[i] & 0xFF) == 0xFF && i + 1 < b.length && b[i + 1] == 0) i++;
        }
        return out.toByteArray();
    }
}

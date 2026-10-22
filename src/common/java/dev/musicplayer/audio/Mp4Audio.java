package dev.musicplayer.audio;

import dev.musicplayer.util.T;
import java.io.IOException;
import java.util.Arrays;

/**
 * Достаёт из MP4 аудиодорожку AAC: настройки декодера и таблицу кадров. Понимает и обычный MP4 (таблицы stbl),
 * и фрагментированный (moof/trun) — именно такой отдаёт HLS SoundCloud.
 */
public final class Mp4Audio {
    private final byte[] data;
    private byte[] config;
    private long timescale = 1000;
    private int trackId = -1;
    private long defaultDuration, defaultSize;

    private long[] offsets = new long[2048];
    private int[] sizes = new int[2048];
    private long[] starts = new long[2048];
    private long clock;
    private int count;

    private Mp4Audio(byte[] data) { this.data = data; }

    public static Mp4Audio parse(byte[] data) throws IOException {
        Mp4Audio m = new Mp4Audio(data);
        m.walkTopLevel();
        if (m.config == null) throw new IOException(T.get("err.no_aac"));
        if (m.count == 0) throw new IOException(T.get("err.no_frames"));
        return m;
    }

    /** MP4 узнаётся по первому блоку; mp3 начинается с тега ID3 или синхрослова кадра. */
    public static boolean looksLikeMp4(byte[] d) {
        if (d.length < 12) return false;
        String t = new String(d, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        return t.equals("ftyp") || t.equals("styp") || t.equals("moov") || t.equals("moof");
    }

    public byte[] decoderConfig() { return config; }
    public int count() { return count; }
    public byte[] frame(int i) { return Arrays.copyOfRange(data, (int) offsets[i], (int) offsets[i] + sizes[i]); }

    public long durationMs() { return clock * 1000 / timescale; }

    public int indexAt(long ms) {
        long target = ms * timescale / 1000;
        int lo = 0, hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= target) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private void walkTopLevel() {
        int pos = 0;
        while (pos + 8 <= data.length) {
            long size = u32(pos);
            int header = 8;
            if (size == 1 && pos + 16 <= data.length) { size = u64(pos + 8); header = 16; }
            else if (size == 0) size = data.length - pos;
            if (size < header) return;
            long end = Math.min(pos + size, data.length);
            String type = type(pos + 4);
            if (type.equals("moov")) readMoov(pos + header, (int) end);
            else if (type.equals("moof") && end == pos + size) readMoof(pos, pos + header, (int) end);
            pos = (int) end;
        }
    }

    private void readMoov(int start, int end) {
        for (int p = start; p + 8 <= end; ) {
            int size = (int) u32(p);
            if (size < 8 || p + size > end) return;
            String type = type(p + 4);
            if (type.equals("trak") && config == null) readTrak(p + 8, p + size);
            else if (type.equals("mvex")) readMvex(p + 8, p + size);
            p += size;
        }
    }

    private void readMvex(int start, int end) {
        for (int p = start; p + 8 <= end; ) {
            int size = (int) u32(p);
            if (size < 8 || p + size > end) return;
            if (type(p + 4).equals("trex") && u32(p + 12) == trackId) {
                defaultDuration = u32(p + 20);
                defaultSize = u32(p + 24);
            }
            p += size;
        }
    }

    private void readTrak(int start, int end) {
        int[] tkhd = child(start, end, "tkhd");
        int[] mdia = child(start, end, "mdia");
        if (tkhd == null || mdia == null) return;
        int[] hdlr = child(mdia[0], mdia[1], "hdlr");
        if (hdlr == null || !type(hdlr[0] + 8).equals("soun")) return;
        int[] mdhd = child(mdia[0], mdia[1], "mdhd");
        int[] minf = child(mdia[0], mdia[1], "minf");
        int[] stbl = minf == null ? null : child(minf[0], minf[1], "stbl");
        int[] stsd = stbl == null ? null : child(stbl[0], stbl[1], "stsd");
        if (mdhd == null || stsd == null) return;

        byte[] cfg = readEntryConfig(stsd[0] + 8, stsd[1]);
        if (cfg == null) return;
        trackId = (int) u32(tkhd[0] + (data[tkhd[0]] == 1 ? 20 : 12));
        timescale = Math.max(1, u32(mdhd[0] + (data[mdhd[0]] == 1 ? 20 : 12)));
        config = cfg;
        readFlatSamples(stbl[0], stbl[1]);
    }

    /** В записи mp4a настройки декодера лежат в esds: ES_Descriptor → DecoderConfig → DecoderSpecificInfo. */
    private byte[] readEntryConfig(int start, int end) {
        if (start + 36 > end || !type(start + 4).equals("mp4a")) return null;
        int version = (data[start + 8 + 8] & 0xFF) << 8 | (data[start + 8 + 9] & 0xFF);
        int children = start + 8 + 28 + (version == 1 ? 16 : version == 2 ? 36 : 0);
        int[] esds = child(children, start + (int) u32(start), "esds");
        return esds == null ? null : readDescriptors(esds[0] + 4, esds[1]);
    }

    private byte[] readDescriptors(int pos, int end) {
        while (pos + 2 <= end) {
            int tag = data[pos++] & 0xFF;
            int len = 0;
            for (int i = 0; i < 4 && pos < end; i++) {
                int b = data[pos++] & 0xFF;
                len = len << 7 | (b & 0x7F);
                if ((b & 0x80) == 0) break;
            }
            int bodyEnd = Math.min(end, pos + len);
            if (tag == 0x05) return Arrays.copyOfRange(data, pos, bodyEnd);
            if (tag == 0x03) {
                int flags = data[pos + 2] & 0xFF;
                int inner = pos + 3 + ((flags & 0x80) != 0 ? 2 : 0);
                if ((flags & 0x40) != 0) inner += 1 + (data[inner] & 0xFF);
                if ((flags & 0x20) != 0) inner += 2;
                byte[] r = readDescriptors(inner, bodyEnd);
                if (r != null) return r;
            } else if (tag == 0x04) {
                byte[] r = readDescriptors(pos + 13, bodyEnd);
                if (r != null) return r;
            }
            pos = bodyEnd;
        }
        return null;
    }

    private void readFlatSamples(int start, int end) {
        int[] stsz = child(start, end, "stsz"), stsc = child(start, end, "stsc"), stts = child(start, end, "stts");
        int[] stco = child(start, end, "stco"), co64 = child(start, end, "co64");
        if (stsz == null || stsc == null || stts == null || (stco == null && co64 == null)) return;

        int n = (int) u32(stsz[0] + 8);
        if (n <= 0) return;
        long fixed = u32(stsz[0] + 4);
        int chunks = (int) u32((stco != null ? stco : co64)[0] + 4);
        int runs = (int) u32(stsc[0] + 4);

        long[] durations = new long[n];
        int di = 0;
        for (int e = 0, p = stts[0] + 8, entries = (int) u32(stts[0] + 4); e < entries && di < n; e++, p += 8) {
            long cnt = u32(p), delta = u32(p + 4);
            for (long k = 0; k < cnt && di < n; k++) durations[di++] = delta;
        }

        int sample = 0;
        for (int run = 0; run < runs && sample < n; run++) {
            int rp = stsc[0] + 8 + run * 12;
            int firstChunk = (int) u32(rp), perChunk = (int) u32(rp + 4);
            int lastChunk = run + 1 < runs ? (int) u32(rp + 12) - 1 : chunks;
            for (int c = firstChunk; c <= lastChunk && sample < n; c++) {
                long off = stco != null ? u32(stco[0] + 8 + (c - 1) * 4) : u64(co64[0] + 8 + (c - 1) * 8);
                for (int k = 0; k < perChunk && sample < n; k++, sample++) {
                    int size = fixed != 0 ? (int) fixed : (int) u32(stsz[0] + 12 + sample * 4);
                    add(off, size, durations[sample]);
                    off += size;
                }
            }
        }
    }

    private void readMoof(int moofStart, int start, int end) {
        for (int p = start; p + 8 <= end; ) {
            int size = (int) u32(p);
            if (size < 8 || p + size > end) return;
            if (type(p + 4).equals("traf")) readTraf(moofStart, p + 8, p + size);
            p += size;
        }
    }

    private void readTraf(int moofStart, int start, int end) {
        int[] tfhd = child(start, end, "tfhd");
        if (tfhd == null) return;
        int flags = (int) (u32(tfhd[0]) & 0xFFFFFF);
        if (u32(tfhd[0] + 4) != trackId) return;
        int at = tfhd[0] + 8;
        long base = moofStart;
        long duration = defaultDuration, size = defaultSize;
        if ((flags & 0x1) != 0) { base = u64(at); at += 8; }
        if ((flags & 0x2) != 0) at += 4;
        if ((flags & 0x8) != 0) { duration = u32(at); at += 4; }
        if ((flags & 0x10) != 0) size = u32(at);

        long cursor = base;
        for (int p = start; p + 8 <= end; ) {
            int boxSize = (int) u32(p);
            if (boxSize < 8 || p + boxSize > end) return;
            if (type(p + 4).equals("trun")) cursor = readTrun(p + 8, base, cursor, duration, size);
            p += boxSize;
        }
    }

    private long readTrun(int at, long base, long cursor, long defDuration, long defSize) {
        int flags = (int) (u32(at) & 0xFFFFFF);
        int samples = (int) u32(at + 4);
        at += 8;
        if ((flags & 0x1) != 0) { cursor = base + (int) u32(at); at += 4; }
        if ((flags & 0x4) != 0) at += 4;
        for (int i = 0; i < samples; i++) {
            long duration = defDuration, size = defSize;
            if ((flags & 0x100) != 0) { duration = u32(at); at += 4; }
            if ((flags & 0x200) != 0) { size = u32(at); at += 4; }
            if ((flags & 0x400) != 0) at += 4;
            if ((flags & 0x800) != 0) at += 4;
            add(cursor, (int) size, duration);
            cursor += size;
        }
        return cursor;
    }

    private void add(long offset, int size, long duration) {
        if (size <= 0 || offset < 0 || offset + size > data.length) return;
        if (count == offsets.length) {
            int cap = count * 2;
            offsets = Arrays.copyOf(offsets, cap);
            sizes = Arrays.copyOf(sizes, cap);
            starts = Arrays.copyOf(starts, cap);
        }
        offsets[count] = offset;
        sizes[count] = size;
        starts[count] = clock;
        clock += duration;
        count++;
    }

    /** Ищет вложенный блок; возвращает {начало содержимого, конец блока}. */
    private int[] child(int start, int end, String type) {
        for (int p = start; p + 8 <= end; ) {
            long size = u32(p);
            if (size < 8 || p + size > end) return null;
            if (type(p + 4).equals(type)) return new int[]{p + 8, (int) (p + size)};
            p += (int) size;
        }
        return null;
    }

    private long u32(int p) {
        return p + 4 > data.length ? 0 : ((data[p] & 0xFFL) << 24) | ((data[p + 1] & 0xFF) << 16) | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
    }

    private long u64(int p) { return (u32(p) << 32) | u32(p + 4); }

    private String type(int p) {
        return p + 4 > data.length ? "" : new String(data, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}

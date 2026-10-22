package dev.musicplayer.audio;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** FLAC → PCM 16 бит. У многоканальных файлов берутся первые два канала. */
public final class FlacDecoder {
    public final int sampleRate, channels, bitsPerSample, maxBlock;
    public final long totalSamples;
    public final int outChannels;

    private final byte[] d;
    private final int audioStart;
    private final int fixedBlock;   // при фиксированном блоке в заголовке номер кадра, а не сэмпла
    private final long[] seekSamples, seekOffsets;
    private final int[][] ch;
    private int bp;                 // в битах
    private int blockSize, blockFilled, blockRead;
    private long nextSample;

    public FlacDecoder(byte[] data) throws IOException {
        this.d = data;
        int p = skipId3(data);
        if (p + 4 > d.length || d[p] != 'f' || d[p + 1] != 'L' || d[p + 2] != 'a' || d[p + 3] != 'C')
            throw new IOException("not a FLAC file");
        p += 4;
        int rate = 0, chans = 0, bps = 0, minB = 0, maxB = 0;
        long total = 0;
        List<long[]> seek = new ArrayList<>();
        boolean last = false;
        while (!last) {
            if (p + 4 > d.length) throw new EOFException("truncated FLAC metadata");
            int h = d[p] & 0xFF;
            last = (h & 0x80) != 0;
            int type = h & 0x7F;
            int len = u24(p + 1);
            int body = p + 4;
            if (body + len > d.length) throw new EOFException("truncated FLAC metadata");
            if (type == 0) {
                minB = u16(body);
                maxB = u16(body + 2);
                long v = 0;
                for (int i = 0; i < 8; i++) v = (v << 8) | (d[body + 10 + i] & 0xFF);
                rate = (int) (v >>> 44);
                chans = (int) ((v >>> 41) & 7) + 1;
                bps = (int) ((v >>> 36) & 31) + 1;
                total = v & 0xFFFFFFFFFL;
            } else if (type == 3) {
                for (int i = 0; i + 18 <= len; i += 18) {
                    long s = u64(body + i);
                    if (s == -1L) continue;   // placeholder
                    seek.add(new long[]{s, u64(body + i + 8)});
                }
            }
            p = body + len;
        }
        if (rate <= 0 || chans <= 0) throw new IOException("FLAC without STREAMINFO");
        sampleRate = rate; channels = chans; bitsPerSample = bps; maxBlock = Math.max(16, maxB);
        totalSamples = total;
        fixedBlock = minB == maxB ? minB : 0;
        outChannels = Math.min(2, chans);
        audioStart = p;
        seekSamples = new long[seek.size()];
        seekOffsets = new long[seek.size()];
        for (int i = 0; i < seek.size(); i++) { seekSamples[i] = seek.get(i)[0]; seekOffsets[i] = seek.get(i)[1]; }
        ch = new int[chans][Math.max(maxBlock, 65536)];
        bp = audioStart * 8;
    }

    public long durationMs() { return totalSamples <= 0 ? 0 : totalSamples * 1000 / sampleRate; }

    public void seekToSample(long target) throws IOException {
        if (target <= 0) { bp = audioStart * 8; blockFilled = blockRead = 0; nextSample = 0; return; }
        int from = audioStart;
        long fromSample = 0;
        for (int i = 0; i < seekSamples.length; i++) {
            if (seekSamples[i] <= target && seekSamples[i] >= fromSample && audioStart + seekOffsets[i] < d.length) {
                from = (int) (audioStart + seekOffsets[i]);
                fromSample = seekSamples[i];
            }
        }
        if (seekSamples.length == 0 && totalSamples > 0) {
            // без SEEKTABLE: прыгаем пропорционально и отступаем, пока не найдём кадр до цели
            double k = Math.min(1.0, (double) target / totalSamples);
            long est = audioStart + (long) ((d.length - audioStart) * k) - 4096;
            for (int tries = 0; tries < 24 && est > audioStart; tries++) {
                long[] f = findFrame((int) est);
                if (f != null && f[1] <= target) { from = (int) f[0]; fromSample = f[1]; break; }
                est -= 32768L << Math.min(tries, 6);
            }
        }
        bp = from * 8;
        blockFilled = blockRead = 0;
        nextSample = fromSample;
        long skip = target - fromSample;
        while (skip > 0) {
            if (!decodeFrame()) return;
            int n = (int) Math.min(skip, blockFilled);
            blockRead = n;
            skip -= n;
            if (blockRead < blockFilled) break;
        }
    }

    /** {смещение, первый сэмпл} */
    private long[] findFrame(int from) {
        for (int i = Math.max(audioStart, from); i + 16 < d.length; i++) {
            if ((d[i] & 0xFF) != 0xFF || ((d[i + 1] & 0xFF) >> 1) != 0x7C) continue;
            int save = bp;
            bp = i * 8;
            try {
                long s = readHeaderSample();
                if (s >= 0) return new long[]{i, s};
            } catch (IOException ignored) {
            } finally {
                bp = save;
            }
        }
        return null;
    }

    public int read(byte[] out) throws IOException {
        int o = 0, frame = outChannels * 2;
        while (o + frame <= out.length) {
            if (blockRead >= blockFilled) {
                if (!decodeFrame()) break;
                continue;
            }
            int shift = bitsPerSampleOfBlock - 16;
            for (int c = 0; c < outChannels; c++) {
                int v = ch[c][blockRead];
                v = shift > 0 ? v >> shift : v << -shift;
                out[o++] = (byte) v;
                out[o++] = (byte) (v >> 8);
            }
            blockRead++;
        }
        return o == 0 ? -1 : o;
    }

    private int bitsPerSampleOfBlock = 16;

    /** Битый кадр пропускается до следующего синхрослова. */
    private boolean decodeFrame() throws IOException {
        while (true) {
            int start = (bp + 7) >> 3;
            if (start + 4 >= d.length) return false;
            bp = start * 8;
            try {
                long first = readHeaderSample();
                if (first < 0) throw new IOException("bad frame header");
                decodeSubframes();
                bp = ((bp + 7) >> 3) * 8 + 16;   // CRC-16 не проверяем
                blockFilled = blockSize;
                blockRead = 0;
                nextSample = first + blockSize;
                return true;
            } catch (IOException | ArrayIndexOutOfBoundsException e) {
                long[] f = findFrame(start + 1);
                if (f == null) return false;
                bp = (int) f[0] * 8;
            }
        }
    }

    private int hdrChannels, hdrAssign, hdrBps;

    private long readHeaderSample() throws IOException {
        int startByte = bp >> 3;
        if (bits(15) != 0x7FFC) return -1;
        boolean variable = bits(1) == 1;
        int bsCode = bits(4), srCode = bits(4), chCode = bits(4), ssCode = bits(3);
        if (bits(1) != 0 || bsCode == 0 || srCode == 15 || chCode > 10 || ssCode == 3) return -1;
        long num = utf8();
        if (num < 0) return -1;
        int bs;
        if (bsCode == 1) bs = 192;
        else if (bsCode <= 5) bs = 576 << (bsCode - 2);
        else if (bsCode == 6) bs = bits(8) + 1;
        else if (bsCode == 7) bs = bits(16) + 1;
        else bs = 256 << (bsCode - 8);
        if (srCode == 12) bits(8);
        else if (srCode == 13 || srCode == 14) bits(16);
        int crcAt = bp >> 3;
        int crc = bits(8);
        if (crc8(startByte, crcAt) != crc) return -1;
        if (bs > ch[0].length) return -1;
        int bps = switch (ssCode) {
            case 0 -> bitsPerSample;
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            default -> 32;
        };
        int chans = chCode < 8 ? chCode + 1 : 2;
        if (chans != channels) return -1;
        blockSize = bs;
        hdrChannels = chans;
        hdrAssign = chCode;
        hdrBps = bps;
        return variable ? num : num * (fixedBlock > 0 ? fixedBlock : bs);
    }

    private void decodeSubframes() throws IOException {
        for (int c = 0; c < hdrChannels; c++) {
            int bps = hdrBps;
            if ((hdrAssign == 8 && c == 1) || (hdrAssign == 9 && c == 0) || (hdrAssign == 10 && c == 1)) bps++;   // side-канал на бит шире
            subframe(ch[c], bps);
        }
        int[] a = ch[0], b = hdrChannels > 1 ? ch[1] : null;
        int n = blockSize;
        switch (hdrAssign) {
            case 8 -> { for (int i = 0; i < n; i++) b[i] = a[i] - b[i]; }
            case 9 -> { for (int i = 0; i < n; i++) a[i] += b[i]; }
            case 10 -> {
                for (int i = 0; i < n; i++) {
                    int side = b[i];
                    int mid = (a[i] << 1) | (side & 1);
                    a[i] = (mid + side) >> 1;
                    b[i] = (mid - side) >> 1;
                }
            }
            default -> { }
        }
        bitsPerSampleOfBlock = hdrBps;
    }

    private void subframe(int[] out, int bps) throws IOException {
        if (bits(1) != 0) throw new IOException("bad subframe");
        int type = bits(6);
        int wasted = 0;
        if (bits(1) == 1) { wasted = 1; while (bits(1) == 0) wasted++; }
        int eb = bps - wasted;
        int n = blockSize;
        if (type == 0) {
            int v = sbits(eb);
            for (int i = 0; i < n; i++) out[i] = v;
        } else if (type == 1) {
            for (int i = 0; i < n; i++) out[i] = sbits(eb);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            for (int i = 0; i < order; i++) out[i] = sbits(eb);
            residual(out, order);
            fixed(out, order);
        } else if (type >= 32) {
            int order = type - 31;
            for (int i = 0; i < order; i++) out[i] = sbits(eb);
            int precision = bits(4) + 1;
            if (precision == 16) throw new IOException("bad LPC precision");
            int shift = sbits(5);
            int[] coef = new int[order];
            for (int i = 0; i < order; i++) coef[i] = sbits(precision);
            residual(out, order);
            lpc(out, order, coef, Math.max(0, shift));
        } else {
            throw new IOException("reserved subframe type");
        }
        if (wasted > 0) for (int i = 0; i < n; i++) out[i] <<= wasted;
    }

    private void fixed(int[] s, int order) {
        int n = blockSize;
        switch (order) {
            case 1 -> { for (int i = 1; i < n; i++) s[i] += s[i - 1]; }
            case 2 -> { for (int i = 2; i < n; i++) s[i] += 2 * s[i - 1] - s[i - 2]; }
            case 3 -> { for (int i = 3; i < n; i++) s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]; }
            case 4 -> { for (int i = 4; i < n; i++) s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]; }
            default -> { }
        }
    }

    private void lpc(int[] s, int order, int[] coef, int shift) {
        int n = blockSize;
        for (int i = order; i < n; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) sum += (long) coef[j] * s[i - 1 - j];
            s[i] += (int) (sum >> shift);
        }
    }

    private void residual(int[] out, int order) throws IOException {
        int method = bits(2);
        if (method > 1) throw new IOException("bad residual coding");
        int paramBits = method == 0 ? 4 : 5, escape = method == 0 ? 15 : 31;
        int partOrder = bits(4);
        int parts = 1 << partOrder;
        int per = blockSize >> partOrder;
        int i = order;
        for (int p = 0; p < parts; p++) {
            int count = p == 0 ? per - order : per;
            if (count < 0) throw new IOException("bad partition");
            int param = bits(paramBits);
            if (param == escape) {
                int raw = bits(5);
                for (int k = 0; k < count; k++) out[i++] = raw == 0 ? 0 : sbits(raw);
            } else {
                for (int k = 0; k < count; k++) {
                    int q = unary();
                    int v = (q << param) | (param == 0 ? 0 : bits(param));
                    out[i++] = (v >>> 1) ^ -(v & 1);
                }
            }
        }
    }

    private int bits(int n) throws IOException {
        if (n == 0) return 0;
        int end = bp + n;
        if ((end + 7) >> 3 > d.length) throw new EOFException();
        int v = 0;
        while (n > 0) {
            int off = bp & 7, avail = 8 - off, take = Math.min(avail, n);
            int b = ((d[bp >> 3] & 0xFF) >>> (avail - take)) & ((1 << take) - 1);
            v = (v << take) | b;
            n -= take;
            bp += take;
        }
        return v;
    }

    private int sbits(int n) throws IOException {
        if (n == 0) return 0;
        if (n > 32) throw new IOException("sample too wide");
        if (n == 32) return (bits(16) << 16) | bits(16);
        int v = bits(n);
        return (v << (32 - n)) >> (32 - n);
    }

    private int unary() throws IOException {
        int q = 0;
        while (true) {
            int idx = bp >> 3;
            if (idx >= d.length) throw new EOFException();
            int off = bp & 7;
            int b = (d[idx] << off) & 0xFF;
            if (b == 0) { q += 8 - off; bp += 8 - off; continue; }
            int lead = Integer.numberOfLeadingZeros(b) - 24;
            q += lead;
            bp += lead + 1;
            return q;
        }
    }

    private long utf8() throws IOException {
        int b = bits(8);
        if ((b & 0x80) == 0) return b;
        int extra;
        long v;
        if ((b & 0xE0) == 0xC0) { extra = 1; v = b & 0x1F; }
        else if ((b & 0xF0) == 0xE0) { extra = 2; v = b & 0x0F; }
        else if ((b & 0xF8) == 0xF0) { extra = 3; v = b & 0x07; }
        else if ((b & 0xFC) == 0xF8) { extra = 4; v = b & 0x03; }
        else if ((b & 0xFE) == 0xFC) { extra = 5; v = b & 0x01; }
        else if (b == 0xFE) { extra = 6; v = 0; }
        else return -1;
        for (int i = 0; i < extra; i++) {
            int c = bits(8);
            if ((c & 0xC0) != 0x80) return -1;
            v = (v << 6) | (c & 0x3F);
        }
        return v;
    }

    private int crc8(int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc ^= d[i] & 0xFF;
            for (int k = 0; k < 8; k++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    private int u16(int p) { return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF); }
    private int u24(int p) { return ((d[p] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p + 2] & 0xFF); }
    private long u64(int p) { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (d[p + i] & 0xFF); return v; }

    /** Некоторые программы ставят ID3v2 перед «fLaC». */
    static int skipId3(byte[] d) {
        if (d.length > 10 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            int size = ((d[6] & 0x7F) << 21) | ((d[7] & 0x7F) << 14) | ((d[8] & 0x7F) << 7) | (d[9] & 0x7F);
            return 10 + size + ((d[5] & 0x10) != 0 ? 10 : 0);
        }
        return 0;
    }
}

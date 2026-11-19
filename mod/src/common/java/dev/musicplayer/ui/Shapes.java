package dev.musicplayer.ui;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Tex;
import dev.musicplayer.compat.Textures;

/**
 * Скруглённые панели и рамки в пиксельной сетке интерфейса, как у самой игры.
 * <p>
 * Мелкое скругление (до {@link #SMALL_R}) рисуется обычными заливками: их всего несколько, и игра склеивает их
 * с остальными заливками в один пакет. Крупное берётся из одной общей текстуры-атласа «девятью кусками»
 * (углы как есть, края и середина тянутся): все такие фигуры используют одну текстуру и тоже идут одним пакетом.
 */
public final class Shapes {
    private Shapes() {}

    private static final int SMALL_R = 3;
    /** Скругление больше этого всё равно не отличить на глаз. */
    private static final int MAX_R = 24;
    private static final int ATLAS = 512;

    /** Для мелких радиусов: отступ слева в каждой строке угла (заливка) и отрезки рамки по строкам угла. */
    private static final int[][] FILL_INSET = new int[SMALL_R + 1][];
    private static final int[][][] RING_RUNS = new int[SMALL_R + 1][][];

    private record Mask(int u, int v, int corner) {}

    private static final Mask[] FILLS = new Mask[MAX_R + 1], RINGS = new Mask[MAX_R + 1];
    private static Tex atlas;
    private static boolean atlasFailed;

    static {
        for (int r = 1; r <= SMALL_R; r++) {
            boolean[] in = bits(r);
            int size = r * 2 + 1;
            FILL_INSET[r] = new int[r];
            RING_RUNS[r] = new int[r][];
            for (int y = 0; y < r; y++) {
                int x = 0;
                while (x < r && !in[y * size + x]) x++;
                FILL_INSET[r][y] = x;
                // свой пиксель рамки — тот, у которого сосед снаружи; в строке угла это короткий отрезок
                int from = -1, to = -1;
                for (int i = 0; i < r; i++) {
                    if (in[y * size + i] && edge(in, size, i, y)) {
                        if (from < 0) from = i;
                        to = i + 1;
                    }
                }
                RING_RUNS[r][y] = from < 0 ? new int[0] : new int[]{from, to};
            }
        }
    }

    public static void fill(Canvas g, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        r = Math.max(0, Math.min(Math.min(r, MAX_R), Math.min(w, h) / 2));
        if (r == 0) { g.fill(x, y, x + w, y + h, argb); return; }
        if (r <= SMALL_R) { smallFill(g, x, y, w, h, r, argb); return; }
        Mask m = mask(r, false);
        if (m == null) { smallFill(g, x, y, w, h, Math.min(r, SMALL_R), argb); return; }
        nine(g, m, x, y, w, h, argb, true);
    }

    /** Рамка в один пиксель интерфейса, внутри пусто. */
    public static void ring(Canvas g, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        r = Math.max(0, Math.min(Math.min(r, MAX_R), Math.min(w, h) / 2));
        if (r <= SMALL_R) { smallRing(g, x, y, w, h, r, argb); return; }
        Mask m = mask(r, true);
        if (m == null) { smallRing(g, x, y, w, h, SMALL_R, argb); return; }
        nine(g, m, x, y, w, h, argb, false);
    }

    /** Жёсткая тень, сдвинутая вниз: так тени рисует сама игра (у текста, у предметов). */
    public static void shadow(Canvas g, int x, int y, int w, int h, int r, int drop, int argb) {
        fill(g, x, y + drop, w, h, r, argb);
    }

    private static void smallFill(Canvas g, int x, int y, int w, int h, int r, int argb) {
        int[] inset = FILL_INSET[r];
        int row = 0;
        while (row < r) {
            int in = inset[row], end = row + 1;
            while (end < r && inset[end] == in) end++;
            g.fill(x + in, y + row, x + w - in, y + end, argb);
            g.fill(x + in, y + h - end, x + w - in, y + h - row, argb);
            row = end;
        }
        g.fill(x, y + r, x + w, y + h - r, argb);
    }

    private static void smallRing(Canvas g, int x, int y, int w, int h, int r, int argb) {
        if (r == 0) {
            g.fill(x, y, x + w, y + 1, argb);
            g.fill(x, y + h - 1, x + w, y + h, argb);
            g.fill(x, y + 1, x + 1, y + h - 1, argb);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
            return;
        }
        int[][] runs = RING_RUNS[r];
        int top = FILL_INSET[r][0];
        g.fill(x + top, y, x + w - top, y + 1, argb);
        g.fill(x + top, y + h - 1, x + w - top, y + h, argb);
        for (int row = 1; row < r; row++) {
            int[] run = runs[row];
            if (run.length == 0) continue;
            int a = run[0], b = run[1];
            g.fill(x + a, y + row, x + b, y + row + 1, argb);
            g.fill(x + w - b, y + row, x + w - a, y + row + 1, argb);
            g.fill(x + a, y + h - 1 - row, x + b, y + h - row, argb);
            g.fill(x + w - b, y + h - 1 - row, x + w - a, y + h - row, argb);
        }
        g.fill(x, y + r, x + 1, y + h - r, argb);
        g.fill(x + w - 1, y + r, x + w, y + h - r, argb);
    }

    private static void nine(Canvas g, Mask m, int x, int y, int w, int h, int argb, boolean solid) {
        int c = m.corner, u = m.u, v = m.v;
        int midW = w - c * 2, midH = h - c * 2;
        g.blit(atlas, x, y, c, c, u, v, c, c, ATLAS, argb);
        g.blit(atlas, x + w - c, y, c, c, u + c + 1, v, c, c, ATLAS, argb);
        g.blit(atlas, x, y + h - c, c, c, u, v + c + 1, c, c, ATLAS, argb);
        g.blit(atlas, x + w - c, y + h - c, c, c, u + c + 1, v + c + 1, c, c, ATLAS, argb);
        if (midW > 0) {
            g.blit(atlas, x + c, y, midW, c, u + c, v, 1, c, ATLAS, argb);
            g.blit(atlas, x + c, y + h - c, midW, c, u + c, v + c + 1, 1, c, ATLAS, argb);
        }
        if (midH > 0) {
            g.blit(atlas, x, y + c, c, midH, u, v + c, c, 1, ATLAS, argb);
            g.blit(atlas, x + w - c, y + c, c, midH, u + c + 1, v + c, c, 1, ATLAS, argb);
        }
        if (solid && midW > 0 && midH > 0) g.blit(atlas, x + c, y + c, midW, midH, u + c, v + c, 1, 1, ATLAS, argb);
    }

    private static Mask mask(int r, boolean ring) {
        if (atlas == null && !atlasFailed) buildAtlas();
        return atlas == null ? null : (ring ? RINGS : FILLS)[r];
    }

    /** Все маски крупных радиусов один раз раскладываются по полкам одной текстуры. */
    private static void buildAtlas() {
        int[] px = new int[ATLAS * ATLAS];
        int cx = 0, cy = 0, shelf = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean ring = pass == 1;
            for (int r = SMALL_R + 1; r <= MAX_R; r++) {
                int size = r * 2 + 1;
                if (cx + size > ATLAS) { cx = 0; cy += shelf; shelf = 0; }
                boolean[] in = bits(r);
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        boolean on = in[y * size + x] && (!ring || edge(in, size, x, y));
                        px[(cy + y) * ATLAS + cx + x] = on ? 0xFFFFFFFF : 0x00FFFFFF;
                    }
                }
                (ring ? RINGS : FILLS)[r] = new Mask(cx, cy, r);
                cx += size;
                shelf = Math.max(shelf, size);
            }
        }
        atlas = Textures.upload("shapes_atlas", px, ATLAS);
        atlasFailed = atlas == null;
    }

    /** Пиксель внутри, если его центр не дальше радиуса от центра скругления (0.35 срезает одиночные «зубы»). */
    private static boolean[] bits(int r) {
        int size = r * 2 + 1;
        boolean[] in = new boolean[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float qx = Math.max(0, r - Math.min(x + 0.5f, size - x - 0.5f));
                float qy = Math.max(0, r - Math.min(y + 0.5f, size - y - 0.5f));
                in[y * size + x] = qx * qx + qy * qy <= (r - 0.35f) * (r - 0.35f) + 0.001f;
            }
        }
        return in;
    }

    private static boolean edge(boolean[] in, int size, int x, int y) {
        return x == 0 || y == 0 || x == size - 1 || y == size - 1
                || !in[y * size + x - 1] || !in[y * size + x + 1] || !in[(y - 1) * size + x] || !in[(y + 1) * size + x];
    }
}

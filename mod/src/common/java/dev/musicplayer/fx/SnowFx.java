package dev.musicplayer.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * Новогодний снег для окон мода: падающие снежинки (за содержимым окна), снежная шапка на верхней кромке, которую
 * можно счищать мышью, скатывать в комья и собирать из них снеговиков. Ядро не знает про Minecraft: рисует через
 * {@link Paint}, поэтому считается и проверяется отдельно. Без аллокаций в кадре; на слабом железе плотность падает сама.
 */
public final class SnowFx {
    public interface Paint { void rect(int x, int y, int w, int h, int argb); }

    private static final int CW = 3, MAX_FLAKES = 96, MAX_BALLS = 10, MAX_R = 14;
    private static final float CAP_MAX = 10f;
    private static final float[] SIN = new float[256];
    private static final int[][] DISC = new int[MAX_R + 2][];

    static {
        for (int i = 0; i < 256; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2 / 256);
    }

    private static final class Ball {
        float x, y, r, vy;
        Ball parent;
        Ball(float x, float y, float r) { this.x = x; this.y = y; this.r = r; }
    }

    // окно
    private int x0, y0, w, h, cols;
    private float[] cap = new float[0], taper = new float[0];
    // снежинки
    private final float[] fx = new float[MAX_FLAKES], fy = new float[MAX_FLAKES], fv = new float[MAX_FLAKES], fp = new float[MAX_FLAKES];
    private final byte[] fs = new byte[MAX_FLAKES];
    private int flakes, active;
    // состояние
    private float time, grow, quality = 1f, slow, checkIn;
    private int seed = 0x2F6E2B1;
    // снежки и мышь
    /** Один снег на окно плеера и один на настройки: живёт до закрытия игры, поэтому снеговики не пропадают при повторном входе. */
    public static final SnowFx PLAYER = new SnowFx(), SETTINGS = new SnowFx();

    /** Снег окна плеера: при первом обращении подгружается сохранённый (см. {@link SnowStore}). */
    public static SnowFx player() { SnowStore.load(); return PLAYER.reopened(); }

    public static SnowFx settings() { SnowStore.load(); return SETTINGS.reopened(); }

    private final List<Ball> balls = new ArrayList<>();
    private Ball rolling, grabbed;
    private float grabDx, grabDy, rollX;
    private int lastMx, lastMy;
    private boolean wasDown, pressed;
    // сохранённое состояние ждёт первого кадра: размер окна становится известен только тогда
    private int[] pCap;
    private int pW, pH;
    private List<float[]> pBalls;

    /** Нажатие мыши берётся из событий окна: опрос кнопки у игры при открытом экране ничего не показывает. */
    public void press(boolean down) { pressed = down; }

    /** Окно открыли заново: незаконченное действие мышью сбрасывается (снежок в руке падает), снег и снеговики остаются. */
    public SnowFx reopened() {
        if (grabbed != null) drop(grabbed);
        grabbed = null;
        rolling = null;
        pressed = false;
        wasDown = false;
        return this;
    }

    public boolean pressed() { return pressed; }

    private float rand() {
        seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
        return (seed & 0xFFFFFF) / 16777216f;
    }

    /** Окно изменилось (или первый кадр): снег раскладывается заново, снежки остаются. */
    private void layout(int x, int y, int ww, int hh) {
        x0 = x; y0 = y; w = ww; h = hh;
        cols = Math.max(8, (ww - 16) / CW);
        cap = new float[cols];
        taper = new float[cols];
        for (int i = 0; i < cols; i++) {
            int edge = Math.min(i, cols - 1 - i);
            taper[i] = edge >= 5 ? 1f : 0.3f + 0.14f * edge;
            cap[i] = 4.2f + 2.6f * rand();
        }
        for (int pass = 0; pass < 3; pass++) for (int i = 1; i < cols - 1; i++) cap[i] = (cap[i - 1] + cap[i] * 2 + cap[i + 1]) / 4f;
        if (pCap != null && pW == ww && pH == hh && pCap.length == cols) {   // тот же размер окна: шапка как её оставили
            for (int i = 0; i < cols; i++) cap[i] = Math.min(CAP_MAX, pCap[i] / 10f);
        }
        pCap = null;
        if (pBalls != null) {
            List<Ball> made = new ArrayList<>();
            for (float[] b : pBalls) {
                Ball nb = new Ball(x0 + Math.max(10, Math.min(w - 10, b[0])), y0 + b[1], Math.max(2f, Math.min(MAX_R, b[2])));
                made.add(nb);
            }
            for (int i = 0; i < made.size(); i++) {
                int par = (int) pBalls.get(i)[3];
                if (par >= 0 && par < made.size() && par != i) made.get(i).parent = made.get(par);
            }
            balls.clear();
            balls.addAll(made);
            pBalls = null;
        }
        flakes = Math.max(36, Math.min(MAX_FLAKES, ww * hh / 2400));
        for (int i = 0; i < flakes; i++) {
            int s = rand() < 0.45f ? 1 : rand() < 0.65f ? 2 : 3;
            fs[i] = (byte) s;
            fv[i] = s == 1 ? 14 + 8 * rand() : s == 2 ? 24 + 10 * rand() : 38 + 14 * rand();
            fp[i] = rand() * 256f;
            fx[i] = x0 + rand() * w;
            fy[i] = y0 + rand() * h;
        }
    }

    public void update(float dt, int x, int y, int ww, int hh, int mx, int my, boolean down, boolean play) {
        dt = Math.max(0.0005f, Math.min(dt, 0.05f));
        time += dt;
        adapt(dt);
        if (ww != w || hh != h || cols == 0) {
            layout(x, y, ww, hh);
        } else if (x != x0 || y != y0) {   // окно просто сдвинулось: снег и снеговики едут вместе с ним
            float mdx = x - x0, mdy = y - y0;
            for (int i = 0; i < flakes; i++) { fx[i] += mdx; fy[i] += mdy; }
            for (Ball b : balls) { b.x += mdx; b.y += mdy; }
            x0 = x; y0 = y;
        }

        active = Math.max(8, (int) (flakes * quality));
        for (int i = 0; i < active; i++) {
            fy[i] += fv[i] * dt;
            int idx = ((int) (time * 40f * (0.6f + 0.1f * fs[i]) + fp[i])) & 255;
            fx[i] += (SIN[idx] * (6f + 3f * fs[i]) + 2f) * dt;
            if (fx[i] < x0) fx[i] += w; else if (fx[i] > x0 + w) fx[i] -= w;
            if (fy[i] > y0 + h + 2) { fy[i] = y0 - 2 - rand() * 14; fx[i] = x0 + rand() * w; }
        }

        grow += dt * 24f;
        while (grow >= 1f) {
            grow -= 1f;
            int c = (int) (rand() * cols);
            if (c < cols && cap[c] < CAP_MAX) { cap[c] += 0.25f; relax(c); }
        }

        if (play) interact(mx, my, down); else { grabbed = null; rolling = null; }
        stepBalls(dt);
        lastMx = mx; lastMy = my; wasDown = down;
    }

    /** Слабый компьютер: если кадры стали долгими, снежинок становится меньше. */
    private void adapt(float dt) {
        slow += (dt - slow) * 0.05f;
        checkIn -= dt;
        if (checkIn > 0) return;
        checkIn = 0.7f;
        float target = slow > 0.05f ? 0.25f : slow > 0.034f ? 0.5f : 1f;
        quality = target < quality ? target : Math.min(1f, quality + 0.25f);
    }

    private void relax(int c) {
        for (int i = c - 1; i >= 0 && cap[i] < cap[i + 1] - 1.2f; i--) cap[i] = cap[i + 1] - 1.2f;
        for (int i = c + 1; i < cols && cap[i] < cap[i - 1] - 1.2f; i++) cap[i] = cap[i - 1] - 1.2f;
    }

    /** Сколько места над окном до верха экрана: от него зависит, какого размера снежки и снеговики помещаются. */
    private float maxRadius() { return Math.max(6f, Math.min(MAX_R, (y0 - 3) / 2f)); }

    private int col(float px) { return Math.max(0, Math.min(cols - 1, (int) ((px - x0 - 8) / CW))); }

    private float groundY(float px) { return y0 + 2 - cap[col(px)] * taper[col(px)]; }

    /** Убирает снег вокруг точки; возвращает, сколько убрано (из этого катится ком). */
    private float wipe(float px, float amount, float radius) {
        float got = 0;
        int a = col(px - radius), b = col(px + radius);
        for (int i = a; i <= b; i++) {
            float d = Math.abs(x0 + 8 + i * CW + CW / 2f - px) / radius;
            if (d >= 1f) continue;
            float dec = Math.min(cap[i], amount * (1f - d));
            cap[i] -= dec;
            got += dec;
        }
        // края борозды сглаживаются, чтобы не оставалось рваных ям
        for (int i = Math.max(1, a - 1); i <= Math.min(cols - 2, b + 1); i++) cap[i] = cap[i] * 0.5f + (cap[i - 1] + cap[i + 1]) * 0.25f;
        return got;
    }

    private void interact(int mx, int my, boolean down) {
        boolean inZone = mx >= x0 + 6 && mx < x0 + w - 6 && my >= y0 - 22 && my < y0 + 3;
        float speed = (float) Math.hypot(mx - lastMx, my - lastMy);
        if (!down) {
            if (grabbed != null) drop(grabbed);
            if (rolling != null) finishRoll();
            if (inZone && speed > 0.6f) wipe(mx, Math.min(1.2f, speed * 0.05f), 10f);   // смахиваем мышью
            return;
        }
        if (!wasDown) {
            grabbed = pickBall(mx, my);
            if (grabbed != null) {
                grabbed.parent = null;
                grabDx = mx - grabbed.x; grabDy = my - grabbed.y;
            } else if (inZone && rolling == null && maxRadius() >= 4f) {
                rollX = Math.max(x0 + 10, Math.min(x0 + w - 10, mx));
                rolling = new Ball(rollX, groundY(rollX) - 2.5f, 2.5f);
            }
        }
        if (grabbed != null) {
            grabbed.x = mx - grabDx; grabbed.y = my - grabDy; grabbed.vy = 0;
        } else if (rolling != null) {
            float nx = Math.max(x0 + 10, Math.min(x0 + w - 10, mx));
            float dx = Math.abs(nx - rollX);
            if (dx > 0) {
                float got = wipe((nx + rollX) / 2f, dx * 0.34f, 8f);
                rolling.r = Math.min(maxRadius(), (float) Math.sqrt(rolling.r * rolling.r + got * 2.6f));
                rollX = nx;
            }
            rolling.x = rollX;
            rolling.y = groundY(rollX) - rolling.r;
        }
    }

    private Ball pickBall(int mx, int my) {
        for (int i = balls.size() - 1; i >= 0; i--) {
            Ball b = balls.get(i);
            float dx = mx - b.x, dy = my - b.y;
            if (dx * dx + dy * dy <= (b.r + 2) * (b.r + 2)) return b;
        }
        return null;
    }

    private void finishRoll() {
        Ball b = rolling;
        rolling = null;
        if (b.r < 4f) return;   // слишком маленький, просто счистили
        if (balls.size() >= MAX_BALLS) removeWithChildren(balls.get(0));
        balls.add(b);
    }

    private void removeWithChildren(Ball b) {
        balls.removeIf(o -> o == b || isAbove(o, b));
    }

    private boolean isAbove(Ball o, Ball base) {
        for (Ball p = o.parent; p != null; p = p.parent) if (p == base) return true;
        return false;
    }

    private boolean hasChild(Ball b) {
        for (Ball o : balls) if (o.parent == b) return true;
        return false;
    }

    private int depth(Ball b) {
        int d = 0;
        for (Ball p = b.parent; p != null; p = p.parent) d++;
        return d;
    }

    /** Отпустили снежок: если он над большим, встаёт сверху (так собирается снеговик), иначе падает на кромку. */
    private void drop(Ball b) {
        grabbed = null;
        b.x = Math.max(x0 + 10, Math.min(x0 + w - 10, b.x));
        Ball best = null;
        float bestD = Float.MAX_VALUE;
        for (Ball c : balls) {
            if (c == b || isAbove(c, b) || hasChild(c) || depth(c) >= 2) continue;
            float dx = Math.abs(b.x - c.x);
            if (dx < c.r * 0.8f && b.r < c.r * 0.95f && b.y < c.y + c.r * 0.2f && dx < bestD
                    && c.y - (c.r + b.r) * 0.78f - b.r >= 1f) { best = c; bestD = dx; }   // голова должна остаться на экране
        }
        b.parent = best;
        b.vy = 0;
        if (best == null) {   // не встал на другой ком: чтобы не слиться с соседним, отодвигаем его в сторону
            for (Ball c : balls) {
                if (c == b || c.parent != null) continue;
                float need = (b.r + c.r) * 0.85f, dx = b.x - c.x;
                if (Math.abs(dx) < need) {
                    float nx = c.x + (dx >= 0 ? need : -need);
                    if (nx < x0 + 10 || nx > x0 + w - 10) nx = c.x + (dx >= 0 ? -need : need);
                    b.x = Math.max(x0 + 10, Math.min(x0 + w - 10, nx));
                }
            }
        }
    }

    private void stepBalls(float dt) {
        for (Ball b : balls) {
            if (b == grabbed || b.parent != null) continue;
            float target = groundY(b.x) - b.r;
            if (b.y < target - 0.01f || b.vy != 0f) {
                b.vy += 520f * dt;
                b.y += b.vy * dt;
                if (b.y >= target) { b.y = target; b.vy = 0f; }
            } else {
                b.y = target;
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            for (Ball b : balls) {
                if (b.parent == null || b == grabbed) continue;
                b.x = b.parent.x;
                b.y = b.parent.y - (b.parent.r + b.r) * 0.78f;
            }
        }
    }

    // ---------------- сохранение ----------------

    /** Состояние текстом: размер окна, высоты шапки и снежки с положением относительно окна. */
    String dump() {
        StringBuilder sb = new StringBuilder();
        if (cols == 0) {   // окно ещё не показывали в этот запуск: оставляем то, что было загружено
            if (pCap != null) {
                sb.append("size ").append(pW).append(' ').append(pH).append('\n').append("cap");
                for (int v : pCap) sb.append(' ').append(v);
                sb.append('\n');
            }
            if (pBalls != null) for (float[] b : pBalls) sb.append("ball ").append(b[0]).append(' ').append(b[1]).append(' ').append(b[2]).append(' ').append((int) b[3]).append('\n');
            return sb.toString();
        }
        sb.append("size ").append(w).append(' ').append(h).append('\n').append("cap");
        for (int i = 0; i < cols; i++) sb.append(' ').append(Math.round(cap[i] * 10f));
        sb.append('\n');
        for (Ball b : balls) {
            sb.append("ball ").append(b.x - x0).append(' ').append(b.y - y0).append(' ').append(b.r).append(' ').append(balls.indexOf(b.parent)).append('\n');
        }
        return sb.toString();
    }

    void restore(List<String> lines) {
        try {
            int[] c = null;
            int rw = 0, rh = 0;
            List<float[]> bl = new ArrayList<>();
            for (String line : lines) {
                String[] t = line.trim().split("\\s+");
                if (t.length == 0 || t[0].isEmpty()) continue;
                if (t[0].equals("size") && t.length >= 3) { rw = Integer.parseInt(t[1]); rh = Integer.parseInt(t[2]); }
                else if (t[0].equals("cap")) { c = new int[t.length - 1]; for (int i = 1; i < t.length; i++) c[i - 1] = Integer.parseInt(t[i]); }
                else if (t[0].equals("ball") && t.length >= 5 && bl.size() < MAX_BALLS) {
                    bl.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]), Integer.parseInt(t[4])});
                }
            }
            if (c != null && rw > 0 && rh > 0) { pCap = c; pW = rw; pH = rh; }
            if (!bl.isEmpty()) pBalls = bl;
        } catch (RuntimeException ignored) {
            // испорченный файл: начнётся с чистого снега
        }
    }

    // ---------------- рисование ----------------

    /** Снежинки: рисуются сразу после фона окна, под содержимым, чтобы не лезть на текст. */
    public void drawFlakes(Paint p) {
        for (int i = 0; i < active; i++) {
            int ix = (int) fx[i], iy = (int) fy[i];
            if (iy < y0 + 1 || iy > y0 + h - 2) continue;
            int s = fs[i];
            if (s == 1) p.rect(ix, iy, 1, 1, 0x96FFFFFF);
            else if (s == 2) p.rect(ix, iy, 2, 2, 0xB4FFFFFF);
            else {
                p.rect(ix, iy - 1, 1, 5, 0x78FFFFFF);
                p.rect(ix - 2, iy + 1, 5, 1, 0x78FFFFFF);
                p.rect(ix - 1, iy, 3, 3, 0xD2FFFFFF);
            }
        }
    }

    /** Шапка снега и снежки поверх всего окна. Соседние столбцы одной высоты склеиваются в один прямоугольник. */
    public void drawGround(Paint p, int accent) {
        int base = y0 + 2;
        int i = 0;
        while (i < cols) {
            int hi = Math.round(cap[i] * taper[i]);
            int j = i + 1;
            while (j < cols && Math.round(cap[j] * taper[j]) == hi) j++;
            if (hi > 0) {
                int rx = x0 + 8 + i * CW, rw = (j - i) * CW;
                if (quality >= 0.5f) p.rect(rx, base - hi - 1, rw, 1, 0x994A5A7A);   // тонкий контур сверху: виден и на светлом небе
                p.rect(rx, base - hi, rw, hi, 0xFFF1F6FF);
                if (quality >= 0.5f) {
                    p.rect(rx, base - hi, rw, 1, 0xFFFFFFFF);
                    if (hi > 2) p.rect(rx, base - 1, rw, 1, 0xFFC9D6EE);   // тень у кромки окна
                }
            }
            i = j;
        }
        for (Ball b : balls) drawBall(p, b, accent);
        if (rolling != null) drawBall(p, rolling, accent);
    }

    private static int[] disc(int r) {
        int[] t = DISC[r];
        if (t == null) {
            t = new int[2 * r + 1];
            for (int dy = -r; dy <= r; dy++) t[dy + r] = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy + 0.2)));
            DISC[r] = t;
        }
        return t;
    }

    private void drawBall(Paint p, Ball b, int accent) {
        int r = Math.max(2, Math.min(MAX_R, Math.round(b.r)));
        int cx = Math.round(b.x), cy = Math.round(b.y);
        int[] t = disc(r);
        for (int dy = -r; dy <= r; dy++) {   // контур: чуть больший диск под телом
            int hw = t[dy + r] + 1;
            p.rect(cx - hw, cy + dy, 2 * hw + 1, 1, 0xAA4A5A7A);
        }
        p.rect(cx - 1, cy - r - 1, 3, 1, 0xAA4A5A7A);
        p.rect(cx - 1, cy + r + 1, 3, 1, 0xAA4A5A7A);
        for (int dy = -r; dy <= r; dy++) {
            int hw = t[dy + r];
            p.rect(cx - hw, cy + dy, 2 * hw + 1, 1, 0xFFF4F8FF);
            if (dy > r / 4) {
                int dark = Math.max(1, hw * 2 / 3);
                p.rect(cx + hw - dark + 1, cy + dy, dark, 1, 0xFFC8D4EE);
            }
        }
        p.rect(cx - r / 2, cy - r / 2, Math.max(1, r / 3), 1, 0xFFFFFFFF);

        boolean child = hasChild(b);
        if (b.parent != null && !child) {          // голова: глаза, нос и шарф
            int er = Math.max(1, r / 4);
            p.rect(cx - r / 2, cy - r / 4, er, er, 0xFF20242E);
            p.rect(cx + r / 2 - er + 1, cy - r / 4, er, er, 0xFF20242E);
            p.rect(cx, cy, Math.max(2, r / 2 + 1), Math.max(1, r / 5), 0xFFF28C28);
            p.rect(cx - r + 1, cy + r - 2, 2 * r - 1, 2, 0xFF000000 | accent);
            p.rect(cx + r / 2, cy + r, 2, Math.max(2, r / 2), 0xFF000000 | accent);
        } else if (child) {                         // туловище: пуговицы
            int by = cy - r / 3;
            p.rect(cx, by, 2, 2, 0xFF20242E);
            p.rect(cx, by + r / 2 + 1, 2, 2, 0xFF20242E);
        }
    }
}

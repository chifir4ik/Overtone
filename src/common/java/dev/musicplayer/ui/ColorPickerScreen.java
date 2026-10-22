package dev.musicplayer.ui;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.KeyInput;
import dev.musicplayer.compat.Keys;
import dev.musicplayer.compat.ModScreen;
import dev.musicplayer.util.T;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/** Выбор своего цвета: квадрат насыщенности и яркости, полоса оттенка и поле для hex. */
public final class ColorPickerScreen extends ModScreen {
    private static final int SIZE = 120, CELL = 4, CELLS = SIZE / CELL, HUE_W = 14;
    private static final int PW = 196, PH = 232;

    private final Screen parent;
    private final IntConsumer onPick;
    private float hue, sat, val;
    private EditBox hex;
    private String lastHex = "";
    private boolean syncing;
    private int dragging;
    private PlayerTheme th;
    private int px, py, sx, sy, hx, mouseX, mouseY;
    private final int[] okBtn = new int[4], cancelBtn = new int[4];

    public ColorPickerScreen(Screen parent, int rgb, IntConsumer onPick) {
        super(Component.literal(T.get("picker.title")));
        this.parent = parent;
        this.onPick = onPick;
        float[] hsv = toHsv(rgb);
        hue = hsv[0]; sat = hsv[1]; val = hsv[2];
    }

    @Override
    protected void init() {
        px = (width - PW) / 2;
        py = (height - PH) / 2;
        sx = px + 16;
        sy = py + 34;
        hx = sx + SIZE + 12;

        int fy = sy + SIZE + 14;
        hex = new EditBox(font, px + 16 + 48 + 6, fy + 5, PW - 32 - 48 - 12, 10, Component.empty());
        hex.setBordered(false);
        hex.setMaxLength(7);
        hex.setResponder(this::typed);
        addRenderableWidget(hex);
        showHex();
    }

    private int rgb() { return toRgb(hue, sat, val); }

    private void showHex() {
        setHex(String.format("#%06X", rgb()));
    }

    private void setHex(String text) {
        syncing = true;
        hex.setValue(text);
        lastHex = text;
        syncing = false;
    }

    private void typed(String text) {
        if (syncing) return;
        // setFilter убрали в 26.2
        if (!text.matches("#?[0-9a-fA-F]*")) { setHex(lastHex); return; }
        lastHex = text;
        String d = text.startsWith("#") ? text.substring(1) : text;
        if (d.length() != 6) return;
        float[] hsv = toHsv(Integer.parseInt(d, 16));
        // у серого оттенка нет: не даём полосе прыгать в ноль
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
    }

    @Override
    protected void draw(Canvas g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        th = PlayerTheme.current();
        g.fill(0, 0, width, height, th.dim);
        th.window(g, px, py, PW, PH);
        g.text(font, T.get("picker.title"), px + 16, py + 12, th.accent);

        for (int cx = 0; cx < CELLS; cx++) {
            for (int cy = 0; cy < CELLS; cy++) {
                int c = toRgb(hue, (cx + 0.5f) / CELLS, 1f - (cy + 0.5f) / CELLS);
                g.fill(sx + cx * CELL, sy + cy * CELL, sx + (cx + 1) * CELL, sy + (cy + 1) * CELL, 0xFF000000 | c);
            }
        }
        int kx = sx + Math.round(sat * SIZE), ky = sy + Math.round((1f - val) * SIZE);
        PlayerTheme.circle(g, kx, ky, 5, 0xFFFFFFFF);
        PlayerTheme.circle(g, kx, ky, 3, 0xFF000000 | rgb());

        for (int i = 0; i < CELLS; i++)
            g.fill(hx, sy + i * CELL, hx + HUE_W, sy + (i + 1) * CELL, 0xFF000000 | toRgb((i + 0.5f) / CELLS, 1f, 1f));
        int hy = sy + Math.round(hue * SIZE);
        g.fill(hx - 2, hy - 1, hx + HUE_W + 2, hy + 2, 0xFFFFFFFF);

        int fy = sy + SIZE + 14;
        PlayerTheme.rrect(g, px + 16, fy, 48, 20, 4, 0xFF000000 | rgb());
        PlayerTheme.rrect(g, px + 16 + 54, fy, PW - 32 - 54, 20, 4, th.fieldBg);
        drawWidgets(g, mx, my, delta);

        int by = py + PH - 32, bw = (PW - 32 - 8) / 2;
        button(g, okBtn, px + 16, by, bw, T.get("picker.ok"), true);
        button(g, cancelBtn, px + 16 + bw + 8, by, bw, T.get("picker.cancel"), false);
    }

    private void button(Canvas g, int[] rect, int x, int y, int w, String label, boolean primary) {
        rect[0] = x; rect[1] = y; rect[2] = w; rect[3] = 20;
        boolean hover = inside(rect, mouseX, mouseY);
        int bg = primary ? (hover ? th.accentHover : th.accent) : (hover ? th.hover : th.trackBg);
        PlayerTheme.rrect(g, x, y, w, 20, 5, bg);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 6, PlayerTheme.TEXT_MAIN);
    }

    private static boolean inside(int[] r, int x, int y) { return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]; }

    private void pickSquare(int mx, int my) {
        sat = clamp((mx - sx) / (float) SIZE);
        val = 1f - clamp((my - sy) / (float) SIZE);
        showHex();
    }

    private void pickHue(int my) {
        hue = Math.min(0.999f, clamp((my - sy) / (float) SIZE));
        showHex();
    }

    private void confirm() {
        onPick.accept(rgb());
        Game.open(parent);
    }

    @Override
    protected boolean onClick(double x, double y, int button, boolean doubleClick) {
        int mx = (int) x, my = (int) y;
        if (mx >= sx && mx < sx + SIZE && my >= sy && my < sy + SIZE) { dragging = 1; pickSquare(mx, my); return true; }
        if (mx >= hx && mx < hx + HUE_W && my >= sy && my < sy + SIZE) { dragging = 2; pickHue(my); return true; }
        if (inside(okBtn, mx, my)) { confirm(); return true; }
        if (inside(cancelBtn, mx, my)) { onClose(); return true; }
        return super.onClick(x, y, button, doubleClick);
    }

    @Override
    protected boolean onDrag(double x, double y, int button, double dx, double dy) {
        if (dragging == 1) { pickSquare((int) x, (int) y); return true; }
        if (dragging == 2) { pickHue((int) y); return true; }
        return super.onDrag(x, y, button, dx, dy);
    }

    @Override
    protected boolean onRelease(double x, double y, int button) {
        dragging = 0;
        return super.onRelease(x, y, button);
    }

    @Override
    protected boolean onKey(KeyInput event) {
        if (event.key() == Keys.ENTER || event.key() == Keys.KP_ENTER) { confirm(); return true; }
        return super.onKey(event);
    }

    @Override
    public void onClose() { Game.open(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    static int toRgb(float h, float s, float v) {
        float c = v * s, hh = h * 6f, x = c * (1f - Math.abs(hh % 2f - 1f)), m = v - c;
        float r, g, b;
        switch ((int) hh % 6) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = d == 0 ? 0 : max == r ? ((g - b) / d % 6f) / 6f : max == g ? ((b - r) / d + 2f) / 6f : ((r - g) / d + 4f) / 6f;
        return new float[]{h < 0 ? h + 1f : h, max == 0 ? 0 : d / max, max};
    }
}

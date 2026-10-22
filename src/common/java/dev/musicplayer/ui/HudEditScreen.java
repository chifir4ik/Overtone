package dev.musicplayer.ui;

import dev.musicplayer.compat.Game;
import dev.musicplayer.compat.Keys;
import dev.musicplayer.compat.KeyInput;
import dev.musicplayer.compat.ModScreen;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.hud.HudBounds;
import dev.musicplayer.hud.LyricsRenderer;
import dev.musicplayer.hud.VisualizerRenderer;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WidgetRenderer;
import dev.musicplayer.util.T;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Редактор положения HUD прямо поверх игры: виджет, текст песни и плашку громкости можно схватить мышью
 * и перетащить. Колесо над элементом меняет масштаб, стрелки двигают выбранный элемент на 1 пиксель.
 * Якорь выбирается сам по тому, в какой части экрана оказался элемент, — вручную его трогать не нужно.
 */
public class HudEditScreen extends ModScreen {
    private enum Target {
        WIDGET, LYRICS, VOLUME, VISUALIZER;
        String label() { return T.get("edit.target." + name().toLowerCase()); }
    }

    private static final int SNAP = 3;

    private final Screen parent;
    private Target selected = Target.WIDGET;
    private Target dragging;
    private boolean gestureDecided;
    private double startX, startY, accX, accY;
    private int mouseX, mouseY;
    private boolean changed;

    public HudEditScreen(Screen parent) {
        super(T.component("edit.title"));
        this.parent = parent;
    }

    private static HudConfig cfg() { return HudConfig.get(); }

    @Override
    protected void init() {
        HudConfigScreen.OPEN = true;
        int cx = width / 2, y = height - 28;
        addRenderableWidget(Button.builder(T.component("btn.done"), b -> onClose())
                .bounds(cx - 125, y, 120, 20).build());
        addRenderableWidget(Button.builder(T.component("edit.reset"), b -> resetPositions())
                .bounds(cx + 5, y, 120, 20).build());
    }

    private static HudBounds.Box box(Target t) {
        return switch (t) {
            case WIDGET -> HudBounds.widget;
            case LYRICS -> HudBounds.lyrics;
            case VOLUME -> HudBounds.volume;
            case VISUALIZER -> HudBounds.visualizer;
        };
    }

    private Target pick(double x, double y) {
        Target[] order = {Target.VISUALIZER, Target.VOLUME, Target.LYRICS, Target.WIDGET};
        for (Target t : order) {
            HudBounds.Box b = box(t);
            if (b != null && x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h) return t;
        }
        return null;
    }

    private void moveTo(Target t, int x, int y, boolean snap) {
        HudBounds.Box b = box(t);
        if (b == null) return;
        if (snap && Math.abs(x + b.w / 2 - width / 2) <= SNAP) x = width / 2 - b.w / 2;
        HudBounds.Placement p = HudBounds.place(b, width, height, x, y);
        HudConfig c = cfg();
        switch (t) {
            case WIDGET -> { c.anchor = p.anchor; c.offsetX = p.offsetX; c.offsetY = p.offsetY; }
            case LYRICS -> { c.lyricsAnchor = p.anchor; c.lyricsOffsetX = p.offsetX; c.lyricsOffsetY = p.offsetY; }
            case VOLUME -> { c.volumeAnchor = p.anchor; c.volumeOffsetX = p.offsetX; c.volumeOffsetY = p.offsetY; }
            case VISUALIZER -> { c.vizAnchor = p.anchor; c.vizOffsetX = p.offsetX; c.vizOffsetY = p.offsetY; }
        }
        changed = true;
    }

    /** Сдвиг на 1 единицу отступа в сторону стрелки (знак зависит от якоря: от правого/нижнего края отступ растёт внутрь). */
    private void nudge(Target t, int dx, int dy) {
        HudConfig c = cfg();
        switch (t) {
            case WIDGET -> {
                c.offsetX += c.anchor.h > 0 ? -dx : dx;
                c.offsetY += c.anchor.v > 0 ? -dy : dy;
            }
            case LYRICS -> {
                c.lyricsOffsetX += c.lyricsAnchor.h > 0 ? -dx : dx;
                c.lyricsOffsetY += c.lyricsAnchor.v > 0 ? -dy : dy;
            }
            case VISUALIZER -> {
                c.vizOffsetX += c.vizAnchor.h > 0 ? -dx : dx;
                c.vizOffsetY += c.vizAnchor.v > 0 ? -dy : dy;
            }
            case VOLUME -> {
                c.volumeOffsetX += c.volumeAnchor.h > 0 ? -dx : dx;
                c.volumeOffsetY += c.volumeAnchor.v > 0 ? -dy : dy;
            }
        }
        changed = true;
    }

    private void resetPositions() {
        HudConfig d = new HudConfig(), c = cfg();
        c.anchor = d.anchor; c.offsetX = d.offsetX; c.offsetY = d.offsetY;
        c.lyricsAnchor = d.lyricsAnchor; c.lyricsOffsetX = d.lyricsOffsetX; c.lyricsOffsetY = d.lyricsOffsetY;
        c.volumeAnchor = d.volumeAnchor; c.volumeOffsetX = d.volumeOffsetX; c.volumeOffsetY = d.volumeOffsetY;
        c.vizAnchor = d.vizAnchor; c.vizOffsetX = d.vizOffsetX; c.vizOffsetY = d.vizOffsetY;
        changed = true;
    }

    @Override
    protected boolean onDrag(double ex, double ey, int button, double dragX, double dragY) {
        mouseX = (int) ex;
        mouseY = (int) ey;
        if (!gestureDecided) {
            // первое движение после нажатия: хватаем элемент, который был под курсором в момент нажатия
            gestureDecided = true;
            Target t = pick(ex - dragX, ey - dragY);
            HudBounds.Box b = t == null ? null : box(t);
            if (b != null) {
                dragging = t;
                selected = t;
                startX = b.x; startY = b.y;
                accX = 0; accY = 0;
            }
        }
        if (dragging != null) {
            accX += dragX;
            accY += dragY;
            moveTo(dragging, (int) Math.round(startX + accX), (int) Math.round(startY + accY), true);
            return true;
        }
        return super.onDrag(ex, ey, button, dragX, dragY);
    }

    @Override
    protected boolean onRelease(double ex, double ey, int button) {
        boolean was = dragging != null;
        dragging = null;
        gestureDecided = false;
        if (was) { saveIfChanged(); return true; }
        return super.onRelease(ex, ey, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Target t = pick(mx, my);
        if (t == null) return super.mouseScrolled(mx, my, sx, sy);
        selected = t;
        float step = sy > 0 ? 0.05f : -0.05f;
        HudConfig c = cfg();
        switch (t) {
            case WIDGET -> c.scale = stepScale(c.scale, step);
            case LYRICS -> c.lyricsScale = stepScale(c.lyricsScale, step);
            case VOLUME -> c.volumeScale = stepScale(c.volumeScale, step);
            case VISUALIZER -> c.vizScale = stepScale(c.vizScale, step);
        }
        changed = true;
        return true;
    }

    private static float stepScale(float cur, float step) {
        float v = Math.round((cur + step) * 20f) / 20f;
        return Math.max(0.5f, Math.min(2.0f, v));
    }

    @Override
    protected boolean onKey(KeyInput event) {
        int k = event.key();
        int dx = 0, dy = 0;
        if (k == Keys.LEFT) dx = -1;
        else if (k == Keys.RIGHT) dx = 1;
        else if (k == Keys.UP) dy = -1;
        else if (k == Keys.DOWN) dy = 1;
        if (dx != 0 || dy != 0) {
            nudge(selected, dx, dy);
            return true;
        }
        if (k == Keys.ENTER || k == Keys.KP_ENTER) { onClose(); return true; }
        return super.onKey(event);
    }

    @Override
    protected void draw(Canvas g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        drawWidgets(g, mx, my, delta);

        Canvas c = g;
        WidgetRenderer.render(c, true);
        LyricsRenderer.render(c, true);
        VolumeRenderer.render(c, true);
        VisualizerRenderer.render(c, true);

        Target hover = dragging != null ? dragging : pick(mx, my);
        int accent = cfg().accentRgb();
        for (Target t : Target.values()) {
            HudBounds.Box b = box(t);
            if (b == null) continue;
            boolean hot = t == hover;
            int col = hot ? (0xFF000000 | accent) : t == selected ? 0xA0FFFFFF : 0x50FFFFFF;
            frame(c, b, col);
            int ly = b.y >= 12 ? b.y - 10 : b.y + b.h + 3;
            c.text(t.label(), b.x, ly, hot ? (0xFF000000 | accent) : 0xFFC8C8D0, true);
        }

        int y = 8;
        y = lines(c, T.get("edit.help"), y);
        y = lines(c, T.get("edit.done"), y);
        if (cfg().layout == HudConfig.Layout.HOTBAR && HudBounds.widget == null)
            y = lines(c, T.get("edit.hotbar"), y);
        if (cfg().lyricsMode == HudConfig.LyricsMode.OFF)
            lines(c, T.get("edit.lyrics_off"), y);
    }

    /** Подсказка переносится по словам и не вылезает за экран; возвращает следующий y. */
    private int lines(Canvas c, String text, int y) {
        for (String l : c.wrap(text, width - 40, 2)) {
            c.text(l, (width - c.textWidth(l)) / 2, y, 0xFFE6E6EA, true);
            y += 11;
        }
        return y + 1;
    }

    private static void frame(Canvas c, HudBounds.Box b, int argb) {
        c.rect(b.x - 1, b.y - 1, b.w + 2, 1, argb);
        c.rect(b.x - 1, b.y + b.h, b.w + 2, 1, argb);
        c.rect(b.x - 1, b.y, 1, b.h, argb);
        c.rect(b.x + b.w, b.y, 1, b.h, argb);
    }

    private void saveIfChanged() {
        if (!changed) return;
        changed = false;
        cfg().save();
    }

    @Override
    public void removed() {
        saveIfChanged();
        super.removed();
    }

    @Override
    public void onClose() {
        saveIfChanged();
        Game.open(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

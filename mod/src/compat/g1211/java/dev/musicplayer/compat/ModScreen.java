package dev.musicplayer.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Основа экранов мода: скрывает различия API ввода и отрисовки между версиями игры.
 * Подклассы переопределяют draw/onClick/onDrag/onRelease/onKey.
 */
public abstract class ModScreen extends AnimatedScreen {
    /** Фон рисуется ровно один раз за кадр, кто бы его ни вызвал: два размытия подряд ломают рендер. */
    private boolean backgroundDone;

    protected ModScreen(Component title) { super(title); }

    /** Рисует ванильные виджеты (поля ввода, кнопки). Вызывать из draw в нужный момент. */
    protected final void drawWidgets(Canvas g, int mx, int my, float delta) {
        super.render(((CanvasImpl) g).ctx, mx, my, delta);
    }

    @Override
    public final void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        frame(new CanvasImpl(g), mx, my, delta);
        backgroundDone = false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (backgroundDone) return;
        backgroundDone = true;
        super.renderBackground(g, mx, my, delta);
    }

    private long lastClick;

    protected boolean onClick(double x, double y, int button, boolean doubleClick) { return super.mouseClicked(x, y, button); }
    protected boolean onDrag(double x, double y, int button, double dx, double dy) { return super.mouseDragged(x, y, button, dx, dy); }
    protected boolean onRelease(double x, double y, int button) { return super.mouseReleased(x, y, button); }
    protected boolean onKey(KeyInput in) { return super.keyPressed(in.key(), in.scan(), in.mods()); }

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        long now = System.currentTimeMillis();
        boolean doubleClick = now - lastClick < 250;
        lastClick = now;
        return isClosing() || onClick(x, y, button, doubleClick);
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) { return onDrag(x, y, button, dx, dy); }

    @Override
    public final boolean mouseReleased(double x, double y, int button) { return onRelease(x, y, button); }

    @Override
    public final boolean keyPressed(int key, int scan, int mods) { return isClosing() || onKey(new KeyInput(key, scan, mods)); }
}

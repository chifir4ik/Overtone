package dev.musicplayer.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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

    private MouseButtonEvent mouse;

    protected boolean onClick(double x, double y, int button, boolean doubleClick) { return super.mouseClicked(mouse, doubleClick); }
    protected boolean onDrag(double x, double y, int button, double dx, double dy) { return super.mouseDragged(mouse, dx, dy); }
    protected boolean onRelease(double x, double y, int button) { return super.mouseReleased(mouse); }
    protected boolean onKey(KeyInput in) { return super.keyPressed(in.raw()); }

    @Override
    public final boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        mouse = e;
        return isClosing() || onClick(e.x(), e.y(), e.button(), doubleClick);
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        mouse = e;
        return onDrag(e.x(), e.y(), e.button(), dx, dy);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent e) {
        mouse = e;
        return onRelease(e.x(), e.y(), e.button());
    }

    @Override
    public final boolean keyPressed(KeyEvent e) { return isClosing() || onKey(new KeyInput(e)); }
}

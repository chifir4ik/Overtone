package dev.musicplayer.compat;

import dev.musicplayer.config.PlayerConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Анимация открытия и закрытия окон мода. Лежит в общем коде, а ModScreen каждого поколения игры наследуется
 * от неё вместо Screen и вызывает {@link #frame} из своего метода отрисовки.
 */
public abstract class AnimatedScreen extends Screen {
    private static final float OPEN_NS = 3.4e8f;
    private static final float CLOSE_NS = 2.2e8f;

    private long shownAt;
    private long closeAt;
    private Runnable afterClose;

    protected AnimatedScreen(Component title) { super(title); }

    protected abstract void draw(Canvas g, int mx, int my, float delta);

    /** Окна, которые анимируются при открытии и закрытии, возвращают true. */
    protected boolean animated() { return false; }

    /** Затемнение всего экрана за окном. Рисуется вне анимации, visible от 0 до 1. */
    protected void backdrop(Canvas g, float visible) {}

    @Override
    public void added() {
        super.added();
        shownAt = 0;
        closeAt = 0;
        afterClose = null;
    }

    /** Проигрывает анимацию закрытия и затем выполняет then (обычно открывает родительский экран). */
    protected final void closeAnimated(Runnable then) {
        if (style() == PlayerConfig.WindowAnim.OFF) {
            then.run();
        } else if (closeAt == 0) {
            closeAt = System.nanoTime();
            afterClose = then;
        }
    }

    protected final boolean isClosing() { return closeAt != 0; }

    /** Идёт открытие или закрытие: ванильные виджеты не умеют выцветать, поэтому экраны прячут их на это время. */
    protected final boolean transitioning() {
        return closeAt != 0 || style() != PlayerConfig.WindowAnim.OFF
                && (shownAt == 0 || System.nanoTime() - shownAt < OPEN_NS * 0.6f);
    }

    private PlayerConfig.WindowAnim style() {
        return animated() ? PlayerConfig.get().windowAnim : PlayerConfig.WindowAnim.OFF;
    }

    protected final void frame(Canvas c, int mx, int my, float delta) {
        PlayerConfig.WindowAnim style = style();
        long now = System.nanoTime();
        if (shownAt == 0) shownAt = now;

        if (closeAt != 0 && now - closeAt >= CLOSE_NS) {
            Runnable then = afterClose;
            afterClose = null;
            if (then != null) {
                then.run();
                return;
            }
        }

        float in = style == PlayerConfig.WindowAnim.OFF ? 1f : Math.min(1f, (now - shownAt) / OPEN_NS);
        float out = closeAt == 0 ? 0f : Math.min(1f, (now - closeAt) / CLOSE_NS);
        boolean closing = closeAt != 0;
        float eIn = 1f - (1f - in) * (1f - in) * (1f - in);
        float eOut = out * out * (3f - 2f * out);
        backdrop(c, closing ? 1f - eOut : eIn);

        if (style == PlayerConfig.WindowAnim.OFF || (in >= 1f && !closing)) {
            draw(c, mx, my, delta);
            return;
        }

        float cx = width / 2f, cy = height / 2f;
        float alpha = closing ? 1f - eOut : Math.min(1f, in * 2.2f);
        c.pushMatrix();
        switch (style) {
            case SLIDE -> {
                float k = closing ? 1f - 0.04f * eOut : 0.96f + 0.04f * eIn;
                c.translate(cx, cy + (closing ? eOut * height * 0.1f : (1f - backOut(in, 1.2f)) * height * 0.14f));
                c.scale(k, k);
                c.translate(-cx, -cy);
            }
            case POP -> {
                float k = closing
                        ? 1f - 0.25f * eOut + 0.05f * (float) Math.sin(Math.PI * Math.min(1f, out * 2f))
                        : 0.4f + 0.6f * elasticOut(in);
                c.translate(cx, cy);
                c.scale(k, k);
                c.translate(-cx, -cy);
                alpha = closing ? 1f - eOut : Math.min(1f, in * 4f);
            }
            case UNFOLD -> {
                // как у старого телевизора: яркая линия раскрывается в окно, а при закрытии схлопывается
                float sx, sy;
                if (closing) {
                    sy = Math.max(0.01f, 1f - Math.min(1f, out * 1.6f));
                    sx = 1f - 0.6f * Math.max(0f, out * 2.5f - 1.5f);
                    alpha = 1f - Math.max(0f, out * 3f - 2f);
                } else {
                    float a1 = Math.min(1f, in * 2.5f);
                    float a2 = Math.max(0f, (in - 0.3f) / 0.7f);
                    sx = 0.5f + 0.5f * (1f - (1f - a1) * (1f - a1));
                    sy = Math.max(0.01f, backOut(a2, 1.1f));
                    alpha = Math.min(1f, in * 3f);
                }
                c.translate(cx, cy);
                c.scale(sx, sy);
                c.translate(-cx, -cy);
            }
            default -> {
                float k = closing ? 1f - 0.08f * eOut : 0.86f + 0.14f * backOut(in, 1.4f);
                c.translate(cx, cy + (closing ? eOut * 10f : (1f - eIn) * 14f));
                c.scale(k, k);
                c.translate(-cx, -cy);
            }
        }

        c.setAlpha(alpha);
        draw(c, mx, my, delta);
        c.setAlpha(1f);
        c.popMatrix();
    }

    private static float backOut(float t, float s) {
        float u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    private static float elasticOut(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (Math.pow(2, -9 * t) * Math.sin((t * 6 - 0.5) * Math.PI * 2 / 3) + 1);
    }
}

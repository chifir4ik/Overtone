package dev.musicplayer.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Окно плеера рассчитано на фиксированный размер. При крупном масштабе интерфейса игры экран в «пикселях GUI»
 * получается меньше, и всё слипается. Пока плеер открыт, масштаб временно уменьшается ровно настолько, чтобы окно
 * поместилось, а при закрытии возвращается.
 */
final class GuiFit {
    private static double saved = -1, applied;

    private GuiFit() {}

    static void fit(Screen screen, int needW, int needH) {
        Window w = Minecraft.getInstance().getWindow();
        double cur = w.getGuiScale();
        // после смены размера окна игра сама пересчитывает масштаб: это новое «родное» значение
        if (saved < 0 || cur != applied) saved = cur;
        double want = Math.floor(Math.min((double) w.getWidth() / needW, (double) w.getHeight() / needH));
        want = Math.max(1, Math.min(saved, want));
        // с 1.21.8 setGuiScale(int), раньше setGuiScale(double)
        if (want != cur) w.setGuiScale((int) want);
        applied = w.getGuiScale();
        screen.width = w.getGuiScaledWidth();
        screen.height = w.getGuiScaledHeight();
    }

    static void restore() {
        if (saved < 0) return;
        Minecraft.getInstance().getWindow().setGuiScale((int) saved);
        saved = -1;
    }
}

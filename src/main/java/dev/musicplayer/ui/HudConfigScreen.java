package dev.musicplayer.ui;

import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.compat.Canvas;
import dev.musicplayer.config.HudConfig;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.input.Hotkeys;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.hud.LyricsRenderer;
import dev.musicplayer.hud.VolumeRenderer;
import dev.musicplayer.hud.WorldLyricsRenderer;
import dev.musicplayer.hud.WidgetRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Настройки с живым предпросмотром, построены из ванильных виджетов. */
public class HudConfigScreen extends Screen {
    /** Пока экран открыт, обычный HUD не рисуется — вместо него предпросмотр. */
    public static volatile boolean OPEN;

    private enum Tab {
        WIDGET("Виджет"), LYRICS("Текст"), WORLD("В мире"), VOLUME("Громкость"), PLAYER("Плеер"), KEYS("Клавиши"), OTHER("Прочее");
        final String label;
        Tab(String l) { label = l; }
    }

    private static final int BTN_W = 150, GAP = 8, ROW_H = 22;
    private static final float[] SCALES = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final int[] OPACITIES = {0, 20, 40, 60, 75, 88, 100};
    private static final int[] WIDTHS = {200, 260, 320, 400, 500};
    private static final int[] LINES = {3, 5, 7, 9};
    private static final int[] DIMS = {0, 25, 45, 65, 85};
    private static final int[] RADII = {0, 2, 4, 6, 8};
    private static final int[] SECONDS = {1, 2, 3, 5, 8};
    private static final int[] PREFETCH = {1, 2, 3, 4, 6, 8};
    private static final float[] WORLD_SCALES = {0.6f, 0.8f, 1.0f, 1.3f, 1.6f, 2.0f};
    private static final float[] WORLD_DISTANCES = {2.5f, 3f, 4f, 5f, 6.5f, 8f, 10f, 12f, 14f, 16f};
    private static final float[] WORLD_HEIGHTS = {-1f, -0.5f, 0f, 0.5f, 1f, 2f};

    private static final int PREVIEW_W = BTN_W * 2 + GAP, PREVIEW_H = 60, PREVIEW_Y = 34;

    private final Screen parent;
    private Tab tab = Tab.WIDGET;
    private Hotkeys.Action listening;
    private final Map<Hotkeys.Action, Button> keyButtons = new EnumMap<>(Hotkeys.Action.class);

    public HudConfigScreen(Screen parent) {
        super(Component.literal(MusicPlayerMod.NAME));
        this.parent = parent;
    }

    public static HudConfigScreen playerTab(Screen parent) {
        HudConfigScreen s = new HudConfigScreen(parent);
        s.tab = Tab.PLAYER;
        return s;
    }

    private static HudConfig cfg() { return HudConfig.get(); }
    private static PlayerConfig pcfg() { return PlayerConfig.get(); }

    @Override
    protected void init() {
        OPEN = true;
        int cx = width / 2;
        int left = cx - BTN_W - GAP / 2, right = cx + GAP / 2;

        Tab[] tabs = Tab.values();
        int tw = Math.max(50, Math.min(80, (width - 20 - 4 * (tabs.length - 1)) / tabs.length)), tabsX = cx - (tw * tabs.length + 4 * (tabs.length - 1)) / 2;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            Button b = Button.builder(Component.literal(t.label), btn -> { tab = t; rebuildWidgets(); })
                    .bounds(tabsX + i * (tw + 4), 8, tw, 18).build();
            b.active = t != tab;
            addRenderableWidget(b);
        }

        int top = 34, row = 0;
        HudConfig c = cfg();
        switch (tab) {
            case WIDGET -> {
                row(left, right, top, row++,
                        toggle("Виджет", () -> cfg().enabled, v -> cfg().enabled = v),
                        cycle(() -> "Стиль: " + cfg().layout.label, () -> cfg().layout = HudConfig.next(cfg().layout)));
                row(left, right, top, row++,
                        cycle(() -> "Позиция: " + cfg().anchor.label, () -> cfg().anchor = HudConfig.next(cfg().anchor)),
                        cycle(() -> "Масштаб: " + String.format("%.2fx", cfg().scale), () -> cfg().scale = nextOf(SCALES, cfg().scale)));
                row(left, right, top, row++,
                        cycle(() -> "Фон: " + cfg().backgroundOpacity + "%", () -> cfg().backgroundOpacity = nextOf(OPACITIES, cfg().backgroundOpacity)),
                        cycle(() -> "Цвет: " + cfg().accent.label, () -> cfg().accent = HudConfig.next(cfg().accent)));
                row(left, right, top, row++,
                        cycle(() -> "Показ: " + cfg().showMode.label, () -> cfg().showMode = HudConfig.next(cfg().showMode)),
                        toggle("Обложка", () -> cfg().showCover, v -> cfg().showCover = v));
                row(left, right, top, row++,
                        toggle("Время", () -> cfg().showTimestamps, v -> cfg().showTimestamps = v),
                        toggle("Эквалайзер", () -> cfg().showWaveform, v -> cfg().showWaveform = v));
                row(left, right, top, row++,
                        toggle("Показ при старте", () -> cfg().showOnSongStart, v -> cfg().showOnSongStart = v),
                        toggle("Показ в конце", () -> cfg().showOnSongEnd, v -> cfg().showOnSongEnd = v));
                offsetRow(left, top, row++, "Отступ X", () -> cfg().offsetX, v -> cfg().offsetX = v);
                offsetRow(left, top, row++, "Отступ Y", () -> cfg().offsetY, v -> cfg().offsetY = v);
                dragButton(left, top, row++);
            }
            case LYRICS -> {
                row(left, right, top, row++,
                        cycle(() -> "Текст: " + cfg().lyricsMode.label, () -> cfg().lyricsMode = HudConfig.next(cfg().lyricsMode)),
                        cycle(() -> "Позиция: " + cfg().lyricsAnchor.label, () -> cfg().lyricsAnchor = HudConfig.next(cfg().lyricsAnchor)));
                row(left, right, top, row++,
                        cycle(() -> "Масштаб: " + String.format("%.2fx", cfg().lyricsScale), () -> cfg().lyricsScale = nextOf(SCALES, cfg().lyricsScale)),
                        cycle(() -> "Ширина: " + cfg().lyricsWidth, () -> cfg().lyricsWidth = nextOf(WIDTHS, cfg().lyricsWidth)));
                row(left, right, top, row++,
                        cycle(() -> "Фон: " + cfg().lyricsBackground + "%", () -> cfg().lyricsBackground = nextOf(OPACITIES, cfg().lyricsBackground)),
                        cycle(() -> "Строк (караоке): " + cfg().lyricsLines, () -> cfg().lyricsLines = nextOf(LINES, cfg().lyricsLines)));
                row(left, right, top, row++,
                        toggle("Цвет акцента", () -> cfg().lyricsAccent, v -> cfg().lyricsAccent = v),
                        toggle("Тень текста", () -> cfg().lyricsShadow, v -> cfg().lyricsShadow = v));
                row(left, right, top, row++,
                        toggle("Текст в карточке", () -> cfg().cardLyrics, v -> cfg().cardLyrics = v),
                        toggle("Караоке по словам", () -> cfg().lyricsWordKaraoke, v -> cfg().lyricsWordKaraoke = v));
                offsetRow(left, top, row++, "Отступ X", () -> cfg().lyricsOffsetX, v -> cfg().lyricsOffsetX = v);
                offsetRow(left, top, row++, "Отступ Y", () -> cfg().lyricsOffsetY, v -> cfg().lyricsOffsetY = v);
                // если текст опаздывает — сдвиньте в минус, если спешит — в плюс
                stepRow(left, top, row++, "Сдвиг текста", " мс", new int[]{-100, -50, 50, 100}, -5000, 5000,
                        () -> cfg().lyricsShiftMs, v -> cfg().lyricsShiftMs = v);
                dragButton(left, top, row++);
            }
            case WORLD -> {
                row(left, right, top, row++,
                        toggle("Летающий текст", () -> cfg().worldLyrics, v -> cfg().worldLyrics = v),
                        cycle(() -> "Режим: " + cfg().worldMode.label, () -> cfg().worldMode = HudConfig.next(cfg().worldMode)));
                row(left, right, top, row++,
                        cycle(() -> "Размер: " + String.format("%.1fx", cfg().worldScale), () -> cfg().worldScale = nextOf(WORLD_SCALES, cfg().worldScale)),
                        cycle(() -> "Дистанция: " + String.format("%.1f", cfg().worldDistance), () -> cfg().worldDistance = nextOf(WORLD_DISTANCES, cfg().worldDistance)));
                row(left, right, top, row++,
                        cycle(() -> "Высота: " + String.format("%+.1f", cfg().worldHeight), () -> cfg().worldHeight = nextOf(WORLD_HEIGHTS, cfg().worldHeight)),
                        toggle("Сквозь блоки", () -> cfg().worldSeeThrough, v -> cfg().worldSeeThrough = v));
                addRenderableWidget(cycle(() -> "Следование: " + cfg().worldFollowStyle.label,
                        () -> cfg().worldFollowStyle = HudConfig.next(cfg().worldFollowStyle))
                        .bounds(left, top + row++ * ROW_H, BTN_W * 2 + GAP, 20).build());
                row(left, right, top, row++,
                        toggle("Тень текста", () -> cfg().worldShadow, v -> cfg().worldShadow = v),
                        cycle(() -> "Слова: " + cfg().worldLayout.label, () -> { cfg().worldLayout = HudConfig.next(cfg().worldLayout); WorldLyricsRenderer.demo(); }));
                addRenderableWidget(Button.builder(Component.literal("Показать пример перед собой"),
                        b -> WorldLyricsRenderer.demo())
                        .bounds(left, top + row++ * ROW_H, BTN_W * 2 + GAP, 20).build());
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 4, BTN_W * 2 + GAP, 10,
                        Component.literal("Слова песни летят в мире по одному, когда их поют. Клавиша — во вкладке «Клавиши»."), font));
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 2, BTN_W * 2 + GAP, 10,
                        Component.literal("Если у трека нет текста с таймкодами — остаётся обычный HUD-текст."), font));
            }
            case VOLUME -> {
                row(left, right, top, row++,
                        toggle("Плашка громкости", () -> cfg().volumeEnabled, v -> cfg().volumeEnabled = v),
                        cycle(() -> "Вид: " + cfg().volumeStyle.label, () -> cfg().volumeStyle = HudConfig.next(cfg().volumeStyle)));
                row(left, right, top, row++,
                        cycle(() -> "Позиция: " + cfg().volumeAnchor.label, () -> cfg().volumeAnchor = HudConfig.next(cfg().volumeAnchor)),
                        cycle(() -> "Масштаб: " + String.format("%.2fx", cfg().volumeScale), () -> cfg().volumeScale = nextOf(SCALES, cfg().volumeScale)));
                row(left, right, top, row++,
                        cycle(() -> "Фон: " + cfg().volumeBackground + "%", () -> cfg().volumeBackground = nextOf(OPACITIES, cfg().volumeBackground)),
                        cycle(() -> "Показ: " + cfg().volumeSeconds + " с", () -> cfg().volumeSeconds = nextOf(SECONDS, cfg().volumeSeconds)));
                row(left, right, top, row++,
                        toggle("Проценты", () -> cfg().volumePercent, v -> cfg().volumePercent = v),
                        cycle(() -> "Цвет: " + cfg().accent.label, () -> cfg().accent = HudConfig.next(cfg().accent)));
                offsetRow(left, top, row++, "Отступ X", () -> cfg().volumeOffsetX, v -> cfg().volumeOffsetX = v);
                offsetRow(left, top, row++, "Отступ Y", () -> cfg().volumeOffsetY, v -> cfg().volumeOffsetY = v);
                dragButton(left, top, row++);
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 4, BTN_W * 2 + GAP, 10,
                        Component.literal("Плашка появляется при смене громкости клавишами (см. вкладку «Клавиши»)."), font));
            }
            case PLAYER -> {
                top = PREVIEW_Y + PREVIEW_H + 10;
                row(left, right, top, row++,
                        toggle("Цвет как у виджета", () -> pcfg().syncAccent, v -> pcfg().syncAccent = v),
                        cycle(() -> "Цвет: " + (pcfg().syncAccent ? cfg().accent.label + " (виджет)" : pcfg().accent.label), () -> {
                            if (pcfg().syncAccent) pcfg().syncAccent = false;   // выбор своего цвета отключает синхронизацию
                            pcfg().accent = HudConfig.next(pcfg().accent);
                            rebuildWidgets();
                        }));
                row(left, right, top, row++,
                        cycle(() -> "Подсветка фона: " + pcfg().tint * 5 + "%", () -> pcfg().tint = (pcfg().tint + 1) % 4),
                        cycle(() -> "Затемнение: " + pcfg().dim + "%", () -> pcfg().dim = nextOf(DIMS, pcfg().dim)));
                row(left, right, top, row++,
                        cycle(() -> "Скругление: " + pcfg().cornerRadius, () -> pcfg().cornerRadius = nextOf(RADII, pcfg().cornerRadius)),
                        cycle(() -> "Окно: " + pcfg().size.label, () -> pcfg().size = HudConfig.next(pcfg().size)));
                row(left, right, top, row++,
                        toggle("Обложка", () -> pcfg().showCover, v -> pcfg().showCover = v),
                        cycle(() -> "Размер обложки: " + pcfg().coverSize.label, () -> pcfg().coverSize = HudConfig.next(pcfg().coverSize)));
                row(left, right, top, row++,
                        toggle("Текст песни", () -> pcfg().showLyrics, v -> pcfg().showLyrics = v),
                        toggle("Название цветом", () -> pcfg().accentText, v -> pcfg().accentText = v));
                addRenderableWidget(Button.builder(Component.literal("Сбросить настройки плеера"), b -> {
                    PlayerConfig.resetToDefaults();
                    rebuildWidgets();
                }).bounds(left, top + row++ * ROW_H, BTN_W * 2 + GAP, 20).build());
            }
            case KEYS -> {
                keyButtons.clear();
                for (Hotkeys.Action a : Hotkeys.Action.values()) {
                    Button b = Button.builder(keyLabel(a), btn -> { listening = a; refreshKeyButtons(); })
                            .bounds(left, top + row++ * ROW_H, BTN_W * 2 + GAP, 20).build();
                    keyButtons.put(a, b);
                    addRenderableWidget(b);
                }
                addRenderableWidget(Button.builder(Component.literal("Сбросить клавиши"), b -> {
                    Hotkeys.resetAll();
                    listening = null;
                    refreshKeyButtons();
                }).bounds(left, top + row++ * ROW_H, BTN_W * 2 + GAP, 20).build());
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 4, BTN_W * 2 + GAP, 10,
                        Component.literal("Клик по кнопке, затем клавиша. Esc — отмена, Delete — снять."), font));
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 2, BTN_W * 2 + GAP, 10,
                        Component.literal("Работают в игре и в окне плеера; видны и в ванильном меню «Управление»."), font));
            }
            case OTHER -> {
                row(left, right, top, row++,
                        toggle("Весь HUD (клавиша H)", () -> cfg().master, v -> cfg().master = v),
                        toggle("Прятать при открытом меню", () -> cfg().hideWithGui, v -> cfg().hideWithGui = v));
                row(left, right, top, row++,
                        toggle("Громкость игры", () -> pcfg().useGameVolume, v -> pcfg().useGameVolume = v),
                        toggle("Глушить при паузе", () -> pcfg().duckWhenInactive, v -> pcfg().duckWhenInactive = v));
                row(left, right, top, row++,
                        toggle("Предзагрузка", () -> pcfg().prefetch, v -> { pcfg().prefetch = v; PlayerController.I.refreshPrefetch(); }),
                        cycle(() -> "Вперёд: " + pcfg().prefetchCount, () -> { pcfg().prefetchCount = nextOf(PREFETCH, pcfg().prefetchCount); PlayerController.I.refreshPrefetch(); }));
                row(left, right, top, row++,
                        toggle("Кэш на диске", () -> pcfg().diskCache, v -> pcfg().diskCache = v),
                        Button.builder(Component.literal("Очистить кэш (" + PlayerController.I.yandex.cacheMegabytes() + " МБ)"), b -> {
                            PlayerController.I.yandex.clearCache();
                            rebuildWidgets();
                        }));
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 4, BTN_W * 2 + GAP, 10,
                        Component.literal("Для медленного интернета: следующие треки качаются заранее, повторы — без ожидания."), font));
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 4, BTN_W * 2 + GAP, 10,
                        Component.literal("Громкость игры = Основная × Музыка из настроек звука Minecraft."), font));
                addRenderableWidget(Button.builder(Component.literal("Сбросить все настройки HUD"), b -> {
                    HudConfig.resetToDefaults();
                    rebuildWidgets();
                }).bounds(left, top + row++ * ROW_H, BTN_W * 2 + GAP, 20).build());
                addRenderableWidget(new StringWidget(left, top + row++ * ROW_H + 4, BTN_W * 2 + GAP, 10,
                        Component.literal("M — плеер, H — показать/скрыть HUD, K — эти настройки"), font));
                int aboutY = top + row++ * ROW_H;
                addRenderableWidget(new StringWidget(left, aboutY + 2, BTN_W * 2 + GAP, 10,
                        Component.literal(MusicPlayerMod.NAME + " — авторы: " + MusicPlayerMod.AUTHORS), font));
                addRenderableWidget(new StringWidget(left, aboutY + 13, BTN_W * 2 + GAP, 10,
                        Component.literal("Дизайн: SpotifyMusicMod (Kart-Modding, MIT)"), font));
            }
        }

        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
                .bounds(cx - 75, Math.min(height - 26, top + row * ROW_H + 8), 150, 20).build());
    }

    private void dragButton(int left, int top, int row) {
        addRenderableWidget(Button.builder(Component.literal("Перетащить мышью…"),
                b -> Minecraft.getInstance().gui.setScreen(new HudEditScreen(this)))
                .bounds(left, top + row * ROW_H, BTN_W * 2 + GAP, 20).build());
    }

    private void row(int left, int right, int top, int row, Button.Builder a, Button.Builder b) {
        int y = top + row * ROW_H;
        addRenderableWidget(a.bounds(left, y, BTN_W, 20).build());
        addRenderableWidget(b.bounds(right, y, BTN_W, 20).build());
    }

    private void offsetRow(int left, int top, int row, String name, IntSupplier get, IntConsumer set) {
        int y = top + row * ROW_H, total = BTN_W * 2 + GAP, small = 34, gap = 2;
        int centerW = total - 4 * small - 4 * gap;
        Button[] center = new Button[1];
        center[0] = Button.builder(Component.literal(name + ": " + get.getAsInt()), b -> {}).bounds(left + 2 * (small + gap), y, centerW, 20).build();
        center[0].active = false;

        int[] deltas = {-5, -1, 1, 5};
        int[] xs = {left, left + small + gap, left + 2 * (small + gap) + centerW + gap, left + 3 * small + 3 * gap + centerW + gap};
        for (int i = 0; i < 4; i++) {
            int d = deltas[i];
            addRenderableWidget(Button.builder(Component.literal(d > 0 ? "+" + d : String.valueOf(d)), b -> {
                set.accept(Math.max(-600, Math.min(600, get.getAsInt() + d)));
                center[0].setMessage(Component.literal(name + ": " + get.getAsInt()));
            }).bounds(xs[i], y, small, 20).build());
        }
        addRenderableWidget(center[0]);
    }

    private void stepRow(int left, int top, int row, String name, String unit, int[] deltas, int min, int max,
                         IntSupplier get, IntConsumer set) {
        int y = top + row * ROW_H, total = BTN_W * 2 + GAP, small = 40, gap = 2;
        int centerW = total - 4 * small - 4 * gap;
        Button[] center = new Button[1];
        center[0] = Button.builder(Component.literal(name + ": " + signed(get.getAsInt()) + unit), b -> {})
                .bounds(left + 2 * (small + gap), y, centerW, 20).build();
        center[0].active = false;
        int[] xs = {left, left + small + gap, left + 2 * (small + gap) + centerW + gap, left + 3 * small + 3 * gap + centerW + gap};
        for (int i = 0; i < 4; i++) {
            int d = deltas[i];
            addRenderableWidget(Button.builder(Component.literal(signed(d)), b -> {
                set.accept(Math.max(min, Math.min(max, get.getAsInt() + d)));
                center[0].setMessage(Component.literal(name + ": " + signed(get.getAsInt()) + unit));
            }).bounds(xs[i], y, small, 20).build());
        }
        addRenderableWidget(center[0]);
    }

    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    private Button.Builder toggle(String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return Button.builder(Component.literal(label + ": " + onOff(getter.getAsBoolean())), b -> {
            boolean v = !getter.getAsBoolean();
            setter.accept(v);
            b.setMessage(Component.literal(label + ": " + onOff(v)));
        });
    }

    private Button.Builder cycle(Supplier<String> label, Runnable action) {
        return Button.builder(Component.literal(label.get()), b -> {
            action.run();
            b.setMessage(Component.literal(label.get()));
        });
    }

    private Component keyLabel(Hotkeys.Action a) {
        return Component.literal(a.label + ": " + (listening == a ? "> нажмите клавишу <" : Hotkeys.keyName(a)));
    }

    private void refreshKeyButtons() {
        keyButtons.forEach((a, b) -> b.setMessage(keyLabel(a)));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tab == Tab.KEYS && listening != null) {
            int k = event.key();
            Hotkeys.Action a = listening;
            listening = null;
            if (k != 256) Hotkeys.setKey(a, (k == 261 || k == 259) ? -1 : k);   // Esc — отмена, Delete/Backspace — снять
            refreshKeyButtons();
            return true;
        }
        return super.keyPressed(event);
    }

    private static String onOff(boolean v) { return v ? "ВКЛ" : "ВЫКЛ"; }

    private static float nextOf(float[] vals, float cur) {
        for (int i = 0; i < vals.length; i++) if (Math.abs(vals[i] - cur) < 0.001f) return vals[(i + 1) % vals.length];
        return vals[0];
    }

    private static int nextOf(int[] vals, int cur) {
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) return vals[(i + 1) % vals.length];
        return vals[0];
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (tab == Tab.PLAYER) {
            PlayerTheme.miniPreview(g, font, (width - PREVIEW_W) / 2, PREVIEW_Y, PREVIEW_W, PREVIEW_H);
        } else if (tab == Tab.WORLD) {
            // текст летает в самом мире за этим экраном — отдельного предпросмотра не нужно
        } else if (tab == Tab.VOLUME) {
            VolumeRenderer.render(new Canvas(g), true);
        } else if (tab != Tab.KEYS) {
            Canvas canvas = new Canvas(g);
            WidgetRenderer.render(canvas, true);
            LyricsRenderer.render(canvas, true);
        }
    }

    @Override
    public void removed() {
        OPEN = false;   // при переходе в редактор его init() включит флаг снова
        super.removed();
    }

    @Override
    public void onClose() {
        OPEN = false;
        cfg().save();
        pcfg().save();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

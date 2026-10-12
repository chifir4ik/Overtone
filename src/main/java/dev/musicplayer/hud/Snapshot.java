package dev.musicplayer.hud;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.lyrics.LrcParser;
import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;

import java.util.ArrayList;
import java.util.List;

/** Мгновенный снимок состояния плеера для отрисовки (или демо-данные для предпросмотра в настройках). */
public final class Snapshot {
    public String id, title, artist, coverUrl;
    public long durationMs, posMs;
    public boolean playing;
    public List<LyricLine> lyrics = List.of();
    public boolean synced;

    private static List<LyricLine> demoLyrics;

    public static Snapshot live() {
        PlayerController ctl = PlayerController.I;
        Track t = ctl.current;
        if (t == null || ctl.idle) return null;
        Snapshot s = new Snapshot();
        s.id = t.id();
        s.title = t.title() == null ? "" : t.title();
        s.artist = t.artist() == null ? "" : t.artist();
        s.coverUrl = t.coverUrl();
        s.durationMs = t.durationMs();
        s.posMs = ctl.yandex.positionMs();
        s.playing = ctl.yandex.isPlaying();
        s.lyrics = ctl.lyrics;
        s.synced = !s.lyrics.isEmpty() && s.lyrics.get(0).timeMs() != LyricLine.UNSYNCED;
        return s;
    }

    public static Snapshot liveOrDemo() {
        Snapshot s = live();
        return s != null ? s : demo();
    }

    public static Snapshot demo() {
        if (demoLyrics == null) {
            String[] text = {
                    "Здесь будет текст песни", "Строки подсвечиваются по времени", "Настрой позицию, стиль и размер",
                    "Всё видно сразу, пока ты настраиваешь", "Яндекс Музыка прямо в Minecraft", "♪",
                    "Эта строка чуть длиннее, чтобы проверить перенос длинного текста по словам", "Спасибо, что слушаешь"};
            List<LyricLine> l = new ArrayList<>();
            for (int i = 0; i < text.length; i++) l.add(new LyricLine(60_000L + i * 4000L, text[i]));
            demoLyrics = l;
        }
        Snapshot s = new Snapshot();
        s.id = "demo";
        s.title = "Название трека";
        s.artist = "Исполнитель";
        s.durationMs = 215_000;
        s.posMs = 60_000L + System.currentTimeMillis() % 32_000L;
        s.playing = true;
        s.lyrics = demoLyrics;
        s.synced = true;
        return s;
    }

    public int lyricIndex() { return synced ? LrcParser.currentIndex(lyrics, posMs) : -1; }

    public double fraction() { return durationMs <= 0 ? 0 : Math.max(0, Math.min(1, posMs / (double) durationMs)); }

    public static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    public String timeLabel() { return fmt(posMs) + " / " + fmt(durationMs); }
}

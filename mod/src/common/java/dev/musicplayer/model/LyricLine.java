package dev.musicplayer.model;

import java.util.List;

/**
 * Одна строка текста с временной меткой начала (мс). UNSYNCED — строка без таймкода.
 * {@code words} — тайминги отдельных слов, если они есть в самом тексте (расширенный LRC с тегами
 * {@code <mm:ss.xx>}); иначе null, и слова размечаются по длине строки (см. WorldLyricsRenderer).
 */
public record LyricLine(long timeMs, String text, List<Word> words) {
    public static final long UNSYNCED = Long.MAX_VALUE;

    public record Word(long timeMs, String text) {}

    public LyricLine(long timeMs, String text) { this(timeMs, text, null); }

    public boolean hasWordTimings() { return words != null && !words.isEmpty(); }
}

package dev.musicplayer.lyrics;

import dev.musicplayer.model.LyricLine;

/**
 * Тайминги слов строки в абсолютном времени трека. Если в тексте есть теги слов (расширенный LRC) — берутся они,
 * иначе длительность строки делится между словами пропорционально их длине (так же, как у летающего текста в мире).
 */
public final class WordTimings {
    public final String[] words;
    public final long[] start, end;
    /** true — тайминги настоящие (из тегов слов), false — оценка по длине слов. */
    public final boolean real;

    private WordTimings(String[] words, long[] start, long[] end, boolean real) {
        this.words = words; this.start = start; this.end = end; this.real = real;
    }

    public static WordTimings of(LyricLine line, LyricLine next) {
        if (line == null || line.text() == null || line.timeMs() == LyricLine.UNSYNCED) return null;
        long nextGap = next != null && next.timeMs() != LyricLine.UNSYNCED ? Math.max(1, next.timeMs() - line.timeMs()) : 5000;

        if (line.hasWordTimings()) {
            var src = line.words();
            int n = src.size();
            String[] w = new String[n];
            long[] s = new long[n], e = new long[n];
            for (int i = 0; i < n; i++) {
                w[i] = src.get(i).text();
                s[i] = src.get(i).timeMs();
                long end = i + 1 < n ? src.get(i + 1).timeMs() : Math.min(line.timeMs() + nextGap - 100, s[i] + 1500);
                e[i] = Math.max(s[i] + 140, end);
            }
            return new WordTimings(w, s, e, true);
        }

        String t = line.text().trim();
        if (t.isEmpty()) return null;
        String[] parts = t.split("\\s+");
        int chars = 0;
        for (String p : parts) chars += Math.max(1, p.length());
        long usable = Math.max(400, Math.min(nextGap - 120, Math.max(1400L, chars * 80L)));
        long[] s = new long[parts.length], e = new long[parts.length];
        int before = 0;
        for (int i = 0; i < parts.length; i++) {
            int len = Math.max(1, parts[i].length());
            s[i] = line.timeMs() + usable * before / chars;
            e[i] = s[i] + Math.max(140, usable * len / chars);
            before += len;
        }
        return new WordTimings(parts, s, e, false);
    }
}

package dev.musicplayer.lyrics;

import dev.musicplayer.config.HudConfig;
import dev.musicplayer.model.LyricLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Парсер синхронизированных текстов формата LRC: [mm:ss.xx] строка */
public final class LrcParser {
    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    /** Тег времени слова в расширенном LRC: <mm:ss.xx> */
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");

    private static final Pattern OFFSET = Pattern.compile("^\\s*\\[offset:\\s*([+-]?\\d{1,6})\\s*]", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private LrcParser() {}

    public static List<LyricLine> plain(String text) {
        List<LyricLine> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\\R")) out.add(new LyricLine(LyricLine.UNSYNCED, line));
        return out;
    }

    public static List<LyricLine> parse(String lrc) {
        List<LyricLine> out = new ArrayList<>();
        if (lrc == null) return out;
        // [offset:+300] — по спецификации LRC положительное значение сдвигает текст раньше
        long offset = 0;
        Matcher om = OFFSET.matcher(lrc);
        if (om.find()) offset = Long.parseLong(om.group(1));
        for (String raw : lrc.split("\\R")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {          // теги только в начале строки
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                times.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String body = raw.substring(end);
            if (WORD_TAG.matcher(body).find()) {
                for (long t : times) out.add(parseWords(Math.max(0, t - offset), body, offset));
                continue;
            }
            String text = body.trim();
            for (long t : times) out.add(new LyricLine(Math.max(0, t - offset), text));
        }
        out.sort(Comparator.comparingLong(LyricLine::timeMs));
        return out;
    }

    private static long tagMs(Matcher m) {
        long ms = 0;
        String frac = m.group(3);
        if (frac != null) {
            ms = Long.parseLong(frac);
            if (frac.length() == 1) ms *= 100;
            else if (frac.length() == 2) ms *= 10;
        }
        return Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000 + ms;
    }

    /**
     * Строка с тегами слов: «<00:12.30>Hel<00:12.60>lo <00:13.00>world». Слоги без пробела склеиваются в слово,
     * слово начинается в момент тега, под которым стоит его первая буква.
     */
    private static LyricLine parseWords(long lineMs, String body, long offset) {
        StringBuilder plain = new StringBuilder();
        List<long[]> marks = new ArrayList<>();            // {индекс символа в plain, время мс}
        Matcher m = WORD_TAG.matcher(body);
        int last = 0;
        while (m.find()) {
            plain.append(body, last, m.start());
            marks.add(new long[]{plain.length(), Math.max(0, tagMs(m) - offset)});
            last = m.end();
        }
        plain.append(body.substring(last));

        String text = plain.toString();
        List<LyricLine.Word> words = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > start) {
                long t = lineMs;
                for (long[] mk : marks) if (mk[0] <= start) t = mk[1]; else break;
                words.add(new LyricLine.Word(Math.max(lineMs, t), text.substring(start, i)));
            }
        }
        return new LyricLine(lineMs, text.trim(), words.isEmpty() ? null : words);
    }

    public static int currentIndex(List<LyricLine> lines, long positionMs) {
        positionMs -= TrackLyrics.totalShift();
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= positionMs) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }
}

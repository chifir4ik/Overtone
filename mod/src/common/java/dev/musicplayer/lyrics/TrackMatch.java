package dev.musicplayer.lyrics;

import dev.musicplayer.model.LyricLine;
import dev.musicplayer.model.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сверка найденной в интернете песни с играющим треком: лучше пустой текст, чем чужой. */
final class TrackMatch {
    private TrackMatch() {}

    enum Level {
        NONE,
        /** Название то же, исполнитель отличается (типично для SoundCloud, где «исполнитель» — это имя канала). */
        LOOSE,
        FULL
    }

    record Query(String title, String artist) {}

    private static final Pattern ARTIST_DASH_TITLE = Pattern.compile("^(.+?)\\s+[-–—]\\s+(.+)$");
    private static final String SPLIT = ",|&| feat\\.| ft\\.| x ";

    /** Как есть, плюс разбор заголовка вида «Исполнитель - Название», который на SoundCloud пишут вместо тегов. */
    static List<Query> queries(Track t) {
        List<Query> out = new ArrayList<>();
        out.add(new Query(t.title(), t.artist()));
        Matcher m = ARTIST_DASH_TITLE.matcher(t.title() == null ? "" : t.title());
        if (m.matches()) out.add(new Query(m.group(2).trim(), m.group(1).trim()));
        return out;
    }

    static Level level(String foundTitle, String foundArtist, Track t) {
        Level best = Level.NONE;
        for (Query q : queries(t)) {
            if (!titleOk(foundTitle, q.title())) continue;
            if (artistOk(foundArtist, q.artist())) return Level.FULL;
            if (norm(cleanTitle(foundTitle)).equals(norm(cleanTitle(q.title())))) best = Level.LOOSE;
        }
        return best;
    }

    /** Допустимая разница длительностей, с. У LOOSE строже, потому что исполнителю уже не верим. */
    static boolean durationFits(double diff, double max, Level level) {
        if (level == Level.LOOSE) return !Double.isNaN(diff) && diff <= Math.min(max, 3);
        return Double.isNaN(diff) || diff <= max;
    }

    static double durationDiff(double foundSeconds, Track t) {
        if (t.durationMs() <= 0 || foundSeconds <= 0) return Double.NaN;
        return Math.abs(foundSeconds - t.durationMs() / 1000.0);
    }

    /** Название на кириллице — значит и текст должен быть в основном на кириллице, а не на английском. */
    static boolean scriptOk(String title, List<LyricLine> lines) {
        if (!hasCyrillic(title)) return true;
        int cyr = 0, letters = 0;
        for (LyricLine l : lines) {
            for (char c : l.text().toCharArray()) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) { cyr++; letters++; }
                else if (Character.isLetter(c)) letters++;
            }
        }
        return letters == 0 || cyr >= letters * 0.2;
    }

    static boolean titleOk(String found, String wanted) {
        return similar(norm(cleanTitle(found)), norm(cleanTitle(wanted))) || similar(norm(found), norm(wanted));
    }

    /** Совпадает хотя бы один из исполнителей трека (в списке «A, B & C»). */
    static boolean artistOk(String found, String wanted) {
        String f = norm(found);
        if (f.isEmpty()) return false;
        for (String name : (wanted == null ? "" : wanted).split(SPLIT)) {
            String n = norm(name);
            if (!n.isEmpty() && similar(f, n)) return true;
        }
        return similar(f, norm(wanted));
    }

    /** Равны, либо одна строка входит в другую и не короче её половины («Звезда» ≠ «Звезда по имени Солнце»). */
    private static boolean similar(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b, longer = shorter == a ? b : a;
        return longer.contains(shorter) && shorter.length() * 2 >= longer.length();
    }

    private static boolean hasCyrillic(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) return true;
        return false;
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(SPLIT)[0].trim();
        return a.isEmpty() ? artist : a;
    }

    private static final Pattern SLOWER = Pattern.compile("(?i)\\b(slowed|daycore)\\b");
    private static final Pattern FASTER = Pattern.compile("(?i)\\b(sped|speed)\\s*up\\b|\\bnightcore\\b");

    /** -1 — замедленная версия (slowed), 1 — ускоренная (sped up, nightcore), 0 — обычная. */
    static int tempo(String title) {
        if (title == null) return 0;
        if (SLOWER.matcher(title).find()) return -1;
        return FASTER.matcher(title).find() ? 1 : 0;
    }

    /** Убирает хвосты вроде «(Remix)», «[Live]», «- Radio Edit», «feat. …». */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String c = title.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")
                .replaceAll("(?i)\\s+(feat\\.|ft\\.|featuring)\\s.*$", "")
                .replaceAll("\\s+-\\s+.*$", "")
                .trim();
        return c.isEmpty() ? title : c;
    }
}

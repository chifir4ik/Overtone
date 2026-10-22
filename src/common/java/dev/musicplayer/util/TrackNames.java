package dev.musicplayer.util;

import java.util.regex.Pattern;

/** Приводит названия с видеохостингов к виду «исполнитель — трек», чтобы по ним находились тексты. */
public final class TrackNames {
    private TrackNames() {}

    public record Parsed(String artist, String title) {}

    private static final Pattern NOISE = Pattern.compile(
            "\\s*[(\\[]\\s*(official|lyric|lyrics|audio|video|music video|visuali[sz]er|hd|hq|4k|clip|клип|премьера|текст)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static Parsed split(String rawTitle, String uploader) {
        String title = NOISE.matcher(rawTitle == null ? "" : rawTitle).replaceAll("").trim();
        String artist = cleanUploader(uploader);
        for (String sep : new String[]{" - ", " – ", " — "}) {
            int i = title.indexOf(sep);
            if (i > 0 && i <= 60 && i + sep.length() < title.length()) {
                return new Parsed(title.substring(0, i).trim(), title.substring(i + sep.length()).trim());
            }
        }
        return new Parsed(artist, title);
    }

    /** «Artist - Topic» и «ArtistVEVO» — служебные названия каналов, а не имя исполнителя. */
    public static String cleanUploader(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.endsWith(" - Topic")) s = s.substring(0, s.length() - " - Topic".length());
        if (s.length() > 4 && s.toUpperCase().endsWith("VEVO")) s = s.substring(0, s.length() - 4);
        return s.trim();
    }
}

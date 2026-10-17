package dev.musicplayer.model;

/**
 * Артист, альбом или плейлист: то, что можно открыть и увидеть внутри треки.
 * Для плейлиста id имеет вид «владелец:номер».
 */
public record MusicEntity(Kind kind, String id, String title, String subtitle, String coverUrl) {
    public enum Kind { ARTIST, ALBUM, PLAYLIST }

    /** Служебная строка «Мои плейлисты» в списке плейлистов: название подставляется из языковых файлов. */
    public static final MusicEntity MINE = new MusicEntity(Kind.PLAYLIST, "mine", "", "", null);

    public boolean isMine() { return this == MINE; }
}

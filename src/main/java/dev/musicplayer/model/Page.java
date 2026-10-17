package dev.musicplayer.model;

import java.util.List;

/** Открытая страница: треки и/или вложенные альбомы или плейлисты (у артиста есть и то и другое). */
public record Page(MusicEntity source, List<Track> tracks, List<MusicEntity> entities) {}

package dev.musicplayer.model;

/** Трек. durationMs нужен, чтобы сверять версию при поиске текста. */
public record Track(String id, String title, String artist, String album,
                    long durationMs, String coverUrl, String provider) {}

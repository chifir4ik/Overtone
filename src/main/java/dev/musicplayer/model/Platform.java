package dev.musicplayer.model;

import dev.musicplayer.util.T;

public enum Platform {
    YANDEX("yandex", 0xFFCC00, true),
    SOUNDCLOUD("soundcloud", 0xFF5500, false),
    LOCAL("local", 0x34D399, false);

    public final String id;
    public final int rgb;
    public final boolean needsLogin;

    Platform(String id, int rgb, boolean needsLogin) {
        this.id = id; this.rgb = rgb; this.needsLogin = needsLogin;
    }

    public String title() { return T.get("platform." + id); }

    /** Треки из старых сохранений приходят без площадки и считаются яндексовскими. */
    public static Platform byId(String id) {
        Platform p = byIdOrNull(id);
        return p != null ? p : YANDEX;
    }

    public static Platform byIdOrNull(String id) {
        for (Platform p : values()) if (p.id.equals(id)) return p;
        return null;
    }
}

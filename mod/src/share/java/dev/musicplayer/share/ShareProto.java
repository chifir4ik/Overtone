package dev.musicplayer.share;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сообщения «наушника» между модом и сервером. Тот же файл лежит в плагине для Paper, поэтому здесь только чистая Java.
 * <p>
 * Наружу уходит лишь то, что видно в плеере: площадка, ID трека, название, исполнитель, обложка и позиция.
 * Ссылки на поток (у Яндекса в них подпись аккаунта), токены и пути к локальным файлам сюда не попадают никогда.
 */
public final class ShareProto {
    private ShareProto() {}

    public static final String CHANNEL = "overtone:share";
    public static final int VERSION = 2;
    /** От клиента: с запасом меньше лимита Minecraft (32 КБ). */
    public static final int MAX_BYTES = 4096;
    /** От сервера: список игроков с Overtone на большом сервере бывает длиннее. */
    public static final int MAX_PACKET = 65536;
    public static final int MAX_STR = 200;
    public static final int MAX_ROSTER = 2000;
    public static final int MAX_LISTENERS = 16;

    // клиент → сервер
    public static final int HELLO = 1, INVITE = 2, ANSWER = 3, STATE = 4, LEAVE = 5, KICK = 6;
    // сервер → клиент
    public static final int ROSTER = 20, ROSTER_ADD = 21, ROSTER_REMOVE = 22, INVITED = 23, ANSWERED = 24,
            HOST_STATE = 25, ENDED = 26, LISTENERS = 27, DENIED = 28;

    /** Почему приглашение не дошло. */
    public static final int DENY_FAR = 1, DENY_COOLDOWN = 2, DENY_UNAVAILABLE = 3, DENY_FULL = 4, DENY_LISTENING = 5, DENY_ALREADY = 6;

    public static final UUID NOBODY = new UUID(0, 0);

    /** Что играет у раздающего. platform пустой — ничего не играет. */
    public record State(String platform, String id, String title, String artist, String album, long durationMs,
                        String cover, long positionMs, boolean playing) {
        public static final State NONE = new State("", "", "", "", "", 0, "", 0, false);

        public boolean empty() { return platform.isEmpty(); }

        /** Один и тот же трек: у локальных файлов ID не передаётся, их узнаём по названию. */
        public String key() { return platform + ":" + (id.isEmpty() ? artist + "\u0000" + title : id); }

        State clean() {
            return new State(str(platform, 16), str(id, 64), str(title, MAX_STR), str(artist, MAX_STR), str(album, MAX_STR),
                    Math.max(0, Math.min(durationMs, 24L * 3600_000)), cleanCover(cover),
                    Math.max(0, Math.min(positionMs, 24L * 3600_000)), playing);
        }
    }

    public record Listener(UUID id, String name) {}

    /** Игрок с Overtone и цвет его значка в табе: у каждого свой, как акцент в его плеере. */
    public record Badge(UUID id, int rgb) {}

    public static final class Msg {
        public final int type;
        public UUID who = NOBODY;
        public String name = "";
        public boolean flag;
        public int reason, rgb;
        public State state = State.NONE;
        public List<Badge> badges = List.of();
        public List<Listener> listeners = List.of();

        public Msg(int type) { this.type = type; }
    }

    public static byte[] hello(boolean visible, int rgb) { return write(HELLO, o -> { o.writeBoolean(visible); o.writeInt(rgb & 0xFFFFFF); }); }
    public static byte[] invite(UUID target) { return write(INVITE, o -> uuid(o, target)); }
    public static byte[] answer(UUID host, boolean accept) { return write(ANSWER, o -> { uuid(o, host); o.writeBoolean(accept); }); }
    public static byte[] state(State s) { return write(STATE, o -> state(o, s)); }
    public static byte[] leave() { return write(LEAVE, o -> { }); }
    /** NOBODY — отключить всех слушателей. */
    public static byte[] kick(UUID listener) { return write(KICK, o -> uuid(o, listener)); }

    public static byte[] roster(List<Badge> badges) {
        return write(ROSTER, o -> {
            int n = Math.min(badges.size(), MAX_ROSTER);
            o.writeShort(n);
            for (int i = 0; i < n; i++) { uuid(o, badges.get(i).id()); o.writeInt(badges.get(i).rgb()); }
        });
    }

    /** Новый игрок с Overtone или тот, кто сменил цвет. */
    public static byte[] rosterAdd(UUID id, int rgb) { return write(ROSTER_ADD, o -> { uuid(o, id); o.writeInt(rgb & 0xFFFFFF); }); }
    public static byte[] rosterRemove(UUID id) { return write(ROSTER_REMOVE, o -> uuid(o, id)); }

    /** nowPlaying — что сейчас играет у приглашающего, чтобы было видно, на что соглашаешься. */
    public static byte[] invited(UUID host, String hostName, String nowPlaying) {
        return write(INVITED, o -> { uuid(o, host); o.writeUTF(str(hostName, 32)); o.writeUTF(str(nowPlaying, MAX_STR)); });
    }

    public static byte[] answered(UUID listener, String name, boolean accept) {
        return write(ANSWERED, o -> { uuid(o, listener); o.writeUTF(str(name, 32)); o.writeBoolean(accept); });
    }

    public static byte[] hostState(UUID host, String hostName, State s) {
        return write(HOST_STATE, o -> { uuid(o, host); o.writeUTF(str(hostName, 32)); state(o, s); });
    }

    public static byte[] ended(UUID host) { return write(ENDED, o -> uuid(o, host)); }

    public static byte[] listeners(List<Listener> list) {
        return write(LISTENERS, o -> {
            int n = Math.min(list.size(), MAX_LISTENERS);
            o.writeByte(n);
            for (int i = 0; i < n; i++) { uuid(o, list.get(i).id()); o.writeUTF(str(list.get(i).name(), 32)); }
        });
    }

    public static byte[] denied(UUID target, int reason) { return write(DENIED, o -> { uuid(o, target); o.writeByte(reason); }); }

    /** null — мусор, чужая версия или слишком большой пакет: такое молча выбрасывается. */
    public static Msg read(byte[] data) {
        if (data == null || data.length < 2 || data.length > MAX_PACKET) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readUnsignedByte() != VERSION) return null;
            Msg m = new Msg(in.readUnsignedByte());
            switch (m.type) {
                case HELLO -> { m.flag = in.readBoolean(); m.rgb = in.readInt() & 0xFFFFFF; }
                case ROSTER_ADD -> { m.who = uuid(in); m.rgb = in.readInt() & 0xFFFFFF; }
                case INVITE, ROSTER_REMOVE, ENDED, KICK -> m.who = uuid(in);
                case ANSWER -> { m.who = uuid(in); m.flag = in.readBoolean(); }
                case STATE -> m.state = state(in);
                case LEAVE -> { }
                case ROSTER -> {
                    int n = Math.min(in.readUnsignedShort(), MAX_ROSTER);
                    List<Badge> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(new Badge(uuid(in), in.readInt() & 0xFFFFFF));
                    m.badges = list;
                }
                case INVITED -> { m.who = uuid(in); m.name = str(in.readUTF(), 32); m.state = nowPlaying(str(in.readUTF(), MAX_STR)); }
                case ANSWERED -> { m.who = uuid(in); m.name = str(in.readUTF(), 32); m.flag = in.readBoolean(); }
                case HOST_STATE -> { m.who = uuid(in); m.name = str(in.readUTF(), 32); m.state = state(in); }
                case LISTENERS -> {
                    int n = Math.min(in.readUnsignedByte(), MAX_LISTENERS);
                    List<Listener> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(new Listener(uuid(in), str(in.readUTF(), 32)));
                    m.listeners = list;
                }
                case DENIED -> { m.who = uuid(in); m.reason = in.readUnsignedByte(); }
                default -> { return null; }
            }
            return m;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Строка «исполнитель — название» для приглашения хранится в title пустого состояния. */
    private static State nowPlaying(String s) { return new State("", "", s, "", "", 0, "", 0, false); }

    public static String describe(State s) {
        if (s == null || s.empty()) return "";
        return s.artist().isEmpty() ? s.title() : s.artist() + " — " + s.title();
    }

    private interface Body { void write(DataOutputStream o) throws IOException; }

    private static byte[] write(int type, Body body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream o = new DataOutputStream(bytes)) {
            o.writeByte(VERSION);
            o.writeByte(type);
            body.write(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static void uuid(DataOutputStream o, UUID id) throws IOException {
        o.writeLong(id.getMostSignificantBits());
        o.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    private static void state(DataOutputStream o, State s) throws IOException {
        State c = (s == null ? State.NONE : s).clean();
        o.writeUTF(c.platform());
        o.writeUTF(c.id());
        o.writeUTF(c.title());
        o.writeUTF(c.artist());
        o.writeUTF(c.album());
        o.writeLong(c.durationMs());
        o.writeUTF(c.cover());
        o.writeLong(c.positionMs());
        o.writeBoolean(c.playing());
    }

    private static State state(DataInputStream in) throws IOException {
        return new State(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(),
                in.readUTF(), in.readLong(), in.readBoolean()).clean();
    }

    /** Обрезает и вычищает управляющие символы и коды цвета «§»: чужие строки рисуются у нас в интерфейсе. */
    public static String str(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch == '§' || Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT) continue;
            b.append(ch);
        }
        return b.toString().trim();
    }

    /** Обложки грузим только с известных CDN площадок: чужая ссылка не должна заставлять клиент куда-то ходить. */
    private static final String[] COVER_HOSTS = {"avatars.yandex.net", "i.scdn.co", "mosaic.scdn.co"};
    private static final String[] COVER_SUFFIXES = {".sndcdn.com", ".spotifycdn.com"};

    static String cleanCover(String url) {
        String u = str(url, 300);
        if (!u.startsWith("https://")) return "";
        String rest = u.substring(8);
        int slash = rest.indexOf('/');
        String host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(java.util.Locale.ROOT);
        if (host.contains("@") || host.contains(":")) return "";
        for (String h : COVER_HOSTS) if (host.equals(h)) return u;
        for (String h : COVER_SUFFIXES) if (host.endsWith(h)) return u;
        return "";
    }
}

package dev.musicplayer.discord;

import com.google.gson.JsonObject;
import dev.musicplayer.MusicPlayerMod;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.util.T;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Rich Presence через локальный IPC Discord: named pipe в Windows, unix-сокет в остальных системах. */
public final class DiscordPresence {
    private static final String CLIENT_ID = "1562608168792498358";
    private static final long TICK_MS = 2000, RECONNECT_MS = 15_000, MIN_SEND_MS = 2500;
    private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2;

    private static final ScheduledExecutorService LOOP = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-discord");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "musicplayer-discord-wd");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean started;

    private static Pipe pipe;
    private static long nextConnectAt, lastSentAt;
    private static String lastKey;
    private static boolean loggedConnect;

    private DiscordPresence() {}

    public static void start() {
        if (started) return;
        started = true;
        LOOP.scheduleWithFixedDelay(DiscordPresence::tick, 3000, TICK_MS, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(DiscordPresence::shutdown, "musicplayer-discord-close"));
    }

    private static void tick() {
        try {
            if (!PlayerConfig.get().discordPresence) { disconnect(true); return; }
            long now = System.currentTimeMillis();
            if (pipe == null) {
                if (now < nextConnectAt) return;
                nextConnectAt = now + RECONNECT_MS;
                pipe = connect();
                if (pipe == null) return;
                lastKey = null;
            }
            JsonObject activity = activity();
            String key = activity == null ? "none" : activity.toString();
            if (key.equals(lastKey) || activity != null && lastKey != null && sameExceptDrift(activity, lastKey)) return;
            if (now - lastSentAt < MIN_SEND_MS) return;
            send(activity);
            lastKey = key;
            lastSentAt = now;
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.debug("Discord presence: {}", e.toString());
            disconnect(false);
        }
    }

    private static JsonObject activity() {
        PlayerController ctl = PlayerController.I;
        Track t = ctl.current;
        if (t == null || ctl.idle) return null;
        boolean playing = ctl.engine().isPlaying();

        JsonObject a = new JsonObject();
        a.addProperty("type", 2);   // listening
        a.addProperty("details", clip(nz(t.title()), 128));
        String by = nz(t.artist());
        String state = playing ? by : T.get("discord.paused") + (by.isEmpty() ? "" : " · " + by);
        if (!state.isBlank()) a.addProperty("state", clip(state, 128));

        if (playing) {
            long pos = ctl.engine().positionMs();
            long start = (System.currentTimeMillis() - pos) / 1000 * 1000;
            JsonObject ts = new JsonObject();
            ts.addProperty("start", start);
            if (t.durationMs() > 0) ts.addProperty("end", start + t.durationMs());
            a.add("timestamps", ts);
        }

        JsonObject assets = new JsonObject();
        String cover = t.coverUrl();
        // у локальных треков обложка file://, Discord её всё равно не покажет
        if (cover != null && cover.startsWith("https://")) assets.addProperty("large_image", cover);
        else assets.addProperty("large_image", "overtone");
        String album = nz(t.album());
        assets.addProperty("large_text", clip(album.isEmpty() ? MusicPlayerMod.NAME : album, 128));
        Platform p = Platform.byIdOrNull(t.provider());
        if (p != null) {
            assets.addProperty("small_image", p.id);
            assets.addProperty("small_text", p.title());
        }
        a.add("assets", assets);
        return a;
    }

    private static void send(JsonObject activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        Frame reply = pipe.call(OP_FRAME, cmd.toString());
        if (reply.op == OP_CLOSE) throw new IOException("Discord closed the pipe");
        if (reply.json.contains("\"evt\":\"ERROR\"")) MusicPlayerMod.LOGGER.debug("Discord: {}", reply.json);
    }

    /** start гуляет на секунду-другую из-за неточной позиции, ради этого обновление слать не надо. */
    private static boolean sameExceptDrift(JsonObject now, String lastJson) {
        try {
            JsonObject last = com.google.gson.JsonParser.parseString(lastJson).getAsJsonObject();
            if (!now.has("timestamps") || !last.has("timestamps")) return false;
            long a = now.getAsJsonObject("timestamps").get("start").getAsLong();
            long b = last.getAsJsonObject("timestamps").get("start").getAsLong();
            if (Math.abs(a - b) > 2500) return false;
            JsonObject x = now.deepCopy(), y = last.deepCopy();
            x.remove("timestamps");
            y.remove("timestamps");
            return x.equals(y);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Pipe connect() {
        for (Path p : candidates()) {
            Pipe c = null;
            try {
                c = Pipe.open(p);
                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("client_id", CLIENT_ID);
                Frame f = c.call(OP_HANDSHAKE, hello.toString());
                if (f.op == OP_CLOSE) throw new IOException("Discord refused: " + f.json);
                if (!loggedConnect) { MusicPlayerMod.LOGGER.info("Discord Rich Presence connected"); loggedConnect = true; }
                return c;
            } catch (IOException | RuntimeException e) {
                if (c != null) c.close();
            }
        }
        return null;
    }

    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (int i = 0; i < 10; i++) {
            if (windows) {
                out.add(Path.of("\\\\.\\pipe\\discord-ipc-" + i));
                continue;
            }
            List<String> bases = new ArrayList<>();
            for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank()) bases.add(v);
            }
            bases.add("/tmp");
            for (String b : bases)
                for (String sub : new String[]{"", "app/com.discordapp.Discord/", "snap.discord/", ".flatpak/com.discordapp.Discord/xdg-run/"}) {
                    Path p = Path.of(b, sub + "discord-ipc-" + i);
                    if (Files.exists(p) && !out.contains(p)) out.add(p);
                }
        }
        return out;
    }

    private static void disconnect(boolean clear) {
        Pipe c = pipe;
        if (c == null) return;
        if (clear) {
            try { send(null); } catch (IOException ignored) { }
        }
        c.close();
        pipe = null;
        lastKey = null;
    }

    private static void shutdown() {
        try { LOOP.submit(() -> disconnect(true)).get(1, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    /** Discord отклоняет поля короче 2 и длиннее 128 символов. */
    private static String clip(String s, int max) {
        if (s.length() < 2) s = (s + "  ").substring(0, 2);
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private record Frame(int op, String json) {}

    private static final class Pipe {
        private final InputStream in;
        private final OutputStream out;
        private final AutoCloseable handle;

        private Pipe(InputStream in, OutputStream out, AutoCloseable handle) { this.in = in; this.out = out; this.handle = handle; }

        static Pipe open(Path p) throws IOException {
            String s = p.toString();
            if (s.startsWith("\\\\.\\pipe\\")) {
                RandomAccessFile f = new RandomAccessFile(s, "rw");
                return new Pipe(Channels.newInputStream(f.getChannel()), Channels.newOutputStream(f.getChannel()), f);
            }
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(p));
            return new Pipe(Channels.newInputStream(ch), Channels.newOutputStream(ch), ch);
        }

        /** Строго запрос-ответ: на pipe в Windows висящее чтение блокирует запись из другого потока. */
        Frame call(int op, String json) throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(op).putInt(body.length).put(body);
            var guard = WATCHDOG.schedule(this::close, 5, TimeUnit.SECONDS);
            try {
                out.write(b.array());
                out.flush();
                return read();
            } finally {
                guard.cancel(false);
            }
        }

        private Frame read() throws IOException {
            byte[] h = in.readNBytes(8);
            if (h.length < 8) throw new IOException("Discord closed the pipe");
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int op = b.getInt(), len = b.getInt();
            if (len < 0 || len > (1 << 20)) throw new IOException("bad Discord frame");
            byte[] body = in.readNBytes(len);
            return new Frame(op, new String(body, StandardCharsets.UTF_8));
        }

        void close() {
            try { handle.close(); } catch (Exception ignored) { }
        }
    }

}

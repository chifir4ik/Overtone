package dev.musicplayer.provider;

import dev.musicplayer.Env;
import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.util.T;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Запускает установленный пользователем yt-dlp и ограничивает время и размер ответа. */
final class YtDlp {
    private YtDlp() {}

    static byte[] run(List<String> args, long maxBytes, Duration timeout, BooleanSupplier cancelled) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable());
        command.addAll(List.of("--ignore-config", "--no-warnings", "--no-progress", "--quiet"));
        command.addAll(args);
        Process process;
        try { process = new ProcessBuilder(command).start(); }
        catch (IOException e) {
            IOException missing = new IOException(T.get("err.ytdlp_missing"));
            missing.addSuppressed(e);
            throw missing;
        }

        StringBuilder stderr = new StringBuilder();
        FutureTask<byte[]> output = new FutureTask<>(() -> read(process.getInputStream(), maxBytes));
        Thread outThread = new Thread(output, "musicplayer-ytdlp-out");
        Thread errThread = new Thread(() -> drain(process.getErrorStream(), stderr), "musicplayer-ytdlp-err");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        long started = System.nanoTime();
        long limit = timeout.toNanos();
        try {
            close(process.getOutputStream());
            outThread.start();
            errThread.start();
            byte[] data;
            while (true) {
                check(cancelled, started, limit);
                try { data = output.get(100, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException ignored) {}
                catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException io) throw io;
                    Throwable cause = e.getCause();
                    throw new IOException(T.get("err.ytdlp_failed", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
                }
            }
            while (true) {
                check(cancelled, started, limit);
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) break;
            }
            errThread.join(200);
            check(cancelled, started, limit);
            if (process.exitValue() != 0) throw new IOException(T.get("err.ytdlp_failed", lastError(stderr, process.exitValue())));
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("cancelled");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
            try { process.onExit().get(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | TimeoutException ignored) {}
        }
    }

    private static String executable() {
        String configured = PlayerConfig.get().ytDlpPath;
        if (configured != null && !configured.isBlank()) {
            try { if (Files.exists(Path.of(configured))) return Path.of(configured).toAbsolutePath().normalize().toString(); }
            catch (InvalidPathException ignored) {}
        }
        Path game = Env.gameDir();
        if (game != null) {
            for (String name : List.of("yt-dlp.exe", "yt-dlp")) {
                Path path = game.resolve(name);
                if (Files.exists(path)) return path.toAbsolutePath().normalize().toString();
            }
        }
        return "yt-dlp";
    }

    private static byte[] read(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (n > maxBytes - out.size()) throw new IOException(T.get("err.track_too_big"));
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void drain(InputStream in, StringBuilder tail) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) synchronized (tail) {
                tail.append(buf, 0, n);
                if (tail.length() > 2048) tail.delete(0, tail.length() - 2048);
            }
        } catch (IOException ignored) {}
    }

    private static String lastError(StringBuilder tail, int code) {
        String[] lines;
        synchronized (tail) { lines = tail.toString().split("\\R"); }
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) return line.substring(0, Math.min(200, line.length()));
        }
        return "exit code " + code;
    }

    private static void check(BooleanSupplier cancelled, long started, long limit) throws IOException {
        if (cancelled.getAsBoolean()) throw new CancellationException("cancelled");
        if (System.nanoTime() - started >= limit) throw new IOException(T.get("err.ytdlp_failed", "timeout"));
    }

    private static void close(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) {}
    }
}

package dev.musicplayer.provider;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class Background {
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "musicplayer-io");
        t.setDaemon(true);
        return t;
    });

    private Background() {}

    static <T> CompletableFuture<T> run(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, POOL);
    }

    /** Показывает пользователю первопричину, а не цепочку обёрток. */
    static Consumer<Throwable> errorsTo(Consumer<String> sink) {
        return e -> {
            Throwable t = e;
            while (t.getCause() != null) t = t.getCause();
            sink.accept(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        };
    }
}

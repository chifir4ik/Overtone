package dev.musicplayer.net;

import dev.musicplayer.util.T;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.musicplayer.MusicPlayerMod;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP-клиент только для перечисленных доменов: адрес проверяется до отправки, в том числе при редиректах, поэтому токен не уйдёт на чужой сервер. */
public final class SafeHttp {
    private static final int MAX_REDIRECTS = 5;
    public static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final long LOG_EVERY_MS = 5 * 60_000, DEAD_FOR_MS = 30_000;
    private static final Map<String, Long> loggedAt = new ConcurrentHashMap<>();
    private static final Map<String, Long> deadUntil = new ConcurrentHashMap<>();

    /**
     * Обычный клиент и два запасных на случай, когда провайдер рвёт TLS-рукопожатие или VPN подсовывает
     * неразрешимый прокси. Сработавший вариант запоминается и пробуется первым.
     */
    private final HttpClient[] clients = {
            builder().build(),
            builder().version(HttpClient.Version.HTTP_1_1).sslParameters(tls12()).build(),
            builder().version(HttpClient.Version.HTTP_1_1).sslParameters(tls12()).proxy(ProxySelector.of(null)).build(),
    };
    private volatile int preferred;
    private final Set<String> domains;
    private final Duration timeout;

    private static HttpClient.Builder builder() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER);
    }

    private static SSLParameters tls12() {
        SSLParameters p = new SSLParameters();
        p.setProtocols(new String[]{"TLSv1.2"});
        return p;
    }

    public SafeHttp(Set<String> domains) { this(domains, Duration.ofSeconds(30)); }

    public SafeHttp(Set<String> domains, Duration timeout) {
        this.domains = Set.copyOf(domains);
        this.timeout = timeout;
    }

    public boolean allowed(URI uri) {
        String scheme = uri.getScheme(), host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String d : domains) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }

    public HttpResponse<InputStream> open(String method, String url, Map<String, String> headers) throws IOException, InterruptedException {
        return open(method, url, headers, null);
    }

    public HttpResponse<InputStream> open(String method, String url, Map<String, String> headers, String body) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowed(uri)) throw new IOException(T.get("err.blocked_host", uri.getHost()));
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(rb::header);
            HttpResponse<InputStream> r = sendWithFallback(rb.build(), body);
            int sc = r.statusCode();
            if (sc / 100 != 3) return r;
            String next = r.headers().firstValue("Location").orElseThrow(() -> new IOException(T.get("err.redirect")));
            r.body().close();
            uri = uri.resolve(next);
        }
        throw new IOException(T.get("err.too_many_redirects"));
    }

    private HttpResponse<InputStream> sendWithFallback(HttpRequest req, String body) throws IOException, InterruptedException {
        String host = req.uri().getHost();
        Long dead = deadUntil.get(host);
        if (dead != null && System.currentTimeMillis() < dead) throw new IOException(T.get("err.net_unreachable", host));

        IOException first = null;
        int start = preferred;
        for (int k = 0; k < clients.length; k++) {
            int i = (start + k) % clients.length;
            try {
                HttpResponse<InputStream> r = clients[i].send(req, HttpResponse.BodyHandlers.ofInputStream());
                preferred = i;
                return r;
            } catch (IOException e) {
                if (!connectionProblem(e)) throw e;
                if (first == null) first = e;
            }
        }
        // браузер сайт открывает, а Java нет: чаще всего DNS провайдера, поэтому спрашиваем адрес по HTTPS
        try {
            return DohConnector.send(req, body);
        } catch (IOException e) {
            if (first == null) first = e;
        }

        long now = System.currentTimeMillis();
        deadUntil.put(host, now + DEAD_FOR_MS);
        Long last = loggedAt.get(host);
        if (last == null || now - last > LOG_EVERY_MS) {
            loggedAt.put(host, now);
            MusicPlayerMod.LOGGER.warn("Cannot connect to {}: {}", host, rootCause(first));
        }
        throw new IOException(T.get("err.net_unreachable", host));
    }

    private static boolean connectionProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SSLException || t instanceof ConnectException || t instanceof HttpConnectTimeoutException) return true;
        return false;
    }

    private static String rootCause(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.toString();
    }

    public record Reply(int status, String body) {}

    public Reply send(String method, String url, Map<String, String> headers) throws IOException, InterruptedException {
        return send(method, url, headers, null);
    }

    public Reply send(String method, String url, Map<String, String> headers, String body) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = open(method, url, headers, body);
        try (InputStream in = r.body()) {
            return new Reply(r.statusCode(), new String(in.readNBytes(8 << 20), StandardCharsets.UTF_8));
        }
    }

    public String get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Reply r = send("GET", url, headers);
        if (r.status() / 100 != 2) throw new IOException("HTTP " + r.status());
        return r.body();
    }

    public JsonObject getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        return JsonParser.parseString(get(url, headers)).getAsJsonObject();
    }
}

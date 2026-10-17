package dev.musicplayer.net;

import dev.musicplayer.util.T;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** HTTP-клиент только для перечисленных доменов: адрес проверяется до отправки, в том числе при редиректах, поэтому токен не уйдёт на чужой сервер. */
public final class SafeHttp {
    private static final int MAX_REDIRECTS = 5;
    public static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Set<String> domains;

    public SafeHttp(Set<String> domains) { this.domains = Set.copyOf(domains); }

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
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(rb::header);
            HttpResponse<InputStream> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            int sc = r.statusCode();
            if (sc / 100 != 3) return r;
            String next = r.headers().firstValue("Location").orElseThrow(() -> new IOException(T.get("err.redirect")));
            r.body().close();
            uri = uri.resolve(next);
        }
        throw new IOException(T.get("err.too_many_redirects"));
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

package org.quartzlang.embed.kits;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.quartzlang.embed.Kit;
import org.quartzlang.embed.KitContext;
import org.quartzlang.jvm.HostCall;

/** {@code org.quartzlang.embed.http}: blocking HTTP requests. */
public final class HttpKit implements Kit {
    private static final String P = "org.quartzlang.embed.http.";
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();

    @Override
    public String name() {
        return "http";
    }

    @Override
    public void bind(KitContext k) {
        k.bind(P + "request", c -> {
            List<Object> headers = c.listArg(2);
            if (headers.size() % 2 != 0) {
                throw new IllegalArgumentException("headers alternate name and value");
            }
            Object body = c.arg(3);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray((byte[]) body);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(c.stringArg(1)))
                    .method(c.stringArg(0), publisher).timeout(Duration.ofMinutes(5));
            for (int i = 0; i < headers.size(); i += 2) {
                request.header((String) headers.get(i), (String) headers.get(i + 1));
            }
            return k.handles().idOf(client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
        });
        k.bind(P + "get", c -> k.handles().idOf(client.send(HttpRequest.newBuilder(URI.create(c.stringArg(0)))
                .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofByteArray())));
        k.bind("Response.status", c -> (long) response(k, c).statusCode());
        k.bind("Response.text", c -> new String(response(k, c).body(), StandardCharsets.UTF_8));
        k.bind("Response.bytes", c -> response(k, c).body());
        k.bind("Response.header", c -> response(k, c).headers().firstValue(c.stringArg(1)).orElse(null));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(KitContext k, HostCall c) {
        return k.handles().get(c.longArg(0), HttpResponse.class);
    }
}

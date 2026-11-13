package bbb.atalanta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Package-private HTTP/JSON boundary for the Alpaca paper Trading API.
 *
 * <p>Only this class knows the HTTP and Jackson representations. Future public
 * endpoint methods will convert successful JSON trees to immutable Java records.
 * Requests are GET-only; never follow redirects, silently retry, or expose
 * credentials and provider response bodies in errors.
 */
final class AlpacaHttpTransport {

    private static final URI PAPER_HOST = URI.create("https://paper-api.alpaca.markets");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final URI baseUri;
    private final String keyId;
    private final String secretKey;
    private final Duration requestTimeout;
    private final BiFunction<HttpRequest, HttpResponse.BodyHandler<String>,
            CompletableFuture<HttpResponse<String>>> send;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * The only production factory: intentionally no live-host or arbitrary URL option.
     */
    static AlpacaHttpTransport paper(String keyId, String secretKey) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new AlpacaHttpTransport(
                PAPER_HOST, keyId, secretKey, DEFAULT_TIMEOUT, client::sendAsync);
    }

    /** Package-private injection seam used by deterministic offline tests. */
    AlpacaHttpTransport(
            URI baseUri,
            String keyId,
            String secretKey,
            Duration requestTimeout,
            BiFunction<HttpRequest, HttpResponse.BodyHandler<String>,
                    CompletableFuture<HttpResponse<String>>> send) {
        this.baseUri = validateBaseUri(baseUri);
        this.keyId = credential(keyId, "keyId");
        this.secretKey = credential(secretKey, "secretKey");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.send = Objects.requireNonNull(send, "send");
    }

    /**
     * Fetch a Trading API JSON object/array asynchronously.
     *
     * @param pathSegments distinct unencoded segments such as ["v2", "orders", orderId]
     * @param query original parameter names and values; encoded exactly once
     */
    CompletionStage<Result<JsonNode>> get(List<String> pathSegments, Map<String, String> query) {
        URI uri = uriFor(pathSegments, query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        // Only remote/transport errors become Err. Unexpected programming failures
        // remain exceptional rather than silently being mislabeled as network faults.
        return send.apply(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle(this::responseOrError);
    }

    private Result<JsonNode> responseOrError(HttpResponse<String> response, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrap(failure);
            if (cause instanceof CancellationException cancellation) {
                throw cancellation;
            }
            if (cause instanceof HttpTimeoutException) {
                return Result.err(new AlpacaError.Timeout());
            }
            if (cause instanceof IOException) {
                return Result.err(new AlpacaError.TransportFailure("Could not contact Alpaca"));
            }
            throw new CompletionException(cause);
        }

        int status = response.statusCode();
        if (status == 401) {
            return Result.err(new AlpacaError.Authentication("Alpaca authentication failed"));
        }
        if (status == 403) {
            return Result.err(new AlpacaError.Forbidden("Alpaca access denied"));
        }
        if (status == 429) {
            return Result.err(new AlpacaError.RateLimited("Alpaca rate limit exceeded"));
        }
        if (status < 200 || status >= 300) {
            return Result.err(new AlpacaError.HttpFailure(status, "Alpaca returned HTTP " + status));
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return Result.err(new AlpacaError.InvalidResponse("Alpaca returned an empty response"));
        }
        try {
            JsonNode data = mapper.readTree(body);
            if (data == null || !(data.isObject() || data.isArray())) {
                return Result.err(new AlpacaError.InvalidResponse("Alpaca returned unexpected JSON"));
            }
            return Result.ok(data);
        } catch (JsonProcessingException e) {
            // Do not use Jackson's exception message: it may echo private data.
            return Result.err(new AlpacaError.InvalidResponse("Alpaca returned malformed JSON"));
        }
    }

    private URI uriFor(List<String> pathSegments, Map<String, String> query) {
        Objects.requireNonNull(pathSegments, "pathSegments");
        Objects.requireNonNull(query, "query");
        if (pathSegments.isEmpty() || !"v2".equals(pathSegments.get(0))) {
            throw new IllegalArgumentException("Only v2 paper Trading API paths are supported");
        }

        String path = pathSegments.stream()
                .map(AlpacaHttpTransport::encodePathSegment)
                .collect(Collectors.joining("/"));

        StringBuilder url = new StringBuilder(baseUri.toASCIIString()).append('/').append(path);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> pair : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(encodeValue(pair.getKey(), "query key"))
                        .append('=')
                        .append(encodeValue(pair.getValue(), "query value"));
                first = false;
            }
        }
        return URI.create(url.toString());
    }

    private static String encodePathSegment(String segment) {
        Objects.requireNonNull(segment, "path segment");
        if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("Invalid path segment");
        }
        return encodeValue(segment, "path segment");
    }

    private static String encodeValue(String value, String label) {
        Objects.requireNonNull(value, label);
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI validateBaseUri(URI base) {
        Objects.requireNonNull(base, "baseUri");
        boolean paper = PAPER_HOST.equals(base);
        boolean loopbackTest = "http".equalsIgnoreCase(base.getScheme())
                && ("localhost".equalsIgnoreCase(base.getHost())
                    || "127.0.0.1".equals(base.getHost())
                    || "::1".equals(base.getHost()));
        if (!(paper || loopbackTest) || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getFragment() != null
                || !(base.getRawPath() == null || base.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("Only Alpaca paper or loopback test hosts are permitted");
        }
        return base;
    }

    private static String credential(String value, String name) {
        if (value == null || value.isBlank()
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be a valid, nonblank HTTP header value");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

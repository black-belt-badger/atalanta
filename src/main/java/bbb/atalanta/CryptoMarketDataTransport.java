package bbb.atalanta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/**
 * Package-private, read-only Alpaca historical crypto Market Data transport.
 *
 * <p>Separate from the fixed-host paper Trading transport so data requests can
 * never accidentally reach paper order endpoints. Reuse for M4 bars/trades/quotes.
 */
final class CryptoMarketDataTransport {

    private static final URI DATA_HOST = URI.create("https://data.alpaca.markets");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> OPERATIONS = Set.of("bars", "trades", "quotes");

    private final URI baseUri;
    private final String keyId;
    private final String secretKey;
    private final Duration timeout;
    private final BiFunction<HttpRequest, HttpResponse.BodyHandler<String>,
            CompletableFuture<HttpResponse<String>>> send;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static CryptoMarketDataTransport alpaca(String keyId, String secretKey) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new CryptoMarketDataTransport(DATA_HOST, keyId, secretKey, TIMEOUT, client::sendAsync);
    }

    /** Only test code may inject loopback HTTP and an offline response sender. */
    CryptoMarketDataTransport(URI baseUri, String keyId, String secretKey,
            Duration timeout,
            BiFunction<HttpRequest, HttpResponse.BodyHandler<String>,
                    CompletableFuture<HttpResponse<String>>> send) {
        this.baseUri = validateHost(baseUri);
        this.keyId = credential(keyId);
        this.secretKey = credential(secretKey);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
        this.send = Objects.requireNonNull(send, "send");
    }

    CompletionStage<Result<JsonNode>> get(
            CryptoLocation location, String operation, Map<String, String> parameters) {
        URI target = uriFor(location, operation, parameters);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Accept", "application/json")
                .GET().build();
        return send.apply(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle(this::responseOrError);
    }

    private URI uriFor(CryptoLocation location, String operation, Map<String, String> parameters) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(parameters, "parameters");
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unsupported read-only crypto operation");
        }
        var uri = new StringBuilder(baseUri.toASCIIString())
                .append("/v1beta3/crypto/")
                .append(location.path())
                .append('/').append(operation);

        if (!parameters.isEmpty()) {
            boolean first = true;
            for (var pair : parameters.entrySet()) {
                uri.append(first ? '?' : '&');
                uri.append(encode(pair.getKey())).append('=').append(encode(pair.getValue()));
                first = false;
            }
        }
        return URI.create(uri.toString());
    }

    private Result<JsonNode> responseOrError(HttpResponse<String> response, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrap(failure);
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof HttpTimeoutException) {
                return Result.err(new AlpacaError.Timeout());
            }
            if (cause instanceof IOException) {
                return Result.err(new AlpacaError.TransportFailure("Could not contact Alpaca"));
            }
            throw new CompletionException(cause);
        }
        int status = response.statusCode();
        if (status == 401) return Result.err(new AlpacaError.Authentication("Alpaca authentication failed"));
        if (status == 403) return Result.err(new AlpacaError.Forbidden("Alpaca access denied"));
        if (status == 429) return Result.err(new AlpacaError.RateLimited("Alpaca rate limit exceeded"));
        if (status < 200 || status >= 300) {
            return Result.err(new AlpacaError.HttpFailure(status, "Alpaca returned HTTP " + status));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return Result.err(new AlpacaError.InvalidResponse("Alpaca returned an empty response"));
        }
        try {
            JsonNode json = mapper.readTree(body);
            if (json == null || !json.isObject()) {
                return Result.err(new AlpacaError.InvalidResponse("Unexpected market-data JSON"));
            }
            return Result.ok(json);
        } catch (JsonProcessingException e) {
            return Result.err(new AlpacaError.InvalidResponse("Alpaca returned malformed JSON"));
        }
    }

    private static URI validateHost(URI base) {
        Objects.requireNonNull(base, "baseUri");
        boolean data = DATA_HOST.equals(base);
        boolean loopback = "http".equalsIgnoreCase(base.getScheme())
                && ("127.0.0.1".equals(base.getHost())
                || "localhost".equalsIgnoreCase(base.getHost())
                || "::1".equals(base.getHost()));
        if (!(data || loopback) || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getFragment() != null
                || !(base.getRawPath() == null || base.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("Only Alpaca Market Data or loopback tests permitted");
        }
        return base;
    }

    private static String credential(String value) {
        if (value == null || value.isBlank()
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Credentials must be nonblank valid header values");
        }
        return value;
    }

    private static String encode(String raw) {
        return URLEncoder.encode(Objects.requireNonNull(raw, "query field"),
                StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable value = error;
        while ((value instanceof CompletionException || value instanceof ExecutionException)
                && value.getCause() != null) value = value.getCause();
        return value;
    }

    @Override
    public String toString() {
        return "CryptoMarketDataTransport[read-only]";
    }
}

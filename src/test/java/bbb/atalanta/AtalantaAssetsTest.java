package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaAssetsTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<String>> remote = new CompletableFuture<>();

    private Atalanta client() {
        AlpacaHttpTransport transport = new AlpacaHttpTransport(
                LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                (request, handler) -> {
                    lastRequest.set(request);
                    calls.incrementAndGet();
                    return remote;
                });
        return new Atalanta(transport);
    }

    private String fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture missing " + name);
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void defaultAssetsUsesAlpacaProviderDefaultsAndCompletesAsynchronously() throws Exception {
        CompletionStage<Result<List<Asset>>> stage = client().assets();
        assertThat(stage.toCompletableFuture()).isNotDone();

        assertThat(lastRequest.get().method()).isEqualTo("GET");
        assertThat(lastRequest.get().uri())
                .isEqualTo(URI.create("http://127.0.0.1:8765/v2/assets"));
        assertThat(lastRequest.get().headers().firstValue("APCA-API-KEY-ID"))
                .contains("TEST-KEY");
        assertThat(lastRequest.get().headers().firstValue("APCA-API-SECRET-KEY"))
                .contains("TEST-SECRET");
        assertThat(calls).hasValue(1);

        remote.complete(new Reply(200, fixture("assets-mixed.json")));
        Result<List<Asset>> result = stage.toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<List<Asset>>) result).value()).hasSize(4).isUnmodifiable();
        assertThat(((Result.Ok<List<Asset>>) result).value().get(1).symbol()).isEqualTo("BTC/USD");
    }

    @Test
    void explicitFiltersSerializeEverySelectedParameterOnce() {
        AssetQuery query = AssetQuery.defaults()
                .withAssetClass("us_equity")
                .withStatus("active")
                .withExchanges(List.of("NASDAQ", "NYSE"))
                .withAttributes(List.of("has_options", "overnight_tradable"));

        CompletionStage<Result<List<Asset>>> stage = client().assets(query);
        assertThat(lastRequest.get().uri().toASCIIString())
                .isEqualTo("http://127.0.0.1:8765/v2/assets"
                        + "?status=active"
                        + "&asset_class=us_equity"
                        + "&exchange=NASDAQ%2CNYSE"
                        + "&attributes=has_options%2Covernight_tradable");

        remote.complete(new Reply(200, "[]"));
        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.ok(List.of()));
        assertThat(calls).hasValue(1);
    }

    @Test
    void cryptoFactorySelectsCryptoExplicitlyWithoutExchangeFilter() {
        CompletionStage<Result<List<Asset>>> stage = client().assets(
                AssetQuery.crypto().withAttributes(List.of("ipo")));

        assertThat(lastRequest.get().uri().getRawQuery())
                .isEqualTo("asset_class=crypto&attributes=ipo");
        remote.complete(new Reply(200, "[]"));
        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.ok(List.of()));
    }

    @Test
    void httpFailuresRemainFunctionalErrorsAndDoNotExposePrivatePayloads() {
        CompletionStage<Result<List<Asset>>> stage = client().assets();
        remote.complete(new Reply(429, "{\"message\":\"PRIVATE_SECRET_ACCOUNT\"}"));

        assertThat(stage.toCompletableFuture().join())
                .isEqualTo(Result.err(new AlpacaError.RateLimited("Alpaca rate limit exceeded")));
        assertThat(stage.toCompletableFuture().join().toString())
                .doesNotContain("PRIVATE_SECRET_ACCOUNT", "TEST-SECRET");
    }

    @Test
    void malformedResponsesBecomeInvalidResponseResults() {
        CompletionStage<Result<List<Asset>>> stage = client().assets();
        remote.complete(new Reply(200, "{}"));

        assertThat(stage.toCompletableFuture().join())
                .isEqualTo(Result.err(new AlpacaError.InvalidResponse("Expected an assets JSON array")));
    }

    @Test
    void clientErrorsAreNotConvertedIntoFakeHttpFailures() {
        Atalanta atalanta = client();
        assertThatNullPointerException().isThrownBy(() -> atalanta.assets(null));
        assertThat(calls).hasValue(0);

        CompletionStage<Result<List<Asset>>> stage = atalanta.assets();
        remote.completeExceptionally(new IllegalStateException("Programming defect"));
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public URI uri() { return LOOPBACK; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}

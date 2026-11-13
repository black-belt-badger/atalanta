package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaPositionsTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpRequest> request = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<String>> remote = new CompletableFuture<>();

    private Atalanta client() {
        return new Atalanta(new AlpacaHttpTransport(
                LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                (req, handler) -> {
                    request.set(req);
                    calls.incrementAndGet();
                    return remote;
                }));
    }

    private String fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void sendsOneAsynchronousReadOnlyGetWithoutAnyParameters() throws Exception {
        Atalanta client = client();
        CompletionStage<Result<List<Position>>> stage = client.positions();

        assertThat(stage.toCompletableFuture()).isNotDone();
        assertThat(calls).hasValue(1);
        assertThat(request.get().method()).isEqualTo("GET");
        assertThat(request.get().uri()).isEqualTo(URI.create(
                "http://127.0.0.1:8765/v2/positions"));
        assertThat(request.get().uri().getRawQuery()).isNull();
        assertThat(request.get().headers().firstValue("APCA-API-KEY-ID"))
                .contains("TEST-KEY");
        assertThat(request.get().headers().firstValue("APCA-API-SECRET-KEY"))
                .contains("TEST-SECRET");
        assertThat(request.get().timeout()).contains(Duration.ofSeconds(5));

        remote.complete(new Reply(200, fixture("positions-mixed.json")));
        Result<List<Position>> result = stage.toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        List<Position> positions = ((Result.Ok<List<Position>>) result).value();
        assertThat(positions).hasSize(6).isUnmodifiable();
        assertThat(positions.get(1).symbol()).isEqualTo("BTC/USD");
        assertThat(positions.get(4).usd()).isNotNull();
        assertThat(client.toString()).doesNotContain("TEST-KEY", "TEST-SECRET");
    }

    @Test
    void emptyPortfolioIsSuccessfulEmptyList() {
        CompletionStage<Result<List<Position>>> stage = client().positions();
        remote.complete(new Reply(200, "[]"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.ok(List.of()));
        assertThat(calls).hasValue(1);
    }

    @Test
    void authenticationAndRateLimitFailuresRemainTypedErrors() {
        CompletionStage<Result<List<Position>>> stage = client().positions();
        remote.complete(new Reply(401, "{\"message\":\"PRIVATE_ACCOUNT_AND_SECRET\"}"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.Authentication("Alpaca authentication failed")));
        assertThat(stage.toCompletableFuture().join().toString())
                .doesNotContain("PRIVATE_ACCOUNT_AND_SECRET", "TEST-SECRET");
    }

    @Test
    void malformedProviderResponsesAreInvalidResponseResults() {
        CompletionStage<Result<List<Position>>> stage = client().positions();
        remote.complete(new Reply(200, "{\"symbol\":\"AAPL\"}"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.InvalidResponse("Expected a positions JSON array")));
    }

    @Test
    void transportFailuresRemainFunctionalAndProgrammingDefectsRemainExceptional() {
        CompletionStage<Result<List<Position>>> stage = client().positions();
        remote.completeExceptionally(new IOException("PRIVATE_TRANSPORT_DETAILS"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(Result.err(
                new AlpacaError.TransportFailure("Could not contact Alpaca")));
        assertThat(stage.toCompletableFuture().join().toString())
                .doesNotContain("PRIVATE_TRANSPORT_DETAILS");
    }

    @Test
    void programmingDefectLeavesFutureExceptionalInsteadOfPretendingItIsHttpError() {
        CompletionStage<Result<List<Position>>> stage = client().positions();
        remote.completeExceptionally(new IllegalStateException("Unexpected programmer fault"));

        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
        @Override public URI uri() { return LOOPBACK; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}

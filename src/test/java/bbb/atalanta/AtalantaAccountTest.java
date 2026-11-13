package bbb.atalanta;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaAccountTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<String>> remote = new CompletableFuture<>();

    private Atalanta client() {
        AlpacaHttpTransport http = new AlpacaHttpTransport(
                LOOPBACK, "TEST-KEY", "TEST-SECRET", Duration.ofSeconds(5),
                (request, bodyHandler) -> {
                    lastRequest.set(request);
                    calls.incrementAndGet();
                    return remote;
                });
        return new Atalanta(http);
    }

    private String fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IllegalStateException("Fixture missing: " + name);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void accountIsAsynchronousAndCallsExactlyOnePaperAccountRequest() throws Exception {
        Atalanta atalanta = client();
        CompletionStage<Result<Account>> stage = atalanta.account();

        assertThat(stage.toCompletableFuture()).isNotDone();
        assertThat(calls).hasValue(1);
        assertThat(lastRequest.get().method()).isEqualTo("GET");
        assertThat(lastRequest.get().uri()).isEqualTo(URI.create("http://127.0.0.1:8765/v2/account"));
        assertThat(lastRequest.get().headers().firstValue("APCA-API-KEY-ID")).contains("TEST-KEY");
        assertThat(lastRequest.get().headers().firstValue("APCA-API-SECRET-KEY")).contains("TEST-SECRET");

        remote.complete(new Reply(200, fixture("account-full.json")));
        Result<Account> result = stage.toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<Account>) result).value().status()).isEqualTo("ACTIVE");
        assertThat(calls).hasValue(1);
        assertThat(atalanta.toString()).doesNotContain("TEST-KEY", "TEST-SECRET");
    }

    @Test
    void badProviderDataBecomesExpectedFunctionalFailure() {
        CompletionStage<Result<Account>> stage = client().account();
        remote.complete(new Reply(200, "{\"id\":\"not-a-uuid\",\"status\":\"ACTIVE\"}"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.InvalidResponse("Invalid account field or type")));
    }

    @Test
    void transportFailuresPropagateAsAlpacaErrors() {
        CompletionStage<Result<Account>> stage = client().account();
        remote.complete(new Reply(401, "{\"message\":\"SECRET_PRIVATE\"}"));

        assertThat(stage.toCompletableFuture().join()).isEqualTo(
                Result.err(new AlpacaError.Authentication("Alpaca authentication failed")));
        assertThat(stage.toCompletableFuture().join().toString())
                .doesNotContain("SECRET_PRIVATE", "TEST-SECRET");
    }

    @Test
    void unexpectedExceptionsRemainExceptional() {
        CompletionStage<Result<Account>> stage = client().account();
        remote.completeExceptionally(new IllegalStateException("Programming error"));
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private record Reply(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
        @Override public URI uri() { return LOOPBACK; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }
}

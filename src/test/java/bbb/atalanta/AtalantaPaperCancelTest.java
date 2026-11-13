package bbb.atalanta;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtalantaPaperCancelTest {

    private static final UUID ORDER_ID =
            UUID.fromString("9275f66a-0a1b-4a22-8c55-167416c7cf14");
    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8765");

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpRequest> request = new AtomicReference<>();
    private final CompletableFuture<HttpResponse<String>> remote = new CompletableFuture<>();

    private Atalanta client() {
        AlpacaHttpTransport http = new AlpacaHttpTransport(
                LOOPBACK, "SYNTHETIC-KEY", "SYNTHETIC-SECRET", Duration.ofSeconds(5),
                (req, handler) -> {
                    request.set(req);
                    calls.incrementAndGet();
                    return remote;
                });
        return new Atalanta(http);
    }

    @Test
    void onlyExplicitCancelMethodIssuesOnePaperDeleteAndReturnsAcceptanceNotFinalStatus() {
        Atalanta client = client();
        assertThat(calls).hasValue(0); // construction is pure
        CompletionStage<Result<PaperCancellationAccepted>> stage =
                client.cancelPaperOrder(ORDER_ID);
        assertThat(calls).hasValue(1);
        assertThat(stage.toCompletableFuture()).isNotDone();

        HttpRequest req = request.get();
        assertThat(req.method()).isEqualTo("DELETE");
        assertThat(req.uri()).isEqualTo(
                URI.create("http://127.0.0.1:8765/v2/orders/" + ORDER_ID));
        assertThat(req.uri().getRawQuery()).isNull();
        assertThat(req.bodyPublisher()).isEmpty();
        assertThat(req.timeout()).contains(Duration.ofSeconds(5));
        assertThat(req.headers().firstValue("APCA-API-KEY-ID")).contains("SYNTHETIC-KEY");
        assertThat(req.headers().firstValue("APCA-API-SECRET-KEY")).contains("SYNTHETIC-SECRET");
        assertThat(req.headers().firstValue("Accept")).contains("application/json");
        assertThat(req.headers().firstValue("Content-Type")).isEmpty();

        // The body must not be parsed, echoed, or logged for a status-only DELETE.
        remote.complete(new Reply(204, "{\"private\":\"ACCOUNT-SECRET-DO-NOT-PRINT\"}"));
        Result<PaperCancellationAccepted> actual = stage.toCompletableFuture().join();

        assertThat(actual).isEqualTo(Result.ok(new PaperCancellationAccepted(ORDER_ID)));
        assertThat(actual.toString())
                .doesNotContain("ACCOUNT-SECRET-DO-NOT-PRINT", ORDER_ID.toString(), "SYNTHETIC-SECRET");
        assertThat(actual.toString()).contains("not terminal status");
        assertThat(calls).hasValue(1); // no retries, no implicit status lookup
    }

    @Test
    void readOperationsCannotAccidentallyCancelAnOrder() {
        Atalanta client = client();
        CompletionStage<Result<Optional<Order>>> stage = client.order(ORDER_ID);
        assertThat(request.get().method()).isEqualTo("GET");
        remote.complete(new Reply(404, "not found"));
        assertThat(stage.toCompletableFuture().join())
                .isEqualTo(Result.ok(Optional.empty()));
        assertThat(calls).hasValue(1);
    }

    @Test
    void wrongOrMissingUuidFailsBeforeHttpAndThereIsNoCancelAllOverload() {
        Atalanta client = client();
        assertThatNullPointerException().isThrownBy(() -> client.cancelPaperOrder(null));
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> UUID.fromString("../positions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void onlyHttp204CountsAsCancellationRequestAcceptance() {
        for (int status : List.of(200, 201, 202, 206)) {
            var result = cancelWithStatus(status);
            assertThat(result)
                    .isEqualTo(Result.err(new AlpacaError.InvalidResponse(
                            "Expected HTTP 204 cancellation acknowledgment")));
        }
    }

    @Test
    void nonexistentAndNoncancelableAreDistinctTypedErrors() {
        assertThat(cancelWithStatus(404))
                .isEqualTo(Result.err(new AlpacaError.OrderNotFound(
                        "Paper order not found (HTTP 404)")));
        assertThat(cancelWithStatus(422))
                .isEqualTo(Result.err(new AlpacaError.OrderNotCancelable(
                        "Paper order not cancelable (HTTP 422)")));
    }

    @Test
    void authenticationPermissionRateLimitAndOtherHttpFailuresAreSanitized() {
        assertThat(cancelWithStatus(401))
                .isEqualTo(Result.err(new AlpacaError.Authentication("Alpaca authentication failed")));
        assertThat(cancelWithStatus(403))
                .isEqualTo(Result.err(new AlpacaError.Forbidden("Alpaca access denied")));
        assertThat(cancelWithStatus(429))
                .isEqualTo(Result.err(new AlpacaError.RateLimited("Alpaca rate limit exceeded")));
        for (int status : List.of(400, 409, 499)) {
            assertThat(cancelWithStatus(status)).isEqualTo(
                    Result.err(new AlpacaError.HttpFailure(status, "Alpaca returned HTTP " + status)));
        }
    }

    @Test
    void provider5xxMayHaveAcceptedCancellationAndMustNotBeRetried() {
        for (int status : List.of(500, 502, 503, 504)) {
            assertThat(cancelWithStatus(status)).isEqualTo(
                    Result.err(new AlpacaError.CancellationOutcomeUnknown(
                            status, "Paper cancellation outcome unknown after provider HTTP 5xx")));
        }
    }

    @Test
    void transportErrorsAndTimeoutsRemainUncertainNotProofOfFailure() {
        for (Throwable failure : List.of(
                new IOException("PRIVATE ACCOUNT REF"),
                new CompletionException(new HttpTimeoutException("PRIVATE AUTH HEADER")))) {
            Atalanta client = clientForError(failure);
            CompletionStage<Result<PaperCancellationAccepted>> stage =
                    client.cancelPaperOrder(ORDER_ID);
            Result<PaperCancellationAccepted> outcome = stage.toCompletableFuture().join();
            assertThat(outcome).isEqualTo(
                    Result.err(new AlpacaError.CancellationOutcomeUnknown(
                            null, "Paper cancellation outcome unknown; inspect order status")));
            assertThat(outcome.toString())
                    .doesNotContain("PRIVATE ACCOUNT REF", "PRIVATE AUTH HEADER", ORDER_ID.toString());
        }
    }

    @Test
    void programmingErrorsRemainExceptionalAndAreNotHiddenAsNetworkErrors() {
        var pending = new CompletableFuture<HttpResponse<String>>();
        AlpacaHttpTransport transport = new AlpacaHttpTransport(
                LOOPBACK, "KEY", "SECRET", Duration.ofSeconds(5),
                (req, handler) -> pending);
        var stage = new Atalanta(transport).cancelPaperOrder(ORDER_ID);
        pending.completeExceptionally(new IllegalStateException("BUG"));
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationDuringHttpDoesNotProduceFalseAcceptedValue() {
        var pending = new CompletableFuture<HttpResponse<String>>();
        AlpacaHttpTransport transport = new AlpacaHttpTransport(
                LOOPBACK, "KEY", "SECRET", Duration.ofSeconds(5),
                (req, handler) -> pending);
        var stage = new Atalanta(transport).cancelPaperOrder(ORDER_ID);
        pending.cancel(false);
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOfAny(java.util.concurrent.CancellationException.class,
                        java.util.concurrent.CompletionException.class);
    }

    private Result<PaperCancellationAccepted> cancelWithStatus(int status) {
        AtomicInteger attempts = new AtomicInteger();
        AlpacaHttpTransport transport = new AlpacaHttpTransport(
                LOOPBACK, "KEY", "SECRET", Duration.ofSeconds(5),
                (req, handler) -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new Reply(status, "{\"private\":\"SENSITIVE PROVIDER BODY\"}"));
                });
        var result = new Atalanta(transport).cancelPaperOrder(ORDER_ID)
                .toCompletableFuture().join();
        assertThat(attempts).hasValue(1);
        assertThat(result.toString())
                .doesNotContain("SENSITIVE PROVIDER BODY", ORDER_ID.toString(), "SECRET");
        return result;
    }

    private Atalanta clientForError(Throwable failure) {
        AlpacaHttpTransport transport = new AlpacaHttpTransport(
                LOOPBACK, "KEY", "SECRET", Duration.ofSeconds(5),
                (req, handler) -> CompletableFuture.failedFuture(failure));
        return new Atalanta(transport);
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

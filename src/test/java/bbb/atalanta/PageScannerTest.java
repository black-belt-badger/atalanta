package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageScannerTest {
    private record FakePage(long observations, String next) {}

    private record FakeProvider(
            Map<String, Result<FakePage>> responses, List<String> requested) {

        PageScanner.Adapter<String, FakePage> adapter() {
            return new PageScanner.Adapter<>(
                    token -> {
                        requested.add(token);
                        Result<FakePage> reply = responses.get(token);
                        if (reply == null) {
                            throw new AssertionError("Unexpected token " + token);
                        }
                        return CompletableFuture.completedFuture(reply);
                    },
                    (ignored, token) -> token,
                    page -> Optional.ofNullable(page.next()),
                    FakePage::observations);
        }
    }

    @Test
    void deliversPagesSequentiallyWithoutAccumulatingThemInResult() {
        var requested = new ArrayList<String>();
        var accepted = new ArrayList<FakePage>();
        var provider = new FakeProvider(Map.of(
                "start", Result.ok(new FakePage(2, "NEXT")),
                "NEXT", Result.ok(new FakePage(0, "LAST")),
                "LAST", Result.ok(new FakePage(3, null))), requested);
        var result = PageScanner.scan("start", null, 20, provider.adapter(), accepted::add)
                .toCompletableFuture().join();

        assertThat(result).isEqualTo(Result.ok(
                new ScanSummary(3, 5, Optional.empty())));
        assertThat(accepted).containsExactly(
                new FakePage(2, "NEXT"),
                new FakePage(0, "LAST"),
                new FakePage(3, null));
        assertThat(requested).containsExactly("start", "NEXT", "LAST");
        assertThat(ScanSummary.class.getRecordComponents()).extracting("name")
                .containsExactly("pages", "observations", "continuation");
    }

    @Test
    void knownTransportFailureIsResultErrAndDoesNotInvokeConsumerForFailedPage() {
        var accepted = new ArrayList<FakePage>();
        var requested = new ArrayList<String>();
        var error = new AlpacaError.RateLimited("HTTP 429");
        var provider = new FakeProvider(Map.of(
                "start", Result.ok(new FakePage(2, "NEXT")),
                "NEXT", Result.err(error)), requested);

        var result = PageScanner.scan("start", null, 20, provider.adapter(), accepted::add)
                .toCompletableFuture().join();

        assertThat(result).isEqualTo(Result.err(error));
        assertThat(accepted).containsExactly(new FakePage(2, "NEXT"));
        assertThat(requested).containsExactly("start", "NEXT");
        // The caller has a durable checkpoint if the callback persisted it.
        // The error itself carries NO market payload and NO job retry flag.
    }

    @Test
    void localPageBoundReturnsAContinuationWithoutFetchingAnyMore() {
        var requested = new ArrayList<String>();
        var accepted = new ArrayList<FakePage>();
        var provider = new FakeProvider(Map.of(
                "start", Result.ok(new FakePage(4, "NEXT"))), requested);

        var result = PageScanner.scan("start", null, 1, provider.adapter(), accepted::add)
                .toCompletableFuture().join();

        assertThat(result).isEqualTo(Result.ok(
                new ScanSummary(1, 4, Optional.of("NEXT"))));
        assertThat(requested).containsExactly("start");
        assertThat(accepted).hasSize(1);
    }

    @Test
    void detectsResumeTokenCycleBeforeDeliveringBadPage() {
        var requested = new ArrayList<String>();
        var accepted = new ArrayList<FakePage>();
        var provider = new FakeProvider(Map.of(
                "start", Result.ok(new FakePage(8, "EXISTING"))), requested);

        var result = PageScanner.scan("start", "EXISTING", 5, provider.adapter(), accepted::add)
                .toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<ScanSummary>) result).error())
                .isInstanceOf(AlpacaError.InvalidResponse.class);
        assertThat(accepted).isEmpty();
        assertThat(requested).containsExactly("start");
    }

    @Test
    void detectsRepeatedContinuationWithoutUnboundedTraversal() {
        var requested = new ArrayList<String>();
        var accepted = new ArrayList<FakePage>();
        var provider = new FakeProvider(Map.of(
                "start", Result.ok(new FakePage(5, "LOOP")),
                "LOOP", Result.ok(new FakePage(2, "LOOP"))), requested);

        var result = PageScanner.scan("start", null, 20, provider.adapter(), accepted::add)
                .toCompletableFuture().join();

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<ScanSummary>) result).error())
                .isInstanceOf(AlpacaError.InvalidResponse.class);
        assertThat(accepted).containsExactly(new FakePage(5, "LOOP"));
        assertThat(requested).containsExactly("start", "LOOP");
    }

    @Test
    void consumerStorageFailureCompletesExceptionallyAndIsNeverAnAlpacaError() {
        var requested = new ArrayList<String>();
        var provider = new FakeProvider(Map.of(
                "start", Result.ok(new FakePage(10, "NEXT"))), requested);

        CompletionStage<Result<ScanSummary>> stage = PageScanner.scan(
                "start", null, 10, provider.adapter(),
                page -> { throw new IllegalStateException("DATABASE_UNAVAILABLE"); });

        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(requested).containsExactly("start");
    }

    @Test
    void asyncBoundaryDoesNotFetchAgainUntilThePriorPageIsDelivered() {
        var nextFetched = new AtomicInteger();
        var firstResponse = new CompletableFuture<Result<FakePage>>();
        var accepted = new ArrayList<FakePage>();
        var adapter = new PageScanner.Adapter<String, FakePage>(
                token -> {
                    nextFetched.incrementAndGet();
                    if (token.equals("start")) return firstResponse;
                    return CompletableFuture.completedFuture(Result.ok(new FakePage(1, null)));
                },
                (ignored, token) -> token,
                page -> Optional.ofNullable(page.next()), FakePage::observations);

        var stage = PageScanner.scan("start", null, 3, adapter, accepted::add)
                .toCompletableFuture();
        assertThat(stage).isNotDone();
        assertThat(nextFetched).hasValue(1);
        firstResponse.complete(Result.ok(new FakePage(0, "NEXT")));
        assertThat(stage.join()).isEqualTo(Result.ok(
                new ScanSummary(2, 1, Optional.empty())));
        assertThat(nextFetched).hasValue(2);
        assertThat(accepted).containsExactly(new FakePage(0, "NEXT"), new FakePage(1, null));
    }

    @Test
    void canceledProviderFutureDoesNotBecomeAClaimOfSuccessOrRetry() {
        var pending = new CompletableFuture<Result<FakePage>>();
        var requested = new AtomicInteger();
        var adapter = new PageScanner.Adapter<String, FakePage>(
                token -> { requested.incrementAndGet(); return pending; },
                (ignored, token) -> token,
                page -> Optional.ofNullable(page.next()), FakePage::observations);
        var stage = PageScanner.scan("start", null, 3, adapter, ignored -> {})
                .toCompletableFuture();

        pending.cancel(false);
        assertThatThrownBy(stage::join)
                .isInstanceOfAny(CancellationException.class, CompletionException.class);
        assertThat(requested).hasValue(1);
    }

    @Test
    void rejectsUnboundedScansAndMissingCallbacksBeforeDoingAnything() {
        var requested = new ArrayList<String>();
        var provider = new FakeProvider(Map.of("start", Result.ok(new FakePage(1, null))), requested);
        for (int n : new int[]{0, -1, 21, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> PageScanner.scan("start", null, n, provider.adapter(), ignored -> {}));
        }
        assertThatNullPointerException().isThrownBy(
                () -> PageScanner.scan("start", null, 1, provider.adapter(), null));
        assertThatNullPointerException().isThrownBy(
                () -> PageScanner.scan(null, null, 1, provider.adapter(), ignored -> {}));
        assertThat(requested).isEmpty();
    }

    @Test
    void scanSummaryIsJustImmutableProgressMetadata() {
        var summary = new ScanSummary(2, 6, Optional.of("MORE"));
        assertThat(summary.exhausted()).isFalse();
        assertThat(new ScanSummary(2, 6, Optional.empty()).exhausted()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ScanSummary(0, 6, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ScanSummary(1, -1, Optional.empty()));
        assertThatNullPointerException().isThrownBy(
                () -> new ScanSummary(1, 0, null));
    }
}

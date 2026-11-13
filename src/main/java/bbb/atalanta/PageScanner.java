package bbb.atalanta;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * One reusable, bounded, page-at-a-time traversal mechanism for the three
 * historical endpoints. A new page is requested only after the
 * previous page's asynchronous acknowledgement succeeds.
 * This is a transport orchestration helper, NOT an
 * accumulated-data result, checkpoint store or retry policy.
 *
 * <p>No explicit list of pages is accumulated. CompletionStage internals
 * may transiently retain page references: do not claim constant-memory
 * behavior without measuring it.
 */
final class PageScanner {

    /** Typed endpoint-specific operations; private to the Atalanta package. */
    record Adapter<Q, P>(
            Function<Q, CompletionStage<Result<P>>> fetch,
            BiFunction<Q, String, Q> withPageToken,
            Function<P, Optional<String>> continuation,
            ToLongFunction<P> count) {
        Adapter {
            Objects.requireNonNull(fetch, "fetch");
            Objects.requireNonNull(withPageToken, "withPageToken");
            Objects.requireNonNull(continuation, "continuation");
            Objects.requireNonNull(count, "count");
        }
    }

    private PageScanner() {}

    static <Q, P> CompletionStage<Result<ScanSummary>> scan(
            Q initial, String initialToken, int maxPages,
            Adapter<Q, P> adapter,
            Function<? super P, ? extends CompletionStage<Void>> onPage) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(onPage, "onPage");
        if (maxPages < 1 || maxPages > 20) {
            throw new IllegalArgumentException("maxPages must be between 1 and 20");
        }
        var visited = initialToken == null ? Set.<String>of() : Set.of(initialToken);
        return step(initial, maxPages, adapter, onPage, 0, 0L, visited);
    }

    private static <Q, P> CompletionStage<Result<ScanSummary>> step(
            Q query, int maxPages, Adapter<Q, P> adapter,
            Function<? super P, ? extends CompletionStage<Void>> onPage,
            int completed, long observed, Set<String> visited) {

        return adapter.fetch().apply(query).thenCompose(response ->
                response.<CompletionStage<Result<ScanSummary>>>fold(page -> {
                    // Check provider consistency BEFORE acknowledging this page
                    // to the consumer, so an immediate token loop cannot be committed.
                    Optional<String> continuation =
                            Objects.requireNonNull(adapter.continuation().apply(page));
                    String token = continuation.orElse(null);
                    if (token != null && visited.contains(token)) {
                        return CompletableFuture.completedFuture(Result.err(
                                new AlpacaError.InvalidResponse(
                                        "Alpaca returned a repeated historical page token")));
                    }

                    // The callback acknowledges durability asynchronously. Its
                    // stage MUST succeed before the next request starts, or
                    // before this scan reports successful completion.
                    //
                    // Synchronous throws, null returned stages, failed stages,
                    // and canceled stages are caller-side failures: propagate
                    // exceptionally, never convert them into AlpacaError.
                    CompletionStage<Void> acknowledged = Objects.requireNonNull(
                            onPage.apply(page), "onPage returned null acknowledgement");
                    return acknowledged.thenCompose(ignored -> {
                        long nextCount = Math.addExact(observed, adapter.count().applyAsLong(page));
                        int nextPages = completed + 1;

                        if (token == null || nextPages == maxPages) {
                            return CompletableFuture.completedFuture(Result.ok(
                                    new ScanSummary(nextPages, nextCount, continuation)));
                        }
                        var nextVisited = new HashSet<>(visited);
                        nextVisited.add(token);
                        Q nextQuery = Objects.requireNonNull(
                                adapter.withPageToken().apply(query, token), "next query");
                        return step(nextQuery, maxPages, adapter, onPage,
                                nextPages, nextCount, Set.copyOf(nextVisited));
                    });
                }, error -> CompletableFuture.completedFuture(Result.err(error))));
    }
}

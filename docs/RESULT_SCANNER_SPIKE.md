# RFC / code spike: result-based streaming historical scan

**Status: EXPERIMENTAL / DRAFT**, proposed under [Atalanta public API #5](https://github.com/black-belt-badger/atalanta/issues/5). This patch **only adds** a generic package-private scanning engine, one progress record and three public methods. **The existing `cryptoBarsPages` / `cryptoTradesPages` / `cryptoQuotesPages` methods and their sealed `Stopped` / `Failed` result types remain intact.** Nothing here changes the M4 decoders, fixed Market Data host or M5 paper trading.

## What exists today

```java
CompletionStage<Result<CryptoBarPage>> cryptoBars(CryptoBarQuery q);

CompletionStage<CryptoBarScan> cryptoBarsPages(CryptoBarQuery q, int maxPages);
```

The separate `CryptoBarScan.Stopped(List<CryptoBarPage>, continuation)` and `CryptoBarScan.Failed(List<CryptoBarPage>, AlpacaError)` variants represent **data retained so far + operational outcome** in one special-purpose ADT. Trades and Quotes repeat nearly the same API. All bounded scans accumulate up to 20 provider pages and expose different error conventions from other Atalanta methods.

This is valid for convenience clients that **want every fetched page in memory**, but is not an ideal foundational acquisition API for large jobs and streaming persistence.

## Proposed code: public surface

```java
public record ScanSummary(
        int pages,
        long observations,
        Optional<String> continuation) {
    public ScanSummary {
        if (pages < 1 || observations < 0) throw new IllegalArgumentException();
        Objects.requireNonNull(continuation);
    }

    public boolean exhausted() { return continuation.isEmpty(); }
}

// All use the same package-private PageScanner implementation.
CompletionStage<Result<ScanSummary>> scanBars(
        CryptoBarQuery q, int maxPages,
        Function<? super CryptoBarPage, ? extends CompletionStage<Void>> onPage);
CompletionStage<Result<ScanSummary>> scanTrades(
        CryptoTradeQuery q, int maxPages,
        Function<? super CryptoTradePage, ? extends CompletionStage<Void>> onPage);
CompletionStage<Result<ScanSummary>> scanQuotes(
        CryptoQuoteQuery q, int maxPages,
        Function<? super CryptoQuotePage, ? extends CompletionStage<Void>> onPage);
```

Each valid decoded page is delivered to the consumer **one at a time**, with the next provider request starting only after the consumer's **asynchronous acknowledgement stage completes successfully**. The scan cannot report successful completion before its final page is acknowledged. No page list is built explicitly, although asynchronous completion stages may temporarily retain references; constant-memory use is not claimed without profiling. The returned summary describes a **successful** bounded traversal; it may contain a continuation if the cap was reached before provider exhaustion. The caller still chooses a 1–20 page bound.

The existing single-page `CompletionStage<Result<Crypto*Page>>` methods remain and are used unchanged.

## Example: a caller-owned acquisition checkpoint

```java
CryptoBarQuery request = CryptoBarQuery.of(
        List.of("BTC/USD"), CryptoTimeframe.minutes(1));

CompletionStage<Result<ScanSummary>> attempt =
        client.scanBars(request, 5, page -> {
            // Intended as an atomic checkpoint transaction in the job layer:
            return store.savePageAndCheckpoint(page.bars(), page.continuation()); // CompletionStage<Void>
        });

// The job, not the crypto page or Atalanta, decides whether and when to retry:
attempt.thenAccept(result -> result.fold(
        summary -> { job.completeOrRequeue(summary.continuation()); return null; },
        error -> { job.classifyForRetryOrReconciliation(error); return null; }
));
```

**The acknowledgement must represent a durable data+checkpoint commit**, not just accepting a record into an in-memory queue. A returned stage that completes too early cannot guarantee ingestion safety.

`store` and `job` above are **illustrative application-layer objects, not new Atalanta classes**. The consumer must be careful to perform durable persistence and checkpoint updates transactionally; Atalanta does not promise exactly-once persistence, two-phase commit, or durable checkpoints. If the process dies between a write and its checkpoint, the job must handle restart/idempotency.

## Boundary and error semantics

| Situation | Behavior | Responsibility |
| --- | --- | --- |
| Valid page, next token provided | Next page is fetched only after the callback's acknowledgement stage succeeds | Library orchestrates, caller stores/checkpoints |
| Valid short/empty page with token | Continue despite zero observations | Library |
| Reached 1–20 page cap | `Result.Ok(ScanSummary(..., Optional.of(token)))` | Caller decides next job window |
| Provider exhausted | `Result.Ok(ScanSummary(..., Optional.empty()))` | Caller decides completion |
| Provider HTTP 429, 503, timeout, network error | `Result.Err(AlpacaError)`; no market page carried in error | Library reports boundary failure; job chooses retry/backoff |
| Token repeated, including the caller's initial resume token | `Result.Err(InvalidResponse)`; do **not** deliver offending page | Library rejects invalid provider pagination |
| Callback throws, returns a null stage, or its returned stage fails/cancels | Scan stage completes **exceptionally**, never as `AlpacaError`; no further fetch | Caller/job owns local failure |
| Cancellation of network or persistence future | Exceptional completion; no fabricated success or retry | Caller/job decides |

**Why no `These`/`Those` here:** the market-data page should not encode transport failure. The library's existing generic `Result` is sufficient for success/failure of this attempt, and checkpoint/retry classification is a higher-layer concern. A partial-result ADT may still be useful in **planned jobs** to describe successful/failed batches, but not as a replacement for ordinary market observations.

## Ingestion and coverage-math integration

This is intentionally compatible with Metis/Ariadne's `Function<Buffer, Future<Void>>` ingestion sink, though the Java 17 library accepts `CompletionStage<Void>` and **typed provider pages**. A Metis adapter must encode/store the page and its next token in one durable unit of work, and only then acknowledge the page. It must not store a `Result.Err` as market data or treat a successful page as completed interval coverage.

Metis's `HistoricalCoverageMath.assess` derives acquired/no-data/missing/conflicting intervals from **committed terminal evidence**. The scanner alone cannot assert `ACQUIRED` or `NO_DATA`; even `ScanSummary.exhausted()` describes provider pagination, not a database commit. An acquisition worker must verify the bounded target interval, persisted checkpoint and terminal evidence before publishing coverage. If a scan stops at its page cap with a continuation token, that work remains incomplete regardless of the count of acknowledged pages.

All retry/reconciliation policy (temporary HTTP error, unsafe mutation replay, interrupted job, etc.) stays above this transport helper. Cancellation or failed storage is not converted to a retryable data outcome.

## Package-private reusable engine

The implemented `PageScanner.Adapter<Q,P>` takes only four explicitly supplied functions:

```java
record Adapter<Q, P>(
    Function<Q, CompletionStage<Result<P>>> fetch,
    BiFunction<Q, String, Q> withPageToken,
    Function<P, Optional<String>> continuation,
    ToLongFunction<P> count
) {}
```

The scanner uses immutable per-step progress (`completed`, `observed`, visited token set) and uses no explicit page accumulator. It is endpoint-neutral, has no retry policy and has no new dependency on Herkules. The three tiny public adapters map existing query/page types to this engine. There is no mandatory Vert.x, broker, database or mutable service context.

## What needs a design decision before replacing anything

**Acknowledgement decision resolved:** the draft now uses `Function<P, CompletionStage<Void>>`. The caller should return a stage promptly; do not do blocking storage work on the HTTP completion thread before returning it. A synchronous sink can adapt as `page -> { synchronousStore(page); return CompletableFuture.completedFuture(null); }`, provided it is acceptable to block that thread. The next request waits for a *successfully completed* acknowledgement. A failed, canceled or null acknowledgement stops the scan exceptionally. No partially retrieved data is silently called durable.

**Convenience aggregation:** the existing `crypto*Pages` methods retain a small in-memory batch, which is useful to interactive users/tests. We can keep these as documented convenience functions implemented on top of the generic scanner, or deprecate them later after a source-compatibility review. **This draft does neither**; it adds an alternative.

**No automatic retry:** neither the generic scanner nor the client should convert a 429/503 into a planned retry. A job scheduler can use `AlpacaError` to categorize retryable, nonretryable and needs-reconciliation outcomes, with persisted attempt metadata. Error categorization is a **job-level decision**, not a field on `CryptoBar` / `CryptoTrade` / `CryptoQuote`.

## Verification / non-goals

The experimental unit tests use a fake provider, plus real Bars/Trades/Quotes decoders against an injected synthetic Market Data HTTP sender. They cover: empty/short pages with continuation, cap, no accumulated output pages, exact observation counts, initial/repeated token cycles, synchronous callback exceptions, null/failed/canceled persistence acknowledgements, pending-final-page acknowledgement, provider failure, async fetch sequencing, and exact endpoint routing with no paper requests.

Full production API tests should remain green; **no live HTTP requests, POST/DELETE, database access, or job retry policy are added**. A merge would be additive on Java 17, but this PR is intentionally marked draft pending product-owner review.

See [post-feature consistency audit](POST_FEATURE_CONSISTENCY_AUDIT.md) and [existing Result contract](../src/main/java/bbb/atalanta/Result.java).

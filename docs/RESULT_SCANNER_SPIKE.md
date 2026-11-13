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
        CryptoBarQuery q, int maxPages, Consumer<? super CryptoBarPage> onPage);
CompletionStage<Result<ScanSummary>> scanTrades(
        CryptoTradeQuery q, int maxPages, Consumer<? super CryptoTradePage> onPage);
CompletionStage<Result<ScanSummary>> scanQuotes(
        CryptoQuoteQuery q, int maxPages, Consumer<? super CryptoQuotePage> onPage);
```

Each valid decoded page is delivered to the consumer **one at a time**, with the next provider request starting only after the consumer returns normally. No page list is built explicitly, although asynchronous completion stages may temporarily retain references; constant-memory use is not claimed without profiling. The returned summary describes a **successful** bounded traversal; it may contain a continuation if the cap was reached before provider exhaustion. The caller still chooses a 1–20 page bound.

The existing single-page `CompletionStage<Result<Crypto*Page>>` methods remain and are used unchanged.

## Example: a caller-owned acquisition checkpoint

```java
CryptoBarQuery request = CryptoBarQuery.of(
        List.of("BTC/USD"), CryptoTimeframe.minutes(1));

CompletionStage<Result<ScanSummary>> attempt =
        client.scanBars(request, 5, page -> {
            // Intended as an atomic checkpoint transaction in the job layer:
            store.savePageAndCheckpoint(page.bars(), page.continuation());
        });

// The job, not the crypto page or Atalanta, decides whether and when to retry:
attempt.thenAccept(result -> result.fold(
        summary -> { job.completeOrRequeue(summary.continuation()); return null; },
        error -> { job.classifyForRetryOrReconciliation(error); return null; }
));
```

`store` and `job` above are **illustrative application-layer objects, not new Atalanta classes**. The consumer must be careful to perform durable persistence and checkpoint updates transactionally; Atalanta does not promise exactly-once persistence, two-phase commit, or durable checkpoints. If the process dies between a write and its checkpoint, the job must handle restart/idempotency.

## Boundary and error semantics

| Situation | Behavior | Responsibility |
| --- | --- | --- |
| Valid page, next token provided | Callback receives page; only then fetch next page | Library orchestrates, caller stores/checkpoints |
| Valid short/empty page with token | Continue despite zero observations | Library |
| Reached 1–20 page cap | `Result.Ok(ScanSummary(..., Optional.of(token)))` | Caller decides next job window |
| Provider exhausted | `Result.Ok(ScanSummary(..., Optional.empty()))` | Caller decides completion |
| Provider HTTP 429, 503, timeout, network error | `Result.Err(AlpacaError)`; no market page carried in error | Library reports boundary failure; job chooses retry/backoff |
| Token repeated, including the caller's initial resume token | `Result.Err(InvalidResponse)`; do **not** deliver offending page | Library rejects invalid provider pagination |
| Consumer throws, e.g. disk/database unavailable | `CompletionStage` completes **exceptionally**; do not disguise as `AlpacaError` | Caller/job owns local failure |
| Cancellation of network future | Exceptional completion; no fabricated success or retry | Caller/job decides |

**Why no `These`/`Those` here:** the market-data page should not encode transport failure. The library's existing generic `Result` is sufficient for success/failure of this attempt, and checkpoint/retry classification is a higher-layer concern. A partial-result ADT may still be useful in **planned jobs** to describe successful/failed batches, but not as a replacement for ordinary market observations.

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

**Synchronous Consumer vs asynchronous acknowledgement:** `Consumer<P>` keeps the public signature simple, but a slow or blocking database callback runs on the completion thread of the provider stage. For a large async ingestion pipeline, `Function<P, CompletionStage<Void>>` would permit non-blocking persistence with the next request waiting for that stage. **This is the main remaining decision before establishing a stable public scanning API**. We should compare both with real DB ingestion requirements.

**Convenience aggregation:** the existing `crypto*Pages` methods retain a small in-memory batch, which is useful to interactive users/tests. We can keep these as documented convenience functions implemented on top of the generic scanner, or deprecate them later after a source-compatibility review. **This draft does neither**; it adds an alternative.

**No automatic retry:** neither the generic scanner nor the client should convert a 429/503 into a planned retry. A job scheduler can use `AlpacaError` to categorize retryable, nonretryable and needs-reconciliation outcomes, with persisted attempt metadata. Error categorization is a **job-level decision**, not a field on `CryptoBar` / `CryptoTrade` / `CryptoQuote`.

## Verification / non-goals

The experimental unit tests use a fake provider, plus real Bars/Trades/Quotes decoders against an injected synthetic Market Data HTTP sender. They cover: empty/short pages with continuation, cap, no accumulated output pages, exact observation counts, initial/repeated token cycles, consumer exceptions, provider failure, async fetch sequencing, cancellation and exact endpoint routing with no paper requests.

Full production API tests should remain green; **no live HTTP requests, POST/DELETE, database access, or job retry policy are added**. A merge would be additive on Java 17, but this PR is intentionally marked draft pending product-owner review.

See [post-feature consistency audit](POST_FEATURE_CONSISTENCY_AUDIT.md) and [existing Result contract](../src/main/java/bbb/atalanta/Result.java).

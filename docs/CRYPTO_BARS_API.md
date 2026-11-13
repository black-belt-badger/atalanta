# Atalanta M4: Historical crypto bars

**Endpoint:** authenticated, read-only `GET https://data.alpaca.markets/v1beta3/crypto/{loc}/bars` (Alpaca Market Data API). Implementation issue: [#29](https://github.com/black-belt-badger/atalanta/issues/29). Historical bars are not broker orders and never change a paper account.

This is a **Java 17 library API**, not a Java CLI. It follows the provider contract already exercised by Kirke (Haskell) and Medea (Rust) without forcing CLI file-output or terminal options into Java.

## Usage

```java
Atalanta alpaca = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CryptoBarQuery query = CryptoBarQuery.of(
        List.of("BTC/USD", "ETH/USD"), CryptoTimeframe.minutes(1))
        .withLocation(CryptoLocation.US)
        .withStart("2026-10-02T00:00:00.123456789Z")
        .withEnd("2026-10-03T00:00:00Z")
        .withLimit(1000)
        .withSort(CryptoBarQuery.Sort.ASC);

// Exactly ONE provider page: a continuation may be present even on short pages.
CompletionStage<Result<CryptoBarPage>> onePage = alpaca.cryptoBars(query);

// At most three pages; preserve successful pages and the failure if a later page fails.
CompletionStage<CryptoBarScan> retrieval = alpaca.cryptoBarsPages(query, 3);
retrieval.thenAccept(scan -> {
    if (scan instanceof CryptoBarScan.Stopped stopped) {
        for (CryptoBarPage page : stopped.pages()) {
            System.out.println("Bars: " + page.barCount());
        }
        // If present, stopped.continuation() can be used with query.withPageToken(...).
        // stopped.exhausted() is true only when Alpaca returned no more continuation.
    } else if (scan instanceof CryptoBarScan.Failed failed) {
        // Preserve successfully fetched data AND the typed terminal failure.
        System.err.println("Received pages: " + failed.pages().size());
        System.err.println("Error type: " + failed.error().getClass().getSimpleName());
    }
});
```

The call to `Atalanta.paper(...)` constructs two **independent, fixed-host transports** sharing the caller's API credentials: paper Trading API (`paper-api.alpaca.markets`) and read-only crypto Market Data (`data.alpaca.markets`). The latter's implementation exposes only allowlisted read-only `bars`, `trades`, `quotes` operation names internally. There is no live trading host, arbitrary URL, redirects, or hidden request retries.

## Query contract

| Java model | Alpaca field | Behavior |
| --- | --- | --- |
| `CryptoLocation.US / US_1 / US_2 / EU_1 / BS_1` | `loc` | Allowlisted path segment, defaults to `us` |
| `List<String> symbols` | `symbols` | Required 1–30 validated uppercase `BASE/QUOTE` pairs, joined by comma and encoded once |
| `CryptoTimeframe` | `timeframe` | Required: 1–59 minutes, 1–23 hours, 1 day/week, supported months 1/2/3/4/6/12 and Alpaca aliases |
| `withStart / withEnd` | `start` / `end` | Optional inclusive YYYY-MM-DD or qualified RFC3339 with up to nanosecond fractions; original spelling retained; compare absolute instants |
| `withLimit(1..10000)` | `limit` | Optional, Alpaca default **1,000 across all symbols per page**, not per symbol |
| `withSort(ASC / DESC)` | `sort` | Optional; Alpaca defaults ascending |
| `withPageToken(token)` | `page_token` | Opaque printable provider token, passed unmodified and encoded once |
| `cryptoBarsPages(query, 1..20)` | local bound | Explicit max pages (no unbounded fetch), not an Alpaca parameter |

Neither start nor end is invented by the library; Alpaca chooses defaults when omitted. A page can be short or empty **and still have another page**. Follow `next_page_token`, not the number of returned bars. Caller-supplied offset-aware times are validated without changing their textual representation.

## Response contract

```json
{
  "bars": {
    "BTC/USD": [{
      "t": "2026-10-02T00:00:00.123456789Z",
      "o": 29000.000000001,
      "h": 29003.000000001,
      "l": 28999.000000001,
      "c": 29002.000000001,
      "v": 0.000000000000000123456,
      "n": 4,
      "vw": 29001.000000001
    }]
  },
  "next_page_token": "opaque"
}
```

`CryptoBarPage.bars()` is an immutable symbol-keyed map of immutable bar lists. A `CryptoBar` contains:

- `timestamp: String` — original RFC3339 string (preserves nanosecond fractions and offset instead of rounding to milliseconds).
- `open/high/low/close/volume: BigDecimal` — required JSON numbers parsed without binary floating-point.
- `tradeCount: BigInteger` — optional nonnegative integer, capable of values above signed 64-bit.
- `vwap: BigDecimal` — optional, can be null.
- `presentFields` and `supplied(wireName)` — distinguish missing `n`/`vw` from explicit JSON null.
- `additionalFields: Map<String,Object>` — immutable future-provider fields; nested structures retain exact numbers and null.

The page similarly retains unknown top-level fields and `nextPageToken` (null/absent/empty indicates no continuation). Numeric **values** are exact; JSON parsing/serialization may normalize insignificant trailing zeros and object key order. No raw provider bytes are exposed as a public API promise.

Wrong root types, wrong required fields, malformed timestamps, negative trade counts, wrong token types, invalid numeric types and malformed JSON produce sanitized `AlpacaError.InvalidResponse`, not leaked provider payloads.

## Bounded pagination outcomes

`cryptoBars(query)` returns `CompletionStage<Result<CryptoBarPage>>`: success or a typed `AlpacaError`. No hidden second request occurs.

`cryptoBarsPages(query, maxPages)` returns `CompletionStage<CryptoBarScan>` with exactly two variants:

| Outcome | Meaning |
| --- | --- |
| `CryptoBarScan.Stopped(pages, continuation)` | Stopped at provider exhaustion (continuation empty) or local bound (continuation present); not necessarily all requested history |
| `CryptoBarScan.Failed(pages, error)` | At least one page request, response validation, or continuation failed. All previously valid pages remain available alongside the typed error. |

The traversal requests at most 20 pages and retains only those bounded pages in immutable result lists (each page up to 10,000 bars). It never fetches the same continuation token repeatedly and stops on 401/403/429/5xx or invalid JSON. For truly massive historical datasets, callers should request **one page at a time**, consume it, then manually resume using the returned token; don't accumulate all history in memory.

The explicit two-outcome scan type is scoped to this endpoint; it does **not** approve a new shared functional algebra or change an ADR.

## Offline testing and scope

JUnit/AssertJ tests use fake HTTP responses and synthetic credentials. They cover every documented location and timeframe family, strict query validation, RFC3339 offsets and exact encoding, high-precision OHLCV, nanosecond timestamp text, large counts, sparse/empty pages, unknown fields, nullable optional fields, bad JSON, HTTP error classification, network/timeout, late-page errors retaining earlier pages, repeated tokens and explicit page caps.

No credentials, account data, order mutations, or external Alpaca calls appear in CI. Atalanta still targets Java 17. This ticket does not add trades/quotes, live trading, streaming, a Java CLI, architectural refactoring or global triparity decisions.

**Provider reference:** [Alpaca Historical Crypto Bars](https://docs.alpaca.markets/us/reference/cryptobars-1).

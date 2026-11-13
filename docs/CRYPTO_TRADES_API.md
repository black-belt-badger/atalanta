# Atalanta M4 — Historical crypto trades

**Alpaca endpoint:** `GET https://data.alpaca.markets/v1beta3/crypto/{loc}/trades`
**Issue:** [Atalanta #30](https://github.com/black-belt-badger/atalanta/issues/30)
**Reference implementations:** Kirke and Medea historical crypto trades.

This is **read-only market data**, independent of paper Trading API orders and cancellation. It is a Java 17 library API, not a CLI.

## Usage

```java
Atalanta alpaca = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CryptoTradeQuery query = CryptoTradeQuery.of(List.of("BTC/USD", "ETH/USD"))
        .withLocation(CryptoLocation.EU_1)
        .withStart("2026-10-02T00:00:00.123456789Z")
        .withEnd("2026-10-03T00:00:00Z")
        .withSort(CryptoTradeQuery.Sort.ASC)
        .withLimit(1000);

// Exactly one provider page and its continuation token.
CompletionStage<Result<CryptoTradePage>> first = alpaca.cryptoTrades(query);

// At most 3 pages; valid earlier pages survive a later-page error.
CompletionStage<CryptoTradeScan> bounded = alpaca.cryptoTradesPages(query, 3);
bounded.thenAccept(scan -> {
    if (scan instanceof CryptoTradeScan.Stopped stopped) {
        for (CryptoTradePage page : stopped.pages()) {
            System.out.println("Trades: " + page.tradeCount());
        }
        // stopped.continuation(): optional cursor if not yet exhausted.
    } else if (scan instanceof CryptoTradeScan.Failed failed) {
        System.err.println("Valid pages: " + failed.pages().size());
        System.err.println(failed.error().getClass().getSimpleName());
    }
});
```

For large historical datasets, request **one page** at a time, consume it, and continue using `withPageToken(token)`; do not accumulate more data in application memory than necessary.

## Request contract

| Java value | Alpaca parameter | Behavior |
| --- | --- | --- |
| `CryptoLocation` | `{loc}` | Typed: `us` (default), `us-1`, `us-2`, `eu-1`, `bs-1` |
| `List<String> symbols` | `symbols` | Required: 1–30 validated uppercase crypto pairs, e.g. `BTC/USD` |
| `withStart` / `withEnd` | `start` / `end` | Inclusive date or timezone-qualified RFC3339; original spelling and fractional precision retained |
| `withLimit(1..10000)` | `limit` | Optional, default 1,000 **total trades per response page across all symbols**, not per symbol |
| `withSort(ASC/DESC)` | `sort` | Optional; provider default ascending |
| `withPageToken` | `page_token` | Opaque printable provider cursor, forwarded unchanged and URL-encoded once |
| `cryptoTradesPages(query, 1..20)` | local only | Explicit bounded pagination; not an Alpaca query parameter |

There is **no timeframe** on trades. Unspecified optional filters are omitted rather than replaced with inferred defaults. The start/end comparison checks absolute time across offsets. A page can be short or empty and still have a continuation, so inspect `next_page_token` rather than the number of trades.

## Response model and numeric fidelity

```json
{
  "trades": {
    "BTC/USD": [{
      "i": 31455289,
      "p": 29791.125,
      "s": 0.0016,
      "t": "2026-10-02T00:00:00.123456789Z",
      "tks": "S"
    }]
  },
  "next_page_token": "opaque-cursor"
}
```

`CryptoTradePage` exposes an immutable symbol-keyed map, `nextPageToken()`, `continuation()` and immutable unknown provider fields. Empty symbol groups and missing/empty continuation are valid.

`CryptoTrade` fields:

| Java type | Alpaca field | Semantics |
| --- | --- | --- |
| `BigInteger id` | `i` | Required, nonnegative; does not truncate beyond signed 64-bit |
| `BigDecimal price` | `p` | Required exact JSON decimal |
| `BigDecimal size` | `s` | Required exact JSON decimal |
| `String timestamp` | `t` | Required RFC3339, preserved verbatim, including nanoseconds |
| `String takerSide` | `tks` | Optional/nullable, provider string, not restricted to currently known codes |
| `Set<String> presentFields` | field presence | `supplied("tks")` distinguishes missing from explicit null |
| `Map<String,Object> additionalFields` | unknown fields | Deeply immutable future-provider fields, exact numeric values and null |

**No binary floating-point conversions.** Insignificant trailing zero scale or object member order may be normalized by JSON parsing; financial numeric values are exact. No Jackson types enter public method signatures.

Wrong/missing mandatory fields, nonintegral/negative IDs, invalid timestamps, invalid taker-side types, malformed page token and wrong root structures produce a sanitized `AlpacaError.InvalidResponse`.

## Pagination outcomes

`cryptoTrades(query)` returns `CompletionStage<Result<CryptoTradePage>>` and fetches only one provider page.

`cryptoTradesPages(query, maxPages)` returns one of:

- `CryptoTradeScan.Stopped(pages, continuation)` — valid immutable pages, with an optional cursor if the page cap stopped retrieval; `exhausted()` is true only at provider exhaustion.
- `CryptoTradeScan.Failed(pages, error)` — valid earlier pages and the typed error from a later HTTP/transport/JSON/repeated-token failure; never reports partial failure as successful exhaustion.

The scanner requests no more than 20 pages, rejects repeated tokens and never automatically retries. Reuse of the bars-shaped pattern is intentionally scoped to this endpoint rather than a premature shared API overhaul.

## Transport, safety and offline tests

Historical trades reuse the authenticated `CryptoMarketDataTransport` introduced by bars. Requests go exclusively to `https://data.alpaca.markets`, with a 20-second timeout, disabled redirects, and sanitized typed errors. No paper account mutations, live trading URLs or arbitrary request paths are exposed.

Offline Java 17 JUnit/AssertJ tests use synthetic fixtures and credentials. They exercise the exact URL/query, all five locations, precise huge IDs and decimals, nanosecond times, optional taker-side presence, provider extensions, malformed response shapes, empty and short pages, continuation, page caps, repeated tokens, 401/403/429/5xx, timeouts, and errors after earlier successful pages.

**No real Alpaca requests are executed in CI.**

[Alpaca historical crypto trades API reference](https://docs.alpaca.markets/us/reference/cryptotrades-1).

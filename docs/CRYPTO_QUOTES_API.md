# Atalanta M4: Historical crypto quotes

**Endpoint:** authenticated read-only `GET https://data.alpaca.markets/v1beta3/crypto/{loc}/quotes`. [Alpaca reference](https://docs.alpaca.markets/us/reference/cryptoquotes-1) · [Atalanta issue #31](https://github.com/black-belt-badger/atalanta/issues/31).

Quotes are timestamped **best bid and ask observations**, not OHLCV bars. The Java 17 API has **no timeframe parameter**. This implementation reuses the fixed-host `CryptoMarketDataTransport`; it never sends requests to paper order endpoints or the live trading host.

## Usage

```java
Atalanta alpaca = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CryptoQuoteQuery query = CryptoQuoteQuery.of(List.of("BTC/USD", "ETH/USD"))
        .withLocation(CryptoLocation.US)
        .withStart("2026-10-02T00:00:00.123456789Z")
        .withEnd("2026-10-03T00:00:00Z")
        .withLimit(1000)
        .withSort(CryptoQuoteQuery.Sort.ASC);

// Exactly one page with an optional continuation.
CompletionStage<Result<CryptoQuotePage>> single = alpaca.cryptoQuotes(query);

// Explicitly bounded 1..20-page traversal retaining partial successes.
CompletionStage<CryptoQuoteScan> bounded = alpaca.cryptoQuotesPages(query, 3);
bounded.thenAccept(scan -> {
    if (scan instanceof CryptoQuoteScan.Stopped stopped) {
        for (CryptoQuotePage page : stopped.pages()) {
            System.out.println("Quote observations: " + page.quoteCount());
        }
        // stopped.continuation() may contain the next opaque token.
    } else if (scan instanceof CryptoQuoteScan.Failed failed) {
        System.err.println("Successful pages: " + failed.pages().size());
        System.err.println("Error: " + failed.error().getClass().getSimpleName());
    }
});
```

The Trading and Market Data transports use separate fixed hosts and share only the supplied API credentials. Public values are immutable Java 17 records; clients receive typed `Result<T>` / `AlpacaError` rather than Jackson nodes or floating-point doubles.

## Request contract

| Java API | Provider field | Behavior |
| --- | --- | --- |
| `CryptoLocation.US / US_1 / US_2 / EU_1 / BS_1` | `{loc}` | Allowlisted path; defaults to US |
| `List<String> symbols` | `symbols` | Required 1–30 uppercase BASE/QUOTE pairs, comma-separated and URI encoded once |
| `withStart(String)` / `withEnd(String)` | `start` / `end` | Optional *inclusive* date or offset-qualified RFC3339, including nanoseconds; original spelling preserved |
| `withLimit(int)` | `limit` | Optional 1–10,000; provider defaults 1,000 **total across symbols per page** |
| `withSort(ASC / DESC)` | `sort` | Optional; provider defaults ascending |
| `withPageToken(String)` | `page_token` | Opaque provider continuation, validated and percent encoded |
| `cryptoQuotesPages(query, 1..20)` | local cap | Maximum pages, not a query parameter |
| *None* | `timeframe` | **Not supported or sent** |

Omitted dates, limit and sort are **not invented by the Java client**. A response can be short or empty and still include a `next_page_token`; always follow the token rather than comparing record counts.

## Response contract

```json
{
  "quotes": {
    "BTC/USD": [{
      "ap": 29059.123456789012345678,
      "as": 0.000000000000000123456,
      "bp": 29058.987654321012345678,
      "bs": 0.3544,
      "t": "2026-10-02T00:00:00.123456789Z"
    }]
  },
  "next_page_token": "OPAQUE_TOKEN"
}
```

Each `CryptoQuotePage` exposes an immutable symbol-keyed map of immutable quote lists, an optional continuation and immutable provider extension properties.

| JSON | Java component | Meaning / validation |
| --- | --- | --- |
| `ap` | `BigDecimal askPrice` | Required exact JSON decimal; nonnegative |
| `as` | `BigDecimal askSize` | Required; size measured in **base asset**, no lot multiplier |
| `bp` | `BigDecimal bidPrice` | Required exact JSON decimal; nonnegative |
| `bs` | `BigDecimal bidSize` | Required; size measured in **base asset**, no lot multiplier |
| `t` | `String timestamp` | Required offset-qualified RFC3339, original precision/text retained |

All five fields are required. A missing, null, wrongly typed or invalid value rejects the page rather than silently inventing prices. Unknown quote/page fields are copied recursively into immutable Java maps with exact `BigDecimal`/`BigInteger` extension numbers and explicit nulls. `CryptoQuote.supplied(wireName)` distinguishes missing fields from explicit null. A missing/null/empty `next_page_token` means provider exhaustion. Numerical values remain exact, though original JSON lexical formatting is not guaranteed.

## Bounded pagination and errors

- `cryptoQuotes(query)` fetches exactly **one** GET, returning `CompletionStage<Result<CryptoQuotePage>>`.
- `cryptoQuotesPages(query,maxPages)` returns `CompletionStage<CryptoQuoteScan>`: either `Stopped(pages, Optional<continuation>)`, meaning exhaustion or explicit page cap, or `Failed(pages, AlpacaError)` retaining prior valid pages and typed error.
- Short or empty pages do not terminate retrieval while the provider supplies a token. Repeated/resume-token cycles are rejected. At most 20 pages are retained, each limited to 10,000 observations. Prefer manual single-page retrieval for large historical backfills.
- Invalid parameters fail before I/O. Malformed response schemas become sanitized `AlpacaError.InvalidResponse`; HTTP 401, 403, 429, 5xx and network/timeout responses retain the shared typed failure model.
- No redirects, implicit retries, production credentials in tests, Java CLI, order writes or live trading.

## Offline verification

JUnit 5 + AssertJ tests exercise precise decimal and timestamp values, request defaults and all filters, URL encoding, empty/sparse/malformed JSON, immutable nested provider fields and explicit null, full error classification, multi-page short/empty results, repeated/resume tokens, page bounds and later-page error retention. All test responses and credentials are synthetic; **no live Alpaca access or order mutation** occurs.

This M4 endpoint follows Kirke and Medea's historical quote semantics, without approving the deferred triparity/CLI redesign. See [Bars](CRYPTO_BARS_API.md) for the compatible market-data transport design.

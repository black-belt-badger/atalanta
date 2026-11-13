# Positions endpoint contract — Java 17

**Endpoint:** authenticated `GET /v2/positions` using Alpaca's **paper Trading API**. This lists the account's **currently open positions**, not history or closed positions. There are **no documented request parameters**, no pagination, and no symbol path parameter on this collection endpoint.

## Public API

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CompletionStage<Result<List<Position>>> result = client.positions();
```

`Position` and `PositionUsd` are immutable Java 17 records. Values are returned asynchronously with our own sealed `Result<T>` and `AlpacaError`; neither Jackson nor the JDK HTTP client appears in the public method signatures. A valid empty portfolio returns `Result.Ok(List.of())`, **not an error**. Closed positions are not returned by Alpaca's list endpoint.

## Response field coverage

| Alpaca field | Public Java record field | Semantics |
|---|---|---|
| `asset_id` | `Position.assetId(): UUID` | Required provider asset identity |
| `symbol`, `asset_class`, `side` | `String` | Required nonblank; future classes/sides retained verbatim |
| `exchange` | `String` | Optional; empty string is valid for options and IPO allocations |
| `asset_marginable` | `Boolean` | Optional/nullable for class-dependent replies |
| `qty` | `BigDecimal` | Required exact fractional quantity; accepts positive, negative and zero |
| `qty_available` | `BigDecimal` | Optional; shares/units not tied up by open orders |
| `avg_entry_price`, `cost_basis` | `BigDecimal` | Class-dependent: exact entry and cost values |
| `market_value`, `current_price`, `lastday_price` | `BigDecimal` | Optional/nullable for pre-listing IPO allocations |
| `change_today` | `BigDecimal` | Ratio, not a display percentage |
| `unrealized_pl`, `unrealized_plpc` | `BigDecimal` | Overall mark-to-market P/L and fractional rate |
| `unrealized_intraday_pl`, `unrealized_intraday_plpc` | `BigDecimal` | Intraday P/L and fractional rate |
| `swap_rate`, `prev_swap_rate`, `avg_entry_swap_rate` | `BigDecimal` | Optional foreign-exchange rates for local-currency trading (LCT) |
| `usd` | `PositionUsd` | Optional, typed nested USD valuation (LCT) |
| Future properties | `additionalFields(): Map<String,Object>` | Immutable deep Java maps/lists, exact decimals/integers and nulls; no Jackson |
| Original field presence | `presentFields(): Set<String>` and `supplied(String)` | Distinguishes absent from explicit null, numerical zero or an empty string |

The `PositionUsd` record covers its ten documented USD fields: `avg_entry_price`, `change_today`, `cost_basis`, `current_price`, `lastday_price`, `market_value`, `unrealized_intraday_pl`, `unrealized_intraday_plpc`, `unrealized_pl`, and `unrealized_plpc`. It also retains new/unknown nested properties and field presence information.

### Precision and nullability

- Financial values and quantities use `BigDecimal`, not binary `float` or `double`. The provider normally sends them as decimal strings; the decoder also accepts exact JSON numeric nodes.
- `qty`, `asset_id`, `symbol`, `asset_class` and `side` are required. Other fields may be omitted/null because schemas differ across equity, crypto, options, IPO and LCT use cases.
- The [Alpaca IPO guidance](https://docs.alpaca.markets/us/docs/us-ipos-initial-public-offerings-1) explicitly permits open IPO positions with an empty `exchange` and null current/last-day prices, market value, and P/L. The decoder must not reject them.
- Unrecognized new asset classes or side strings are retained without imposing a closed Java enum; unknown fields remain deeply immutable standard Java values.
- Position records override `toString()` to avoid accidentally logging confidential holdings or prices.

### Errors

The existing package-private transport translates authentication/permission failures, rate limits, timeout, I/O, and HTTP errors into `Result.Err(AlpacaError)`. Valid but unexpected JSON shapes or malformed position members become `InvalidResponse`, with no provider body/identifier leaked in error messages. Cancellation and unexpected programming exceptions remain exceptional stage completion rather than routine `Err`.

This endpoint intentionally does **not** implement `GET /v2/positions/{symbol_or_asset_id}` or order/position liquidation.

## Synthetic offline fixtures and tests

- Full US equity position, precise fractional quantity, all P/L fields and unknown nested data.
- Crypto position with very long fractional precision and negative P/L.
- Short US option position with an empty exchange and missing optional financial fields.
- IPO allocation with null market prices, valuation and intraday P/L.
- LCT position with exchange rates and a nested USD valuation containing additional future fields.
- Future asset class/side strings and zero quantity.
- Empty portfolio, malformed JSON/root/member, missing identity/quantity, incorrect scalar/JSON types and privacy checks.
- Mock asynchronous HTTP completion and the exact one-request `GET /v2/positions` route, without any query parameters or credentials in CI.

## References and paired work

- [Official All Open Positions API](https://docs.alpaca.markets/us/reference/getallopenpositions)
- [Position Average Entry Price Calculation](https://docs.alpaca.markets/us/docs/position-average-entry-price-calculation)
- [Local Currency Trading and nested USD response](https://docs.alpaca.markets/us/docs/local-currency-trading-lct)
- [IPO pre-listing position nullability](https://docs.alpaca.markets/us/docs/us-ipos-initial-public-offerings-1)
- [Kirke M3 positions contract #34](https://github.com/black-belt-badger/kirke/issues/34) and [Medea M3 positions contract #35](https://github.com/black-belt-badger/medea/issues/35)

**Contract distinction:** Kirke/Medea expose CLI-oriented summaries and raw JSON exports. Atalanta's consumer API instead returns typed immutable records plus forward-compatible extension fields, consistent with the explicit decision in Atalanta #5 that Jackson and raw JSON are implementation details.

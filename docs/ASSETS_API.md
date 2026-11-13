# Assets endpoint contract — Java 17

**Endpoint:** authenticated `GET /v2/assets` on Alpaca's **paper Trading API**. This is read-only, no mutation or order placement.

## Java consumer API

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

// Alpaca's unmodified default: US equities, all statuses.
CompletionStage<Result<List<Asset>>> equities = client.assets();

// Explicit crypto selection, active status only.
CompletionStage<Result<List<Asset>>> crypto =
        client.assets(AssetQuery.crypto().withStatus("active"));

// Exchange and attribute filters; server matches ANY selected attribute.
CompletionStage<Result<List<Asset>>> filtered =
        client.assets(AssetQuery.defaults()
                .withAssetClass("us_equity")
                .withStatus("active")
                .withExchanges(List.of("NASDAQ", "NYSE", "TXSE"))
                .withAttributes(List.of("has_options", "overnight_tradable")));
```

`Asset` and `AssetQuery` are Java 17 records. The API is asynchronous and returns our own `Result<T>` and sealed `AlpacaError`. No Jackson, raw JSON nodes, HTTP client classes or FP libraries appear in public method signatures.

## Request coverage

| Query parameter | Alpaca behavior | Atalanta |
|---|---|---|
| `status` | Omitted includes all statuses | `withStatus("active" / "inactive")`, or leave null |
| `asset_class` | Omitted defaults to `us_equity` | `defaults()` omits it; `crypto()` sends `crypto`; arbitrary future safe tokens allowed |
| `exchange` | Optional comma-separated filter, applicable to `us_equity` / `us_index` | `withExchanges(List<String>)` sends CSV, rejects filters on crypto/other classes |
| `attributes` | Optional comma-separated values; matches assets containing **any** selected attribute | `withAttributes(List<String>)` sends CSV and accepts future safe codes |

The documented exchange filters for `us_equity` are AMEX, ARCA, BATS, NYSE, NASDAQ, NYSEARCA, OTC and TXSE. `INDEX` requires `asset_class=us_index`, and the `us_index` exchange filter is `INDEX` only. Crypto and crypto perpetual asset classes cannot be filtered by exchange: requesting an exchange there is rejected instead of silently ignored.

Documented attribute values as of November 2026:

- `ptp_no_exception`, `ptp_with_exception`, `ipo`, `has_options`
- `options_late_close`, `fractional_eh_enabled`, `overnight_tradable`, `overnight_halted`

Query values are single, safe API tokens, assembled into CSV by the library; path and query encoding are handled once by the common paper HTTP transport. We do not change the server's default sorting (asset class, exchange, symbol) or replace the default with the crypto preference of the Kirke/Medea CLIs.

## Response coverage matrix

| Alpaca response property | Public Java representation | Notes |
|---|---|---|
| `id` | `UUID` | Required |
| `class`, `exchange`, `symbol`, `name`, `status` | `String` | Required nonempty; preserve future enum strings |
| `tradable`, `marginable`, `shortable`, `fractionable` | `boolean` | Required |
| `borrow_status` | `String` | Optional; current `easy_to_borrow` or `hard_to_borrow`; unknown future values accepted |
| `easy_to_borrow` | `Boolean` | Deprecated; optional and supported for older payloads |
| `cusip` | `String` | Optional/nullable, US equities |
| `margin_requirement_long`, `margin_requirement_short` | `BigDecimal` | Optional; exact values, normally provider decimal strings |
| `maintenance_margin_requirement` | `BigDecimal` | Deprecated numeric field; optional |
| `min_order_size`, `min_trade_increment`, `price_increment` | `BigDecimal` | Optional crypto increments; exact decimals |
| `attributes` | Immutable `List<String>` | Optional; empty array distinct from null/missing |
| Future/unrecognized properties | Immutable `Map<String,Object>` | Deep standard-Java maps/lists, `BigDecimal`, `BigInteger`, booleans, text, null |

Use `asset.supplied("field_name")` to distinguish **omitted** from **explicit JSON null** (and from zero). `additionalFields()` preserves unknown response properties without leaking Jackson. The library retains typed values and unknown fields semantically; it does not promise a byte-for-byte export of the HTTP response body. As with `Account`, raw JSON is intentionally not part of Atalanta's public API.

`GET /v2/assets` returns an array. An empty array becomes `Result.Ok(List.of())`; a non-array, malformed member, invalid required field, malformed decimal or invalid attribute type produces `Result.Err(InvalidResponse)`. HTTP 401/403/429, timeout and transport failures reuse the common sealed errors.

## Notable 2026 API changes

- June 2026: crypto size and price increments; optional `borrow_status` and deprecation of `easy_to_borrow`.
- July 2026: new asset classes including `crypto_perp`, `treasury`, `us_index`, `global_equity`; exchange `CRYPTO`.
- August 2026: long/short margin requirements documented as **decimal strings**.

## Offline fixtures and tests

Synthetic US equity, crypto, index and crypto-perpetual examples; all filters, server defaults, query encoding, multi-value attributes, field types and immutability, unknown fields, missing/null/zero, malformed/empty data, exact decimals, asynchronous I/O and typed provider errors. No real account credentials or Internet connections are required for CI.

## References

- [Official Alpaca Get Assets reference](https://docs.alpaca.markets/us/reference/get-v2-assets-1)
- [June 5: borrow status](https://docs.alpaca.markets/us/changelog/2026-06-05-borrow-status-6b96a5a)
- [June 24: expanded trading API fields](https://docs.alpaca.markets/us/v1.1/changelog/2026-06-24-trading-api-00bf221)
- [July 6: expanded AssetClass](https://docs.alpaca.markets/us/v1.4.2/changelog/2026-07-06-asset-class-ab58bcb)
- [July 6: CRYPTO exchange](https://docs.alpaca.markets/us/v1.1/changelog/2026-07-06-crypto-exchange-ac398a2)
- [August 28: margin decimal strings](https://docs.alpaca.markets/us/v1.1/changelog/2026-08-28-margin-requirement-4638459)
- [Kirke assets fidelity #33](https://github.com/black-belt-badger/kirke/issues/33) · [Medea assets fidelity #34](https://github.com/black-belt-badger/medea/issues/34)

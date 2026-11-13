# Account endpoint contract — Java 17

**Endpoint:** authenticated `GET /v2/account` on Alpaca's **paper Trading API**. There are **no request parameters**. Atalanta adds no undocumented query defaults.

## Public API

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

CompletionStage<Result<Account>> snapshot = client.account();
```

- `Atalanta` is a final class. `Account` and `AccountDetails` are Java 17 records; Jackson, `JsonNode` and HTTP request/response types remain private to the implementation.
- Successful requests yield `Result.Ok<Account>`. Authentication errors, 429, timeouts, network errors and malformed provider responses yield `Result.Err<AlpacaError>`. Programming defects/cancellation can complete exceptionally.
- `Account` exposes `id` (`UUID`), `accountNumber`, `status`, `cryptoStatus`, `currency`, `cash`, `equity`, `buyingPower`, `tradingBlocked` and `details`.
- `AccountDetails` exposes the remaining documented fields in typed components. Amounts/quantities are `BigDecimal`; flags `Boolean`; option levels and counters `Integer`; date/time wire strings `String` to retain subsecond precision and timezone spelling.
- Unrecognized future `status` and `crypto_status` strings are preserved unchanged. Unknown fields survive in immutable `additionalFields` made of standard Java values (nested maps/lists, strings, booleans, `BigInteger` and `BigDecimal`), **never Jackson types**.
- Optional property values are nullable; `account.details().supplied("field_name")` distinguishes *omitted* from explicit JSON `null` and from numerical zero. Required fields: valid UUID `id` and nonblank string `status`; other missing fields do not make the response invalid.
- Both records redact sensitive data in `toString()`. Do not print personal account numbers, identifiers, balances or extension values to logs.

## Field coverage matrix

| Provider fields | Java representation | Coverage |
|---|---|---|
| `id`, `status` | UUID, String | Required, validated; future status values accepted |
| `account_number`, `crypto_status`, `currency` | String | Optional/nullable, preserves unknown status text |
| `cash`, `equity`, `buying_power` | BigDecimal | Common public account fields; exact |
| `trading_blocked` | Boolean | Optional and distinguishable from absent |
| `regt_buying_power`, `non_marginable_buying_power`, `options_buying_power`, `effective_buying_power`, `daytrading_buying_power` | BigDecimal | Typed optional; legacy daytrading value accepted |
| `portfolio_value`, `accrued_fees`, `pending_transfer_in`, `pending_transfer_out`, `intraday_adjustments`, `pending_reg_taf_fees`, `sma` | BigDecimal | Typed optional |
| `initial_margin`, `maintenance_margin`, `last_maintenance_margin`, `last_initial_margin` | BigDecimal | Typed optional |
| `long_market_value`, `short_market_value`, `position_market_value`, `last_long_market_value`, `last_short_market_value` | BigDecimal | Typed optional |
| `last_equity`, `last_cash`, `last_regt_buying_power`, `last_daytrading_buying_power`, `multiplier` | BigDecimal | Typed optional; deprecated prior-day buying power tolerated |
| `cash_withdrawable`, `cash_transferable` | BigDecimal | Typed optional |
| `account_blocked`, `transfers_blocked`, `trade_suspended_by_user`, `shorting_enabled`, `pattern_day_trader` | Boolean | Typed optional; deprecated PDT flag tolerated |
| `options_approved_level`, `options_trading_level`, `crypto_tier`, `daytrade_count`, `last_daytrade_count` | Integer | Typed optional; deprecated counters tolerated |
| `balance_asof`, `created_at`, `previous_close`, `clearing_broker` | String | Typed optional and provider spelling preserved |
| `admin_configurations`, `user_configurations` and future fields | Additional immutable standard-Java fields | Retained without a public JSON dependency |

**2026 API drift:** Alpaca added `crypto_status` and additional account status values on June 24, 2026. On July 6, 2026, its Trading API account schema removed deprecated PDT fields such as `pattern_day_trader`, `daytrade_count` and `daytrading_buying_power`. We do **not** require them; we still accept older payloads containing them.

## Offline acceptance tests

- Full synthetic account including crypto/options/margin fields, long exact decimal strings and nested unknown data.
- Minimal account with omitted fields, explicit null values, future statuses, and precise nonzero decimals.
- Legacy PDT fields, malformed JSON/UUID, invalid required/optional types, invalid option levels.
- Async call `GET /v2/account` with **no query parameters**, authentication headers, fake response, expected operational `Err` and exceptional programming failure.
- No real Alpaca credentials, real account information or external requests needed in CI.

## Sources and related work

- [Alpaca Get Account](https://docs.alpaca.markets/us/reference/getaccount-1)
- [Trading API additions, June 24, 2026](https://docs.alpaca.markets/us/changelog/2026-06-24-trading-api-00bf221)
- [Trading API PDT field removals, July 6, 2026](https://docs.alpaca.markets/us/changelog/2026-07-06-pdt-db49dba)
- [Kirke account fidelity #32](https://github.com/black-belt-badger/kirke/issues/32) · [Medea account fidelity #33](https://github.com/black-belt-badger/medea/issues/33)

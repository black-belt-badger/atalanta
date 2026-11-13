# Triparity: observed field-by-field synthetic wire inventory

**Generated from the canonical Atalanta fixture set revision `1.0.0`** by `python3 tools/triparity/field_inventory.py`. This inventory describes **wire fields actually present in the shared synthetic response examples**, not a complete provider OpenAPI schema, and does not imply that Kirke or Medea accepts them.

Each table reports JSON types, explicit nulls and omissions across sampled response objects. Financial decimal strings are listed as **strings** deliberately: JSON lexical type matters for decoder parity. The source JSON must never be parsed through a binary floating point.

The companion [triparity audit](TRIPARITY_AUDIT.md) identifies per-language decoder behavior and decisions. The verified [fixture manifest](../contracts/alpaca-trading/v1/manifest.json) contains the full request/error expectations.

## account — 36 observed wire keys

Observed objects in current synthetic success cases: **4**. Conditional and future provider fields are included.

| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |
| --- | --- | ---: | ---: | ---: | --- |
| `account_blocked` | boolean | 1 / 4 | 0 | 3 | `account.full` |
| `account_number` | string | 1 / 4 | 0 | 3 | `account.full` |
| `admin_configurations` | object | 1 / 4 | 0 | 3 | `account.full` |
| `balance_asof` | string | 1 / 4 | 0 | 3 | `account.full` |
| `buying_power` | string | 1 / 4 | 0 | 3 | `account.full` |
| `cash` | null, string | 2 / 4 | 1 | 2 | `account.full` |
| `created_at` | string | 1 / 4 | 0 | 3 | `account.full` |
| `crypto_status` | null, string | 2 / 4 | 1 | 2 | `account.full` |
| `crypto_tier` | number | 1 / 4 | 0 | 3 | `account.full` |
| `currency` | string | 1 / 4 | 0 | 3 | `account.full` |
| `daytrade_count` | number | 1 / 4 | 0 | 3 | `account.legacy` |
| `daytrading_buying_power` | null, string | 2 / 4 | 1 | 2 | `account.full` |
| `equity` | string | 1 / 4 | 0 | 3 | `account.full` |
| `id` | string | 3 / 4 | 0 | 1 | `account.full` |
| `initial_margin` | string | 1 / 4 | 0 | 3 | `account.full` |
| `last_daytrade_count` | number | 1 / 4 | 0 | 3 | `account.legacy` |
| `last_equity` | string | 1 / 4 | 0 | 3 | `account.full` |
| `last_maintenance_margin` | string | 1 / 4 | 0 | 3 | `account.full` |
| `long_market_value` | string | 1 / 4 | 0 | 3 | `account.full` |
| `maintenance_margin` | string | 1 / 4 | 0 | 3 | `account.full` |
| `non_marginable_buying_power` | string | 1 / 4 | 0 | 3 | `account.full` |
| `novel_balance` | object | 1 / 4 | 0 | 3 | `account.full` |
| `options_approved_level` | number | 1 / 4 | 0 | 3 | `account.full` |
| `options_buying_power` | string | 1 / 4 | 0 | 3 | `account.full` |
| `options_trading_level` | number | 1 / 4 | 0 | 3 | `account.full` |
| `pattern_day_trader` | boolean, null | 2 / 4 | 1 | 2 | `account.full` |
| `previous_close` | string | 1 / 4 | 0 | 3 | `account.full` |
| `regt_buying_power` | string | 1 / 4 | 0 | 3 | `account.full` |
| `short_market_value` | string | 1 / 4 | 0 | 3 | `account.full` |
| `shorting_enabled` | boolean | 1 / 4 | 0 | 3 | `account.full` |
| `status` | string | 3 / 4 | 0 | 1 | `account.full` |
| `synthetic` | string | 1 / 4 | 0 | 3 | `account.invalid_payload` |
| `trade_suspended_by_user` | boolean | 1 / 4 | 0 | 3 | `account.full` |
| `trading_blocked` | boolean, null | 2 / 4 | 1 | 2 | `account.full` |
| `transfers_blocked` | boolean | 1 / 4 | 0 | 3 | `account.full` |
| `user_configurations` | null | 1 / 4 | 1 | 3 | `account.full` |

## assets — 23 observed wire keys

Observed objects in current synthetic success cases: **7**. Conditional and future provider fields are included.

| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |
| --- | --- | ---: | ---: | ---: | --- |
| `attributes` | array, null | 5 / 7 | 1 | 2 | `assets.default` |
| `borrow_status` | null, string | 3 / 7 | 1 | 4 | `assets.default` |
| `class` | string | 6 / 7 | 0 | 1 | `assets.default` |
| `cusip` | null | 2 / 7 | 2 | 5 | `assets.default` |
| `easy_to_borrow` | boolean | 2 / 7 | 0 | 5 | `assets.default` |
| `exchange` | string | 6 / 7 | 0 | 1 | `assets.default` |
| `fractionable` | boolean | 6 / 7 | 0 | 1 | `assets.default` |
| `future` | object | 2 / 7 | 0 | 5 | `assets.default` |
| `future_null` | null | 2 / 7 | 2 | 5 | `assets.default` |
| `id` | string | 6 / 7 | 0 | 1 | `assets.default` |
| `maintenance_margin_requirement` | string | 2 / 7 | 0 | 5 | `assets.default` |
| `margin_requirement_long` | string | 2 / 7 | 0 | 5 | `assets.default` |
| `margin_requirement_short` | string | 2 / 7 | 0 | 5 | `assets.default` |
| `marginable` | boolean | 6 / 7 | 0 | 1 | `assets.default` |
| `min_order_size` | string | 2 / 7 | 0 | 5 | `assets.default` |
| `min_trade_increment` | string | 2 / 7 | 0 | 5 | `assets.default` |
| `name` | string | 6 / 7 | 0 | 1 | `assets.default` |
| `price_increment` | string | 2 / 7 | 0 | 5 | `assets.default` |
| `shortable` | boolean | 6 / 7 | 0 | 1 | `assets.default` |
| `status` | string | 6 / 7 | 0 | 1 | `assets.default` |
| `symbol` | string | 6 / 7 | 0 | 1 | `assets.default` |
| `synthetic` | string | 1 / 7 | 0 | 6 | `assets.invalid_shape` |
| `tradable` | boolean | 6 / 7 | 0 | 1 | `assets.default` |

## positions — 26 observed wire keys

Observed objects in current synthetic success cases: **7**. Conditional and future provider fields are included.

| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |
| --- | --- | ---: | ---: | ---: | --- |
| `asset_class` | string | 6 / 7 | 0 | 1 | `positions.open_mixed` |
| `asset_id` | string | 6 / 7 | 0 | 1 | `positions.open_mixed` |
| `asset_marginable` | boolean, null | 4 / 7 | 1 | 3 | `positions.open_mixed` |
| `avg_entry_price` | string | 4 / 7 | 0 | 3 | `positions.open_mixed` |
| `avg_entry_swap_rate` | string | 1 / 7 | 0 | 6 | `positions.open_mixed` |
| `change_today` | null, string | 4 / 7 | 1 | 3 | `positions.open_mixed` |
| `cost_basis` | string | 4 / 7 | 0 | 3 | `positions.open_mixed` |
| `current_price` | null, string | 5 / 7 | 1 | 2 | `positions.open_mixed` |
| `exchange` | string | 6 / 7 | 0 | 1 | `positions.open_mixed` |
| `future_null` | null | 1 / 7 | 1 | 6 | `positions.open_mixed` |
| `lastday_price` | null, string | 4 / 7 | 1 | 3 | `positions.open_mixed` |
| `market_value` | null, string | 5 / 7 | 1 | 2 | `positions.open_mixed` |
| `new_provider_field` | object | 1 / 7 | 0 | 6 | `positions.open_mixed` |
| `new_wire_flag` | boolean | 1 / 7 | 0 | 6 | `positions.open_mixed` |
| `prev_swap_rate` | string | 1 / 7 | 0 | 6 | `positions.open_mixed` |
| `qty` | string | 6 / 7 | 0 | 1 | `positions.open_mixed` |
| `qty_available` | string | 4 / 7 | 0 | 3 | `positions.open_mixed` |
| `side` | string | 6 / 7 | 0 | 1 | `positions.open_mixed` |
| `swap_rate` | null, string | 2 / 7 | 1 | 5 | `positions.open_mixed` |
| `symbol` | string | 6 / 7 | 0 | 1 | `positions.open_mixed` |
| `synthetic` | string | 1 / 7 | 0 | 6 | `positions.invalid_shape` |
| `unrealized_intraday_pl` | null, string | 4 / 7 | 1 | 3 | `positions.open_mixed` |
| `unrealized_intraday_plpc` | null, string | 4 / 7 | 1 | 3 | `positions.open_mixed` |
| `unrealized_pl` | null, string | 5 / 7 | 1 | 2 | `positions.open_mixed` |
| `unrealized_plpc` | null, string | 4 / 7 | 1 | 3 | `positions.open_mixed` |
| `usd` | object | 1 / 7 | 0 | 6 | `positions.open_mixed` |

## positions.usd — 12 observed wire keys

Observed objects in current synthetic success cases: **1**. Conditional and future provider fields are included.

| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |
| --- | --- | ---: | ---: | ---: | --- |
| `avg_entry_price` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `change_today` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `cost_basis` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `current_price` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `future_usd_null` | null | 1 / 1 | 1 | 0 | `positions.open_mixed` |
| `lastday_price` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `market_value` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `unknown_usd_field` | object | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `unrealized_intraday_pl` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `unrealized_intraday_plpc` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `unrealized_pl` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |
| `unrealized_plpc` | string | 1 / 1 | 0 | 0 | `positions.open_mixed` |

## orders — 40 observed wire keys

Observed objects in current synthetic success cases: **12**. Conditional and future provider fields are included.

| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |
| --- | --- | ---: | ---: | ---: | --- |
| `asset_class` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `asset_id` | string | 3 / 12 | 0 | 9 | `orders.default` |
| `canceled_at` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `client_order_id` | string | 8 / 12 | 0 | 4 | `orders.default` |
| `created_at` | string | 8 / 12 | 0 | 4 | `orders.default` |
| `expired_at` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `extended_hours` | boolean, null | 4 / 12 | 1 | 8 | `orders.default` |
| `failed_at` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `filled_at` | null, string | 8 / 12 | 3 | 4 | `orders.default` |
| `filled_avg_price` | string | 9 / 12 | 0 | 3 | `orders.default` |
| `filled_qty` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `future_field` | object | 1 / 12 | 0 | 11 | `orders.default` |
| `group_id` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `group_role` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `hwm` | null, string | 4 / 12 | 3 | 8 | `orders.default` |
| `id` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `legs` | array, null | 9 / 12 | 3 | 3 | `orders.default` |
| `limit_price` | string | 6 / 12 | 0 | 6 | `orders.default` |
| `notional` | null, string | 6 / 12 | 3 | 6 | `orders.default` |
| `order_class` | string | 10 / 12 | 0 | 2 | `orders.default` |
| `order_type` | string | 3 / 12 | 0 | 9 | `orders.default` |
| `position_intent` | string | 3 / 12 | 0 | 9 | `orders.default` |
| `provider_future_decimal` | number | 3 / 12 | 0 | 9 | `orders.default` |
| `provider_future_enum` | string | 3 / 12 | 0 | 9 | `orders.default` |
| `provider_future_value` | object | 3 / 12 | 0 | 9 | `orders.default` |
| `qty` | null, string | 10 / 12 | 3 | 2 | `orders.default` |
| `replaced_at` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `replaced_by` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `replaces` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `side` | null, string | 11 / 12 | 2 | 1 | `orders.default` |
| `status` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `stop_price` | null | 3 / 12 | 3 | 9 | `orders.default` |
| `submitted_at` | string | 3 / 12 | 0 | 9 | `orders.default` |
| `symbol` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `synthetic` | string | 1 / 12 | 0 | 11 | `orders.invalid_shape` |
| `time_in_force` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `trail_percent` | null | 4 / 12 | 4 | 8 | `orders.default` |
| `trail_price` | null, string | 4 / 12 | 3 | 8 | `orders.default` |
| `type` | string | 11 / 12 | 0 | 1 | `orders.default` |
| `updated_at` | string | 3 / 12 | 0 | 9 | `orders.default` |

## orders.legs — 18 observed wire keys

Observed objects in current synthetic success cases: **5**. Conditional and future provider fields are included.

| Provider JSON field | Observed types | Present / instances | Explicit null | Omitted | Example case |
| --- | --- | ---: | ---: | ---: | --- |
| `asset_class` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `created_at` | string | 2 / 5 | 0 | 3 | `orders.default` |
| `filled_at` | string | 2 / 5 | 0 | 3 | `orders.default` |
| `filled_avg_price` | string | 2 / 5 | 0 | 3 | `orders.default` |
| `filled_qty` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `id` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `limit_price` | string | 4 / 5 | 0 | 1 | `orders.default` |
| `order_class` | string | 3 / 5 | 0 | 2 | `orders.default` |
| `order_type` | string | 2 / 5 | 0 | 3 | `orders.default` |
| `position_intent` | string | 4 / 5 | 0 | 1 | `orders.default` |
| `qty` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `ratio_qty` | string | 4 / 5 | 0 | 1 | `orders.default` |
| `side` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `status` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `stop_price` | string | 1 / 5 | 0 | 4 | `orders.default` |
| `symbol` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `time_in_force` | string | 5 / 5 | 0 | 0 | `orders.default` |
| `type` | string | 5 / 5 | 0 | 0 | `orders.default` |

# M5 — Explicit crypto PAPER order submission (Java 17)

**Endpoint:** authenticated `POST https://paper-api.alpaca.markets/v2/orders`, an **actual mutation** of an Alpaca PAPER account. See [Atalanta issue #32](https://github.com/black-belt-badger/atalanta/issues/32), [Alpaca Create Order](https://docs.alpaca.markets/us/reference/postorder) and [crypto-specific order rules](https://docs.alpaca.markets/us/docs/crypto-orders).

A validated immutable intent, an explicit authorization value and a named effectful method are separate operations. The Java library does not copy the CLI's `--confirm` flag, auto-generate client IDs or permit live trading.

## Usage

```java
Atalanta paper = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

// Pure construction: no network or credential loading.
CryptoOrderRequest request = CryptoOrderRequest.marketQuantity(
        "BTC/USD",
        CryptoOrderRequest.Side.BUY,
        CryptoOrderRequest.TimeInForce.GTC,
        new BigDecimal("0.000000001"),
        "stable-crypto-order-001");

// Pure authorization: also no network request.
ConfirmedPaperOrder confirmed = request.authorize();

// REAL MUTATION: sends ONE order to the PAPER API.
CompletionStage<Result<Order>> result = paper.submitPaperOrder(confirmed);
```

Market notional and limit quantity are also supported:

```java
CryptoOrderRequest notional = CryptoOrderRequest.marketNotional(
        "ETH/USD", CryptoOrderRequest.Side.BUY,
        CryptoOrderRequest.TimeInForce.IOC,
        new BigDecimal("25.12"), "stable-notional-001");

CryptoOrderRequest limit = CryptoOrderRequest.limitQuantity(
        "SOL/USD", CryptoOrderRequest.Side.SELL,
        CryptoOrderRequest.TimeInForce.GTC,
        new BigDecimal("0.123456789"),
        new BigDecimal("150.123456789"), "stable-limit-001");
```

**Do not execute the submit call unless you intend to create an actual paper order.** The examples are illustrative and are not executed by the test suite.

## Request contract

| Parameter | Java API | Rules |
| --- | --- | --- |
| `symbol` | `String` | Exactly one uppercase BASE/QUOTE pair, not a list or URL fragment |
| `side` | `Side.BUY / SELL` | Required |
| `type` | `Kind.MARKET / LIMIT` | Required; no stop-limit or advanced classes in this milestone |
| `time_in_force` | `TimeInForce.GTC / IOC` | Required crypto-specific values |
| `qty` | `BigDecimal quantity` | Positive, at most 9 fractional digits |
| `notional` | `BigDecimal notional` | Market only, exclusive with qty; maximum 2 fractional digits for USD-quoted pairs and 9 for other quotes |
| `limit_price` | `BigDecimal limitPrice` | Required positive value on limit orders only; at most 9 fractional digits |
| `client_order_id` | `String` | Caller-supplied unique stable ID; 1–128 safe ASCII letters/digits and `. - _ :` |
| `order_class` / `extended_hours` | Not sent | Crypto simple orders only; no advanced instructions |
| `confirmation` / `live` | Not sent | The authorization record is Java-side intent, not an Alpaca JSON flag |

Exactly one of quantity or notional is required. Limit orders require quantity and limit price; market orders forbid limit price. The encoder emits exact decimal **JSON strings** using `BigDecimal.toPlainString()`, without binary floating point, implicit rounding or exponent notation. Large exponent inputs are rejected before formatting. The provider retains responsibility for minimum order size, increment and asset tradability.

### Explicit authorization and safety

`CryptoOrderRequest` and `ConfirmedPaperOrder` are immutable records. Factories and `authorize()` perform no I/O; only `Atalanta.submitPaperOrder(ConfirmedPaperOrder)` initiates the POST. The underlying transport also accepts only the confirmation type, preventing an accidental unconfirmed write through that boundary.

Production HTTP uses only fixed `https://paper-api.alpaca.markets` with existing Alpaca authentication headers, 20-second request and 10-second connection timeouts, redirects disabled, and **no application-level retry**. No live host, cancel-all, auto-submission, paper-account polling or mutable global order queue is added. Sensitive request values and IDs are redacted from diagnostic `toString()` methods.

A stable `client_order_id` assists reconciliation but **does not guarantee exactly-once execution**. Never blindly retry an uncertain POST; inspect existing paper orders by that ID first.

## Response and recovery

The provider documents HTTP 200; a valid 201 creation response is also accepted. Success must contain a decodable Alpaca Order object with the **exact requested client_order_id**. Response decoding reuses the established `OrderDecoder.decodeSingle` with precise money and quantity fields and future-compatible properties.

**Result.Ok(Order) does not guarantee a fill.** The returned status may be new, partially filled, filled, canceled, or another provider state. Inspect `order.status()` and later account state rather than inferring execution.

| Provider / transport outcome | Java result | Meaning |
| --- | --- | --- |
| HTTP 200/201, valid matching order | `Result.Ok(Order)` | Order response verified, not necessarily filled |
| 401 | `Err(Authentication)` | Credentials failed |
| 403 | `Err(Forbidden)` | Permission or buying-power rejection |
| 422 | `Err(OrderRejected)` | Parameters or asset rules rejected |
| 429 | `Err(RateLimited)` | Rate limited; still avoid automatic retry |
| 5xx | `Err(SubmissionOutcomeUnknown)` | Request may have reached Alpaca |
| Connection, TLS, I/O failure, timeout | `Err(SubmissionOutcomeUnknown)` | No definitive acceptance/rejection |
| Empty/malformed 200/201 or missing/mismatched client ID | `Err(SubmissionOutcomeUnknown)` | Outcome cannot be verified |
| Unexpected 202/204/other 2xx | `Err(SubmissionOutcomeUnknown)` | Not a verified order response |
| Other 4xx/3xx | `Err(HttpFailure)` | Non-success status; no redirect followed |
| Cancellation / programming exception | Exceptional completion | Not silently converted to provider failure |

No provider JSON body, raw parser exception, header secret, client order ID or private order details are copied into the error messages. A malformed success is **uncertain**, not proof that no order was created.

## Synthetic offline verification

JUnit 5/AssertJ tests exercise all market/limit, side, TIF, quantity/notional and ID-validation shapes; exact JSON decimal strings and fixed-paper-host headers; zero requests during preparation/authorization; one POST on explicit invocation; successful 200/201 decoding; status and client ID checks; malformed/unknown responses; 401/403/422/429/5xx, network and timeout; and lack of retries. The CI and test suite use **synthetic credentials and an injected HTTP sender** and never send real paper-order POSTs.

There is no Java CLI, provider credential fallback, live-trading mode or unrelated public API redesign in this PR.

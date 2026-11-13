# Order-by-ID endpoint — Java 17

**Endpoint:** authenticated `GET /v2/orders/{order_id}` on Alpaca's **paper Trading API**. This fetches one order by its provider-issued UUID. It is read-only and does not retrieve by `client_order_id`, place orders or cancel them; [paper cancellation](PAPER_CANCEL_API.md) is an independent explicit call.

## Public API

```java
Atalanta client = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

UUID id = UUID.fromString("11111111-2222-4333-8444-555555555555");

// No query parameter; use Alpaca's default nested behavior.
CompletionStage<Result<Optional<Order>>> order = client.order(id);

// Explicitly roll primary-order legs into a typed List<Order>.
CompletionStage<Result<Optional<Order>>> nested = client.order(id, true);

// Explicit false is also supported.
CompletionStage<Result<Optional<Order>>> flat = client.order(id, false);
```

## Request contract

| Element | Alpaca specification | Atalanta |
|---|---|---|
| `order_id` path component | Required UUID | Required `UUID` argument; rejected on null **before** I/O; encoded safely as one path segment |
| `nested` query parameter | Optional boolean; true rolls bracket/option multi-leg orders under `legs` | `order(id)` omits it; `order(id, true/false)` sends exactly one explicitly chosen value |
| Base URL | Trading API | `https://paper-api.alpaca.markets` only; no live host, redirects or retries |
| Response | A single order **object** | Same Java 17 `Order` record and nested leg decoder as `orders(OrderQuery)`; arrays/scalars rejected |

No other query or body parameters are accepted; `GET /v2/orders:by_client_order_id` is a separate operation, not implemented here.

## Result semantics

- **HTTP 200 with a valid order:** `Result.Ok(Optional.of(order))`. The order's UUID must match the requested UUID, or `InvalidResponse` is returned.
- **HTTP 404:** `Result.Ok(Optional.empty())`, representing expected absence rather than an exceptional failed lookup.
- **401 / 403 / 429 / other HTTP failures:** typed `Result.Err(AlpacaError)` using the existing transport error variants.
- **Malformed 200 response** (unexpected array/scalar, malformed nested leg, bad UUID/decimal): `Result.Err(InvalidResponse)`; no sensitive response body or field values leak into the error.
- **Timeout / I/O failure:** typed `Result.Err(AlpacaError)`. Cancellation and unexpected programming failures may complete exceptionally.

The `Optional` decision is an explicit Java library ergonomic choice for a missing UUID, not an Alpaca protocol guarantee for other endpoints. It is intentionally called out here for Product Owner review in [#12](https://github.com/black-belt-badger/atalanta/issues/12).

## Order object fidelity

Reuses the complete typed `Order` model already covered in [orders list contract](ORDERS_API.md), including prices, fills, quantities/notional, dates, statuses, unknown extension fields, legacy fields and `List<Order>` nested legs. A small correction adds `ratioQty(): BigDecimal` for the documented `ratio_qty` on multi-leg option children; the same field is available on list-order responses. All decimals preserve exact values, and no Jackson or HTTP type appears in public APIs.

## Offline acceptance tests

Eleven deterministic JUnit tests: default route and asynchronous completion; `nested=true` and `nested=false`; partial equity, notional crypto, bracket and option-leg responses; exact `ratio_qty`; 404 distinct from authentication and rate limits; malformed shapes and mismatched IDs; no secrets leaked in errors; null ID pre-network rejection; timeout/transport and programming-fault semantics. The full suite also runs all order-list and previous endpoint fixtures. No real credentials or network calls required.

## References

- [Alpaca Get Order by ID](https://docs.alpaca.markets/us/reference/getorderbyorderid-1)
- [Kirke order-by-ID M3 issue #36](https://github.com/black-belt-badger/kirke/issues/36)
- [Medea order-by-ID M3 issue #37](https://github.com/black-belt-badger/medea/issues/37)
- [Atalanta order-list issue #11](https://github.com/black-belt-badger/atalanta/issues/11)

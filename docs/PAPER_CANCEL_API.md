# Single paper-order cancellation — Java 17

**Endpoint:** authenticated **`DELETE https://paper-api.alpaca.markets/v2/orders/{order_id}`**. Atalanta implements this as a deliberately named **mutating** method, separate from every read-only API method. It never connects to Alpaca's live trading host.

This matches the semantics of [Kirke paper cancellation](https://github.com/black-belt-badger/kirke/issues/21) and [Medea paper cancellation](https://github.com/black-belt-badger/medea/issues/15), without copying their CLI `--confirm` switch into a Java library.

## API usage: explicit call, no implicit cancellation

```java
Atalanta paper = Atalanta.paper(
        System.getenv("APCA_API_KEY_ID"),
        System.getenv("APCA_API_SECRET_KEY"));

UUID id = UUID.fromString("9275f66a-0a1b-4a22-8c55-167416c7cf14");

// This line sends ONE real cancellation request to the PAPER account.
CompletionStage<Result<PaperCancellationAccepted>> accepted =
        paper.cancelPaperOrder(id);

// Inspect the latest state separately after Alpaca accepts the request.
CompletionStage<Result<Optional<Order>>> state = paper.order(id);
```

**The UUID shown is synthetic.** Use the actual order's provider-assigned `id`, never its `client_order_id`. Calling `cancelPaperOrder` is the explicit opt-in: the five GET methods and object construction never cancel an order. Java does not use a CLI-style flag. No bulk cancellation method is provided.

**Important:** HTTP 204 means *Alpaca accepted the cancellation request*. It does **not** prove the order has reached terminal `canceled` status; a partially filled or open order may fill before cancellation takes effect. A successful result is named `PaperCancellationAccepted` deliberately. Check `paper.order(id)` after the request and inspect its actual `status`. A missing order lookup returns `Ok(Optional.empty())` in the existing read API, which is separate from a failed cancellation.

## Wire and safety contract

| Component | Contract |
| --- | --- |
| HTTP method and URL | Exactly one `DELETE /v2/orders/{order_id}` |
| Host | `https://paper-api.alpaca.markets`, fixed production host; **never live** |
| Parameter | Required `java.util.UUID` of server-issued order `id`; null rejected synchronously, before I/O |
| Query / body | **None**; cannot cancel all orders |
| Authorization | Shared Alpaca paper API key/secret headers; never included in errors or diagnostic output |
| Redirects | Disabled by the existing JDK 17 client |
| Timeout | Existing 20-second request timeout, 10-second connect timeout |
| Retries | **No application-level retry or silent second DELETE** |
| Response body | Ignored and discarded; not parsed, returned or logged |
| Success | **Only HTTP 204** `Result.Ok(PaperCancellationAccepted)` |
| HTTP 404 | `Result.Err(AlpacaError.OrderNotFound)` |
| HTTP 422 | `Result.Err(AlpacaError.OrderNotCancelable)` |
| HTTP 401 / 403 / 429 | `Authentication`, `Forbidden`, `RateLimited` |
| Other 4xx / 3xx | `HttpFailure(status, sanitizedMessage)`; redirects are not followed |
| HTTP 5xx | `CancellationOutcomeUnknown(status, sanitizedMessage)`, since a DELETE may have reached Alpaca |
| Timeout / DNS / TLS / I/O | `CancellationOutcomeUnknown(null, sanitizedMessage)` |
| Unexpected 200 / 201 / 202 / 206 | `InvalidResponse` — never treated as accepted |
| Cancellation / programming defect | `CompletionStage` exceptional completion; no invented success or provider error |

Errors never contain the server's response body, raw HTTP exception text, authorization headers, or the order UUID. `PaperCancellationAccepted.toString()` also redacts the order UUID.

## Uncertain outcomes and recovery

If cancellation encounters a transport failure, timeout or HTTP 5xx, the request **may or may not have been processed**. Do not blindly issue a second DELETE. Inspect the order with `paper.order(id)`, or reconcile from order/trade events, and only then decide on further action. When an order is already filled, canceled, expired or otherwise noncancelable, Alpaca can reject cancellation with 422. A pending cancellation may still be in flight.

## Verification

All tests use deterministic **offline HTTP stubs** and synthetic UUIDs/credentials. The suite covers successful 204, a bodyless and queryless DELETE to exactly one UUID, shared headers and timeout settings, the absence of implicit mutation on GET, no retries, distinct 404/422/401/403/429/5xx, uncertain transport/timeout errors, redacted response bodies, null UUID rejection, unexpected 2xx and exceptional programming failures. **No live cancellation is performed by CI or the development test suite.**

Atalanta's existing independent Java 17 sealed `Result<T>` / `AlpacaError` algebra and `CompletionStage` conventions are preserved. No new runtime dependencies or CLI are introduced.

References: [Alpaca Delete Order by ID](https://docs.alpaca.markets/us/reference/deleteorderbyorderid-1), [Alpaca order lifecycle](https://docs.alpaca.markets/us/docs/orders-at-alpaca), [Atalanta #33](https://github.com/black-belt-badger/atalanta/issues/33).

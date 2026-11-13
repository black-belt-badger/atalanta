# Internal asynchronous Alpaca HTTP transport

Atalanta has an **internal**, package-private `AlpacaHttpTransport` foundation. It does not yet expose an `Atalanta.account()` or any other public endpoint method. Public consumers will interact with typed Java records and `CompletionStage<Result<T>>`, not with Jackson, HTTP request objects, or raw JSON trees.

## Scope

- **Java 17 JDK HttpClient** and asynchronous `sendAsync` with a 20-second request timeout and a 10-second connection timeout.
- Production factory is **paper-only** at `https://paper-api.alpaca.markets`; a package-private constructor allows **loopback only** for offline testing.
- Exactly `APCA-API-KEY-ID` and `APCA-API-SECRET-KEY` are sent as credential headers; reject empty and control-character-containing values.
- Only GET requests on `/v2/...`; each path segment and query name/value is encoded separately so values cannot inject URL paths or parameters.
- **Never follow redirects** (to avoid forwarding Alpaca headers to another host), never automatically retry, and never put credentials or remote response bodies in errors or logs.
- Read 2xx JSON **objects or arrays**, rejecting empty, malformed, scalar, and trailing-token responses.
- Internally Jackson uses exact `BigDecimal` parsing for JSON floating-point numbers. These JSON types are not a public API.
- Translate routine HTTP/transport errors into `Result.Err(AlpacaError)`. Distinguish 401, 403, 429, timeout, network/IO, malformed responses, and other HTTP statuses, including 404.
- Leave cancellation and unexpected programming failures as exceptional stages, rather than silently classifying them as HTTP failures.

## How endpoint adapters will use it

These lines illustrate the **internal** building block; no endpoint adapter or `Account` record is implemented yet:

```java
var http = AlpacaHttpTransport.paper(keyId, secretKey);
CompletionStage<Result<JsonNode>> json =
        http.get(List.of("v2", "account"), Map.of());
```

A future account adapter will validate and decode the JSON tree into an immutable Java `Account` record. It will then return `CompletionStage<Result<Account>>` to consumers without exposing `JsonNode` or `ObjectMapper`. Malformed typed fields will become `InvalidResponse`, not an unchecked Jackson failure.

The endpoint contracts and consumer-facing API shape are tracked in [#5](https://github.com/black-belt-badger/atalanta/issues/5) and [#8](https://github.com/black-belt-badger/atalanta/issues/8).

## Deterministic tests

`AlpacaHttpTransportTest` uses an injected asynchronous HTTP sender and a local JDK HTTP server:

- Verify request method, credential headers, URI/query encoding, and timeout settings.
- Verify paper-only/loopback URI restrictions, invalid credentials, typed status failures, and error redaction.
- Verify the real JDK HTTP client does not follow redirects.
- Verify exact decimal values and JSON parsing failures.
- Verify delayed futures, timeouts, IO failures, cancellations, and unexpected programming failures.

Run all tests using JDK 17:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn clean verify
```

No real Alpaca credentials, requests to the public internet, or trading operations are involved.

## Intentional limitations

This first transport is for **paper Trading API state reads**. Alpaca Market Data uses a different host and will require a separate, explicitly reviewed client design. Large streaming/bulk market data and automatic rate-limit pacing are out of scope here. HTTP 404 currently remains a typed `HttpFailure`; whether specific public lookup methods expose `Optional.empty()` will be decided in #5.

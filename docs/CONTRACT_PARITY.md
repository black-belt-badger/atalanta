# Five-endpoint portable contract coverage

Atalanta M3 fixture-set revision **`alpaca-trading-read-only/1.0.0`** lives in [`contracts/alpaca-trading/v1/`](../contracts/alpaca-trading/v1/README.md). The source includes a language-neutral manifest, standalone JSON response bodies and integrity hashes. Each row below is a **testable provider-level observation**, not a promise that each language already implements the same client API.

## Coverage matrix

| Endpoint | Portable request variants | Provider response / normal form | Explicit edge coverage |
|---|---|---|---|
| GET `/v2/account` | No query parameters | Single account record | Full 30+ digit cash, future account/crypto status, legacy PDT fields, omitted vs null, unknown nested fields, 401, malformed shape |
| GET `/v2/assets` | Provider default `us_equity`, explicit crypto/active, exchange + attribute CSV | Array of typed assets | Equity/crypto/index/perp classes, exact minimum quantities, precise unknown JSON number, borrowing status, empty array, 429, unexpected object |
| GET `/v2/positions` | No query parameters | Array of open positions | Long fractional crypto quantity, negative P/L, IPO null pricing, nested USD valuation, empty portfolio, unexpected object |
| GET `/v2/orders` | Provider defaults, explicit filters and date bounds, after-order UUID cursor | Array of typed orders | Partial equity fill, notional crypto order, multi-leg option ratios, future order status, empty array, invalid shape, 503 |
| GET `/v2/orders/{id}` | UUID lookup, nested omitted/true/false | Single order or absent | Order ID matching, nested option ratios, 404 as successful absence, invalid shape and mismatched ID |

**26 portable cases** are expressed as independent `input`, `request`, `response` and `expected` objects. In Atalanta, `SharedTradingContractTest` invokes the actual public asynchronous library against an injected offline HTTP sender; it checks precise encoded paths and query strings, status mapping and a test-only normalized observation view. The 115 preexisting endpoint-specific tests continue to check much deeper individual field coverage and validation.

### Contract gaps and known differences to review

- **Potential public API divergence:** Atalanta is a Java *library*, Kirke and Medea are *CLIs*. File location, terminal output color, stdout/stderr conventions, shell completions and exit codes are not forced onto Atalanta. Compare the actual API concepts and underlying semantics, not terminal presentation.
- **Not-found convention:** Atalanta currently maps order-ID 404 to `Ok(Optional.empty())`, while CLI tools may present a different surface. Confirm semantic equivalence and any intentionally different type constructors in the triparity review.
- **Transport failures:** fixture pack currently covers common HTTP status codes, validation failures and malformed roots, not full network timeouts, DNS/TLS, cancellation or mixed failures across pages. These remain exercised by Java endpoint/transport-specific tests. Add portable cases when cross-language behavior is agreed.
- **Paging:** one HTTP response per request; no automatic retrieval of subsequent pages and no invented page token. ID anchor and timestamp constraints are covered in Java `OrderQueryTest` more thoroughly than in the portable pack. Pagination partial successes/failures are a future research question under proposed Metis ADR-0006.
- **Schema coverage:** not every one of the 51 account fields or every order/position field appears as a portable check. Existing local fixtures and field-coverage documents provide fuller type/nullable validation. Expand portable checks based on observed cross-client drift rather than copying all Java record components.
- **Market Data and streaming:** not covered. This pack is five initial read-only paper **Trading API** endpoints.
- **Current triparity status (phase 1):** Java normalized runner passes 26/26; temporary native Haskell/Rust decoder/status smoke runners each match **23/26** of the same synthetic cases. Both reject the quoted deprecated asset margin field and a mixed null/omitted position shape. Full native normalized field and HTTP query equivalence is **still unverified**. See the [evidence and decision matrix](TRIPARITY_AUDIT.md), [155-row field inventory](TRIPARITY_FIELD_INVENTORY.md), and [isolated smoke runner](../tools/triparity/README.md).

### Maintaining consistency

The pack is a transport-neutral, implementation-neutral fixture contract **proposed** as a common source of truth, not an automatic new ADR. We selected Atalanta-hosted vendoring for M3 to avoid premature fourth-repository or CI infrastructure. The phase-1 audit now compares the same response fixture IDs using temporary native decoder adapters. Before declaring complete parity, add cross-language normalized field and request assertions, negotiate legitimate differences explicitly, and file per-repo implementation fixes after PO review.

Internal API review draft: [Atalanta #25 audit and PO decision queue](INTERNAL_API_AUDIT.md).

Related issues: [cross-language fixtures #7](https://github.com/black-belt-badger/atalanta/issues/7), [Atalanta consistency #25](https://github.com/black-belt-badger/atalanta/issues/25), [triparity #26](https://github.com/black-belt-badger/atalanta/issues/26), [public API refinement #5](https://github.com/black-belt-badger/atalanta/issues/5), and Metis proposed ADR-0006.

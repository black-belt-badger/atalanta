package bbb.atalanta;

import java.util.Objects;

/**
 * Expected failures from the Alpaca API and its transport.
 *
 * <p>All messages supplied by the HTTP/JSON boundary must be sanitized:
 * never include credentials, authorization headers or private account payloads.
 * Transport-specific exception and JSON library types are not part of this API.
 */
public sealed interface AlpacaError permits
        AlpacaError.Authentication,
        AlpacaError.Forbidden,
        AlpacaError.RateLimited,
        AlpacaError.HttpFailure,
        AlpacaError.TransportFailure,
        AlpacaError.Timeout,
        AlpacaError.InvalidResponse,
        AlpacaError.OrderNotFound,
        AlpacaError.OrderNotCancelable,
        AlpacaError.CancellationOutcomeUnknown {

    /** Invalid or missing authentication credentials (typically HTTP 401). */
    record Authentication(String message) implements AlpacaError {
        public Authentication {
            Objects.requireNonNull(message, "message");
        }
    }

    /** The credentials do not authorize this operation (typically HTTP 403). */
    record Forbidden(String message) implements AlpacaError {
        public Forbidden {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Provider request rate limit reached (HTTP 429). */
    record RateLimited(String message) implements AlpacaError {
        public RateLimited {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Unexpected non-success HTTP status other than the above categories. */
    record HttpFailure(int statusCode, String message) implements AlpacaError {
        public HttpFailure {
            if (statusCode < 100 || statusCode > 599 || (statusCode >= 200 && statusCode < 300)) {
                throw new IllegalArgumentException("statusCode must be a non-success HTTP status");
            }
            Objects.requireNonNull(message, "message");
        }
    }

    /** Transport-level failure such as DNS, TLS or an interrupted connection. */
    record TransportFailure(String message) implements AlpacaError {
        public TransportFailure {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Request or response timed out. */
    record Timeout() implements AlpacaError {}

    /** Single paper order was not found (DELETE returned HTTP 404). */
    record OrderNotFound(String message) implements AlpacaError {
        public OrderNotFound {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Paper order is no longer cancelable (DELETE returned HTTP 422). */
    record OrderNotCancelable(String message) implements AlpacaError {
        public OrderNotCancelable {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * DELETE outcome is uncertain: connection/timeout or provider HTTP 5xx.
     * The request may have been accepted; query the order before any retry.
     */
    record CancellationOutcomeUnknown(Integer httpStatus, String message) implements AlpacaError {
        public CancellationOutcomeUnknown {
            if (httpStatus != null && (httpStatus < 500 || httpStatus > 599)) {
                throw new IllegalArgumentException("httpStatus must be a 5xx status");
            }
            Objects.requireNonNull(message, "message");
        }
    }

    /** Provider response could not be interpreted as the expected contract. */
    record InvalidResponse(String message) implements AlpacaError {
        public InvalidResponse {
            Objects.requireNonNull(message, "message");
        }
    }
}

package bbb.atalanta;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only snapshot of an Alpaca paper Trading account.
 *
 * <p>Financial amounts use exact BigDecimal values; no Java/JSON transport types
 * occur in this API. Optional provider properties are nullable to preserve
 * missing/null responses. Use {@link AccountDetails#presentFields()} to
 * distinguish omitted from explicitly null properties.
 */
public record Account(
        UUID id,
        String accountNumber,
        String status,
        String cryptoStatus,
        String currency,
        BigDecimal cash,
        BigDecimal equity,
        BigDecimal buyingPower,
        Boolean tradingBlocked,
        AccountDetails details) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        Objects.requireNonNull(details, "details");
    }

    /** Never print account identifiers, balances, or extension data by accident. */
    @Override
    public String toString() {
        return "Account[redacted; status=" + status + ", cryptoStatus=" + cryptoStatus + "]";
    }
}

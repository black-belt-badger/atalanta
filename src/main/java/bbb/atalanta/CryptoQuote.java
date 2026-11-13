package bbb.atalanta;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Alpaca historical best-bid/best-ask quote for one crypto symbol.
 *
 * <p>All prices and sizes are exact decimals. Bid/ask sizes are measured in
 * the base asset without a lot multiplier. Timestamp preserves RFC3339
 * provider spelling and nanosecond fractional digits.
 */
public record CryptoQuote(
        String timestamp,
        BigDecimal askPrice,
        BigDecimal askSize,
        BigDecimal bidPrice,
        BigDecimal bidSize,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public CryptoQuote {
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("Crypto quote timestamp required");
        }
        try {
            OffsetDateTime.parse(timestamp);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid crypto quote timestamp");
        }
        Objects.requireNonNull(askPrice, "askPrice");
        Objects.requireNonNull(askSize, "askSize");
        Objects.requireNonNull(bidPrice, "bidPrice");
        Objects.requireNonNull(bidSize, "bidSize");
        if (askPrice.signum() < 0 || askSize.signum() < 0
                || bidPrice.signum() < 0 || bidSize.signum() < 0) {
            throw new IllegalArgumentException("Crypto quote price and size must be nonnegative");
        }
        presentFields = Set.copyOf(Objects.requireNonNull(presentFields, "presentFields"));
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public boolean supplied(String wireName) {
        return presentFields.contains(wireName);
    }
}

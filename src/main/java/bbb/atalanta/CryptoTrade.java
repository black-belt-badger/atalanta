package bbb.atalanta;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact historical crypto execution: unbounded nonnegative ID, precise price
 * and size, and original high-resolution timestamp. The taker side is optional.
 */
public record CryptoTrade(
        BigInteger id,
        BigDecimal price,
        BigDecimal size,
        String timestamp,
        String takerSide,
        Set<String> presentFields,
        Map<String, Object> additionalFields) {

    public CryptoTrade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(size, "size");
        if (id.signum() < 0) {
            throw new IllegalArgumentException("Trade ID must be nonnegative");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        try {
            OffsetDateTime.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid trade timestamp");
        }
        presentFields = Set.copyOf(Objects.requireNonNull(presentFields, "presentFields"));
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    /** True if the provider supplied this field, including explicit JSON null. */
    public boolean supplied(String wireName) {
        return presentFields.contains(wireName);
    }
}

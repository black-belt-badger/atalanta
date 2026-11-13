package bbb.atalanta;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, provider-aligned query for historical crypto quotes.
 * Optional fields are omitted rather than supplying values Alpaca already defaults.
 * Start/end are inclusive; dates or offset-qualified RFC3339 are preserved verbatim.
 */
public record CryptoQuoteQuery(
        CryptoLocation location,
        List<String> symbols,
        String start,
        String end,
        Integer limit,
        Sort sort,
        String pageToken) {

    public enum Sort {
        ASC("asc"), DESC("desc");
        private final String wire;
        Sort(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public CryptoQuoteQuery {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.isEmpty() || symbols.size() > 30) {
            throw new IllegalArgumentException("Expected 1-30 crypto symbols");
        }
        for (String symbol : symbols) {
            if (symbol == null || !symbol.matches("[A-Z0-9]+/[A-Z0-9]+")) {
                throw new IllegalArgumentException("Invalid crypto pair");
            }
        }
        symbols = List.copyOf(symbols);
        Instant after = instant(start);
        Instant before = instant(end);
        if (after != null && before != null && after.isAfter(before)) {
            throw new IllegalArgumentException("start must not be later than end");
        }
        if (limit != null && (limit < 1 || limit > 10000)) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        if (pageToken != null && (pageToken.isEmpty() || pageToken.length() > 2048
                || !pageToken.chars().allMatch(code -> code >= 33 && code <= 126))) {
            throw new IllegalArgumentException("Invalid opaque page token");
        }
    }

    public static CryptoQuoteQuery of(List<String> symbols) {
        return new CryptoQuoteQuery(CryptoLocation.US, symbols,
                null, null, null, null, null);
    }

    public CryptoQuoteQuery withLocation(CryptoLocation value) {
        return new CryptoQuoteQuery(value, symbols, start, end, limit, sort, pageToken);
    }

    public CryptoQuoteQuery withStart(String value) {
        return new CryptoQuoteQuery(location, symbols, value, end, limit, sort, pageToken);
    }

    public CryptoQuoteQuery withEnd(String value) {
        return new CryptoQuoteQuery(location, symbols, start, value, limit, sort, pageToken);
    }

    public CryptoQuoteQuery withLimit(int value) {
        return new CryptoQuoteQuery(location, symbols, start, end, value, sort, pageToken);
    }

    public CryptoQuoteQuery withSort(Sort value) {
        return new CryptoQuoteQuery(location, symbols, start, end, limit, value, pageToken);
    }

    /** Resume with a token supplied by Alpaca, not a locally generated cursor. */
    public CryptoQuoteQuery withPageToken(String value) {
        return new CryptoQuoteQuery(location, symbols, start, end, limit, sort, value);
    }

    Map<String, String> toQueryParameters() {
        var query = new LinkedHashMap<String, String>();
        query.put("symbols", String.join(",", symbols));
        if (start != null) query.put("start", start);
        if (end != null) query.put("end", end);
        if (limit != null) query.put("limit", Integer.toString(limit));
        if (sort != null) query.put("sort", sort.wire());
        if (pageToken != null) query.put("page_token", pageToken);
        return Collections.unmodifiableMap(query);
    }

    private static Instant instant(String value) {
        if (value == null) return null;
        if (value.isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid start/end date");
        }
        try {
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
            // A time without timezone is intentionally invalid.
            if (!value.contains("T") || !(value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$"))) {
                throw new IllegalArgumentException("Expected date or offset-qualified RFC3339 timestamp");
            }
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid start/end date");
        }
    }
}

package bbb.atalanta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A single bounded provider page; opaque continuation must be followed explicitly. */
public record CryptoQuotePage(
        Map<String, List<CryptoQuote>> quotes,
        String nextPageToken,
        Map<String, Object> additionalFields) {

    public CryptoQuotePage {
        var copy = new LinkedHashMap<String, List<CryptoQuote>>();
        quotes.forEach((symbol, values) -> {
            if (symbol == null || values == null) {
                throw new IllegalArgumentException("Quote page contains invalid symbol or list");
            }
            copy.put(symbol, List.copyOf(values));
        });
        quotes = Collections.unmodifiableMap(copy);
        if (nextPageToken != null && nextPageToken.isEmpty()) nextPageToken = null;
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public Optional<String> continuation() {
        return Optional.ofNullable(nextPageToken);
    }

    public long quoteCount() {
        return quotes.values().stream().mapToLong(List::size).sum();
    }
}

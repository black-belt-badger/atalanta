package bbb.atalanta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A single bounded provider page; opaque continuation must be followed explicitly. */
public record CryptoBarPage(
        Map<String, List<CryptoBar>> bars,
        String nextPageToken,
        Map<String, Object> additionalFields) {

    public CryptoBarPage {
        var copy = new LinkedHashMap<String, List<CryptoBar>>();
        bars.forEach((symbol, values) -> {
            if (symbol == null || values == null) {
                throw new IllegalArgumentException("Bar page contains invalid symbol or list");
            }
            copy.put(symbol, List.copyOf(values));
        });
        bars = Collections.unmodifiableMap(copy);
        if (nextPageToken != null && nextPageToken.isEmpty()) nextPageToken = null;
        additionalFields = ProviderJson.immutableMap(additionalFields);
    }

    public Optional<String> continuation() {
        return Optional.ofNullable(nextPageToken);
    }

    public long barCount() {
        return bars.values().stream().mapToLong(List::size).sum();
    }
}

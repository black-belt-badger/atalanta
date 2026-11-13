package bbb.atalanta;

import java.util.Objects;
import java.util.Optional;

/**
 * Summary of one successful, bounded historical page scan.
 *
 * <p>The scanner does not own or retain pages. A caller must persist
 * each delivered page and its continuation if resumption is required.
 * This value describes retrieval progress, not market data or retry policy.
 */
public record ScanSummary(int pages, long observations, Optional<String> continuation) {
    public ScanSummary {
        if (pages < 1 || observations < 0) {
            throw new IllegalArgumentException("Invalid completed scan counters");
        }
        Objects.requireNonNull(continuation, "continuation");
    }

    /** True when the provider did not return a continuation token. */
    public boolean exhausted() {
        return continuation.isEmpty();
    }
}

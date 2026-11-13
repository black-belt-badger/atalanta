package bbb.atalanta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded pagination preserving every successful page if a later request fails.
 * Stopped with a continuation means the page cap was reached, NOT end-of-data.
 */
public sealed interface CryptoQuoteScan permits CryptoQuoteScan.Stopped, CryptoQuoteScan.Failed {

    List<CryptoQuotePage> pages();

    /** All requested pages were fetched, or a local page bound stopped retrieval. */
    record Stopped(List<CryptoQuotePage> pages, Optional<String> continuation)
            implements CryptoQuoteScan {
        public Stopped {
            pages = List.copyOf(pages);
            continuation = Objects.requireNonNull(continuation, "continuation");
        }

        public boolean exhausted() {
            return continuation.isEmpty();
        }
    }

    /** Retains successfully fetched pages; never mislabels a partial failure as success. */
    record Failed(List<CryptoQuotePage> pages, AlpacaError error) implements CryptoQuoteScan {
        public Failed {
            pages = List.copyOf(pages);
            Objects.requireNonNull(error, "error");
        }
    }
}

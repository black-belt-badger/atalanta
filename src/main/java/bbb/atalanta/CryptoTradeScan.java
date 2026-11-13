package bbb.atalanta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded historical trades traversal. A later error retains successfully
 * decoded pages, rather than erasing the data or misreporting success.
 */
public sealed interface CryptoTradeScan
        permits CryptoTradeScan.Stopped, CryptoTradeScan.Failed {

    List<CryptoTradePage> pages();

    /** Exhausted if and only if Alpaca returned no continuation token. */
    record Stopped(List<CryptoTradePage> pages, Optional<String> continuation)
            implements CryptoTradeScan {
        public Stopped {
            pages = List.copyOf(pages);
            Objects.requireNonNull(continuation, "continuation");
        }

        public boolean exhausted() {
            return continuation.isEmpty();
        }
    }

    record Failed(List<CryptoTradePage> pages, AlpacaError error)
            implements CryptoTradeScan {
        public Failed {
            pages = List.copyOf(pages);
            Objects.requireNonNull(error, "error");
        }
    }
}

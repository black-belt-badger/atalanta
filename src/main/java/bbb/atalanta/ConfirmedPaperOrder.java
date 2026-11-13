package bbb.atalanta;

import java.util.Objects;

/**
 * Explicit, single-order paper submission authorization. No HTTP side effect
 * occurs on construction; only Atalanta.submitPaperOrder(...) performs a POST.
 */
public record ConfirmedPaperOrder(CryptoOrderRequest request) {
    public ConfirmedPaperOrder {
        Objects.requireNonNull(request, "request");
    }

    @Override
    public String toString() {
        return "ConfirmedPaperOrder[redacted]";
    }
}

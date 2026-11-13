package bbb.atalanta;

import java.util.Objects;
import java.util.UUID;

/**
 * Alpaca accepted a request to cancel this paper order (HTTP 204).
 *
 * <p>This does NOT mean that the order reached terminal "canceled" status.
 * The order can fill before cancellation takes effect; check its status
 * independently with {@link Atalanta#order(UUID)}.
 */
public record PaperCancellationAccepted(UUID orderId) {
    public PaperCancellationAccepted {
        Objects.requireNonNull(orderId, "orderId");
    }

    /** Avoid accidentally leaking an account-linked order UUID in logs. */
    @Override
    public String toString() {
        return "PaperCancellationAccepted[not terminal status]";
    }
}

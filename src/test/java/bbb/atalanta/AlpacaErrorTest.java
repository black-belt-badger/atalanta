package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AlpacaErrorTest {

    @Test
    void errorsAreTypedImmutableValues() {
        assertThat(new AlpacaError.Authentication("Unauthorized"))
                .isEqualTo(new AlpacaError.Authentication("Unauthorized"));
        assertThat(new AlpacaError.Forbidden("Not permitted"))
                .isNotEqualTo(new AlpacaError.Authentication("Not permitted"));
        assertThat(new AlpacaError.Timeout()).isEqualTo(new AlpacaError.Timeout());
        assertThat(new AlpacaError.HttpFailure(404, "Not found").statusCode()).isEqualTo(404);
    }

    @Test
    void sealedFamilyIncludesPaperCancellationOutcomes() {
        assertThat(AlpacaError.class.isSealed()).isTrue();
        assertThat(Arrays.stream(AlpacaError.class.getPermittedSubclasses())
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "Authentication", "Forbidden", "RateLimited", "HttpFailure",
                        "TransportFailure", "Timeout", "InvalidResponse",
                        "OrderNotFound", "OrderNotCancelable", "CancellationOutcomeUnknown",
                        "OrderRejected", "SubmissionOutcomeUnknown");
        assertThat(AlpacaError.Timeout.class.isRecord()).isTrue();
    }

    @Test
    void httpFailureRejectsSuccessAndInvalidStatuses() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AlpacaError.HttpFailure(200, "OK"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AlpacaError.HttpFailure(99, "Invalid"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AlpacaError.HttpFailure(600, "Invalid"));
    }

    @Test
    void messageVariantsRequireNonNullDescriptions() {
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.Authentication(null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.Forbidden(null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.RateLimited(null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.HttpFailure(503, null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.TransportFailure(null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.InvalidResponse(null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.OrderNotFound(null));
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.OrderNotCancelable(null));
        assertThatNullPointerException().isThrownBy(
                () -> new AlpacaError.CancellationOutcomeUnknown(null, null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AlpacaError.CancellationOutcomeUnknown(422, "invalid status"));
        assertThat(new AlpacaError.CancellationOutcomeUnknown(503, "outcome unknown").httpStatus())
                .isEqualTo(503);
        assertThatNullPointerException().isThrownBy(() -> new AlpacaError.OrderRejected(null));
        assertThatNullPointerException().isThrownBy(
                () -> new AlpacaError.SubmissionOutcomeUnknown(null, null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AlpacaError.SubmissionOutcomeUnknown(422, "invalid status"));
        assertThat(new AlpacaError.SubmissionOutcomeUnknown(200, "unknown").httpStatus())
                .isEqualTo(200);
        assertThat(new AlpacaError.SubmissionOutcomeUnknown(503, "unknown").httpStatus())
                .isEqualTo(503);
    }
}

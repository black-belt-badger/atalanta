package bbb.atalanta;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ResultTest {

    private final AlpacaError error = new AlpacaError.RateLimited("Rate limit exceeded");

    @Test
    void successContainsTheValue() {
        Result<String> result = Result.ok("BTC/USD");

        assertThat(result).isEqualTo(new Result.Ok<>("BTC/USD"));
        assertThat(result.fold(String::length, ignored -> -1)).isEqualTo(7);
    }

    @Test
    void failureContainsTypedError() {
        Result<String> result = Result.err(error);

        assertThat(result).isEqualTo(new Result.Err<String>(error));
        assertThat(result.fold(String::length, failure -> failure instanceof AlpacaError.RateLimited ? -1 : 0))
                .isEqualTo(-1);
    }

    @Test
    void mapTransformsOnlySuccess() {
        assertThat(Result.ok(21).map(n -> n * 2)).isEqualTo(Result.ok(42));

        AtomicBoolean called = new AtomicBoolean();
        Result<Integer> failed = Result.<Integer>err(error).map(n -> {
            called.set(true);
            return n * 2;
        });

        assertThat(failed).isEqualTo(Result.err(error));
        assertThat(called).isFalse();
    }

    @Test
    void flatMapChainsAndShortCircuits() {
        Result<Integer> result = Result.ok("42")
                .flatMap(text -> Result.ok(Integer.parseInt(text)))
                .flatMap(value -> value > 0 ? Result.ok(value * 2) : Result.err(error));

        assertThat(result).isEqualTo(Result.ok(84));

        AtomicBoolean called = new AtomicBoolean();
        Result<Integer> failure = Result.<String>err(error).flatMap(text -> {
            called.set(true);
            return Result.ok(text.length());
        });

        assertThat(failure).isEqualTo(Result.err(error));
        assertThat(called).isFalse();
    }

    @Test
    void foldCallsExactlyOneBranch() {
        AtomicBoolean failureBranchCalled = new AtomicBoolean();
        String success = Result.ok("OK").fold(
                value -> value + "!",
                ignored -> {
                    failureBranchCalled.set(true);
                    return "failure";
                });

        assertThat(success).isEqualTo("OK!");
        assertThat(failureBranchCalled).isFalse();

        AtomicBoolean successBranchCalled = new AtomicBoolean();
        String failure = Result.<String>err(error).fold(
                value -> {
                    successBranchCalled.set(true);
                    return value;
                },
                problem -> problem.getClass().getSimpleName());

        assertThat(failure).isEqualTo("RateLimited");
        assertThat(successBranchCalled).isFalse();
    }

    @Test
    void nullValueOrErrorIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> Result.ok(null));
        assertThatNullPointerException().isThrownBy(() -> Result.err(null));
        assertThatNullPointerException().isThrownBy(() -> new Result.Ok<>(null));
        assertThatNullPointerException().isThrownBy(() -> new Result.Err<>(null));
    }

    @Test
    void invalidMappersAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").map(null));
        assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").flatMap(null));
        assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").map(ignored -> null));
        assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").flatMap(ignored -> null));
        assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").fold(null, ignored -> "x"));
        assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").fold(value -> value, null));
    }

    @Test
    void mayBeWrappedByACompletionStageWithoutTransportDependencies() {
        CompletionStage<Result<Integer>> future = CompletableFuture.completedFuture(Result.ok(17));

        assertThat(future.toCompletableFuture().join()).isEqualTo(Result.ok(17));
    }

    @Test
    void variantsAreJavaRecordsAndTheSumIsSealed() {
        assertThat(Result.class.isSealed()).isTrue();
        assertThat(Result.Ok.class.isRecord()).isTrue();
        assertThat(Result.Err.class.isRecord()).isTrue();
    }
}

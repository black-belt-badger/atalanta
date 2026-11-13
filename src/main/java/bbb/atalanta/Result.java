package bbb.atalanta;

import java.util.Objects;
import java.util.function.Function;

/**
 * The outcome of an Atalanta operation: either a value or a typed Alpaca failure.
 *
 * <p>Network operations will expose this type inside a {@code CompletionStage}.
 * Expected remote failures are represented by {@link Err}, not by exceptional
 * stage completion.
 *
 * @param <T> successful result type
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

    /** A successful result. Null is not a valid success value. */
    record Ok<T>(T value) implements Result<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A failed operation with a typed error. */
    record Err<T>(AlpacaError error) implements Result<T> {
        public Err {
            Objects.requireNonNull(error, "error");
        }
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Result<T> err(AlpacaError error) {
        return new Err<>(error);
    }

    /**
     * Transform a success; leave an error unchanged. Mapping to null is invalid.
     */
    default <U> Result<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return fold(value -> Result.ok(mapper.apply(value)), Result::err);
    }

    /**
     * Sequence dependent result-producing operations, short-circuiting on error.
     */
    default <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return fold(value -> Objects.requireNonNull(mapper.apply(value), "mapped result"), Result::err);
    }

    /**
     * Collapse either variant to one value. Java 17-compatible alternative to
     * exhaustive pattern-switch (which is preview-only on Java 17).
     */
    default <U> U fold(
            Function<? super T, ? extends U> onSuccess,
            Function<? super AlpacaError, ? extends U> onFailure) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");

        if (this instanceof Ok<T> success) {
            return onSuccess.apply(success.value());
        }
        if (this instanceof Err<T> failure) {
            return onFailure.apply(failure.error());
        }
        throw new IllegalStateException("Unexpected Result variant");
    }
}

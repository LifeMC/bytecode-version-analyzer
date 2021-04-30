package com.lifemcserver.bytecodeversionanalyzer.extensions;

/**
 * Opposite of {@link java.util.function.BooleanSupplier}.
 * <p>
 * This created because there is no {@link BooleanConsumer} in JDK,
 * but there is {@link java.util.function.BooleanSupplier}, which is confusing.
 */
@FunctionalInterface
public interface BooleanConsumer {
    /**
     * Runs this {@link BooleanConsumer} with the given argument.
     *
     * @param value The value to consume.
     */
    void accept(final boolean value);
}

package com.lifemcserver.bytecodeversionanalyzer.extensions.collections;

import java.util.Enumeration;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * A class for using {@link Enumeration}s as {@link Spliterators}.
 *
 * @param <T> The type of the {@link Enumeration}.
 */
public final class EnumerationSpliterator<T> extends Spliterators.AbstractSpliterator<T> {
    /**
     * The {@link Enumeration}.
     */
    private final Enumeration<? extends T> enumeration;

    /**
     * Constructs a new {@link EnumerationSpliterator}.
     *
     * @param enumeration The {@link Enumeration}.
     */
    public EnumerationSpliterator(final Enumeration<? extends T> enumeration) {
        super(Long.MAX_VALUE, Spliterator.ORDERED & Spliterator.IMMUTABLE);

        this.enumeration = enumeration;
    }

    /**
     * Runs the specified consumer if there is more elements.
     *
     * @param action The consumer to invoke.
     * @return True if the consumer is invoked, false otherwise.
     */
    @Override
    public final boolean tryAdvance(final Consumer<? super T> action) {
        if (enumeration.hasMoreElements()) {
            action.accept(enumeration.nextElement());
            return true;
        }
        return false;
    }

    /**
     * Invokes the given consumer for each element remaining.
     *
     * @param action The consumer to invoke.
     */
    @Override
    public final void forEachRemaining(final Consumer<? super T> action) {
        while (enumeration.hasMoreElements()) {
            action.accept(enumeration.nextElement());
        }
    }

    /**
     * Returns debug string of this {@link EnumerationSpliterator}.
     *
     * @return The debug string of this {@link EnumerationSpliterator}.
     */
    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "EnumerationSpliterator{" +
            "enumeration=" + enumeration +
            '}';
    }
}

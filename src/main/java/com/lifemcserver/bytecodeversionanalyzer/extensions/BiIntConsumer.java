package com.lifemcserver.bytecodeversionanalyzer.extensions;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * A combination of {@link BiConsumer} and {@link IntConsumer}.
 * Unfortunately there is no {@link BiIntConsumer} in the JDK, currently. So we use our own interface.
 * <p>
 * This not compatible with any of the mentioned classes and will not necessarily provide their new methods like
 * {@link BiConsumer#andThen(BiConsumer)}.
 *
 * @see BiConsumer
 * @see IntConsumer
 */
@FunctionalInterface
public interface BiIntConsumer {
    /**
     * Runs this {@link BiIntConsumer} with the given parameters.
     *
     * @param value1 The first value.
     * @param value2 The second value.
     */
    void accept(final int value1, final int value2);
}

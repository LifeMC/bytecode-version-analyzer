package com.lifemcserver.bytecodeversionanalyzer.extensions.timing;

import java.util.concurrent.TimeUnit;

/**
 * Used for timing something, i.e a long operation.
 * Or a short one, for analytic purposes.
 */
public final class Timing {
    /**
     * The start time.
     */
    @SuppressWarnings("FieldNotUsedInToString")
    private long startTime;
    /**
     * The end time.
     */
    @SuppressWarnings("FieldNotUsedInToString")
    private long finishTime;

    /**
     * Starts the timing.
     */
    public final void start() {
        startTime = System.nanoTime();
    }

    /**
     * Resets the timing to current time,
     * calls both {@link Timing#start()} and {@link Timing#stop()}.
     */
    public final void reset() {
        start();
        stop();
    }

    /**
     * Stops the timing.
     */
    public final void stop() {
        finishTime = System.nanoTime();
    }

    /**
     * Gets the elapsed time in the given unit.
     *
     * @param unit The unit of the return value.
     * @return The elapsed time in the requested unit.
     */
    public final long getElapsedTime(final TimeUnit unit) {
        return unit.convert(finishTime - startTime, TimeUnit.NANOSECONDS);
    }

    /**
     * Returns the string representation of this timing.
     */
    @Override
    public final String toString() {
        final long elapsedTime = getElapsedTime(TimeUnit.MILLISECONDS);

        //noinspection StringConcatenationMissingWhitespace
        return elapsedTime + "ms";
    }
}

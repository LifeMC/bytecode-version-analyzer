package com.lifemcserver.bytecodeversionanalyzer.utils;

import com.lifemcserver.bytecodeversionanalyzer.extensions.BiIntConsumer;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * A builder for the class {@link ProgressTracker}.
 */
public final class ProgressTrackerBuilder {
    /**
     * The interval of {@link ProgressTracker}.
     */
    private long interval;
    /**
     * The current of {@link ProgressTracker}.
     */
    private IntSupplier current;
    /**
     * The total of {@link ProgressTracker}.
     */
    private IntSupplier total;
    /**
     * The notify of {@link ProgressTracker}.
     */
    private BiIntConsumer notify;

    /**
     * Sets interval of {@link ProgressTracker}.
     *
     * @param interval The interval.
     * @return This {@link ProgressTrackerBuilder} instance for chaining.
     */
    public final ProgressTrackerBuilder interval(final long interval) {
        this.interval = interval;
        return this;
    }

    /**
     * Sets interval of {@link ProgressTracker}.
     *
     * @param interval The interval.
     * @param unit     The unit.
     * @return This {@link ProgressTrackerBuilder} instance for chaining.
     */
    public final ProgressTrackerBuilder interval(final long interval, final TimeUnit unit) {
        return interval(unit.toNanos(interval));
    }

    /**
     * Sets current of {@link ProgressTracker}.
     *
     * @param current The current.
     * @return This {@link ProgressTrackerBuilder} instance for chaining.
     */
    public final ProgressTrackerBuilder current(final IntSupplier current) {
        this.current = current;
        return this;
    }

    /**
     * Sets total of {@link ProgressTracker}.
     *
     * @param total The total.
     * @return This {@link ProgressTrackerBuilder} instance for chaining.
     */
    public final ProgressTrackerBuilder total(final IntSupplier total) {
        this.total = total;
        return this;
    }

    /**
     * Sets notify of {@link ProgressTracker}.
     *
     * @param notify The notify.
     * @return This {@link ProgressTrackerBuilder} instance for chaining.
     */
    public final ProgressTrackerBuilder notify(final BiIntConsumer notify) {
        this.notify = notify;
        return this;
    }

    /**
     * Builds this {@link ProgressTracker}.
     *
     * @return The {@link ProgressTracker}.
     */
    public final ProgressTracker build() {
        return new ProgressTracker(interval, current, total, notify);
    }

    /**
     * Returns the debug string representation of this {@link ProgressTrackerBuilder}.
     *
     * @return The debug string representation of this {@link ProgressTrackerBuilder}.
     */
    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "ProcessTrackerBuilder{" +
            "interval=" + interval +
            ", current=" + current +
            ", total=" + total +
            ", notify=" + notify +
            '}';
    }
}

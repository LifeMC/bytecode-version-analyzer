package com.lifemcserver.bytecodeversionanalyzer.utils;

import com.lifemcserver.bytecodeversionanalyzer.BytecodeVersionAnalyzer;
import com.lifemcserver.bytecodeversionanalyzer.crosscompile.SpinWait;
import com.lifemcserver.bytecodeversionanalyzer.extensions.BiIntConsumer;
import com.lifemcserver.bytecodeversionanalyzer.extensions.threading.NamedThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * A class for tracking progress of something.
 */
public final class ProgressTracker {
    /**
     * The interval to run this tracker on.
     */
    private final long interval;
    /**
     * Currently processed count.
     */
    private final IntSupplier current;
    /**
     * Total count, might not be available, i.e on {@link Stream Streams}.
     */
    private final IntSupplier total;
    /**
     * The notify hook that accepts current and total count.
     */
    private final BiIntConsumer notify;
    /**
     * The executor to schedule our task at given interval.
     */
    private ScheduledExecutorService executor;
    /**
     * Our last scheduled task, stored to be able to cancel it.
     */
    private ScheduledFuture<?> task;

    /**
     * Creates a new {@link ProgressTracker}.
     * Consider using {@link ProgressTrackerBuilder} instead.
     *
     * @param interval The interval to check for progress.
     * @param current  The supplier to the current value.
     * @param total    The supplier to the total value.
     * @param notify   The notify hook that will run every given interval.
     */
    public ProgressTracker(final long interval, final IntSupplier current, final IntSupplier total, final BiIntConsumer notify) {
        this.interval = interval;

        this.current = current;
        this.total = total;

        this.notify = notify;
    }

    /**
     * Starts this {@link ProgressTracker}.
     * <p>
     * The progress tracker thread will be a daemon thread meaning it will stop when all other threads stop and exit the JVM.
     * However this often not desired and you want to stop tracking as soon as the task is complete.
     * <p>
     * Then you must call {@link ProgressTracker#stop()} after the task is complete.
     *
     * @return This {@link ProgressTracker} instance for ease of use.
     * @see ProgressTracker#stop()
     */
    public final ProgressTracker start() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Progress Tracker", t -> {
                t.setDaemon(true);
                t.setPriority(t.getPriority() / 2);
            }));
        }

        if (interval > 0L) {
            task = executor
                .scheduleWithFixedDelay(this::onInterval, interval, interval, TimeUnit.NANOSECONDS);
        }

        return this;
    }

    /**
     * Stops this {@link ProgressTracker} completely.
     * Cancels the tasks and shutdowns the executor, too.
     * <p>
     * It can be started again, however. With a new executor and a new task of course.
     * <p>
     * This method will do nothing if the task is not started, so it can be called before/without
     * starting, or can be called multiple times, without side effects.
     *
     * @return This {@link ProgressTracker} instance for ease of use.
     * @see ProgressTracker#start()
     */
    public final ProgressTracker stop() {
        // Cancel & nullify our task
        if (task != null) {
            task.cancel(false);
            task = null;
        }

        // Shutdown & nullify our executor
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }

        return this;
    }

    /**
     * Runs the notify hook.
     */
    private final void onInterval() {
        // Note: Do not remove try, ScheduledExecutorService hides exception and cancels task if task fails in an exceptional way.
        try {
            notify.accept(current != null ? current.getAsInt() : -1, total != null ? total.getAsInt() : -1);
            SpinWait.spinWait(); // do not burn CPU if interval is low
        } catch (final Throwable tw) {
            throw BytecodeVersionAnalyzer.handleError(tw);
        }
    }

    /**
     * Returns the debug string representation of this {@link ProgressTracker}.
     *
     * @return The debug string representation of this {@link ProgressTracker}.
     */
    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "ProcessTracker{" +
            "interval=" + interval +
            ", current=" + current +
            ", total=" + total +
            ", notify=" + notify +
            ", executor=" + executor +
            ", task=" + task +
            '}';
    }
}

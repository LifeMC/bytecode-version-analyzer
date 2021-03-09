package com.lifemcserver.bytecodeversionanalyzer.extensions;

/**
 * A custom uncaught exception handler that is different from Java's default.
 * Java's default implementation only ignore {@link ThreadDeath} exceptions.
 * <p>
 * We added our {@link StopCodeExecution} too, to the ignored exceptions list.
 */
public final class StopCodeExecutionUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    /**
     * Handles an uncaught exception exactly like Java's default {@link Thread.UncaughtExceptionHandler},
     * but also ignoring {@link StopCodeExecution} together with {@link ThreadDeath}.
     *
     * @param t The thread that generated the exception.
     * @param e The exception that occurred.
     */
    @Override
    public final void uncaughtException(final Thread t, final Throwable e) {
        if (!(e instanceof ThreadDeath) && !(e instanceof StopCodeExecution)) {
            System.err.print("Exception in thread \"" + t.getName() + "\" ");
            e.printStackTrace(System.err);
        }
    }
}

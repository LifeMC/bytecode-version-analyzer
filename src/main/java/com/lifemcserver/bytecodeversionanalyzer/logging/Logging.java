package com.lifemcserver.bytecodeversionanalyzer.logging;

import com.lifemcserver.bytecodeversionanalyzer.Constants;

import java.util.function.Supplier;

public final class Logging {
    /**
     * Controls which messages to print or not depending on how much information is requested.
     */
    private static Verbosity verbosity = Verbosity.INFO;

    /**
     * Static only class.
     */
    private Logging() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    /**
     * Gets the current logging verbosity.
     *
     * @return The current logging verbosity.
     */
    public static final Verbosity getVerbosity() {
        return verbosity;
    }

    /**
     * Sets the logging verbosity.
     *
     * @param newVerbosity The new verbosity to use.
     */
    public static final void setVerbosity(final Verbosity newVerbosity) {
        final Verbosity oldVerbosity = verbosity;
        verbosity = newVerbosity;

        if (oldVerbosity != Verbosity.DEBUG && verbosity == Verbosity.DEBUG) {
            Logging.debug(() -> "note: debug mode is enabled");
        }
    }

    /**
     * Prints an empty debug message.
     */
    public static final void debug() {
        debug(() -> "");
    }

    /**
     * Prints a debug message.
     *
     * @param message The message to be printed.
     */
    public static final void debug(final Supplier<String> message) {
        if (verbosity.canPrint(Verbosity.DEBUG)) {
            Verbosity.DEBUG.println(message.get());
        }
    }

    /**
     * Prints an empty information message.
     */
    public static final void info() {
        info("");
    }

    /**
     * Prints an information message.
     *
     * @param message The message to be printed.
     */
    public static final void info(final String message) {
        if (verbosity.canPrint(Verbosity.INFO)) {
            Verbosity.INFO.println(message);
        }
    }

    /**
     * Prints an empty warning message.
     */
    public static final void warning() {
        warning("");
    }

    /**
     * Prints a warning message.
     *
     * @param message The message to be printed.
     */
    public static final void warning(final String message) {
        if (verbosity.canPrint(Verbosity.WARN)) {
            Verbosity.WARN.println(Constants.WARNING_PREFIX + message);
        }
    }

    /**
     * Prints an empty error message.
     */
    public static final void error() {
        error("");
    }

    /**
     * Prints a error message.
     *
     * @param message The message to be printed.
     */
    public static final void error(final String message) {
        if (verbosity.canPrint(Verbosity.ERROR)) {
            Verbosity.ERROR.println(Constants.ERROR_PREFIX + message);
        }
    }
}

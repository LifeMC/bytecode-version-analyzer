package com.lifemcserver.bytecodeversionanalyzer.logging;

import com.lifemcserver.bytecodeversionanalyzer.BytecodeVersionAnalyzer;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

@SuppressWarnings("ClassHasNoToStringMethod")
public enum Verbosity {
    DEBUG() {
        @Override
        public final void println(final String str) {
            super.println(str);

            System.out.println(str);
        }
    }, INFO() {
        @Override
        public final void println(final String str) {
            super.println(str);

            System.out.println(str);
        }
    }, WARN() {
        @Override
        public final void println(final String str) {
            super.println(str);

            System.err.println(str);
        }
    }, ERROR() {
        @Override
        public final void println(final String str) {
            super.println(str);

            System.err.println(str);
        }
    }, FATAL() {
        @Override
        public final void println(final String str) {
            super.println(str);

            System.err.println(str);
        }
    }, NONE() {
        @Override
        public final void println(final String str) {
            super.println(str);

            throw new UnsupportedOperationException("can't print on NONE verbosity");
        }
    };

    private final Set<Consumer<String>> hooks = BytecodeVersionAnalyzer.newHashSet();

    public static final Verbosity fromString(final boolean printErrors, final String str, final Verbosity defaultValue) {
        try {
            return valueOf(str.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            if (printErrors) {
                Logging.error("illegal verbosity value \"" + str + "\": " + e.getMessage());
            }
            return defaultValue;
        }
    }

    public static final void clearAllHooks() {
        Arrays.stream(values()).forEach(verbosity -> verbosity.hooks.clear());
    }

    public final boolean canPrint(final Verbosity required) {
        return ordinal() <= required.ordinal();
    }

    public final void onPrint(final Consumer<String> hook) {
        hooks.add(hook);
    }

    public final void onPrintRecursive(final Consumer<String> hook) {
        for (final Verbosity verbosity : values()) {
            if (canPrint(verbosity)) {
                verbosity.hooks.add(hook);
            }
        }
    }

    public void println(final String str) {
        hooks.forEach(hook -> hook.accept(str));
    }
}

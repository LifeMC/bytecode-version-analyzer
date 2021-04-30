package com.lifemcserver.bytecodeversionanalyzer.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

public final class WrappedOutputStream extends OutputStream {
    private final OutputStream original;
    private final BooleanSupplier hook;

    public WrappedOutputStream(final OutputStream original, final BooleanSupplier hook) {
        this.original = original;
        this.hook = hook;
    }

    @Override
    public final void write(final int b) throws IOException {
        if (hook.getAsBoolean()) {
            original.write(b);
        }
    }

    @Override
    public final void write(final byte[] b, final int off, final int len) throws IOException {
        if (hook.getAsBoolean()) {
            original.write(b, off, len);
        }
    }

    @Override
    public final String toString() {
        //noinspection MagicCharacter
        return "WrappedOutputStream{" +
            "original=" + original +
            ", hook=" + hook +
            '}';
    }
}

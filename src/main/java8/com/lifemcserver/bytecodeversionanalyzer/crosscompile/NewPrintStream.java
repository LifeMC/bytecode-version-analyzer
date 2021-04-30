package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

public final class NewPrintStream {

    private NewPrintStream() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final PrintStream newPrintStream(final OutputStream out, final boolean autoFlush) throws UnsupportedEncodingException {
        Logging.debug(() -> "using old PrintStream constructor");
        return new PrintStream(out, autoFlush, StandardCharsets.UTF_8.name());
    }

}

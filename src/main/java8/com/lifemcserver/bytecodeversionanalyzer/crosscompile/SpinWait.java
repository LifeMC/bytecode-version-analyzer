package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;

public final class SpinWait {

    private SpinWait() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final void spinWait() {
        Logging.debug(() -> "using Thread#yield instead of Thread#onSpinWait");
        Thread.yield();
    }

}

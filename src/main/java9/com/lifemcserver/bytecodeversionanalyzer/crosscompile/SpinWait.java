package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;

public final class SpinWait {

    private SpinWait() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final void spinWait() {
        Thread.yield();
        Thread.onSpinWait();
    }

}

package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;

public final class RuntimeVersion {

    private RuntimeVersion() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final Runtime.Version getRuntimeVersion() {
        return Runtime.version();
    }

}

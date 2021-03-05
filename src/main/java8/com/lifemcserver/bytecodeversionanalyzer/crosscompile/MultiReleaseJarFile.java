package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;

import java.io.IOException;
import java.util.jar.JarFile;

public final class MultiReleaseJarFile {

    private MultiReleaseJarFile() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final JarFile newJarFile(final String path) throws IOException {
        Logging.debug(() -> "using non-versioned JarFile constructor");
        return new JarFile(path);
    }

}

package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;
import com.lifemcserver.bytecodeversionanalyzer.logging.Logging;

import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class VersionedJarFileStream {

    private VersionedJarFileStream() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final Stream<JarEntry> stream(final JarFile jar) {
        Logging.debug(() -> "using non-versioned enumeration stream");
        return jar.stream();
    }

}

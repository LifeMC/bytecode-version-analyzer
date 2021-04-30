package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;

import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class VersionedJarFileStream {

    private VersionedJarFileStream() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final Stream<JarEntry> stream(final JarFile jar) {
        return jar.versionedStream();
    }

}

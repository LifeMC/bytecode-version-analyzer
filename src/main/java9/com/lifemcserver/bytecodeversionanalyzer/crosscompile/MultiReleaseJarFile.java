package com.lifemcserver.bytecodeversionanalyzer.crosscompile;

import com.lifemcserver.bytecodeversionanalyzer.Constants;

import java.io.IOException;
import java.io.File;

import java.util.jar.JarFile;
import java.util.zip.ZipFile;

public final class MultiReleaseJarFile {

    private MultiReleaseJarFile() {
        throw new UnsupportedOperationException(Constants.STATIC_ONLY_CLASS);
    }

    public static final JarFile newJarFile(final String path) throws IOException {
        return new JarFile(new File(path), true, ZipFile.OPEN_READ, RuntimeVersion.getRuntimeVersion());
    }

}

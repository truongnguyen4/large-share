package com.datalogic.largeshareapp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Random;

/** Helpers for creating deterministic test payloads. */
public final class TestFiles {
    private TestFiles() {}

    public static File tempDir(String prefix) throws IOException {
        File dir = Files.createTempDirectory(prefix).toFile();
        dir.deleteOnExit();
        return dir;
    }

    public static File randomFile(File dir, String name, long size, long seed) throws IOException {
        dir.mkdirs();
        File file = new File(dir, name);
        Random random = new Random(seed);
        byte[] buffer = new byte[64 * 1024];
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long remaining = size;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int n = (int) Math.min(buffer.length, remaining);
                raf.write(buffer, 0, n);
                remaining -= n;
            }
        }
        return file;
    }

    public static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}

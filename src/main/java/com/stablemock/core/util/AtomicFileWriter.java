package com.stablemock.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;

/**
 * Shared atomic write pattern: unique temp file, write, fsync, atomic move.
 * Used by RequestBodyTracker and AnalysisResultStorage to avoid duplication
 * and keep behavior consistent.
 */
public final class AtomicFileWriter {

    private static final Logger logger = LoggerFactory.getLogger(AtomicFileWriter.class);

    private AtomicFileWriter() {
        // utility class
    }

    /**
     * Writes to the target file atomically: creates a unique temp file in the
     * same directory, invokes the writer, forces data to disk, then atomically
     * moves the temp file to the target (with fallback to non-atomic move if
     * unsupported).
     *
     * @param targetFile final destination file
     * @param writer     callback that writes content to the given temp path
     * @throws IOException if writing or moving fails
     */
    public static void writeAtomically(File targetFile, Writer writer) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent == null) {
            throw new IOException("Target file must have a parent directory for atomic write: " + targetFile);
        }
        // Ensure parent directory exists
        Files.createDirectories(parent.toPath());

        // Files.createTempFile requires prefix length >= 3. Use a fixed, safe prefix
        // and include the target file name in the suffix for easier debugging.
        String suffix = "-" + targetFile.getName() + ".tmp";
        Path tempPath = Files.createTempFile(parent.toPath(), "stablemock-", suffix);
        File tempFile = tempPath.toFile();
        try {
            writer.write(tempPath);
            try (FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tempPath, targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // Best-effort fsync of parent directory to improve rename durability on POSIX.
            try (FileChannel dirChannel = FileChannel.open(parent.toPath(), StandardOpenOption.READ)) {
                dirChannel.force(true);
            } catch (IOException dirSyncException) {
                // Non-fatal; log at debug level if needed in the future.
            }
            tempFile = null;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception cleanupException) {
                    logger.warn("Failed to delete temp file {}: {}", tempFile.getAbsolutePath(), cleanupException.getMessage());
                }
            }
        }
    }

    @FunctionalInterface
    public interface Writer {
        void write(Path tempPath) throws IOException;
    }
}

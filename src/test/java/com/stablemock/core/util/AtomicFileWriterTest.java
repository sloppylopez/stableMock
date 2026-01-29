package com.stablemock.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAtomically_success_contentWrittenCorrectly() throws IOException {
        File target = tempDir.resolve("out.txt").toFile();
        String content = "hello atomic write";

        AtomicFileWriter.writeAtomically(target, tempPath ->
                Files.writeString(tempPath, content));

        assertTrue(target.exists());
        assertEquals(content, Files.readString(target.toPath()));
    }

    @Test
    void writeAtomically_overwrite_replacesContent() throws IOException {
        File target = tempDir.resolve("out.txt").toFile();

        AtomicFileWriter.writeAtomically(target, tempPath ->
                Files.writeString(tempPath, "first"));
        assertEquals("first", Files.readString(target.toPath()));

        AtomicFileWriter.writeAtomically(target, tempPath ->
                Files.writeString(tempPath, "second"));
        assertEquals("second", Files.readString(target.toPath()));
    }

    @Test
    void writeAtomically_writerThrows_tempFileRemoved() {
        File target = tempDir.resolve("out.txt").toFile();
        IOException expected = new IOException("writer failed");

        assertThrows(IOException.class, () ->
                AtomicFileWriter.writeAtomically(target, tempPath -> {
                    throw expected;
                }));

        try (Stream<Path> list = Files.list(tempDir)) {
            long stablemockTmpCount = list
                    .filter(p -> p.getFileName().toString().startsWith("stablemock-"))
                    .count();
            assertEquals(0, stablemockTmpCount, "Temp file should be cleaned up when writer throws");
        } catch (IOException e) {
            fail("Listing directory failed", e);
        }
    }

    @Test
    void writeAtomically_targetNoParent_throwsIOException() {
        File targetWithNoParent = new File("bare-name-no-parent");

        IOException thrown = assertThrows(IOException.class, () ->
                AtomicFileWriter.writeAtomically(targetWithNoParent, tempPath ->
                        Files.writeString(tempPath, "x")));

        assertTrue(thrown.getMessage().contains("parent"),
                "Message should mention parent: " + thrown.getMessage());
    }

    @Test
    void writeAtomically_parentDirMissing_createsAndWrites() throws IOException {
        File target = tempDir.resolve("a").resolve("b").resolve("c").resolve("out.txt").toFile();
        String content = "nested content";

        AtomicFileWriter.writeAtomically(target, tempPath ->
                Files.writeString(tempPath, content));

        assertTrue(target.exists());
        assertEquals(content, Files.readString(target.toPath()));
    }
}

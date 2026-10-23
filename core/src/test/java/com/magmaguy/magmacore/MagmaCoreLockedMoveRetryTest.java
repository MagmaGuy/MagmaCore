package com.magmaguy.magmacore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On Windows a freshly populated stage directory can still be held open by an
 * antivirus scanner or the search indexer for a moment, so the rename into
 * {@code nightbreak_rsp_defaults} is denied. Those locks last milliseconds, and
 * the export must wait them out instead of leaving the consumer's pack empty.
 */
class MagmaCoreLockedMoveRetryTest {

    @TempDir
    Path temporaryDirectory;

    private Path source;
    private Path destination;
    private final List<Long> sleeps = new ArrayList<>();
    private final MagmaCore.Sleeper recordingSleeper = sleeps::add;

    /** A mover that is denied a given number of times and then really moves the directory. */
    private static final class LockedMover implements MagmaCore.PathMover {
        private final int deniedAttempts;
        private final IOException denial;
        int calls;

        LockedMover(int deniedAttempts, IOException denial) {
            this.deniedAttempts = deniedAttempts;
            this.denial = denial;
        }

        @Override
        public void move(Path from, Path to) throws IOException {
            calls++;
            if (calls <= deniedAttempts) throw denial;
            Files.move(from, to);
        }
    }

    private void createStagedDirectory() throws IOException {
        source = Files.createDirectories(temporaryDirectory.resolve(".nb-rsp-stage-1"));
        Files.writeString(source.resolve("asset.json"), "payload");
        destination = temporaryDirectory.resolve("nightbreak_rsp_defaults");
    }

    @Test
    void retriesAMoveThatAWindowsLockDeniesUntilItSucceeds() throws Exception {
        createStagedDirectory();
        LockedMover mover = new LockedMover(3, new AccessDeniedException(
                source.toString(), destination.toString(), null));

        MagmaCore.movePath(source, destination, mover, recordingSleeper);

        assertEquals(4, mover.calls);
        assertEquals(3, sleeps.size());
        assertEquals("payload", Files.readString(destination.resolve("asset.json")));
        assertFalse(Files.exists(source));
    }

    @Test
    void retriesASharingViolationReportedAsAPlainFileSystemException() throws Exception {
        createStagedDirectory();
        LockedMover mover = new LockedMover(1, new FileSystemException(
                source.toString(), destination.toString(),
                "The process cannot access the file because it is being used by another process"));

        MagmaCore.movePath(source, destination, mover, recordingSleeper);

        assertEquals(2, mover.calls);
        assertTrue(Files.isDirectory(destination));
    }

    @Test
    void doesNotRetryAFailureThatIsNotATransientLock() throws Exception {
        createStagedDirectory();
        DirectoryNotEmptyException failure = new DirectoryNotEmptyException(destination.toString());
        LockedMover mover = new LockedMover(Integer.MAX_VALUE, failure);

        IOException thrown = assertThrows(IOException.class,
                () -> MagmaCore.movePath(source, destination, mover, recordingSleeper));

        assertSame(failure, thrown);
        assertEquals(1, mover.calls);
        assertTrue(sleeps.isEmpty());
        assertTrue(Files.isDirectory(source));
    }

    @Test
    void givesUpAfterTenAttemptsSpreadOverAboutTwoSeconds() throws Exception {
        createStagedDirectory();
        AccessDeniedException lock = new AccessDeniedException(
                source.toString(), destination.toString(), null);
        LockedMover mover = new LockedMover(Integer.MAX_VALUE, lock);

        IOException thrown = assertThrows(IOException.class,
                () -> MagmaCore.movePath(source, destination, mover, recordingSleeper));

        assertEquals(10, mover.calls);
        assertEquals(9, sleeps.size());
        long totalBackoff = sleeps.stream().mapToLong(Long::longValue).sum();
        assertTrue(totalBackoff >= 1000 && totalBackoff <= 2500,
                "backoff should total about two seconds, was " + totalBackoff + " ms");
        assertTrue(sleeps.stream().allMatch(millis -> millis > 0));
        assertSame(lock, thrown.getCause());
        assertTrue(thrown.getMessage().contains("10 attempts"), thrown.getMessage());
        assertTrue(Files.isDirectory(source));
    }
}

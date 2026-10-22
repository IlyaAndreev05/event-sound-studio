package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinuxPipeBudgetTest {
    @Test void shrinkActuallyReducesAndReadsBackChildPipeCapacity() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"));
        Process child = new ProcessBuilder("/bin/sleep", "30").start();
        try {
            int before = LinuxPipeBudget.capacityBytes(child, 0);
            assumeTrue(before > 4096, "The kernel pipe is already at or below the requested capacity");

            int after = LinuxPipeBudget.shrink(child, 0);

            assertEquals(4096, after, "the helper must report the kernel's post-resize capacity");
            assertEquals(after, LinuxPipeBudget.capacityBytes(child, 0), "reported size must match F_GETPIPE_SZ");
            assertTrue(after < before, "the child pipe must be smaller than its original capacity");
        } finally {
            child.destroyForcibly();
            child.waitFor(2, TimeUnit.SECONDS);
        }
    }
}

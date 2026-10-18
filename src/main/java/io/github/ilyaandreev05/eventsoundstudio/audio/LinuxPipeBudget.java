package io.github.ilyaandreev05.eventsoundstudio.audio;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Keeps pw-cat from buffering most of a second of stdin audio in the kernel pipe. */
final class LinuxPipeBudget {
    private static final int F_SETPIPE_SZ = 1031, F_GETPIPE_SZ = 1032, PIPE_BYTES = 4096;
    private static final MethodHandle FCNTL = fcntl();
    private LinuxPipeBudget() { }

    private static MethodHandle fcntl() {
        try {
            Linker linker = Linker.nativeLinker();
            return linker.downcallHandle(linker.defaultLookup().find("fcntl").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Shrinks the child's stdin pipe on a best-effort basis; a denied fcntl leaves the stream usable. */
    static void shrink(Process process) { shrink(process, 0); }

    static void shrink(Process process, int childFd) {
        if (FCNTL == null || process == null || !process.isAlive()) return;
        try {
            String pipe = Files.readSymbolicLink(Path.of("/proc/" + process.pid() + "/fd/" + childFd)).toString();
            if (!pipe.startsWith("pipe:[")) return;
            int fd = -1;
            try (Stream<Path> paths = Files.list(Path.of("/proc/self/fd"))) {
                for (Path path : paths.toList()) {
                    try {
                        if (pipe.equals(Files.readSymbolicLink(path).toString())) {
                            fd = Integer.parseInt(path.getFileName().toString());
                            break;
                        }
                    } catch (Exception ignored) { }
                }
            }
            if (fd < 0) return;
            int current = (int) FCNTL.invokeExact(fd, F_GETPIPE_SZ, 0);
            if (current > PIPE_BYTES) FCNTL.invokeExact(fd, F_SETPIPE_SZ, PIPE_BYTES);
        } catch (Throwable ignored) {
            // Non-Linux platforms and restricted native access keep the default pipe.
        }
    }
}

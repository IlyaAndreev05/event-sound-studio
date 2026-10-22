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

    /** Shrinks the child's stdin pipe on a best-effort basis; returns the observed capacity in bytes. */
    static void shrink(Process process) { shrink(process, 0); }

    static int shrink(Process process, int childFd) {
        int fd = pipeFd(process, childFd);
        if (fd < 0) return -1;
        try {
            int current = capacity(fd);
            if (current > PIPE_BYTES) {
                int resized = (int) FCNTL.invokeExact(fd, F_SETPIPE_SZ, PIPE_BYTES);
                if (resized < 0) return capacity(fd);
            }
            return capacity(fd);
        } catch (Throwable ignored) {
            // Non-Linux platforms and restricted native access keep the default pipe.
            return -1;
        }
    }

    static int capacityBytes(Process process, int childFd) {
        int fd = pipeFd(process, childFd);
        if (fd < 0) return -1;
        try {
            return capacity(fd);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int capacity(int fd) throws Throwable {
        return (int) FCNTL.invokeExact(fd, F_GETPIPE_SZ, 0);
    }

    private static int pipeFd(Process process, int childFd) {
        if (FCNTL == null || process == null || !process.isAlive() || childFd < 0) return -1;
        try {
            String pipe = Files.readSymbolicLink(Path.of("/proc/" + process.pid() + "/fd/" + childFd)).toString();
            if (!pipe.startsWith("pipe:[")) return -1;
            try (Stream<Path> paths = Files.list(Path.of("/proc/self/fd"))) {
                return paths.filter(path -> {
                    try { return pipe.equals(Files.readSymbolicLink(path).toString()); }
                    catch (Exception ignored) { return false; }
                }).mapToInt(path -> Integer.parseInt(path.getFileName().toString())).findFirst().orElse(-1);
            }
        } catch (Throwable ignored) {
            return -1;
        }
    }
}

package kr.codenamemc.codeengine.externalverification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class VerificationResults {
    private final List<String> checks = new ArrayList<>();
    private final List<String> samples = new ArrayList<>();
    private final List<String> coldLoads = new ArrayList<>();
    void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks.add("PASS " + message);
    }
    void sample(int index, String path, String workload, long nanos, long bytes, int operations) {
        samples.add(index + "," + path + "," + workload + "," + nanos + "," + bytes + "," + operations);
    }
    void coldLoad(int index, long nanos) { coldLoads.add(index + "," + nanos); }
    int count() { return checks.size(); }
    void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("checks.txt"), String.join("\n", checks) + "\n");
        Files.writeString(directory.resolve("samples.csv"), "sample,path,workload,nanos,bytes,operations\n" + String.join("\n", samples) + "\n");
        Files.writeString(directory.resolve("cold-loads.csv"), "index,nanos\n" + String.join("\n", coldLoads) + "\n");
    }
}

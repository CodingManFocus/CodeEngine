package kr.codenamemc.codeengine.runtime;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import kr.codenamemc.codeengine.compiler.ModuleCompiler;

/** Build directories are unique; deletion never needs the server thread or Paper APIs. */
final class BuildCleanup {
    private BuildCleanup() { }

    static CompletableFuture<Void> delete(Path directory) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        // A non-daemon thread lets deletion finish even when the server is exiting.
        try {
            Thread.ofPlatform().daemon(false).name("CodeEngine-BuildCleanup").start(() -> {
                try {
                    ModuleCompiler.deleteBuild(directory);
                    result.complete(null);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }
}

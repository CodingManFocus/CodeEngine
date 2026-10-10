package kr.codenamemc.codeengine.runtime.dependency;

import java.nio.file.Path;

/** The original definition and its compile-time source, never a redefined library copy. */
record ResolvedApiType(Class<?> type, Path jar) { }

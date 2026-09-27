package kr.codenamemc.codeengine.externalverification;

import java.util.function.IntUnaryOperator;

/** Shared test input and observable output, never included in production CodeEngine. */
public final class BenchmarkRegistry {
    private static final String[] singleQueries = new String[256];
    private static final String[] multipleQueries = new String[256];
    private static IntUnaryOperator moduleSingle;
    private static IntUnaryOperator moduleMultiple;
    private static Class<?> moduleApiClass;
    private static volatile long sink;

    static {
        for (int index = 0; index < singleQueries.length; index++) {
            singleQueries[index] = "prefix/%cenative_" + index + "%/suffix";
            multipleQueries[index] = singleQueries[index] + "/%cenative_" + (index + 1)
                + "%/%cenative_" + (index + 2) + "%/%cenative_" + (index + 3) + "%";
        }
    }
    private BenchmarkRegistry() { }
    public static String singleQuery(int index) { return singleQueries[index & 255]; }
    public static String multipleQuery(int index) { return multipleQueries[index & 255]; }
    public static void install(IntUnaryOperator single, IntUnaryOperator multiple, Class<?> apiClass) {
        if (moduleSingle != null) throw new IllegalStateException("Previous module still registered");
        moduleSingle = single;
        moduleMultiple = multiple;
        moduleApiClass = apiClass;
    }
    public static void clear() { moduleSingle = null; moduleMultiple = null; moduleApiClass = null; }
    public static IntUnaryOperator moduleSingle() { return moduleSingle; }
    public static IntUnaryOperator moduleMultiple() { return moduleMultiple; }
    public static Class<?> moduleApiClass() { return moduleApiClass; }
    public static void consume(long value) { sink = value; }
    public static long consumed() { return sink; }
}

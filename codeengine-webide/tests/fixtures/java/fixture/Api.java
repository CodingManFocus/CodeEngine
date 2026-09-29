package fixture;

import java.util.Iterator;
import java.util.List;

/** Real javac fixture. Regenerate classfiles.json with javac -parameters -g. */
public class Api<T extends Number> extends Base<T> implements Iterable<T> {
    public static final String TEXT = "hello\u0000한글😀";
    public static final long LARGE = 9007199254740993L;
    public static final double FRACTION = 1.25;
    public int counter;
    private String hidden;

    public Api(int initial) { counter = initial; }

    @Deprecated
    public List<T> values(String label, int limit) { return List.of(); }

    public Iterator<T> iterator() { return values("", 0).iterator(); }

    public static class Nested { public void nested() {} }
    protected static class ProtectedNested { public void nested() {} }
    private static class PrivateNested { public void hidden() {} }
}

class Hidden { public void hidden() {} }

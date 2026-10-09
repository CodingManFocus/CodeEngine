package fixture;

public class Base<T> {
    public T inherited(T value) { return value; }
    protected void protectedMethod() {}
    private void hidden() {}
}

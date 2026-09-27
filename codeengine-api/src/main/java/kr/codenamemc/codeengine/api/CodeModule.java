package kr.codenamemc.codeengine.api;

/** Generated module lifecycle. Invoked only on the server thread. */
public interface CodeModule {
    void prepare(ModuleContext context) throws Exception;
    default void enable() throws Exception { }
    default void disable() throws Exception { }
}

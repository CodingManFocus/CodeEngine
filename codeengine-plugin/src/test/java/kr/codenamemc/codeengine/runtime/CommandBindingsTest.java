package kr.codenamemc.codeengine.runtime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommandBindingsTest {
    @Test void removesOnlyOwnedCommandsWithoutIteratorRemove() {
        Map<String, Object> backing = new HashMap<>();
        Object own = new Object(), other = new Object();
        backing.put("hello", own); backing.put("module:hello", own); backing.put("other", other);
        Map<String, Object> forwarding = new AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() { return Collections.unmodifiableMap(backing).entrySet(); }
            @Override public Object remove(Object key) { return backing.remove(key); }
        };
        CommandBindings.removeOwned(forwarding, List.of(own));
        assertEquals(Map.of("other", other), backing);
    }
}

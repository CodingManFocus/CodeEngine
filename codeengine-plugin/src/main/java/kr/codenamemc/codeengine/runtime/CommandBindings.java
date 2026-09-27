package kr.codenamemc.codeengine.runtime;

import java.util.Collection;
import java.util.Map;

final class CommandBindings {
    private CommandBindings() { }
    // Paper's forwarding map intentionally does not support Iterator.remove().
    static <T> void removeOwned(Map<String, T> known, Collection<? extends T> owned) {
        var names = known.entrySet().stream().filter(entry -> owned.contains(entry.getValue()))
            .map(Map.Entry::getKey).toList();
        for (String name : names) {
            T value = known.get(name);
            if (owned.contains(value)) known.remove(name, value);
        }
    }
}

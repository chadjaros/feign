package feign;

import java.util.Collection;
import java.util.Map;

public interface HeaderSupplier {
    Map<String, Collection<String>> get();
}

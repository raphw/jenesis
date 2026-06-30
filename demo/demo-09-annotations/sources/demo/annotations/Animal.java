package demo.annotations;

import org.immutables.value.Value;

@Value.Immutable
public interface Animal {

    String name();

    int legs();
}

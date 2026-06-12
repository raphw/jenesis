package greetertest;

import org.junit.jupiter.api.Test;
import sample.greeter.Greeter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreeterTest {

    @Test
    void prefix_is_a_greeting() {
        assertTrue(new Greeter().prefix().startsWith("hello"));
    }

    @Test
    void prefix_is_not_blank() {
        assertFalse(new Greeter().prefix().isBlank());
    }

    @Test
    void prefix_is_loaded_from_the_packaged_resource() {
        assertTrue(new Greeter().prefix().contains("packaged resource"));
    }
}

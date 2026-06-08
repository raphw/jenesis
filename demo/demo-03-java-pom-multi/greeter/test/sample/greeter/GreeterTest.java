package sample.greeter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreeterTest {

    @Test
    @Tag("slow")
    void prefix_is_a_greeting() {
        assertTrue(new Greeter().prefix().startsWith("Hello"));
    }

    @Test
    void prefix_is_not_blank() {
        assertFalse(new Greeter().prefix().isBlank());
    }
}

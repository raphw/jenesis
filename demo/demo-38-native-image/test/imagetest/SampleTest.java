package imagetest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleTest {

    @Test
    void greets_through_reflection() throws Exception {
        Class<?> greeter = Class.forName("sample.Greeter");
        Object greeting = greeter.getMethod("greet", String.class)
                .invoke(greeter.getConstructor().newInstance(), "test");
        assertTrue(((String) greeting).contains("test"));
    }
}

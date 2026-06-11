package sampletest;

import org.junit.jupiter.api.Test;
import sample.Sample;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleTest {

    @Test
    void greeting_is_loaded_from_the_packaged_resource() {
        String greeting = new Sample().greet();
        assertTrue(greeting.contains("packaged resource"), greeting);
        assertTrue(greeting.contains("Kotlin"), greeting);
    }
}

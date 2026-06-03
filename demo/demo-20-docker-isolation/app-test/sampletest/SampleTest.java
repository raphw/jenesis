package sampletest;

import org.junit.jupiter.api.Test;
import sample.Sample;

import java.io.IOException;

class SampleTest {

    @Test
    void touchesHostSecret() throws IOException {
        Sample.peek("test");
    }
}

package calctest;

import calc.Adder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdderTest {

    @Test
    void adds_two_numbers() throws Exception {
        Files.writeString(Files.createDirectories(Path.of("target", "ran")).resolve("AdderTest"), "");
        assertEquals(5, new Adder().add(2, 3));
    }
}

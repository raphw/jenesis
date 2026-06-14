package calctest;

import calc.Subtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtractorTest {

    @Test
    void subtracts_two_numbers() throws Exception {
        Files.writeString(Files.createDirectories(Path.of("target", "ran")).resolve("SubtractorTest"), "");
        assertEquals(2, new Subtractor().subtract(5, 3));
    }
}

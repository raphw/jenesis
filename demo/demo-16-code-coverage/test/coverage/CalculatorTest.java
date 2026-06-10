package coverage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {

    @Test
    void adds() {
        assertEquals(5, new Calculator().add(2, 3));
    }
}

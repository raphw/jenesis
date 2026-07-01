package calctest;

import calc.Calculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorTest {

    @Test
    public void adds() {
        assertEquals(5, new Calculator().add(2, 3));
    }
}

package build.buildbuddy;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class SequencedPropertiesTest {

    @Test
    public void can_suppress_comments_and_subsequent_newline() throws IOException {
        Properties original = new SequencedProperties();
        for (char character = 'z'; character >= 'a'; character--) {
            original.setProperty("key-" + character, "value-" + character);
        }
        StringWriter writer = new StringWriter();
        original.store(writer, null);
        assertThat(writer.toString()).isEqualTo(IntStream.iterate('z',
                        character -> character >= 'a',
                        character -> character - 1)
                .mapToObj(character -> "key-" + (char) character + "=value-" + (char) character)
                .collect(Collectors.joining("\n", "", "\n")));
        Properties copy = new SequencedProperties();
        copy.load(new StringReader(writer.toString()));
        assertThat(copy.stringPropertyNames()).containsExactlyElementsOf(original.stringPropertyNames());
    }
}
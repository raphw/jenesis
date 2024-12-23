package build.buildbuddy;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class CommentSuppressingWriterTest {

    @Test
    public void can_suppress_comments_and_subsequent_newline() throws IOException {
        StringWriter left = new StringWriter(), right = new StringWriter(), original = new StringWriter();
        Properties input = new Properties();
        input.setProperty("key", "value");
        input.store(new CommentSuppressingWriter(left), null);
        input.store(new CommentSuppressingWriter(right), null);
        input.store(original, null);
        assertThat(left.toString()).isEqualTo(right.toString());
        assertThat(left.toString()).doesNotStartWith("\n");
        assertThat(left.toString()).isNotEqualTo(original.toString());
        Properties output = new Properties();
        output.load(new StringReader(left.toString()));
        assertThat(output).containsOnlyKeys("key").containsValue("value");
    }
}

package build.buildbuddy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

public class CommentSuppressingWriter extends BufferedWriter {

    private boolean skipNextNewLine;

    public CommentSuppressingWriter(Writer out) {
        super(out);
    }

    @Override
    public void write(String str) throws IOException {
        if (str.startsWith("#")) {
            skipNextNewLine = true;
        } else {
            super.write(str);
        }
    }

    @Override
    public void newLine() throws IOException {
        if (skipNextNewLine) {
            skipNextNewLine = false;
        } else {
            super.newLine();
        }
    }
}

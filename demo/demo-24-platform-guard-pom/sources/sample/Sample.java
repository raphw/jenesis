package sample;

import org.apache.commons.text.WordUtils;

public class Sample {

    public static void main(String[] args) {
        boolean modern;
        try {
            Class.forName("org.apache.commons.lang3.util.FluentBitSet");
            modern = true;
        } catch (ClassNotFoundException _) {
            modern = false;
        }
        System.out.println(WordUtils.capitalize("selected variant: " + (modern
                ? "the modern commons-lang3 3.14.0"
                : "the legacy commons-lang3 3.12.0")));
    }
}

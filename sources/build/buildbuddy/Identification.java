package build.buildbuddy;

import java.util.SequencedMap;

public record Identification(String coordinate, SequencedMap<String, String> dependencies) {
}

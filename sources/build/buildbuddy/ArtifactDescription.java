package build.buildbuddy;

import java.util.List;

public record ArtifactDescription(String coordinate, List<String> dependencies) {
}

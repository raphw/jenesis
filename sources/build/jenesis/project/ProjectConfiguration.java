package build.jenesis.project;

import module java.base;

/**
 * An ordered set of directories in which a module's configuration files are looked
 * up. A file is resolved from the first directory that contains it, so a
 * module-specific location can override a project-wide default that is listed after
 * it.
 */
public record ProjectConfiguration(SequencedSet<Path> folders) {

    public ProjectConfiguration {
        folders = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(folders));
    }

    public static ProjectConfiguration of(Path... folders) {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        for (Path folder : folders) {
            if (folder != null) {
                ordered.add(folder);
            }
        }
        return new ProjectConfiguration(ordered);
    }

    /**
     * Returns the resolved path of {@code fileName} in the first folder that holds it
     * as a regular file, or {@code null} if no folder contains it.
     */
    public Path locate(String fileName) {
        for (Path folder : folders) {
            Path candidate = folder.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}

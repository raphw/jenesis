package build.buildbuddy.maven;

import build.buildbuddy.Finder;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.SequencedSet;

public class MavenPomFinder implements Finder {

    @Override
    public SequencedSet<Path> search(Path root) throws IOException {
        SequencedSet<Path> modules = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("pom.xml")) {
                    modules.add(file.getParent());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return modules;
    }
}

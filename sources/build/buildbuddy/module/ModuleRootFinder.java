package build.buildbuddy.module;

import build.buildbuddy.RootFinder;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.SequencedSet;

public class ModuleRootFinder implements RootFinder {

    @Override
    public SequencedSet<Path> search(Path root) throws IOException {
        SequencedSet<Path> modules = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("module-info.java")) {
                    modules.add(file.getParent());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return modules;
    }
}

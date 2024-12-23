package codes.rafael.buildbuddy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ChecksumNopDiff implements ChecksumDiff {

    @Override
    public Map<Path, ChecksumStatus> update(Path checksum, Path root) throws IOException {
        return traverse(root);
    }

    @Override
    public Map<Path, ChecksumStatus> read(Path checksum, Path root) throws IOException {
        return traverse(root);
    }

    private static Map<Path, ChecksumStatus> traverse(Path root) throws IOException {
        Map<Path, ChecksumStatus> status = new LinkedHashMap<>();
        Queue<Path> queue = new ArrayDeque<>(List.of(root));
        do {
            Path current = queue.remove();
            if (Files.isDirectory(current)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                    stream.forEach(queue::add);
                }
            } else {
                status.put(root.relativize(current), ChecksumStatus.ADDED);
            }
        } while (!queue.isEmpty());
        return status;
    }
}

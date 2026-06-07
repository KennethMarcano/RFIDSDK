package com.peripheral.workflow;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorkflowSessionStore {

    private static final Path BASE_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "rfidsdk-workflow");

    private Path sessionDirectory;
    private final List<WorkflowReadingRecord> records = new ArrayList<>();

    public void beginSession() throws IOException {
        clearSession();
        Files.createDirectories(BASE_DIR);
        sessionDirectory = BASE_DIR.resolve("session_" + System.currentTimeMillis());
        Files.createDirectories(sessionDirectory);
    }

    public void clearSession() {
        records.clear();
        if (sessionDirectory != null) {
            deleteRecursively(sessionDirectory);
            sessionDirectory = null;
        }
    }

    public Path getSessionDirectory() {
        return sessionDirectory;
    }

    public WorkflowReadingRecord addReading(WorkflowContext context) {
        WorkflowReadingRecord record = new WorkflowReadingRecord(
                records.size() + 1,
                System.currentTimeMillis(),
                context.getWeightKg(),
                new ArrayList<>(context.getTagCodes()),
                context.getPhotoPath());
        records.add(record);
        return record;
    }

    public List<WorkflowReadingRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public int getNextPhotoIndex() {
        return records.size() + 1;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }
}

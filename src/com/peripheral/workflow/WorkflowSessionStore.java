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

    private Path baseDir;
    private Path sessionDirectory;
    private final List<WorkflowReadingRecord> records = new ArrayList<>();

    public void beginSession() throws IOException {
        clearSession();
        baseDir = resolveWritableBaseDir();
        sessionDirectory = baseDir.resolve("session_" + System.currentTimeMillis());
        Files.createDirectories(sessionDirectory);
    }

    private static Path resolveWritableBaseDir() throws IOException {
        String tmp = System.getProperty("java.io.tmpdir");
        String home = System.getProperty("user.home");
        Path[] candidates = new Path[]{
                tmp != null && !tmp.isEmpty() ? Paths.get(tmp, "rfidsdk-workflow") : null,
                home != null && !home.isEmpty() ? Paths.get(home, ".rfidsdk-workflow") : null,
                Paths.get("rfidsdk-workflow")
        };
        IOException lastError = null;
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (Files.isWritable(candidate)) {
                    return candidate;
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw new IOException("Não foi possível criar pasta de sessão do fluxo: " + lastError.getMessage(), lastError);
        }
        throw new IOException("Não foi possível criar pasta de sessão do fluxo (sem permissão em /tmp ou no diretório do usuário).");
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
                context.getPhotoPath(),
                context.getLabelPdfPath(),
                context.getNumeroPedido(),
                context.getVolumeIndex(),
                context.getValidationStatusLabel(),
                context.getAiMessage(),
                context.isOperatorConfirmed());
        records.add(record);
        return record;
    }

    public List<WorkflowReadingRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public int getNextPhotoIndex() {
        return records.size() + 1;
    }

    public int getNextLabelIndex() {
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

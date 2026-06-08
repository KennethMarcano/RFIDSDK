package com.peripheral.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class WorkflowEnvironment {

    private WorkflowEnvironment() {
    }

    public static String checkSessionDirectoryWritable() {
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
                Path probe = candidate.resolve(".write-test-" + System.currentTimeMillis());
                Files.write(probe, new byte[]{0});
                Files.deleteIfExists(probe);
                return null;
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            return "Sem permissão para criar pasta de sessão do fluxo: " + lastError.getMessage()
                    + ". Verifique /tmp e o diretório do usuário.";
        }
        return "Não foi possível preparar pasta de sessão do fluxo.";
    }

    public static String checkPdfLibrariesAvailable() {
        try {
            Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            Class.forName("org.apache.fontbox.FontBoxFont");
            return null;
        } catch (ClassNotFoundException e) {
            return "Bibliotecas PDF não encontradas no classpath (pdfbox/fontbox).\n\n"
                    + "No Raspberry Pi / Linux:\n"
                    + "  1) cd na pasta do projeto\n"
                    + "  2) bash scripts/fetch-pdf-libs.sh\n"
                    + "  3) ./iniciar.sh\n\n"
                    + "Não execute só 'java -cp out ...' — use ./iniciar.sh para incluir os JARs.";
        }
    }
}

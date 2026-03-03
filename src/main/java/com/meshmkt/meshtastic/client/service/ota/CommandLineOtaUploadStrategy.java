package com.meshmkt.meshtastic.client.service.ota;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Linux-friendly uploader strategy that delegates firmware upload to an external CLI tool.
 * <p>
 * The command template supports these placeholders:
 * </p>
 * <ul>
 * <li>{@code {firmware}}: absolute firmware path</li>
 * <li>{@code {hash_hex}}: firmware SHA-256 in lowercase hex</li>
 * <li>{@code {node_id_hex}}: target node id hex string without prefix</li>
 * </ul>
 */
public class CommandLineOtaUploadStrategy implements OtaUploadStrategy {

    private final List<String> commandTemplate;

    /**
     * Creates a new command-line uploader strategy.
     *
     * @param commandTemplate command template tokens.
     */
    public CommandLineOtaUploadStrategy(List<String> commandTemplate) {
        this.commandTemplate = List.copyOf(Objects.requireNonNull(commandTemplate, "commandTemplate must not be null"));
        if (this.commandTemplate.isEmpty()) {
            throw new IllegalArgumentException("commandTemplate must contain at least one command token");
        }
    }

    /**
     * Executes the configured command and streams subprocess output for diagnostics.
     *
     * @param context upload context.
     * @return completion future.
     */
    @Override
    public CompletableFuture<Void> upload(OtaUploadContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return CompletableFuture.runAsync(() -> runCommand(context));
    }

    private void runCommand(OtaUploadContext context) {
        List<String> resolved = resolveTemplate(context);
        ProcessBuilder builder = new ProcessBuilder(resolved);
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (context.isCancelled()) {
                        process.destroyForcibly();
                        throw new IllegalStateException("OTA upload command cancelled by caller");
                    }
                    // Output is consumed for diagnostics and to prevent subprocess stdout blocking.
                }
            }

            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("OTA upload command exited with code " + exit);
            }

            Path firmware = context.firmwarePath();
            long total = java.nio.file.Files.size(firmware);
            context.reportProgress(total, total);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute OTA upload command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for OTA upload command", e);
        }
    }

    private List<String> resolveTemplate(OtaUploadContext context) {
        String firmware = context.firmwarePath().toAbsolutePath().toString();
        String hashHex = toHex(context.firmwareSha256());
        String nodeHex = Integer.toHexString(context.request().targetNodeId());

        List<String> resolved = new ArrayList<>(commandTemplate.size());
        for (String token : commandTemplate) {
            resolved.add(token
                    .replace("{firmware}", firmware)
                    .replace("{hash_hex}", hashHex)
                    .replace("{node_id_hex}", nodeHex));
        }
        return resolved;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

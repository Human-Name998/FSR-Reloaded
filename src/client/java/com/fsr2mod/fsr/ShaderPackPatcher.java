package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.IrisCompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Applies compatibility patches to Iris shaderpacks for FSR Reloaded.
 *
 * The patcher is NON-DESTRUCTIVE: it only injects a {@code #define FSR_RELOADED}
 * into GLSL shader files. This define is inert without corresponding
 * {@code #ifdef FSR_RELOADED} blocks in the pack — the pack continues to work
 * exactly as before when FSR Reloaded is disabled or uninstalled.
 *
 * Pack authors can later add conditional code to disable their own TAA, motion
 * blur, etc. when FSR is active:
 * <pre>{@code
 *   #ifdef FSR_RELOADED
 *   // FSR Reloaded is active — disable my TAA
 *   #else
 *   // Normal TAA code
 *   #endif
 * }</pre>
 *
 * After patching, the player should reload the shaderpack (F3+T) for the
 * define to take effect.
 */
public final class ShaderPackPatcher {

    private ShaderPackPatcher() {}

    /** Result of a patching operation. */
    public static class PatchResult {
        public final boolean success;
        public final String message;
        public final List<String> filesModified;
        public final List<String> warnings;

        private PatchResult(boolean success, String message, List<String> filesModified, List<String> warnings) {
            this.success = success;
            this.message = message;
            this.filesModified = filesModified;
            this.warnings = warnings;
        }

        static PatchResult ok(String msg, List<String> files, List<String> warnings) {
            return new PatchResult(true, msg, files, warnings);
        }

        static PatchResult fail(String msg) {
            return new PatchResult(false, msg, Collections.emptyList(), Collections.emptyList());
        }
    }

    /**
     * Apply all FSR Reloaded patches to the currently active shaderpack.
     * @return result describing what was done
     */
    public static PatchResult patchCurrentPack() {
        Path packPath = IrisCompat.getLoadedShaderPackPath();
        if (packPath == null) {
            return PatchResult.fail("No shaderpack loaded (or path not found)");
        }

        if (Files.isDirectory(packPath)) {
            return patchDirectoryPack(packPath);
        } else if (Files.isRegularFile(packPath) && packPath.toString().endsWith(".zip")) {
            return patchZipPack(packPath);
        } else {
            return PatchResult.fail("Unsupported shaderpack format: " + packPath);
        }
    }

    // ── Directory pack patching ──────────────────────────────────────────

    private static PatchResult patchDirectoryPack(Path packDir) {
        List<String> modified = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Scan all GLSL files and inject the FSR_RELOADED define.
            // This is NON-DESTRUCTIVE: the define is inert without #ifdef blocks
            // in the pack. Pack authors can later add conditional code to disable
            // their own TAA/motion blur when FSR is active.
            try (Stream<Path> walk = Files.walk(packDir)) {
                walk.filter(p -> isGlslFile(p))
                    .forEach(glslFile -> {
                        try {
                            String original = Files.readString(glslFile, StandardCharsets.UTF_8);
                            String patched = patchGlslSource(original, packDir.relativize(glslFile).toString());
                            if (!patched.equals(original)) {
                                backup(glslFile);
                                Files.writeString(glslFile, patched, StandardCharsets.UTF_8);
                                modified.add(packDir.relativize(glslFile).toString());
                            }
                        } catch (IOException e) {
                            warnings.add("Could not read " + packDir.relativize(glslFile) + ": " + e.getMessage());
                        }
                    });
            }

            if (modified.isEmpty()) {
                return PatchResult.ok("Shaderpack is already FSR-compatible (no changes needed)", modified, warnings);
            }

            String summary = "Injected FSR_RELOADED define into " + modified.size() + " shader(s): " + String.join(", ", modified);
            if (!warnings.isEmpty()) {
                summary += " (" + warnings.size() + " warning(s))";
            }
            return PatchResult.ok(summary, modified, warnings);

        } catch (IOException e) {
            return PatchResult.fail("Failed to patch shaderpack: " + e.getMessage());
        }
    }

    // ── ZIP pack patching ────────────────────────────────────────────────

    private static PatchResult patchZipPack(Path zipPath) {
        List<String> modified = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Read existing zip entries into memory
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> e = zip.entries();
                while (e.hasMoreElements()) {
                    ZipEntry entry = e.nextElement();
                    if (!entry.isDirectory()) {
                        entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
                    }
                }
            }

            // Create a temp directory to work in
            Path tempDir = Files.createTempDirectory("fsr-patch-");
            try {
                // Extract all entries to temp dir
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    Path out = tempDir.resolve(entry.getKey());
                    Files.createDirectories(out.getParent());
                    Files.write(out, entry.getValue());
                }

                // Patch the extracted files
                Path packNameDir = tempDir.resolve(getTopLevelDir(entries));
                if (Files.isDirectory(packNameDir)) {
                    PatchResult dirResult = patchDirectoryPack(packNameDir);
                    modified.addAll(dirResult.filesModified);
                    warnings.addAll(dirResult.warnings);

                    if (!dirResult.success && modified.isEmpty()) {
                        return dirResult;
                    }
                }

                // Rebuild the zip from patched files
                backup(zipPath);
                rebuildZip(tempDir, zipPath);

                if (modified.isEmpty()) {
                    return PatchResult.ok("Shaderpack is already FSR-compatible (no changes needed)", modified, warnings);
                }

                String summary = "Patched " + modified.size() + " file(s) in zip: " + String.join(", ", modified);
                return PatchResult.ok(summary, modified, warnings);

            } finally {
                deleteRecursive(tempDir);
            }

        } catch (IOException e) {
            return PatchResult.fail("Failed to patch zipped shaderpack: " + e.getMessage());
        }
    }

    // ── GLSL patching rules ──────────────────────────────────────────────

    /**
     * Apply the FSR_RELOADED define to GLSL source. This is the ONLY patch
     * applied to GLSL files — it is completely NON-DESTRUCTIVE.
     *
     * The define is inert: without corresponding #ifdef FSR_RELOADED blocks
     * in the pack, nothing changes. Pack authors can write:
     *   #ifdef FSR_RELOADED
     *   // FSR Reloaded is active — disable my TAA here
     *   #else
     *   // Normal TAA code
     *   #endif
     *
     * After patching, the player reloads the shaderpack (F3+T) for the
     * define to take effect. The pack continues to work exactly as before
     * when FSR Reloaded is disabled or uninstalled.
     */
    private static String patchGlslSource(String source, String relPath) {
        if (source.contains("#ifdef FSR_RELOADED")) return source; // already patched
        return injectFsReloadedDefine(source);
    }

    /**
     * Injects the FSR_RELOADED define at the top of the shader, after the
     * #version directive. This lets shaderpack authors write conditional
     * blocks: #ifdef FSR_RELOADED ... #endif to disable their own temporal
     * effects when FSR Reloaded is active.
     */
    private static String injectFsReloadedDefine(String source) {
        String define = "#define FSR_RELOADED\n";

        // Find the #version line and inject after it
        Pattern versionPattern = Pattern.compile("(^#version\\s+\\d+\\s+(?:core|es|compatibility)?\\s*$)", Pattern.MULTILINE);
        Matcher m = versionPattern.matcher(source);
        if (m.find()) {
            int insertPos = m.end();
            return source.substring(0, insertPos) + "\n" + define + source.substring(insertPos);
        }

        // No #version line — inject at the very top
        return define + source;
    }

    // ── Utility methods ──────────────────────────────────────────────────

    /** Backup a file before patching. */
    private static void backup(Path file) {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".fsr-backup");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            FSRMod.LOGGER.warn("ShaderPackPatcher: could not backup {}: {}", file, e.getMessage());
        }
    }

    /** Check if a file is a GLSL shader. */
    private static boolean isGlslFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".glsl") || name.endsWith(".fsh") || name.endsWith(".vsh")
            || name.endsWith(".gsh") || name.endsWith(".csh") || name.endsWith(".vert")
            || name.endsWith(".frag") || name.endsWith(".geom") || name.endsWith(".comp");
    }

    /** Find the top-level directory in a zip (first path segment). */
    private static String getTopLevelDir(Map<String, byte[]> entries) {
        for (String key : entries.keySet()) {
            int slash = key.indexOf('/');
            if (slash > 0) {
                return key.substring(0, slash);
            }
        }
        return "";
    }

    /** Rebuild a zip from a temp directory. */
    private static void rebuildZip(Path tempDir, Path zipPath) throws IOException {
        Path tempZip = zipPath.resolveSibling(zipPath.getFileName() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip), StandardCharsets.UTF_8)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String entryName = tempDir.relativize(file).toString().replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(entryName));
                            zos.write(Files.readAllBytes(file));
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }
        Files.move(tempZip, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Recursively delete a directory. */
    private static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException ignored) {}
    }
}

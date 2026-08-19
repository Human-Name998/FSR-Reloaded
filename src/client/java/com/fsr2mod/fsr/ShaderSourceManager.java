package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.fsr2mod.config.FSRConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sources the FSR2/3 GLSL shader sources. The CANONICAL copy of every shader is
 * a classpath resource under {@code /shaders/{group}/{name}.glsl} bundled in the
 * jar (see src/main/resources/shaders/ — the single source of truth; the
 * pipelines no longer embed GLSL strings).
 *
 * At game launch the editable copy lives under
 * {@code config/fsr-reloaded/shaders/} as {@code fsr2/*.glsl} and
 * {@code fsr3/*.glsl}:
 *   1. Bootstrap — when {@code config.fsr2ShaderBootstrap} is on (default), missing
 *      files are written from the canonical jar copy on first use, so the mod
 *      always works offline and never ships broken.
 *   2. Download — when {@code config.fsr2ShaderUrl} is set, the pack is fetched
 *      from that URL on a background thread at launch and REPLACES the files.
 *      This is the "FSR2 files downloaded when the game launches instead of being
 *      packaged into the mod" channel: point the URL at any host serving the pack
 *      zip (entries {@code fsr2/NAME.glsl} + {@code fsr3/NAME.glsl}). A marker
 *      file records the last downloaded URL so the pack is fetched only once.
 *
 * Pipelines call {@link #get(String, String)} at compile time; disk files take
 * precedence over the canonical jar copy, so users can edit the shaders on disk
 * without rebuilding the jar. Strict mode: set {@code fsr2ShaderBootstrap} to
 * false and FSR2/3 will use ONLY the downloaded files (missing = fallback).
 */
public final class ShaderSourceManager {

    private static final Path SHADER_DIR = Path.of("config", "fsr-reloaded", "shaders");
    private static final String PACK_URL_MARKER = ".pack-url";
    private static volatile boolean initialized;

    private ShaderSourceManager() {
    }

    /** Called once from FSRMod.onInitializeClient — kicks off the launch-time download. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        FSRConfig cfg = FSRMod.getInstance().getConfig();
        try {
            Files.createDirectories(SHADER_DIR);
        } catch (IOException e) {
            FSRMod.LOGGER.warn("ShaderSourceManager: cannot create {} — using embedded shaders", SHADER_DIR);
            return;
        }
        if (cfg.fsr2ShaderUrl != null && !cfg.fsr2ShaderUrl.isBlank()) {
            String url = cfg.fsr2ShaderUrl;
            Thread t = new Thread(() -> downloadPack(url), "fsr-shader-download");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Returns the GLSL source for a shader. Precedence:
     *   1. The on-disk file (config/fsr-reloaded/shaders/{group}/{name}.glsl) —
     *      a downloaded pack or a USER EDIT always wins and is never touched.
     *   2. The CANONICAL source bundled in the jar (classpath resource
     *      /shaders/{group}/{name}.glsl). This is the single source of truth
     *      for every shader — the pipeline Java classes no longer embed GLSL
     *      strings, so one edit to the resource updates every copy.
     *
     * Bootstrap copies (written by this class when the file was missing) carry
     * a sidecar marker recording the canonical hash they were written from. On
     * every load, an unchanged bootstrap copy that differs from the current
     * canonical is refreshed — a canonical edit in a jar update propagates to
     * the instance automatically. A copy whose content differs from its marker
     * (user-edited or downloaded) is never overwritten.
     *
     * @param group "fsr2" or "fsr3"
     * @param name  shader constant name, e.g. "MV_GEN_CS"
     */
    public static String get(String group, String name) {
        init();
        Path p = SHADER_DIR.resolve(group).resolve(name + ".glsl");
        String canonical = loadCanonical(group, name);
        if (canonical == null) {
            FSRMod.LOGGER.error("ShaderSourceManager: no canonical source for {}/{} (resource missing?)", group, name);
            return "";
        }
        if (Files.exists(p)) {
            try {
                String s = Files.readString(p);
                if (!s.isBlank() && !isStaleBootstrap(p, canonical)) return s;
            } catch (IOException e) {
                FSRMod.LOGGER.warn("ShaderSourceManager: read failed for {} — canonical fallback", p);
            }
            // Stale bootstrap copy: fall through to refresh it below.
        }
        FSRConfig cfg = FSRMod.getInstance().getConfig();
        if (cfg.fsr2ShaderBootstrap) {
            writeBootstrap(p, canonical);
        }
        return canonical;
    }

    /** Loads the canonical GLSL from the classpath (bundled in the jar). */
    private static String loadCanonical(String group, String name) {
        String res = "/shaders/" + group + "/" + name + ".glsl";
        try (InputStream in = ShaderSourceManager.class.getResourceAsStream(res)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FSRMod.LOGGER.warn("ShaderSourceManager: could not read canonical {} — {}", res, e.toString());
            return null;
        }
    }

    /** Sidecar marker storing the canonical SHA-256 a bootstrap copy was written from. */
    private static Path markerPath(Path shader) {
        return shader.resolveSibling(shader.getFileName() + ".canon");
    }

    /**
     * True when the disk copy is an UNCHANGED bootstrap copy whose canonical
     * has changed (i.e. it needs refreshing). Downloaded/user-edited copies
     * differ from their marker and are never treated as stale.
     */
    private static boolean isStaleBootstrap(Path p, String canonical) {
        Path marker = markerPath(p);
        if (!Files.exists(marker)) return false; // downloaded or pre-marker copy — leave it
        try {
            String markedHash = Files.readString(marker).trim();
            if (markedHash.isEmpty()) return false;
            String diskHash = sha256(Files.readString(p));
            String canonicalHash = sha256(canonical);
            // Untouched bootstrap copy whose canonical moved on -> stale.
            return diskHash.equals(markedHash) && !canonicalHash.equals(markedHash);
        } catch (IOException e) {
            return false;
        }
    }

    /** Atomically writes a bootstrap copy + its canonical-hash marker. */
    private static void writeBootstrap(Path p, String canonical) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, canonical);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(markerPath(p), sha256(canonical));
        } catch (IOException e) {
            FSRMod.LOGGER.warn("ShaderSourceManager: could not bootstrap {} — {}", p, e.toString());
        }
    }

    private static String sha256(String s) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Fetches the shader pack zip (async). Writes each entry atomically. */
    private static void downloadPack(String url) {
        try {
            // Only fetch once per configured URL (pack persists across launches).
            Path marker = SHADER_DIR.resolve(PACK_URL_MARKER);
            String urlHash = Integer.toHexString(url.hashCode());
            if (Files.exists(marker) && Files.readString(marker).trim().equals(urlHash)) {
                return;
            }
            FSRMod.LOGGER.info("FSR: downloading shader pack from {}", url);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                FSRMod.LOGGER.warn("FSR: shader pack download failed: HTTP {}", resp.statusCode());
                return;
            }
            int written = 0;
            try (ZipInputStream zip = new ZipInputStream(resp.body())) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String rel = sanitizeRelPath(entry.getName());
                    if (rel == null || !rel.endsWith(".glsl")) continue;
                    Path dest = SHADER_DIR.resolve(rel);
                    Files.createDirectories(dest.getParent());
                    Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
                    Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    written++;
                }
            }
            if (written > 0) {
                Files.writeString(marker, urlHash);
                FSRMod.LOGGER.info("FSR: shader pack downloaded ({} files)", written);
            } else {
                FSRMod.LOGGER.warn("FSR: shader pack contained no .glsl files");
            }
        } catch (Exception e) {
            FSRMod.LOGGER.warn("FSR: shader pack download failed: {}", e.toString());
        }
    }

    /**
     * Normalizes a zip entry path: forward slashes, strips an optional leading
     * root folder (e.g. "pack/fsr2/foo.glsl" -> "fsr2/foo.glsl"), and rejects
     * traversal ("..") entries.
     */
    private static String sanitizeRelPath(String name) {
        String rel = name.replace('\\', '/');
        if (rel.startsWith("/")) rel = rel.substring(1);
        String[] parts = rel.split("/");
        for (String part : parts) {
            if (part.equals("..") || part.isEmpty()) return null;
        }
        // Strip ONE leading root directory ONLY when there is more than one
        // directory segment (e.g. "pack/fsr2/foo.glsl" -> "fsr2/foo.glsl"). A
        // two-segment entry like "fsr2/foo.glsl" is already in the canonical
        // layout and MUST be preserved — stripping "fsr2" here would flatten the
        // download to SHADER_DIR/foo.glsl, which get() never looks up (the
        // launch-time download would silently never take effect).
        if (parts.length > 2 && !parts[0].contains(".")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) sb.append('/');
                sb.append(parts[i]);
            }
            rel = sb.toString();
        }
        return rel;
    }
}

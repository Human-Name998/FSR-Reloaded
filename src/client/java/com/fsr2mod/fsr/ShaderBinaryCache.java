package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public class ShaderBinaryCache {

    private static Path cacheDir;
    private static String gpuId;
    private static final Set<String> liveHashes = new HashSet<>();
    private static boolean sweepDone;

    private static String getGpuId() {
        if (gpuId == null) {
            String ren = GL43C.glGetString(GL43C.GL_RENDERER);
            String ver = GL43C.glGetString(GL43C.GL_VERSION);
            gpuId = (ren != null ? ren : "unknown") + "|" + (ver != null ? ver : "unknown");
        }
        return gpuId;
    }

    private static Path cacheDir() {
        if (cacheDir == null) {
            cacheDir = Path.of("config", "fsr-reloaded", "shader-cache");
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException e) {
                FSRMod.LOGGER.warn("Could not create shader cache dir: {}", e.getMessage());
            }
        }
        return cacheDir;
    }

    private static String sourceHash(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(src.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(getGpuId().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static int compileCompute(String src) {
        String hash = sourceHash(src);
        if (hash == null) return compileFallback(src);

        if (!sweepDone) {
            sweepDone = true;
            liveHashes.add(hash);
            sweepStale();
        } else {
            liveHashes.add(hash);
        }

        Path cacheFile = cacheDir().resolve(hash + ".bin");

        // Try cache load
        if (Files.exists(cacheFile)) {
            try {
                byte[] data = Files.readAllBytes(cacheFile);
                if (data.length > 4) {
                    ByteBuffer bb = ByteBuffer.allocateDirect(data.length - 4);
                    ByteBuffer header = ByteBuffer.wrap(data, 0, 4);
                    int format = header.getInt();
                    bb.put(data, 4, data.length - 4);
                    bb.flip();

                    int prog = GL43C.glCreateProgram();
                    GL43C.glProgramBinary(prog, format, bb);
                    int[] status = new int[1];
                    GL43C.glGetProgramiv(prog, GL43C.GL_LINK_STATUS, status);
                    if (status[0] == GL43C.GL_TRUE) {
                        FSRMod.LOGGER.debug("Shader cache hit: {}", hash);
                        return prog;
                    }
                    FSRMod.LOGGER.warn("Shader cache invalid (link failed), recompiling: {}", hash);
                    GL43C.glDeleteProgram(prog);
                    try { Files.deleteIfExists(cacheFile); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                FSRMod.LOGGER.warn("Shader cache load failed, recompiling: {}", e.getMessage());
            }
        }

        // Compile fresh
        int prog = compileFallback(src);
        if (prog == 0) return 0;

        // Save to cache
        try {
            int[] binaryLen = new int[1];
            GL43C.glGetProgramiv(prog, GL43C.GL_PROGRAM_BINARY_LENGTH, binaryLen);
                if (binaryLen[0] > 0) {
                    ByteBuffer bin = ByteBuffer.allocateDirect(binaryLen[0]);
                    int[] actualLen = new int[1];
                    int[] format = new int[1];
                    GL43C.glGetProgramBinary(prog, actualLen, format, bin);

                bin.limit(actualLen[0]);
                byte[] binData = new byte[4 + actualLen[0]];
                ByteBuffer out = ByteBuffer.wrap(binData);
                out.putInt(format[0]);
                out.put(bin);
                Files.write(cacheFile, binData);
                FSRMod.LOGGER.debug("Shader cache saved: {}", hash);
            }
        } catch (Exception e) {
            FSRMod.LOGGER.warn("Shader cache save failed: {}", e.getMessage());
        }

        return prog;
    }

    private static void sweepStale() {
        Path dir = cacheDir();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".bin"))
                 .filter(p -> !liveHashes.contains(p.getFileName().toString().replace(".bin", "")))
                 .forEach(p -> {
                     try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                 });
        } catch (Exception e) {
            FSRMod.LOGGER.debug("Shader cache sweep failed: {}", e.getMessage());
        }
    }

    private static int compileFallback(String src) {
        int sh = GL43C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL43C.glShaderSource(sh, src);
        GL43C.glCompileShader(sh);
        if (GL43C.glGetShaderi(sh, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            String log = GL43C.glGetShaderInfoLog(sh);
            FSRMod.LOGGER.error("Compute shader compile: {}", log);
            GL43C.glDeleteShader(sh);
            return 0;
        }
        int prog = GL43C.glCreateProgram();
        GL43C.glProgramParameteri(prog, GL43C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL43C.GL_TRUE);
        GL43C.glAttachShader(prog, sh);
        GL43C.glLinkProgram(prog);
        if (GL43C.glGetProgrami(prog, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
            String log = GL43C.glGetProgramInfoLog(prog);
            FSRMod.LOGGER.error("Compute shader link: {}", log);
            GL43C.glDeleteProgram(prog);
            GL43C.glDeleteShader(sh);
            return 0;
        }
        GL43C.glDeleteShader(sh);
        return prog;
    }
}

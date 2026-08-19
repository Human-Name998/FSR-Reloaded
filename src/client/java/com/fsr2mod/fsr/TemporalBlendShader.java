package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL43C;

public class TemporalBlendShader {
    private int programId;
    private int currentFrameLoc;
    private int historyLoc;
    private int depthLoc;
    private int currVPLoc;
    private int prevVPLoc;
    private int outputSizeLoc;
    private boolean compiled;
    private int vao;
    private int outputFbo;

    private static final String VERTEX_SRC = """
        #version 430 core
        out vec2 texCoord;
        void main() {
            uint idx = gl_VertexID;
            vec2 pos = vec2(
                idx == 1u ? 3.0 : -1.0,
                idx == 2u ? 3.0 : -1.0
            );
            texCoord = pos * 0.5 + 0.5;
            gl_Position = vec4(pos, 0.0, 1.0);
        }
        """;

    private static final String FRAGMENT_SRC = """
        #version 430 core
        in vec2 texCoord;
        layout(location = 0) out vec4 fragColor;
        uniform sampler2D uCurrentFrame;
        uniform sampler2D uHistory;
        uniform sampler2D uDepth;
        uniform mat4 uCurrVP;
        uniform mat4 uPrevVP;
        uniform vec2 uOutputSize;
        void main() {
            vec3 current = texture(uCurrentFrame, texCoord).rgb;
            float d = texture(uDepth, texCoord).r;
            vec2 ndc = texCoord * 2.0 - 1.0;
            float ndcZ = d * 2.0 - 1.0;
            vec4 q = inverse(uCurrVP) * vec4(ndc, ndcZ, 1.0);
            vec3 worldPos = q.xyz / q.w;
            vec4 prevClip = uPrevVP * vec4(worldPos, 1.0);
            vec2 prevNdc = prevClip.xy / prevClip.w;
            vec2 prevUv = prevNdc * 0.5 + 0.5;
            vec3 history = texture(uHistory, prevUv).rgb;
            float luma = dot(current, vec3(0.2126, 0.7152, 0.0722));
            float histLuma = dot(history, vec3(0.2126, 0.7152, 0.0722));
            float blend = 0.0;
            if (prevUv.x >= 0.0 && prevUv.x <= 1.0 && prevUv.y >= 0.0 && prevUv.y <= 1.0) {
                blend = abs(luma - histLuma) < 0.15 ? 0.5 : 0.0;
            }
            vec3 blended = mix(current, history, blend);
            if (any(isnan(blended)) || any(isinf(blended))) {
                blended = current;
            }
            fragColor = vec4(clamp(blended, 0.0, 1.0), 1.0);
        }
        """;

    public boolean compile() {
        if (compiled) return true;
        try {
            int vert = GL43C.glCreateShader(GL43C.GL_VERTEX_SHADER);
            GL43C.glShaderSource(vert, VERTEX_SRC);
            GL43C.glCompileShader(vert);
            if (GL43C.glGetShaderi(vert, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
                String log = GL43C.glGetShaderInfoLog(vert);
                FSRMod.LOGGER.error("Temporal vertex shader compile failed: {}", log);
                GL43C.glDeleteShader(vert);
                return false;
            }

            int frag = GL43C.glCreateShader(GL43C.GL_FRAGMENT_SHADER);
            GL43C.glShaderSource(frag, FRAGMENT_SRC);
            GL43C.glCompileShader(frag);
            if (GL43C.glGetShaderi(frag, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
                String log = GL43C.glGetShaderInfoLog(frag);
                FSRMod.LOGGER.error("Temporal fragment shader compile failed: {}", log);
                GL43C.glDeleteShader(vert);
                GL43C.glDeleteShader(frag);
                return false;
            }

            programId = GL43C.glCreateProgram();
            GL43C.glAttachShader(programId, vert);
            GL43C.glAttachShader(programId, frag);
            GL43C.glLinkProgram(programId);

            if (GL43C.glGetProgrami(programId, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
                String log = GL43C.glGetProgramInfoLog(programId);
                FSRMod.LOGGER.error("Temporal program link failed: {}", log);
                GL43C.glDeleteProgram(programId);
                programId = 0;
                GL43C.glDeleteShader(vert);
                GL43C.glDeleteShader(frag);
                return false;
            }

            GL43C.glDeleteShader(vert);
            GL43C.glDeleteShader(frag);

            currentFrameLoc = GL43C.glGetUniformLocation(programId, "uCurrentFrame");
            historyLoc = GL43C.glGetUniformLocation(programId, "uHistory");
            depthLoc = GL43C.glGetUniformLocation(programId, "uDepth");
            currVPLoc = GL43C.glGetUniformLocation(programId, "uCurrVP");
            prevVPLoc = GL43C.glGetUniformLocation(programId, "uPrevVP");
            outputSizeLoc = GL43C.glGetUniformLocation(programId, "uOutputSize");

            compiled = true;
            FSRMod.LOGGER.info("Temporal blend shader compiled (depth-based reprojection)");
            return true;
        } catch (Exception e) {
            FSRMod.LOGGER.error("Temporal shader compile exception", e);
            compiled = false;
            return false;
        }
    }

    public void dispatch(int currentTex, int historyTex, int depthTex, int resultTex,
                         float[] currVP, float[] prevVP, int w, int h) {
        if (!compiled || programId == 0) return;
        
        // Validate inputs
        if (currentTex <= 0 || historyTex <= 0 || depthTex <= 0 || resultTex <= 0) {
            FSRMod.LOGGER.warn("Temporal dispatch: invalid texture IDs");
            return;
        }
        
        if (w <= 0 || h <= 0 || currVP == null || prevVP == null) {
            FSRMod.LOGGER.warn("Temporal dispatch: invalid parameters");
            return;
        }
        
        RenderSystem.assertOnRenderThread();

        int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevActiveTex = GL43C.glGetInteger(GL43C.GL_ACTIVE_TEXTURE);
        int prevTex0 = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        int prevTex1 = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        int prevTex2 = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
        int[] prevViewport = new int[4];
        GL43C.glGetIntegerv(GL43C.GL_VIEWPORT, prevViewport);

        if (vao == 0) {
            vao = GL43C.glGenVertexArrays();
        }
        if (outputFbo == 0) {
            outputFbo = GL43C.glGenFramebuffers();
        }

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, outputFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, resultTex, 0);
        GL43C.glDrawBuffer(GL43C.GL_COLOR_ATTACHMENT0);

        if (GL43C.glCheckFramebufferStatus(GL43C.GL_DRAW_FRAMEBUFFER) != GL43C.GL_FRAMEBUFFER_COMPLETE) {
            FSRMod.LOGGER.warn("Temporal blend draw FBO incomplete");
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL43C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex2);
            GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex1);
            GL43C.glActiveTexture(prevActiveTex);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex0);
            return;
        }

        GL43C.glUseProgram(programId);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentTex);
        GL43C.glUniform1i(currentFrameLoc, 0);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, historyTex);
        GL43C.glUniform1i(historyLoc, 1);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
        GL43C.glUniform1i(depthLoc, 2);

        GL43C.glUniformMatrix4fv(currVPLoc, false, currVP);
        GL43C.glUniformMatrix4fv(prevVPLoc, false, prevVP);
        GL43C.glUniform2f(outputSizeLoc, w, h);

        GL43C.glViewport(0, 0, w, h);

        GL43C.glBindVertexArray(vao);
        GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, 3);
        GL43C.glBindVertexArray(0);

        GL43C.glUseProgram(0);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);

        GL43C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex2);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex1);
        GL43C.glActiveTexture(prevActiveTex);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex0);
    }

    public void delete() {
        if (programId != 0) {
            GL43C.glDeleteProgram(programId);
            programId = 0;
            compiled = false;
        }
        if (vao != 0) {
            GL43C.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (outputFbo != 0) {
            GL43C.glDeleteFramebuffers(outputFbo);
            outputFbo = 0;
        }
    }
}

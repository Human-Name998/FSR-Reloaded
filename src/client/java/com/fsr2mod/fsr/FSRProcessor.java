package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.IrisCompat;
import com.fsr2mod.compat.DistantHorizonsCompat;
import com.fsr2mod.compat.PhotonicsCompat;
import com.fsr2mod.compat.VulkanModCompat;
import com.fsr2mod.config.FSRConfig;
import com.fsr2mod.mixin.MinecraftClientAccessor;
import com.fsr2mod.vulkan.Fg4Native;
import com.fsr2mod.vulkan.Fsr4Native;
import com.fsr2mod.vulkan.VulkanInterop;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45;

public class FSRProcessor {

    // Diagnostic timers
    private long t0Frame, t0Begin, t0BeginEnd, t0Scene, t0SceneEnd, t0End;
    private int timingFrameCount;
    private void logSlow(String label, long dtNs) {
        long ms = dtNs / 1_000_000;
        if (config.debugMode > 0 && ms >= 8) {
            FSRMod.LOGGER.warn("[TIMING] {} took {}ms (frame #{})", label, ms, timingFrameCount);
        }
    }

    /**
     * Gates all native fsr4_diag.log + stderr diagnostics on config.debugMode.
     * With debug off the native diagLog() is a single compare-and-return — zero
     * per-frame fopen/fprintf/fflush cost.
     */
    private void syncNativeDiagnostics() {
        if (Fsr4Native.isAvailable()) {
            // Best-effort ONLY — never let a JNI mismatch (stale fsrvk.dll from a
            // prior session, mismatched jar/DLL build) crash the game mid-toggle.
            // 2026-08-22: UnsatisfiedLinkError here crashed on FSR enable/disable
            // when fsrvk.dll had never been loaded (FSR off at launch →
            // VulkanInterop init never ran → Fsr4Native natives unresolvable).
            try {
                Fsr4Native.setDiagnosticsEnabled(config.debugMode > 0);
                // FSR4 SDK internal sharpening follows the same config.sharpness
                // slider as FSR1/2/3's GLSL RCAS (2026-08-13). Called here (runs
                // every frame where the FSR4 block executes) so in-game slider
                // changes propagate; the JNI set is a trivial float store.
                Fsr4Native.setSharpness(config.sharpness);
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                FSRMod.LOGGER.warn("FSR4 native diagnostics sync failed (stale fsrvk.dll?): {}", e.toString());
            }
        }
    }

    private static Boolean isIntelGpu;

    private static boolean isIntelGpu() {
        if (isIntelGpu == null) {
            // Always return false. The GL-based check (glGetString) is
            // intentionally skipped because it causes a fatal JVM abort
            // when VulkanMod replaces OpenGL (no GL context available).
            // This check was only a workaround for Intel driver bugs with
            // FSR2/3 compute shaders — harmless to skip on modern systems.
            isIntelGpu = false;
        }
        return isIntelGpu;
    }

    private static Boolean isMac;

    private static boolean isMac() {
        if (isMac == null) {
            String os = System.getProperty("os.name");
            isMac = os != null && os.toLowerCase().contains("mac");
            if (isMac) {
                FSRMod.LOGGER.warn("macOS detected — OpenGL 4.1 only, no compute shader support. FSR Reloaded disabled.");
            }
        }
        return isMac;
    }

    private static Boolean isLinux;

    private static boolean isLinux() {
        if (isLinux == null) {
            String os = System.getProperty("os.name");
            isLinux = os != null && os.toLowerCase().contains("linux");
        }
        return isLinux;
    }

    /**
     * Whether the REAL AMD FSR4 SDK can run here. The SDK ships Windows + DX12
     * only (amd_fsr4_api.dll, RDNA4 NPU / RDNA3.5 compute via the AMD Windows
     * driver) — there is no Linux build. When false on Linux, a FSR4 selection
     * transparently runs the mod's own cross-platform FSR3 temporal engine
     * instead (see {@link #isFsr3()}): same jitter / MV / reactive-mask
     * contract, GLSL compute, works everywhere.
     */
    private boolean fsr4SdkSupported() {
        return Fsr4Native.isAvailable();
    }

    private String gpuRendererCached;

    private String gpuRenderer() {
        if (gpuRendererCached == null) {
            // VulkanMod replaces OpenGL entirely — no GL context is ever current,
            // and calling glGetString aborts the JVM with a FATAL native error
            // ("No context is current or a function that is not available in the
            // current context was called") that try/catch CANNOT catch. This
            // killed the process when opening the settings screen under VulkanMod:
            // SettingsScreen -> getVersionStatus -> isFgUnsupportedGpu ->
            // gpuRenderer -> glGetString. Query the GPU name from the native
            // Vulkan device instead. GL43C.getCapabilities() == null also covers
            // any other no-GL-context situation (e.g. a different mod replacing GL).
            if (VulkanModCompat.isLoaded() || GL.getCapabilities() == null) {
                try {
                    String vkName = VulkanInterop.getDeviceInfo();
                    gpuRendererCached = (vkName != null && !vkName.isEmpty())
                        ? vkName : "Vulkan (unknown)";
                } catch (Throwable t) {
                    gpuRendererCached = "Vulkan (unknown)";
                }
            } else {
                try {
                    gpuRendererCached = GL43C.glGetString(GL43C.GL_RENDERER);
                } catch (Exception e) {
                    gpuRendererCached = "unknown";
                }
            }
            if (gpuRendererCached == null) gpuRendererCached = "unknown";
        }
        return gpuRendererCached;
    }

    private final FSRConfig config;
    private final JitterManager jitterManager;
    private FSR2Pipeline fsr2Pipeline;
    private FSR3Pipeline fsr3Pipeline;

    private RenderTarget mainRenderTarget;
    private RenderTarget originalMainRenderTarget;

    // IRIS DEPTH-BUFFER VERSION (2026-08-29, "switching the FSR version makes
    // the terrain disappear until a quality change" fix). Bumped EVERY time the
    // scaled scene target (renderTarget) is replaced, and handed to the
    // ScaledRenderTarget proxy, which reports it through
    // Blaze3dRenderTargetExt#iris$getDepthBufferVersion. Iris's
    // RenderTargets#resizeIfNeeded adopts the new depth texture only when that
    // version changes (or the size/format changes), so this is what makes Iris
    // re-bind depthtex0 after a same-size recreate — without it the pack keeps
    // depth-testing the world against a closed texture and the terrain
    // vanishes while the sky keeps drawing. See ScaledRenderTarget's field
    // comment for the full diagnosis. Read/written on the render thread only.
    private int sceneTargetVersion;

    private int displayWidth;
    private int displayHeight;
    private int scaledWidth;
    private int scaledHeight;
    // Effective render size the pipelines were last built for. Compared against
    // the freshly computed scaledWidth/Height every frame so a size change that
    // bypasses markDirty()/applyAutoScale()/onResize() (i.e. toggling the shader
    // pack, which flips the scale between irisQualityScale and qualityScale)
    // still triggers a rebuild. See the [SCALE-SYNC] block in onRenderBegin.
    private int lastAppliedScaleW = -1;
    private int lastAppliedScaleH = -1;
    private float jitterX;
    private float jitterY;
    private float prevJitterX;
    private float prevJitterY;

    private volatile boolean pipelineReady;
    private int nativeFbWidth;
    private int nativeFbHeight;
    private boolean insideFrame;
    // Set when renderLevel(TAIL) fired inside a frame (world actually rendered
    // this frame). Lets onRenderEnd skip the fallback upscale if the world did
    // NOT render (e.g. dimension-change transition where level is briefly
    // non-null but renderLevel doesn't fire) instead of processing stale content.
    private boolean worldRenderedThisFrame;

    private int fsr2FrameCount;
    private int fsr2OutputTexId;
    private boolean sceneUpscaled;
    private boolean jitterAppliedThisFrame;

    // ------------------------------------------------------------------
    // IRIS GENERIC TEMPORAL MODE (2026-08-18)
    //
    // When a shader pack is in use AND the selected version is FSR2/FSR3, the
    // scaled mainRT swap (normally the non-Iris GL path) is enabled under Iris
    // too. This works with ANY pack with zero per-pack config because Iris
    // sizes its internal render targets (gbuffers, colortex, composite) from
    // mainRT.width/height (IrisRenderingPipeline.resizeIfNeeded) and draws the
    // final composite into mainRT's color texture (FinalPassRenderer) — so
    // swapping mainRT to the scaled proxy scales the pack's ENTIRE pipeline,
    // and the composite lands in the scaled renderTarget for FSR2/3 to
    // upscale at the vanilla boundary. Jitter reaches the pack because Iris
    // consumes the vanilla projection (gbufferProjection) that captureAndJitter
    // modifies. The jitter AUTO-PROBE below empirically verifies that and
    // degrades to unjittered accumulation for jitter-blind packs.
    private boolean irisSwapActive;       // scaled swap live this frame (Iris)
    // PHOTONICS COMPAT (2026-08-23): static snapshot of the swap state + proxy
    // dimensions, read by PhotonicsColorFramebufferMixin to redirect Photonics'
    // ColorFramebuffer resolution supplier from Window size to the proxy size.
    public static volatile boolean photonicsProxyActive;
    public static volatile int photonicsProxyW;
    public static volatile int photonicsProxyH;
    // Set by PhotonicsColorFramebufferMixin's probe: whether Photonics'
    // ColorFramebuffer actually allocated at the proxy size this frame.
    // Default true (enabled); the mixin sets false on any misaligned frame
    // and the swap gate disables the scaled path (native pass-through).
    public static volatile boolean photonicsBuffersAligned = true;
    // IRIS COMPOSITE VIEWPORT FIX (2026-08-23): static accessors for
    // WindowMixin — Iris sizes its pipeline from Window.getWidth/Height.
    // When the Iris swap is active, the Window must report proxy dims so
    // Iris renders the composite at proxy size (no native-viewport crop).
    // Cleared after FSR dispatch so the GUI reads native dims.
    public static volatile boolean windowScaleActive;
    public static volatile boolean irisSwapActiveStatic;
    public static volatile int scaledWidthForWindow;
    public static volatile int scaledHeightForWindow;
    private boolean irisTemporalLogged;   // one-shot engagement log
    private boolean irisFsr1PassthroughLogged;  // one-shot FSR1-under-Iris pass-through notice
    private boolean renderScaleStatusLogged;    // one-shot RenderScaleCompat.logStatus gate
    // PACK-ACTIVATION FRAME LOCK (2026-08-19): IrisCompat.isShaderPackInUse()
    // can flip MID-FRAME when a pack applies (Iris compiles shaders + Voxy
    // re-creates its render pipeline on the render thread). The swap decision
    // (onRenderBegin) and the dispatch decision (onSceneRendered/onRenderEnd)
    // must use the SAME per-frame value or the frame tears: onRenderBegin
    // swaps the scaled proxy (qualityScale 0.25 -> 420x263) while onSceneRendered
    // re-evaluates the pack as active and runs the legacy Iris branch on the
    // torn state — dispatched FSR into the mid-activation GL state and faulted
    // the AMD driver (0xC0000005 in atio6axx.dll, 2026-08-18 + 2026-08-19,
    // "same crash" after the first fix round). Capture once in onRenderBegin,
    // reuse in onSceneRendered/onRenderEnd; never re-evaluate live.
    private boolean irisActiveThisFrame;
    private boolean irisActivePrevFrame;
    private boolean irisJitterProbeDone;  // auto-probe completed (either verdict)
    private boolean irisJitterBlind;      // probe verdict: pack ignores vanilla jitter
    // IRIS COMPOSITE SIZE PROBE + SELF-HEAL (2026-08-23, "zoomed into a corner"
    // with Photonics): at onSceneRendered entry we GL-query the viewport and
    // the composite texture size BEFORE the display-width viewport override.
    // If they disagree with the scaled proxy while irisSwapActive, either the
    // pack's final pass is drawing at native-res viewport into the scaled
    // texture (corner crop) or the composite texture was never resized. One-
    // shot Iris pipeline destroy (via Reflection) forces a rebuild at proxy
    // size; if the mismatch outlives the rebuild (10s), latch native pass-
    // through (never show the crop).
    private boolean irisCompositeMismatchLogged;
    private boolean irisCompositeOkLogged;
    // irisJitterBlindAtMs removed (2026-08-23): the periodic re-probe was removed,
    // so the blind-at timestamp is no longer needed.
    private boolean irisProbeArmed;       // sample 1 captured, awaiting sample 2
    private boolean irisProbeLocChosen;   // probe block coords locked in
    private int irisProbeX = -1;
    private int irisProbeY = -1;
    private byte[] irisProbeBlock0;       // first 32x32 RGBA8 readback
    private float irisProbeJx0, irisProbeJy0;  // jitter the first sample was rendered with
    private int irisProbeAttempts;        // re-arms (scene-motion retries)
    private int irisProbeBlindVotes;      // clean static pairs with no measurable shift
    private int irisProbeReadFails;       // failed readbacks — abort probe at 3
    // TERRAIN-CONTENT PROBE (2026-08-24, "terrain disappears when switching FSR
    // versions"): decides whether the FSR1 fallback's proxy composite genuinely
    // lacks terrain (the pack renders sky only) or the FSR1 blit fails to
    // display it. Reads small RGBA8 blocks from the proxy (sky/mid/terrain
    // regions) and logs mean color + luma variance. Re-probes every 2s for the
    // whole session (cap removed 2026-08-25 — a late pipeline rebuild must
    // still be voted on); the latch self-stops probing once native pass-through
    // engages (irisSwapActive drops).
    private long terrainProbeAtMs = -1;
    // Dense-sampling window for the terrain probe — see armTerrainProbeBurst().
    private long terrainProbeBurstUntilMs;
    // DIAGNOSTIC-READBACK FRAME FLAG (2026-08-29, "every second it blinks").
    // Set when this frame performs a SYNCHRONOUS pixel readback for diagnostics
    // (the [TERRAIN-PROBE] broken-composite probe and the Iris jitter probe).
    // Measured cost of one 16x16 readback: worldMs jumps from ~4.5ms to ~30ms —
    // a glReadPixels is a full pipeline flush + CPU wait, so the pixel COUNT is
    // irrelevant (the old "one 16x16 block pair every 2s — negligible cost"
    // comment was simply wrong).
    //
    // That 26ms spike is measured INSIDE the window the GPU-saturation guards
    // time (t0BeginEnd -> pre-check), so every probe frame tripped the FSR4
    // saturation skip and the shared FSR2/3 SAT guard. Session proof
    // (logs/latest.log 17:32:46-17:33:32): [TERRAIN-PROBE] and [FSR4-SKIP] fire
    // on EXACTLY the same frames, 2s apart, worldMs 24-40ms vs ema 4-6ms and a
    // 12ms threshold. The FSR4 skip presents the PREVIOUS frame's output and
    // clears fsr4PrevColorFresh -> the visible blink.
    //
    // A deliberate diagnostic stall is not GPU saturation, so both guards now
    // ignore these frames for the spike test AND the baseline EMA.
    private boolean diagnosticReadbackThisFrame;
    private int terrainProbeCount;
    // BROKEN-COMPOSITE LATCH (2026-08-24, root cause #2): after a version switch
    // forces Iris to rebuild its pipeline (native pass-through phase -> scaled
    // swap again), the pack's composite can come back WITHOUT terrain (sky/fog
    // only) while sizes all match — the one-shot size probe cannot see content.
    // The pixel probe below votes on this; 3 consecutive broken votes latch
    // native pass-through for the rest of the world session (native always
    // renders correctly — the proven pre-port FSR1-under-Iris behavior).
    private static final int BROKEN_COMPOSITE_VOTES_NEEDED = 3;
    private int brokenCompositeVotes;
    private boolean irisSwapBrokenLatch;
    private boolean irisSwapBrokenLatchLogged;
    // True when the previous frame had no level loaded — a null->non-null flip
    // (world/dimension join) clears the latch so the scaled path retries.
    private boolean prevLevelNull = true;
    // When the current level was joined (ms epoch, 0 = unknown). Gates the
    // deliberate-switch fast-init grace: a FRESH world load keeps the full
    // 25s storm guard (cross-API init on a loading queue faults the AMD
    // driver — the 2026-08-18 crash class); an ESTABLISHED world (>20s old)
    // gets the short 2s settle after a settings click.
    private long worldJoinedAtMs;

    // Conclude the probe immediately when readbacks keep failing (GL errors or
    // driver-hostile reads). The safe default verdict is "pack consumes vanilla
    // jitter" — unjittered accumulation is merely softer, never broken, so
    // aborting the probe degrades gracefully instead of repeatedly poking a
    // driver that already faulted once (0xC0000005 in atio6axx.dll, 2026-08-19).
    private boolean irisProbeReadFailed() {
        if (++irisProbeReadFails >= 3) {
            irisJitterProbeDone = true;
            FSRMod.LOGGER.info("[IRIS-JITTER] probe aborted after {} failed readbacks — assuming pack consumes vanilla jitter (captureAndJitter fired)",
                irisProbeReadFails);
            return true;
        }
        return false;
    }
    private static final int IRIS_PROBE_SIZE = 32;
    private static final int IRIS_PROBE_MAX_ATTEMPTS = 60;
    private static final int IRIS_PROBE_BLIND_VOTES = 6;
    // One-shot flag for the "VulkanMod active: only FSR4 supported" warning —
    // the VulkanMod block runs in onRenderBegin EVERY frame, so without this the
    // log line is emitted dozens of times per second under VulkanMod + FSR1/2/3.
    private boolean vulkanModFallbackLogged;
    // One-shot for the "VulkanMod render-scale ACTIVE" info line (POC).
    private boolean vulkanModRenderScaleLogged;
    // Last observed in-game state (client.level != null) on the VulkanMod path.
    // When it flips (title <-> world), onRenderBegin schedules a VulkanMod
    // swapchain+main-pass recreate so the render-scale framebuffer swap engages
    // (or disengages) at the next frame start.
    private boolean lastVkModInGame;
    // Tracks the last observed config.enabled state for the VulkanMod swapchain
    // recreate logic. Init true (matching the default config) so the first frame
    // with FSR enabled doesn't schedule a needless recreate at startup; the
    // disabled path flips it false and schedules the recreate back to native.
    private boolean lastVkModEnabled = true;
    // Last observed desired render-scale (1.0 = disabled). When the quality slider
    // changes this (or the toggle flips), onRenderBegin schedules a swapchain
    // recreate so the scaled framebuffer is actually rebuilt at the new scale —
    // without this the slider only changed config, not the framebuffer.
    private float lastVkModScale = 1.0f;
    // Debounce for render-scale recreates: dragging the quality slider changes
    // qualityScale on EVERY drag event, and each change would otherwise schedule
    // a full recreateSwapChain (waitIdle GPU drain) — one per frame during the
    // drag = visible stutter. Only one recreate per VK_MOD_RECREATE_DEBOUNCE_MS
    // (the framebuffer snapshots the scale at recreate time; intermediate drag
    // values settle on release anyway). Mirrors the FSR4_DEBOUNCE_MS pattern.
    private long lastVkModScheduleMs;
    private static final long VK_MOD_RECREATE_DEBOUNCE_MS = 500L;
    // DEFERRED SCALE CHANGE (2026-08-21, "see through the ground on drastic
    // quality changes" fix): the settings-screen quality slider used to call
    // applyAutoScale() SYNCHRONOUSLY on every drag event. GUI callbacks run on
    // the CLIENT thread while onRenderBegin/onSceneRendered run concurrently on
    // the RENDER thread (the world keeps rendering behind the settings screen),
    // so a fast drastic drag fired many teardowns (renderTarget.destroyBuffers,
    // glDeleteTextures, FSR4/FG context shutdown + pool destroy) WHILE the
    // render thread was mid-dispatch reading those very textures → torn GL
    // state → the "see through the ground" artifact. Gradual drags spread the
    // events between frames, so the race rarely hit. Fix: the slider only sets
    // this flag (thread-safe write); onRenderBegin consumes it at frame start
    // on the render thread and runs applyAutoScale() there — the same safe
    // path updateAutoQuality() already uses (render-thread applyAutoScale is
    // proven: the target-FPS adjuster calls it from onRenderBegin's top).
    private volatile boolean scaleChangeQueued;
    // Deferred markDirty from the toggle key (2026-08-21, "freezes and crashes
    // when toggling FSR on/off with a shaderpack" fix). The toggle handler runs
    // on the CLIENT thread; markDirty() tears down FSR4/FG D3D12 contexts, frees
    // the pool, and destroys GL resources — calling it synchronously while the
    // render thread is mid-dispatch causes a hard freeze/crash (no hs_err — the
    // render thread stalls on torn D3D12 state). This flag is consumed at the
    // top of onRenderBegin (render thread) before any rendering or dispatch.
    private volatile boolean toggleDirtyQueued;
    // Deferred markDirty from the settings screen (2026-08-23, "switching FSR
    // versions makes terrain disappear until a quality change" fix). The
    // version pills / iris slider / VulkanMod toggle / FSR1-under-Iris toggle /
    // Async-FSR4 toggle callbacks all run on the CLIENT (GUI) thread, and each
    // called markDirty() synchronously — the same race the toggle key fix
    // (toggleDirtyQueued) addresses: markDirty() tears down the render target,
    // the FSR4/FG D3D12 contexts and the shared pool while the render thread is
    // mid-dispatch. Deleted GL texture IDs get reused, so the in-flight dispatch
    // reads a wrong/stale texture and the terrain renders black/garbage until a
    // later recreate (a quality change) happens to land cleanly. This flag is
    // consumed at the top of onRenderBegin (render thread) next to
    // toggleDirtyQueued, so the teardown + recreate happen with no concurrent
    // reader.
    private volatile boolean configDirtyQueued;
    private SharedTextureManager sharedTexMgr;
    private boolean sharedMemoryActive;
    private boolean fsr4Available;
    private boolean fsr4Initialized;
    // PARKED D3D12 CONTEXT (2026-08-25): when the user switches AWAY from
    // FSR4/FG_DX12 to a non-D3D12 version, the SDK context + shared pool are
    // PARKED (kept alive, unused) instead of torn down — a re-select at the
    // same size reuses them with ZERO cross-API work (the expensive +
    // crash-prone part: pool allocate + external-memory imports + ffxCreate
    // context). The D3D12 device itself already survives switches
    // (disposeD3D12 only runs at full destroy()); this extends that survival
    // to the SDK context + pool. fsr4ContextRenderW/H + DisplayW/H keep the
    // parked size so the unpark can validate a match; a size change while
    // parked tears the parked context down and re-inits fresh. fg4Parked
    // parks the FG SDK context the same way (it references pool textures, so
    // it must die only after the pool, or stay parked with it).
    private boolean fsr4Parked;
    private boolean fg4Parked;
    // One-shot for the "FSR4 selected but SDK unavailable (Linux) → running the
    // FSR3 temporal engine" notice — logged once when a FSR4 config is first
    // observed without the Windows-only FSR4 SDK.
    private boolean fsr4SdkFallbackLogged;
    // One-shot for the [FSR4-PATH] diagnostic (2026-08-20): confirms the real
    // D3D12 dispatch engaged instead of a silent FSR1 parity-bypass / suspend /
    // init-failed route, with the Iris jitter/depth state that gates quality.
    private boolean fsr4PathLogged;
    // One-shot for the [FSR4-PATH] fallback diagnostic (2026-08-20): FSR4 was
    // SELECTED but the frame is running the FSR1 spatial pipeline (init in
    // flight/deferred, suspended, hard-disabled, or pool alloc failed).
    private boolean fsr4FallbackPathLogged;
    // NOTE: the DYNAMIC_RESOLUTION pool-only recreate (2026-08-08) was REVERTED:
    // the FSR4 SDK dispatch reads the input with the context-cached render size,
    // so a resized pool was sampled with stale UVs -> magnified corner crop
    // ('zooming into the corner'). Scale changes go through the full teardown +
    // re-init below (needsRecreate), which re-creates the context at the new size.
    private int fsr4ContextRenderW, fsr4ContextRenderH;
    private int fsr4ContextDisplayW, fsr4ContextDisplayH;
    private long lastFsr4ContextChange;
    private static final long FSR4_DEBOUNCE_MS = 500;
    // SIZE-ALIGNMENT GUARD (2026-08-23, "zoomed into a corner" fix): one-shot
    // log when a dispatch is skipped because the pool/context size differs from
    // the current scene size (see onSceneRendered's guard). The recreate path
    // re-aligns within the debounce window, so this should fire at most once
    // per episode.
    private boolean fsr4SizeMismatchLogged;
    // VERSION-SWITCH DIAGNOSTIC: frame counter after markDirty (version switch),
    // logs the FSR4 dispatch decision for the first 60 frames post-dirty.
    // Initialized to Integer.MAX_VALUE so the blit-skip (<= 8) and diag log
    // (< 60) do NOT fire on the initial game load — only after markDirty()
    // explicitly resets this to 0 on an actual version switch.
    private int fsr4PostDirtyDiagFrames = Integer.MAX_VALUE;
    private int fsr4FramesSkipped;
    // GPU-saturation guard: consecutive GL sync timeouts (fast movement → chunk
    // streaming + Voxy meshing on the same GL context) escalate the fence wait,
    // then suspend FSR4 to the pure-GL FSR1 path until a probe shows the queue
    // drained. Prevents the skip-spiral that collapsed FPS to <10 while moving.
    private static final int FSR4_SKIP_SUSPEND_AFTER = 6;
    private int fsr4ConsecutiveSkips;
    private boolean fsr4Suspended;    // FALLBACK-STATUS LOG (2026-08-23, "blinking every second" diag): the chat
    // notifications are throttled at FSR4_FALLBACK_NOTIFY_COOLDOWN_MS (10s), so
    // a rapid suspend/resume flap is invisible in the log except as sparse
    // messages. Log the fallback state at a fixed 3s cadence instead so the
    // true flap cadence is measurable from a normal session log.
    private long lastFsr4FallbackStatusLogMs;
    // PACK-ACTIVATION HOLD / BASELINE RESEED (2026-08-23, "blinking every
    // second" fix): activating a heavy shader pack (Photonics) multiplies GPU
    // time ~2x, but the FSR4 saturation EMA was seeded during the pack-OFF
    // scene — so every pack-loading frame spikes past the iris-tightened
    // guard (1.5x/12ms, suspend after 2) and FSR4 flaps suspend->resume in
    // ~12s cycles (the 12:30 session log: fallback 12:32:04, restore 12:32:17,
    // restore 12:32:29, restore 12:32:52). The pack's own pipeline rebuilds at
    // activation also stomp the queue. On the activation frame: force-suspend
    // FSR4 (FSR1 renders while the pack settles), treat it as a dimension
    // change (reuses FSR4_DIM_CHANGE_HOLD_MS so the resume probe cannot re-arm
    // through the storm), and RESEED the EMA from the pack's real frame time —
    // the new baseline is the pack's load profile, so once the probe resumes
    // (after the hold) sustained game time stays within it and the flap stops.
    private long fsr4PackActivateHoldLogged;

    // FSR4-fallback diagnostic notification state (2026-08-06): edge-triggered
    // chat message when FSR4 suspends to FSR1 or hard-disables, and when it
    // restores. lastNotifiedFsr4Fallback remembers which side of the transition
    // we last told the player about (true = we said it fell back);
    // lastFsr4FallbackNotifyMs throttles repeat messages so a storm that flips
    // suspend/resume repeatedly can't spam chat.
    private boolean lastNotifiedFsr4Fallback;
    private long lastFsr4FallbackNotifyMs;
    private static final long FSR4_FALLBACK_NOTIFY_COOLDOWN_MS = 10_000L;
    // Backoff for DISPATCH-FAILURE suspensions: a failed dispatchFsr4 sets this
    // to now; the resume probe below won't re-arm FSR4 until the cooldown
    // elapses. Prevents a genuinely broken D3D12 path (not a saturated queue —
    // the probe would pass every frame since the GL queue is fine) from
    // retrying a failing dispatch every frame.
    private long lastFsr4DispatchFail;
    private static final long FSR4_DISPATCH_FAIL_BACKOFF_MS = 2000L;
    // Resume-probe throttling: while suspended, a full bounded fence wait runs
    // only every 16th frame so the recovery check costs <1ms/frame average
    // during sustained saturation (see probe below for why a 0ns poll alone can
    // never detect a drained queue).
    private int fsr4ProbeCounter;
    // DEFERRED RESUME-PROBE FENCE (2026-08-06, "FSR4 never auto-recovers"):
    // the fence the suspended-state probe waits on is created at the END of the
    // PREVIOUS frame's FSR4 block, NOT in the same frame. A full frame of GPU
    // time elapses between creation and check, so a drained queue has ALREADY
    // signaled it and the free 0ns poll succeeds. The old code created + waited
    // on the fence in the same frame — but the FSR4 block runs right after
    // renderLevel submitted THIS frame's 16-30ms scene render (GPU-bound iGPU),
    // so the fence could never signal within the 8ms bounded wait, the 2-signal
    // streak was unreachable, and FSR4 stayed on FSR1 until a manual version
    // toggle forced a reinit. Deleted on reinit/resize/shutdown (see
    // deleteFsr4ResumeProbeFence).
    private long fsr4ResumeProbeFence;

    private void deleteFsr4ResumeProbeFence() {
        if (fsr4ResumeProbeFence != 0) {
            GL43C.glDeleteSync(fsr4ResumeProbeFence);
            fsr4ResumeProbeFence = 0;
        }
    }
    // Escalation for PERSISTENT dispatch failures (incl. SEH-caught native
    // crashes, which surface as dispatchFsr4 returning false): after
    // FSR4_MAX_CONSECUTIVE_DISPATCH_FAILURES consecutive failures the D3D12
    // path is presumed broken for this session and FSR4 is hard-disabled
    // (permanent FSR1) instead of the 2s-backoff probe re-arming a broken path
    // every 2s forever. A successful dispatch resets the counter. Mirrors the
    // FG_MAX_RUNTIME_FAILURES pattern.
    private int fsr4DispatchFailCount;
    private boolean fsr4HardDisabled;
    private static final int FSR4_MAX_CONSECUTIVE_DISPATCH_FAILURES = 5;
    // BYPASS NOTIFICATION LATCH (2026-08-30, "make FSR3 FG DX12 work with
    // shaders"): once the native FSR4 SDK upscale throws against a live FG
    // context and the bilinear bypass arms (Fsr4Native.isBypassActive() true),
    // we log the downgrade to the user exactly ONCE per session. Cleared on a
    // deliberate rebuild (markDirty / applyAutoScale) alongside the native flag.
    private boolean fsr4BypassNotified;
    // FG_DX12 is not viable on AMD integrated GPUs (2026-08-30, "still
    // completely frozen while the game runs fine in the background"): the FG
    // SDK's ffxCreateContext cannot coexist with the FSR4 DX12 context on these
    // parts (Radeon 860M) — no matter whether FSR4 dispatches the SDK or the
    // pre-armed bilinear bypass, the D3D12 device is removed at the first frame
    // (DXGI_ERROR_DEVICE_REMOVED 0x887a0005). When set, FG is never created and
    // the session runs plain real FSR4. Cleared on a deliberate settings change.
    private boolean fgIgpuUnsupported;
    private boolean fgIgpuUnsupportedNotified;
    // Consecutive dispatch failures while the bilinear bypass is active
    // (2026-08-30, "allocator Reset FAILED storm → heap corruption" crash).
    // The bypass branch must not swallow failures forever: a healthy device
    // makes the bypass dispatch succeed every frame, so a STREAK of bypass
    // failures means the D3D12 device was removed/TDR'd and every D3D12 call
    // fails. That state used to spin silently (holding the previous output)
    // until the process died with heap corruption — this counter escalates it
    // to a suspend + session hard-disable (FSR1 spatial) like the real
    // dispatch path.
    private int fsr4BypassFailStreak;
    // DRIVER-CRASH MITIGATION (2026-08-05, atio6axx.dll 0xC0000005 during
    // elytra): when the previous frame's GL fence timed out, the queue was
    // saturated — the GL-side shared-texture ops (glCopyImageSubData into/out
    // of the D3D12-imported textures + MV-gen compute) fault the AMD GL driver
    // under that load. fsr4FenceTimedOutPrevFrame gates the NEXT frame to skip
    // ALL cross-API work (present the previous FSR4 output), halving driver
    // exposure per storm. Sticky resume: fsr4ProbeCleanStreak requires 2
    // consecutive signaled probes before re-arming FSR4 after a suspension.
    private boolean fsr4FenceTimedOutPrevFrame;
    private int fsr4ProbeCleanStreak;
    // DEFERRED resume-probe fence (2026-08-06): a GL sync object created at the
    // END of each suspended frame's probe block and polled at the START of the
    // NEXT frame's probe block. Because it guards only work submitted through
    // the PREVIOUS frame, the 0ns poll can actually signal once the GPU drains
    // past that frame — the old code polled a fence created in the SAME block,
    // which guarded the just-submitted scene render and could never signal on
    // a GPU-bound iGPU (FSR4 never auto-resumed; only a manual version toggle
    // forced a reinit).
    private long fsr4ProbeFence;
    // SUSTAINED-CALM RESUME GATE (2026-08-18, "<1 FPS in GL" — the storm-flap
    // fix): the old resume re-armed FSR4 after just 2 clean probe signals,
    // which a storm's 8ms lull frames satisfy — FSR4 re-dispatched into the
    // saturated queue, its async drain hit the 2000ms D3D12 fence timeout
    // (fsr4_context.cpp waitForFenceValue(..., 2000)), suspended, and the
    // 2-signal resume re-armed again at the next lull: ~2s stalls every other
    // frame for the whole storm window (log-verified: frame totals 1975/2004/
    // 1999/2109/2001/2091/2065/2068ms alternating with 8-12ms frames). Resume
    // now additionally requires (a) a minimum parked time and (b) the streak's
    // signals to be DENSE — all within FSR4_CALM_SPAN_MS — which storm lulls
    // (separated by multi-second stalls) can never satisfy: a stale streak
    // restarts on each signal, so the streak can only reach 2 when the queue
    // signals on consecutive frames (a genuinely drained queue). FSR4 stays on
    // FSR1 (fresh, smooth frames) through the whole storm and re-arms within
    // ~2 frames of the queue truly draining.
    private long fsr4SuspendedAtMs;       // when the current suspension began
    private long fsr4FirstCleanProbeMs;   // first signal of the current clean run
    private static final long FSR4_SUSPEND_MIN_MS = 1500L;  // min parked time
    private static final long FSR4_CALM_SPAN_MS = 1000L;    // streak signals must be this dense
    // FSR4 WORLD-CALM RESUME (2026-08-23, "blinking after a few minutes, only
    // fixed by toggling off/on" fix): the probe-fence resume requires signals
    // on dense consecutive frames, which an always-busy iGPU (Voxy/C2ME
    // background meshing) may never satisfy -> FSR4 stayed parked on FSR1 for
    // the whole session (visible quality drop = the reported "blinking").
    // While suspended, ALSO check world-render calm directly (the CPU-side
    // worldMs signal): 10 consecutive calm frames resume FSR4 even if the
    // fence never signals. Calm frames are cheap (FSR1 fallback), so this
    // cannot stack stalls; the dimension-hold and min-parked gates still
    // apply.
    // RESUME GRACE (2026-08-23): immediately after a resume, a single spike
    // frame must not re-suspend (the flap that made the calm-resume useless).
    // The pre-check still skips cross-API work on a spike, but does not count
    // it toward suspension for this window.
    private long fsr4ResumeAtMs;         // when FSR4 last resumed (0 = never)
    private static final long FSR4_RESUME_GRACE_MS = 1500L;
    private long fsr4WorldCalmStreak;    // consecutive calm frames while suspended
    private long fsr4WorldCalmStartMs;    // wall-clock start of the current calm streak
    // WARM-UP TRANSITION (2026-08-23, residual blink fix): when FSR4 resumes
    // from suspension, the first 2 frames have fresh history (no temporal
    // accumulation) — a hard spatial-to-temporal quality jump reads as a
    // blink, even though it's only 1-2 frames. Instead, run FSR4 dispatch
    // silently for 2 frames (the SDK builds history while the display stays
    // on FSR1), then switch to FSR4 output — the transition is invisible.
    private int fsr4ResumeBlendRemaining;
    private static final int FSR4_RESUME_BLEND_FRAMES = 2;
    private static final int FSR4_WORLD_CALM_FRAMES = 10;         // minimum frame count
    private static final long FSR4_WORLD_CALM_MS = 2500L;         // MINIMUM duration of the calm streak (2026-08-23 v2: 10 frames at 60fps = 0.17s — a momentary queue drain resumed straight into the next burst and re-suspended 178ms later; resume must not fire until the load has stayed calm for REAL time)
    // RAPID-STORM BACKOFF (2026-08-23, "still blinks but every ~10s" fix):
    // each resume that is followed by another suspension within
    // FSR4_RAPID_WINDOW_MS increments fsr4RapidEpisodes; fsr4CalmFramesNeeded()
    // multiplies the calm-frames requirement for the NEXT resume. First
    // episode: 2s of calm. Repeated storms: 4s, 8s, ... cap ~10s of sustained
    // calm — so a genuinely stormy stretch parks on FSR1 instead of blinking
    // on every storm cycle (resume->burst->suspend->lull->resume...).
    private int fsr4RapidEpisodes;
    private static final long FSR4_RAPID_WINDOW_MS = 30_000L;
    // DIMENSION-CHANGE HOLD (2026-08-22, "going into the End freezes and crashes"):
    // a dimension change tears down and recreates Voxy's pipeline + Iris shader
    // state (log: 9s of pipeline rebuild on End entry) while the resume probe
    // sees the transition's light loading frames as "queue drained" and re-arms
    // FSR4 right into the new dimension's chunk-streaming storm → the same
    // atio6axx.dll hard-freeze class as the physics-mod crash. On a client.level
    // object swap (dimension change), force-suspend FSR4 to FSR1 and hold the
    // resume (and the first init) for this window so the rebuild + initial
    // streaming settle before any cross-API D3D12 work re-engages.
    private static final long FSR4_DIM_CHANGE_HOLD_MS = 10000L;
    private Object fsr4LastLevelObj;      // last client.level identity (dimension-change detector)
    private long fsr4DimChangeAtMs;       // when the current hold window began (0 = none)
    // PACK-SWITCH HOLD (2026-08-25, "Process crashed with exitcode -1073741819" fix):
    // the pack-activation hold (field fsr4PackActivateHoldLogged) fires only on the
    // activate/deactivate FLIP. A pack SWITCH (Eclipse -> BSL) leaves irisActive=true
    // the whole time, so neither the activation hold nor the onSceneRendered
    // transition skip fires — yet Iris still destroys + rebuilds its composite
    // pipeline and Photonics re-allocates its GI buffers (~2-3s of GL pipeline
    // churn) while the GL render thread keeps dispatching. Re-enabling FSR4 in that
    // window (FSR off->on) runs the cross-API D3D12 pool re-creation + SDK init
    // concurrently with the mid-rebuild GL pipeline and faults the AMD GL driver
    // (0xC0000005, atio6axx/amdxc64 — the crash at 11:54:44 with fsrvk.dll +
    // amd_fsr4_api.dll + amdxc64.dll loaded; diag log ends "bypass: resources
    // destroyed" = the re-init teardown). This tracker reuses FSR4_DIM_CHANGE_HOLD_MS
    // so a switch engages the same forced-suspend + init-defer + resume-hold the
    // dimension-change path uses.
    private String fsr4PackNamePrev;
    private long fsr4PackSwitchLogged;    // 0 until the first switch is announced
    // BLACK-INPUT SANITY CHECK (2026-08-18, "flickering loading-terrain screen with
    // Voxy" fix): Voxy's AsyncNodeManager issues GL work from its own thread on the
    // same context; when it collides with the mod's glCopyImageSubData into the
    // D3D12-imported shared textures, the copies silently fail → FSR4's shared input
    // stays black → the SDK's output never updates → the display freezes on the last
    // presented frame (user: "repeats the last 2 frames", "flickering loading terrain
    // while the game runs fine behind it"; log-verified: SHARE_VERIFY colorC=(0,0,0)
    // with Voxy vs real scene content without, and on Aug 13 with Voxy). The race is
    // timing-dependent, so instead of a fragile serialization we DETECT it and fall
    // back: periodically compare the shared color's center pixel against the scene's;
    // FSR4_SHARED_BLACK_MAX consecutive black-while-nonzero reads hard-disable FSR4
    // for the session → FSR1 spatial (live display) instead of a frozen one.
    private int fsr4SharedCheckCounter;
    private int fsr4SharedBlackStreak;
    private static final int FSR4_SHARED_CHECK_INTERVAL = 90;  // frames (~1.5s)
    private static final int FSR4_SHARED_BLACK_MAX = 3;        // consecutive failures
    // Consecutive pre-dispatch saturation-skip frames (worldMs spike or prior
    // fence timeout). After FSR4_PRE_SUSPEND_AFTER of them, suspend to FSR1 so
    // the presented image stays fresh during sustained storms instead of
    // freezing on the last FSR4 frame.
    private int fsr4PreCheckSkips;
    private static final int FSR4_PRE_SUSPEND_AFTER = 3;
    // FSR4 SKIP DIAGNOSTIC (2026-08-29, "the disappearing stopped but every
    // second it blinks"). A saturation/fence SKIP frame is completely invisible
    // in the log today: it returns before the "[SWITCH-DIAG] FSR4 dispatch OK"
    // line, and it does not suspend (fsr4PreCheckSkips resets on the very next
    // calm frame, so an isolated 1-2 frame burst never reaches
    // FSR4_PRE_SUSPEND_AFTER). The session therefore looks perfectly healthy
    // while the image is stuttering.
    //
    // A skip is visually costly for two reasons:
    //   1. the dispatch is not re-issued, so mainRT keeps the PREVIOUS frame's
    //      FSR4 output -> the presented image freezes for a frame;
    //   2. fsr4PrevColorFresh is cleared, so the NEXT dispatch runs with T&C
    //      (uTCActive) gated off -> a visibly different frame right after.
    // A periodic single-frame skip (Photonics GI bursts, chunk streaming) is
    // therefore a periodic freeze + appearance change — the reported blink.
    // These counters let that be confirmed instead of guessed at.
    private int fsr4SkipFrames;
    private long lastFsr4SkipLogMs;
    // Measured GL-fence wait of the most recent FSR4 frame — i.e. the GPU queue
    // depth the cross-API sync has to clear. See the [FSR4-SYNC] log.
    private float fsr4LastFenceWaitMs;
    // BLIT AUDIT (2026-08-29, "every second it blinks"): count of
    // RenderTarget.blitToScreen calls seen for the MAIN present target this
    // frame. Zero at the end of a frame = the frame was never presented = the
    // back buffer reached the screen undrawn = a fully black frame (HUD
    // included). Reset in onRenderBegin, incremented in presentDirect().
    private int mainPresentBlits;
    private long lastBlitAuditLogMs;
    // Snapshot of sceneUpscaled taken at the top of onRenderEnd (it is cleared
    // later in that method), so the blit audit can report whether the previous
    // frame actually reached an upscale path.
    private boolean lastFrameUpscaled;
    // FSR4's OWN calm-baseline EMA for the worldMs pre-check. The FSR1/2/3 SAT
    // guard's satEmaWorldMs is never updated on FSR4 frames (this block returns
    // before that guard runs), so FSR4 needs a private one. Only calm frames
    // feed it, so the baseline can't adapt to a storm and stop firing mid-storm.
    private float fsr4EmaWorldMs;
    // FIRST-INIT DEFERRAL (2026-08-05 v5, "FSR4 crashes when loading into the
    // game"): the FSR4 init — importing the D3D12 external-memory handles into
    // GL + creating the D3D12 device — runs inside onRenderBegin WHILE the
    // world-load storm saturates the GPU queue, and those cross-API GL ops
    // fault the AMD GL driver (atio6axx.dll 0xC0000005; the WER crash clusters
    // land exactly at launch/load, and the user's proven workaround is "load
    // with FSR1, switch to FSR4 later"). So the FIRST init is deferred until
    // the frame-duration EMA is calm (≤ FSR4_INIT_CALM_MS) OR a 6s grace
    // elapses (bounds the FSR1-only window at load — never leave the user on
    // FSR1 indefinitely). This automates the manual workaround; a mid-game
    // config switch to FSR4 inits immediately because the EMA is already calm.
    private float fsr4InitEmaMs;             // frame-duration EMA (end of onRenderBegin)
    private long lastInitFrameNanos;         // previous end-of-frame timestamp
    private long fsr4InitDeferStartMs;       // when the current deferral began (0 = none)
    private int fsr4InitDeferFrames;         // deferred frames so far (debug log cadence)
    private static final float FSR4_INIT_CALM_MS = 26.0f;   // ≤ this = calm enough to init
    // MAX FSR1-ONLY WINDOW AT LOAD (2026-08-18, "can't get into the game — repeats
    // the last 2 frames" fix): was 6s — the init then force-ran MID-STORM on this
    // machine's 15-20s world-load storms (new world + Voxy meshing + C2ME on the
    // iGPU), and the init path's GL43C.glFinish() (the documented pre-import queue
    // drain) plus the D3D12 pool import blocked the render thread ~1s (session
    // log: onRenderBegin 961ms at frame #480, the init frame) on top of the
    // already-1s storm frames → the loading screen visibly froze, repeating the
    // same 2 frames for seconds. 25s covers typical load storms (Aug 13: ~8s,
    // Aug 18 sessions: ~15-20s) so init waits until the queue has genuinely
    // drained (the EMA drops below CALM within ~20 frames of the storm ending),
    // while always-busy machines still engage FSR4 within the bound. Also reduces
    // the documented load-with-FSR4 driver-crash exposure (init runs on a calm
    // queue instead of a saturated one).
    private static final long FSR4_INIT_GRACE_MS = 25000L;  // max FSR1-only window at load
    // DELIBERATE-SWITCH FAST INIT (2026-08-24, "terrain disappears when switching
    // FSR versions" root cause #1): the first-init calm-gate deferral above treats
    // ANY session where FSR4 has not yet run as "world-load storm" — including a
    // mid-session VERSION SWITCH (session booted on FSR1/FSR3_FG, user picks
    // FSR4). fsr4ContextRenderW==0 there, so the 25s grace applied and FSR4 sat
    // uninitialized on the FSR1 fallback for 21+s (19:26 session log: frames
    // 1-60 post-dirty all init=false, user quit before the grace fired). A
    // deliberate settings change gets a SHORT settle window instead: the menu
    // click itself guarantees the queue has drained for seconds.
    private static final long FSR4_SWITCH_INIT_GRACE_MS = 2000L;
    private static final long FSR4_SWITCH_RECENT_MS = 30000L;
    // Timestamp of the last deliberate config change (set in markDirty).
    private long fsr4SwitchConfigAtMs;
    private static final float FSR4_INIT_EMA_ALPHA = 0.05f; // ~20-frame EMA window
    // FSR2/3 jitter amplitude dial (2026-08-06): max jitter in DISPLAY pixels
    // at low render scales. 0.6 = the old shake-hiding cap (killed edge
    // smoothing — 12+ accumulated samples within ±0.3 render px average to
    // nothing); 1.5 = 75% of FSR2's standard ±0.5 render px at 25% scale,
    // full amplitude at ≥33% scale. Higher = more temporal AA but more
    // sky/cloud shimmer (those pixels bypass accumulation by design).
    private static final float JITTER_DISPLAY_PX_MAX = 1.5f;
    // GPU-saturation guard for the PURE-GL paths (FSR1 / FSR2 / FSR3_UPS — the
    // FSR4 cross-API path has its own fence-based guard above). When the GL
    // queue is deep (chunk streaming: DH LOD→real chunk transitions, Voxy/DH
    // meshing on the same context), the render thread stalls inside the world
    // pass waiting for the GPU, so a world render time far above its running
    // average is a reliable saturation signal. While degraded we run only a
    // single bilinear blit (no compute dispatches / barriers / extra copies)
    // so the mod stops adding GPU work to an overloaded queue; the temporal
    // pipelines resume with a clean history rebuild once the storm passes.
    private static final float SAT_EMA_ALPHA = 0.05f;    // ~20-frame window
    private static final float SAT_SPIKE_MULT = 2.0f;    // spike = >2x running avg
    private static final float SAT_SPIKE_MIN_MS = 20.0f; // ...and at least 20ms
    private static final int SAT_EXIT_CLEAN_FRAMES = 10; // resume after 10 clean frames
    private float satEmaWorldMs;
    private int satConsecutiveClean;
    private boolean saturationDegraded;
    // One-shot throttle for the FG_DX12 GL sync timeout warn (once per episode).
    private boolean fgTimeoutLogged;
    private int fsr4InitFailCount;
    private static final int FSR4_MAX_INIT_FAILURES = 3;
    private boolean fsr4PermanentlyDisabled;

    // FG DX12 state (FSR3_FG_DX12 mode)
    private boolean fg4Available;
    private boolean fg4Initialized;
    // One-shot "FG without real depth" warning (2026-08-13): the FSR 4.0.1 FG
    // prepare API has NO reactive/disocclusion input — FG's disocclusion
    // detection runs entirely on depth discontinuities. On the GL path the
    // depth probe latches "no real geometry" (uniform far plane), so FG
    // interpolates across object edges → spikes at edges during motion, even
    // when the FSR4 base frames (presented in between, double-present mode)
    // are perfectly smooth. Warn once so this exact symptom is explainable
    // in the log instead of looking like an FSR4 upscale regression.
    private boolean fgNoDepthWarned;
    private boolean fg4InitFailed;
    private boolean d3d12DeviceLost;
    // FG STALL WATCHDOG (2026-08-29, "FSR3 FG DX12 freezes with a shader pack"):
    // the native D3D12 fence wait in Fsr4Context::finish() has a 5000ms timeout,
    // so a dispatch that never completes blocks the render thread for 5s EVERY
    // frame — a hard freeze. Count consecutive stalls (see the waitIdle call in
    // the synchronous FSR4 dispatch branch) and escalate to switching FG off.
    private int fgStallConsecutive;
    private static final float FG_STALL_MS = 250.0f;
    private static final int FG_STALL_LIMIT = 3;
    private int fgRuntimeFailCount;
    private boolean fg4PermanentlyDisabled;
    private static final int FG_MAX_RUNTIME_FAILURES = 3;

    // Multi-present state: after the vanilla blitToScreen+swapBuffers shows the
    // first generated FG frame, onAfterSwapBuffers() (hooked at RenderSystem.flipFrame
    // TAIL) presents the remaining generated frames + the real FSR4 frame.
    // With fgMultiplier=1 (default): 1 generated + 1 real = 2× FPS (double-present).
    // With fgMultiplier=N: N generated + 1 real = (N+1)× display FPS.
    private boolean fgSecondPresentPending;
    private boolean fgInSecondPresent;  // recursion guard for the nested present
    private boolean fgDoublePresentLogged;
    // Number of ADDITIONAL generated frames to present in onAfterSwapBuffers
    // (beyond the first one already shown by vanilla). 0 for fgMultiplier=1.
    private int fgExtraGenFramesToPresent;
    // Number of generated frames produced this cycle (from fgMultiplier).
    private int fgNumGeneratedThisCycle;

    // Deferred FG generate state: the FG SDK needs presentColor = the real frame
    // WITH the GUI, which only exists AFTER the HUD paints onto mainRT. So
    // onSceneRendered stashes the prepare params and sets fgGeneratePending;
    // presentDirect() (blitToScreen HEAD hook — runs after the GUI) performs the
    // actual prepare+generate and swaps the generated frame into mainRT.
    private boolean fgGeneratePending;
    private long fgFrameID;
    private long fgDeltaNs;
    private float fgJitterX, fgJitterY;
    private float fgCameraNear, fgCameraFar, fgCameraFovY;
    private float fgCamPosX, fgCamPosY, fgCamPosZ;
    private float fgCamUpX, fgCamUpY, fgCamUpZ;
    private float fgCamRightX, fgCamRightY, fgCamRightZ;
    private float fgCamForwardX, fgCamForwardY, fgCamForwardZ;
    private boolean fgReset;
    private boolean fgStalePendingLogged;
    // GL FG TRANSLATION GATE (2026-09-02, parity with the VKMod path's
    // vkModFgEyeSpeed gate): eye speed in blocks/sec (dt-normalized from
    // lastFrameDeltaMs so it is FPS-independent — the VKMod signal was
    // dt-normalized for exactly this reason), computed at the GL param
    // stash from consecutive stash positions. While the camera TRANSLATES
    // faster than the gate, the FG frame is a static blend of two offset
    // real frames (constant-depth history, edges smear) — so single-present
    // the REAL frame instead (the generate still runs to keep the SDK's
    // frameID consecutive). Still/rotating = full frame doubling.
    private float fgEyeSpeed;
    private float fgLastCamX, fgLastCamY, fgLastCamZ;
    private boolean fgHasLastCam;
    private boolean fgTranslationGateEngaged;
    private static final float FG_TRANSLATION_GATE_SPEED = 0.5f;
    private boolean fgTranslateGateLogged;
    // FG INIT RETRY BUDGET (2026-09-02): the GL init site treated ONE
    // Fg4Native.nativeInit failure as permanent for the whole session
    // (fg4Initialized=false with no retry) — a transient driver hiccup
    // during the world-load window killed FG even though FSR4's own init
    // DEFERS during storms and retries every frame. Mirror the FSR4
    // FSR4_MAX_INIT_FAILURES policy: retry on every FSR4 reinit until the
    // budget is spent, then latch fg4InitFailed for the session (cleared
    // by a deliberate settings change like every other FG latch).
    private int fg4InitFailCount;
    private static final int FG_MAX_INIT_FAILURES = 3;
    // FSR3_FG_DX12 under VulkanMod (2026-08-15, step 1): FG prepare params
    // stashed in the VKMod dispatch block; the FG run fires from the
    // mixin/UIHelper after waitForD3D12Output() (pool 4 — FSR4 output — valid).
    private boolean vkModFgParamsStashed;
    private boolean fgVkModFirstGenLogged;

    // FSR3_FG_DX12 step 2 (2026-08-15): double-present under VulkanMod. After
    // the GUI paints (scaled path) or the copies record (non-scaled path), the
    // FG frame is generated and copied into a SECOND swapchain image acquired
    // per frame; the Renderer.endFrame TAIL hook presents it right after
    // VulkanMod's own real-frame present. pending holds the acquired image index
    // + the swapchain identity it belongs to (recreation invalidates it — the
    // stale check drops the FG frame, single-present that frame).
    private boolean vkModFgDoublePresentLogged;
    private boolean vkModFgPresentImagesReady;  // pool 5/6 VkImage imports succeeded
    // Scaled path: the FG presentColor (pool 6) only exists if the GUI painted
    // this frame (the swapchain capture is recorded in endNativeResGui). Frames
    // where GuiRenderer.render never runs have no capture — skip FG that frame
    // rather than feeding the SDK a stale pool 6.
    private boolean vkModFgNeedsCapture;
    private boolean vkModFgCapturedThisFrame;
    // Translation gate for the FG present (2026-08-15, "no edge smoothening"
    // fix): constant-depth translation MVs are ~0 on the VKMod path, so the FG
    // SDK cannot warp during a walk — the generated frame is a static blend of
    // two offset frames -> edge smear. Keep generating (preserves the SDK's
    // consecutive prepare pairing) but skip the extra present while the camera
    // translates; the 2x rate resumes when still/rotating (rotation MVs are
    // depth-independent -> FG interpolates correctly).
    private float vkModFgEyeSpeed;
    private boolean vkModFgTranslateGateLogged;

    // VKMod FSR4 motion-adaptive jitter (2026-08-15): per-frame camera deltas
    // for shrinking the FSR4 jitter amplitude while the camera moves (the
    // spatially-rendered frames during motion show the full ±0.5px Halton
    // oscillation as shake). Dedicated fields — the FSR2/3 block's fsr23*
    // fields never run on the VKMod path (it returns earlier).
    private boolean vkModPrevCamInit;
    private double vkModPrevCamX, vkModPrevCamY, vkModPrevCamZ;
    private org.joml.Quaternionf vkModPrevCamRot = new org.joml.Quaternionf();

    // Async FSR4 overlap state: when config.fsr4Async is on (non-FG mode), the
    // dispatch is submitted WITHOUT waiting and the waitIdle + output copy are
    // deferred to the START of the next frame, where the 3-6ms of FSR4 GPU work
    // has already run concurrently with the present + next scene render. The
    // presented frame is therefore the PREVIOUS frame's upscaled output (~1 frame
    // of input latency) — the tradeoff that removes the CPU stall from the
    // critical path.
    private boolean fsr4AsyncPending;
    private boolean fsr4AsyncLogged;

    private TextureTarget renderTarget;
    private ScaledRenderTarget proxyTarget;

    private FSR1Pipeline fsr1Pipeline;

    private int rcasUpscaleProg = -1;
    private int rcasUpscaleTex = -1;
    private int rcasUpscaleVao = -1;
    private int rcasUpscaleFbo = -1;
    private int rcasUpscaleTexW;
    private int rcasUpscaleTexH;
    private boolean rcasUpscaleCompiled;

    // Depth copy compute shader: reads depth texture (any format) and writes to
    // shared R32F texture. Used instead of glCopyImageSubData because depth
    // format (GL_DEPTH_COMPONENT*) and R32F are incompatible for copy.
    private int depthClearFbo;

	    // MV gen for FSR4 path (duplicated MV_GEN_CS from FSR2Pipeline/FSR3Pipeline)
	    //
	    // NOTE: uUseDepth=0 workaround for Minecraft 1.21.4 depth texture issue
	    // (render target depth texture reads 0 everywhere — likely a GpuDevice
	    // abstraction layer issue where render passes use a separate internal
	    // depth buffer and don't write to the RenderTarget's depth texture).
	    // Until this is resolved, ndcZ is a constant. 2026-08-13 motion-tracking
	    // experiment: FAR constant ndcZ=0.999 (≈ z≈500 blocks), matching the
	    // shared device depth 0.9995 the SDK receives — the MV reprojection and
	    // the SDK must agree on the world depth (the ndcZ 0.999 + SDK 0.5
	    // combination is exactly what produced scale-dependent jaggies). Rotation
	    // MVs are depth-independent (exact at any constant); translation
	    // parallax ~0 at z≈500. Sky detection is disabled when uUseDepth=0 since
	    // it requires valid depth.
	    // MV gen for FSR4 path (duplicated MV_GEN_CS from FSR2Pipeline/FSR3Pipeline)
	    //
	    // NOTE: uUseDepth=0 workaround for Minecraft 1.21.4 depth texture issue
	    // (render target depth texture reads 0 everywhere — likely a GpuDevice
	    // abstraction layer issue where render passes use a separate internal
	    // depth buffer and don't write to the RenderTarget's depth texture).
	    // Until this is resolved, ndcZ is a constant. 2026-08-13 motion-tracking
	    // experiment: FAR constant ndcZ=0.999 (≈ z≈500 blocks), matching the
	    // shared device depth 0.9995 the SDK receives — the MV reprojection and
	    // the SDK must agree on the world depth (the ndcZ 0.999 + SDK 0.5
	    // combination is exactly what produced scale-dependent jaggies). Rotation
	    // MVs are depth-independent (exact at any constant); translation
	    // parallax ~0 at z≈500. Sky detection is disabled when uUseDepth=0 since
	    // it requires valid depth.
	    //
	    // SHADING-CHANGE DETECTION (FSR3-style tile histogram, merged here to save
	    // a dispatch): each 8x8 workgroup accumulates a luminance histogram of its
	    // tile, compares the bright-pixel ratio against the previous frame's ratio,
	    // and feeds the diff into the reactive mask. Content that moves beyond the
	    // camera reprojection (mobs, falling blocks, disocclusion) changes the
	    // tile's luminance distribution → reactive rises → the SDK discards history
	    // on those pixels → no ghosting/smearing of moving entities. Static tiles
	    // keep a stable ratio (translation-invariant) so camera pans over terrain
	    // do NOT fire it — that's why this beats a naive per-pixel luma diff.
	    private static final String MV_GEN_CS = """
	        #version 430 core
	        layout(local_size_x = 8, local_size_y = 8) in;
	        layout(binding = 0) uniform sampler2D uInputDepth;
		    layout(binding = 1, rg16f) writeonly uniform image2D uOutputMV;
		    layout(binding = 2, r8) writeonly uniform image2D uOutputReactive;
		    layout(binding = 3) uniform sampler2D uInputColor;
		    layout(binding = 4) uniform sampler2D uPrevShadingChange;
		    layout(binding = 5, rg8) writeonly uniform image2D uCurrShadingChange;
		    // PREVIOUS frame's scene color (binding 6) — the T&C auto-reactive
		    // term compares the CURRENT color (binding 3) against it at the
		    // reprojected position (prevUv). Copied from renderTex every frame
		    // AFTER the MV-gen dispatch (Java side: glCopyImageSubData).
		    layout(binding = 6) uniform sampler2D uPrevColor;

		    uniform ivec2 uRenderSize;
	        uniform mat4 uInvCurrVP;
	        uniform mat4 uPrevVP;
	        uniform vec2 uJitter;
	        uniform vec2 uPrevJitter;
	        uniform float uFarDepth;
	        uniform vec4 uDeviceToViewDepth;
	        uniform float uFarViewZ;
	        uniform int uUseDepth;  // 0=constant ndcZ, 1=read depth texture
        uniform float uConstNdcZ; // constant reprojection ndcZ when uUseDepth=0 (0.999 far default; 0.0 near when config.fsr4NearDepthMotion is on to AGREE with the SDK's 0.5 accumulation depth)
	        uniform float uShadingThreshold;
	        uniform int uTCActive;   // 1 when prevColorTex holds last frame's color (hasPrevFrame)
	        uniform float uCamMotion; // 1 when the PLAYER camera genuinely moved this frame (CPU-computed, bob-excluded)
	        uniform float uCamReactive; // 0..0.9 CPU camera-motion reactive FLOOR (2026-08-13, FSR2/3 uCamReactive parity): constant-depth MVs underestimate NEAR-content screen motion while walking, so the SDK would blend misaligned history (smear) — the floor shifts the blend toward the stable de-jittered current frame during real motion, 0 standing still (bit-identical to the pre-floor path)

	        float getViewSpaceDepth(float d) {
	            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0] + 1e-10);
	        }		shared uint s_histHigh;
		shared uint s_histLow;
		shared uint s_skyCount;
		shared float s_shadingDiff;

	        // Luma deadband around uShadingThreshold: pixels within this band are
	        // excluded from the histogram (jitter can flip them across the
	        // threshold). Sized at the smoothstep ramp start (0.10) so static
	        // tiles can never accumulate a ratio swing that reaches the ramp.

	        void main() {
	            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	            bool inBounds = p.x < uRenderSize.x && p.y < uRenderSize.y;

	            // --- Tile luminance histogram (all threads must reach barriers) ---
            if (gl_LocalInvocationIndex == 0u) {
                s_histHigh = 0u;
                s_histLow = 0u;
                s_skyCount = 0u;
            }
	            barrier();

            if (inBounds) {
                vec3 rgb = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
                float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                float maxC = max(rgb.r, max(rgb.g, rgb.b));
                float minC = min(rgb.r, min(rgb.g, rgb.b));
                vec2 uv = (vec2(p) + 0.5) / vec2(uRenderSize);
                // SKY/CLOUD EXCLUSION (FSR3-style): bright desaturated white
                // (clouds) and blue (sky) pixels in the top half of the screen
                // are excluded from the histogram — clouds drift independently
                // of camera motion and time-of-day shifts sky brightness, both
                // of which would otherwise fire the detector on static views.
                bool isWhiteSky = luma > 0.85 && (maxC - minC) < 0.06;
                bool isBlueSky = luma > 0.60 && rgb.b > max(rgb.r, rgb.g) * 1.25;
                bool isSkyColor = (isWhiteSky || isBlueSky) && uv.y > 0.5;
                // JITTER-IMMUNITY DEADBAND: the scene renders with sub-pixel
                // Halton jitter (±0.5 render px). On anti-aliased edges (common
                // at high render scales like 65%), pixels within a luma band
                // around the threshold get flipped across it frame-to-frame by
                // the jitter — making the tile ratio swing and the detector
                // fire on STATIC content → history discarded → visible shake
                // when standing still. Excluding the ambiguous band keeps the
                // ratio stable under jitter; real content motion (mobs) moves
                // pixels far past the band and is still detected.
                const float kJitterDeadband = 0.10;
                if (isSkyColor) {
                    atomicAdd(s_skyCount, 1u);
                } else if (abs(luma - uShadingThreshold) < kJitterDeadband) {
                    // ambiguous under jitter — do not count
                } else if (luma > uShadingThreshold) {
                    atomicAdd(s_histHigh, 1u);
                } else {
                    atomicAdd(s_histLow, 1u);
                }
            }
            barrier();

	            if (gl_LocalInvocationIndex == 0u) {
	                uint total = s_histHigh + s_histLow;
	                float ratio = float(s_histHigh) / float(max(total, 1u));
	                ivec2 tilePos = ivec2(gl_WorkGroupID.xy * 8);
	                vec2 tileUv = (vec2(tilePos) + 4.0) / vec2(uRenderSize);
	                float prevRatio = textureLod(uPrevShadingChange, tileUv, 0.0).r;
	                // The G channel stores persist/3.0 (capped at 3). UNORM8
	                // clamps imageStore values to [0,1], so the raw counter (1,2)
	                // would collapse to 1.0 and be unreadable; /3.0 keeps 1/3 and
	                // 2/3 EXACTLY representable (85/255, 170/255), so the *3.0
	                // decode below is lossless.
	                float prevPersist = textureLod(uPrevShadingChange, tileUv, 0.0).g * 3.0;
	                float diff = abs(ratio - prevRatio);
	                float skyRatio = float(s_skyCount) / float(max(total + s_skyCount, 1u));
	                // MIN-SAMPLE / SKY-HEAVY GUARD: a tile with almost no
	                // classified pixels (or mostly sky/cloud) has a noise ratio —
	                // 1-2 pixels flipping can swing it wildly, so never fire on it.
	                if (total < 12 || skyRatio > 0.5) {
	                    diff = 0.0;
	                    prevPersist = 0.0;
	                }
	                // TEMPORAL PERSISTENCE GATE: increment a per-tile counter on
	                // frames whose ratio diff exceeds the ramp start, decay it
	                // otherwise. Jitter-induced ratio flips on static edges are
	                // single-frame noise (Halton samples land at well-separated
	                // sub-pixel offsets, so consecutive diffs are independent) —
	                // requiring 2 consecutive high frames makes the detector
	                // effectively immune to jitter, while real content motion
	                // (mobs, falling blocks, disocclusion) sustains the diff for
	                // many frames and still fires. Stored in the G channel
	                // (previously the raw diff — now recomputed each frame from
	                // the R-channel ratio, so the raw value is not needed).
	                float persist = (diff > 0.10)
	                    ? min(prevPersist + 1.0, 3.0)
	                    : max(prevPersist - 1.0, 0.0);
	                s_shadingDiff = (persist >= 2.0) ? diff : 0.0;
	                // Write current ratio + persistence for every pixel in the tile
	                for (int dy = 0; dy < 8; dy++) {
	                    for (int dx = 0; dx < 8; dx++) {
	                        ivec2 tp = tilePos + ivec2(dx, dy);
	                        if (tp.x < uRenderSize.x && tp.y < uRenderSize.y) {
	                            imageStore(uCurrShadingChange, tp, vec4(ratio, persist / 3.0, 0.0, 0.0));
	                        }
	                    }
	                }
	            }
	            barrier();

	            float shadingDiff = s_shadingDiff;

	            if (!inBounds) return;

	            vec2 uv = (vec2(p) + 0.5) / vec2(uRenderSize);
	            float ndcZ;
	            bool isSkyPx;

	            if (uUseDepth > 0) {
	                float depth = texelFetch(uInputDepth, p, 0).r;
	                // SKY/SUN/CLOUDS (2026-08-08, "terrain is upscaling but not the
	                // sun and clouds" fix): the old VK-parity branch forced sky
	                // pixels to mv=0 + fully reactive — the SDK rendered them
	                // SPATIALLY every frame → blocky sun/cloud edges. Removed: the
	                // SHARED depth is ALWAYS non-reversed (0=near, 1=far), so
	                // sky/far-plane pixels (depth >= 0.999) reproject EXACTLY at
	                // their true depth. Accumulation is gated by the SKY MOTION
	                // POLICY at the reactive site (sky accumulates only while
	                // ~static — the camera-locked gradient can't use world-anchored
	                // MVs during motion; see the comment there).
	                ndcZ = depth * 2.0 - 1.0;
	                isSkyPx = depth >= 0.999;
	            } else {
	                // Constant-depth fallback: FAR depth, CONSISTENT with the
	                // shared depth 0.9995 (device) the SDK receives — ndcZ 0.999
	                // and device 0.9995 are the same world depth (0.9995 =
	                // 0.999·0.5+0.5), ≈ z≈500 blocks with this mod's near/far.
	                //
	                // 2026-08-13 history: the validated-smooth pre-Vulkan-fixing
	                // baseline had ndcZ=0.0 + device 0.5 (both z≈1) with
	                // motionVectorScale 1/renderSize — crushed MVs made the SDK
	                // accumulate in place, so the near constant was harmless. A
	                // DESYNC (MV z≈500 vs SDK z≈1) is exactly what produces
	                // scale-dependent jaggies: the SDK reprojects history at its
	                // own depth (device 0.5 = z≈1) while the MVs were computed at
	                // z≈500, so every history read lands at the wrong screen spot
	                // → rejected → spatial → jagged diagonals at every scale.
	                //
	                // config.fsr4NearDepthMotion (2026-08-30, "experimental near
	                // depth motion MV"): bias ndcZ to NEAR (0.0, z≈1) to AGREE
	                // with the SDK's 0.5 accumulation depth — terrain gets genuine
	                // translation parallax → history tracked → smooth diagonals
	                // while walking; tradeoff is sky/horizon smear on motion.
	                ndcZ = uConstNdcZ;
	                // No depth on this path — classify sky by color (the relaxed
	                // T&C-guard heuristic, FSR2/3-validated thresholds; the
	                // screen-Y > 0.5 guard keeps terrain out).
	                vec3 rgb0 = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
	                float luma0 = dot(rgb0, vec3(0.2126, 0.7152, 0.0722));
	                float maxC0 = max(rgb0.r, max(rgb0.g, rgb0.b));
	                float minC0 = min(rgb0.r, min(rgb0.g, rgb0.b));
	                bool isWhiteSky0 = luma0 > 0.58 && (maxC0 - minC0) < 0.12;
	                bool isBlueSky0 = luma0 > 0.55 && rgb0.b > max(rgb0.r, rgb0.g) * 1.15;
	                isSkyPx = (isWhiteSky0 || isBlueSky0) && uv.y > 0.5;
	            }

	            // Jitter is applied as a translation in clip space (via Matrix4f.translation),
	            // which is automatically handled by the inverse view-projection matrix.
	            // No manual de-jitter needed here — just use raw UV coordinates.
	            vec2 ndc = uv * 2.0 - 1.0;
	            vec4 worldH = uInvCurrVP * vec4(ndc, ndcZ, 1.0);
	            worldH /= worldH.w;

	            vec4 prevClip = uPrevVP * vec4(worldH.xyz, 1.0);
	            vec2 prevNdc = prevClip.xy / prevClip.w;
	            vec2 prevUv = prevNdc * 0.5 + 0.5;

	            vec2 mv = (prevUv - uv) * vec2(uRenderSize);

	            // 3x3 LOCAL COLOR WINDOW — feeds BOTH the sky camera-locked
	            // detection below and the cutout detector further down.
	            float aMin = 1.0, aMax = 0.0;
	            vec3 cMin = vec3(1e9), cMax = vec3(-1e9);
	            for (int dy = -1; dy <= 1; dy++) {
	                for (int dx = -1; dx <= 1; dx++) {
	                    ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), uRenderSize - 1);
	                    vec4 c = texelFetch(uInputColor, q, 0);
	                    aMin = min(aMin, c.a); aMax = max(aMax, c.a);
	                    cMin = min(cMin, c.rgb); cMax = max(cMax, c.rgb);
	                }
	            }
	            float colorSpread = length(cMax - cMin);

	            // SKY-LIKE CLASSIFIER (2026-08-08 v5/v6): depth far plane
	            // (isSkyPx) OR bright-desaturated pixels (clouds/snow
	            // silhouettes/the sun — clouds carry REAL depth below 0.999 at
	            // y≈192, so the depth test alone misses them). Computed ONCE
	            // here; reused by the cutout colorEdge exclusion, the gentler
	            // sky MV ramp, and the T&C sky guard.
	            vec3 skyCol0 = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
	            float skyLuma0 = dot(skyCol0, vec3(0.2126, 0.7152, 0.0722));
	            float skyChroma0 = max(skyCol0.r, max(skyCol0.g, skyCol0.b)) - min(skyCol0.r, min(skyCol0.g, skyCol0.b));
	            bool isSkyLike = isSkyPx || (skyLuma0 > 0.5 && skyChroma0 < 0.25);

	            // SKY CAMERA-LOCKED DETECTION (2026-08-08 v3 — "the smoothing
	            // for the sky was happening before" fix): the sky region mixes
	            // camera-LOCKED content (the gradient/horizon — zero true
	            // screen motion under ANY camera movement) with world-ANCHORED
	            // content (sun/moon/stars/clouds — screen motion = the
	            // far-plane reprojection MV, correct). A single MV can't serve
	            // both: applying the pan MV to the gradient smears/slants the
	            // horizon ("slanted edges" report), and forcing the WHOLE sky
	            // fresh on any pan (the v2 uCamSkyMotion flag) killed the
	            // sun/cloud smoothing the user had. Distinguish the regimes by
	            // LOCAL CONTRAST: the gradient is FLAT (spread < 0.08 over the
	            // 3x3); the sun/clouds/stars are detailed. Flat -> camera-LOCKED
	            // -> mv = 0 — exact under ALL camera motion (the gradient stays
	            // fixed on screen), so history accumulates in place -> smooth AND
	            // straight. Detailed -> keep the real far-plane MV — the SDK
	            // follows the sun/clouds across the screen -> smooth during pans;
	            // fast turns make mvLen large -> the MV ramp discards -> fresh
	            // (masked by motion).
	            // SUN/MOON WHITE-BODY EXCLUSION (2026-08-08 v8, "the edges going
	            // down are tilted" fix): the sun/moon body is FLAT WHITE (spread
	            // < 0.08) AND far-plane (isSkyPx), so this rule classified it
	            // camera-LOCKED (mv=0) — but the sun/moon are WORLD-ANCHORED
	            // billboards. During any camera motion their history accumulated
	            // locked at a stale screen position → the body GHOSTED offset from
	            // its true position and the edges read tilted/kinked (measured in
	            // the user's screenshot: a 4px horizontal ghost in the sun's lower
	            // body). Only the COLORED sky-dome gradient is truly camera-locked;
	            // the pure-white sun/moon body (luma > 0.88, chroma < 0.06) keeps
	            // its real far-plane MV so it tracks correctly and accumulates
	            // cleanly.
	            if (isSkyPx && colorSpread < 0.08 && !(skyLuma0 > 0.88 && skyChroma0 < 0.06)) {
	                mv = vec2(0.0);
	            }

	            // GENUINELY-STILL CAMERA → mv = 0 (2026-08-13, "the upscaler
	            // thinks I am going right when I'm not moving at all" fix): the
	            // motion-tracking experiment (motionVectorScale=1.0) made the
	            // FSR4 SDK follow the REAL per-pixel MVs for the first time. The
	            // idle camera bob is real micro-motion (0.5-2.5px MVs in the
	            // clean matrices, [DEBUG_MV] verified ~0.4-0.8px here) whose
	            // NEAR-content parallax the constant-depth (z≈500) reprojection
	            // underestimates — so while the player stands still the SDK
	            // reprojects history by wrong bob MVs, reads misaligned history,
	            // and the accumulated image appears to drift/shake sideways
	            // ("going right"). uCamMotion is the CPU-computed bob-EXCLUDED
	            // motion flag (position delta > 0.05 blocks OR rotation delta >
	            // 0.01 rad vs the previous frame — the same signal the cutout
	            // detector gates on): when it is 0 the camera is genuinely still,
	            // and forcing mv=0 makes the SDK accumulate history IN PLACE —
	            // the exact behavior of the validated-smooth pre-experiment
	            // baseline (its 1/renderSize scale crushed MVs to ~0.001). When
	            // uCamMotion=1 (walking/turning), real MVs flow and the SDK
	            // tracks motion. Also collapses the reactive mask to 0 (mvLen=0)
	            // on still frames — full accumulation.
	            if (uCamMotion <= 0.0) {
	                mv = vec2(0.0);
	            }

	            imageStore(uOutputMV, p, vec4(mv, 0.0, 0.0));

	            // Generate reactive mask from MV magnitude (merged from REACTIVE_MASK_CS)
	            // Saves one compute dispatch per frame.
	            // < 0.5 px: reactive 0 (static — full accumulation; safe now that
	            //   MVs are clean, so reprojection is correct on slow camera motion)
	            // 0.5-16 px: gentle ramp — normal walking/rotation (1-8px) keeps
	            //   MOST of the history. Saturation at 8px made the whole screen
	            //   ~70-100% reactive while walking → the SDK discarded history
	            //   every frame → the output reverted to a spatial upscale → the
	            //   "pixelated when moving" artifact.
	            // > 16 px: fully reactive (fast turns — discard history).
	            //
	            // SHADING-CHANGE term: tile luminance-ratio diff between frames.
	            // Catches content that moved beyond camera reprojection (mobs,
	            // disocclusion). Translation-invariant → camera pans over static
	            // terrain do NOT fire it.
	            float mvLen = length(mv);
	            // SKY MV RAMP IS GENTLER (2026-08-08 v6, "the clouds and sun are
	            // still jagged" fix): the sun/clouds' reprojection MVs are EXACT
	            // (rotation MVs are depth-independent; far-plane translation
	            // parallax ~0), so they can accumulate heavily during the idle bob /
	            // slow pans — their clean high-contrast edges are exactly where the
	            // jaggedness shows. Terrain keeps the steeper 16-px ramp. Fast turns
	            // (>32 px) still fully discard. The flat gradient has mv = 0 → inert.
	            float mvReactive = smoothstep(0.5, isSkyLike ? 32.0 : 16.0, mvLen);
	            // SHADING-CHANGE REACTIVE SOFTENING (2026-08-13, "voxy lods don't
	            // fully render properly" fix): Voxy swaps LOD tiles ASYNCHRONOUSLY
	            // while the camera moves — each swap changes the 8x8 tile's
	            // luminance histogram, the persistence gate (2 frames) confirms it,
	            // and shadingReactive hit the FULL 1.0 → the SDK discarded ALL
	            // history on those tiles → the low-res scaled LOD content rendered
	            // SPATIALLY → upscaled low-detail geometry = the white stretched
	            // polygon artifacts (log-verified: reactive=1.00 with tiny mv at the
	            // exact AsyncNodeManager buffer-resize moment). The MV ramp remains
	            // the primary mover-catcher (real screen motion of mobs/entities), so
	            // capping this term at 0.5 keeps HALF the history weight on LOD-
	            // streamed tiles: the SDK blends current + previous instead of
	            // dropping to pure spatial, so LOD content accumulates sub-pixel
	            // samples like terrain. Genuine disocclusion still reaches full
	            // discard via mvReactive (moving content has real MVs).
	            float shadingReactive = smoothstep(0.10, 0.45, shadingDiff) * 0.5;
	            // Modest camera-motion floor: constant-depth MVs are approximate
	            // during ANY motion (near/far terrain move at different rates than
	            // the mid-depth estimate), so cap history weight ~0.85 during motion
	            // to reduce slow-pan smear without the full-discard pixelated look.
	            // ZEROED for sky-like pixels (2026-08-08 v6): their MVs are exact —
	            // the floor only exists to damp the constant-depth error.
	            float motionFloor = isSkyLike ? 0.0 : smoothstep(0.4, 2.5, mvLen) * 0.15;
	            float reactive = max(mvReactive, max(shadingReactive, motionFloor));

	            // CAMERA-MOTION REACTIVE FLOOR (2026-08-13, "walking smear" fix —
	            // FSR2/3 uCamReactive parity): the CPU knows the camera is moving
	            // exactly (per-frame position/rotation deltas, bob-excluded). The
	            // constant-depth MVs (z≈500) UNDERESTIMATE near terrain's true
	            // screen motion while walking → the SDK blends misaligned history
	            // at ~90% weight = smear + edge wobble. Floor the reactive mask on
	            // known motion so the blend shifts toward the de-jittered (stable)
	            // current frame. TRANSLATION-dominant: rotation MVs are
	            // depth-independent/exact, so the rotation term only fires on
	            // extreme turns (>= 0.04 rad/frame); pans are covered by the MV
	            // ramp. Bob is ~0.01-0.02 blocks / ~0.002-0.005 rad per frame →
	            // thresholds start above it; 0.9 cap keeps a little history at
	            // sprint. uCamReactive is 0 standing still (the same deltas that
	            // drive uCamMotion), so this path is bit-identical to the
	            // pre-floor behavior there — and the still-camera MV gate (mv=0)
	            // keeps the standing-still accumulation untouched.
	            reactive = max(reactive, uCamReactive);

	            // THIN-DETAIL/CUTOUT-EDGE REACTIVE (2026-08-06, "FSR4 can't handle
	            // flowers"): flowers/tall-grass/leaves are 1-2px alpha-CUTOUT
	            // geometry (a 2px-wide cross billboard with a hard transparent→
	            // opaque silhouette). Temporal accumulation smears them into
	            // vertical streaks regardless of MV accuracy: the history sample
	            // for a feature smaller than the bilinear sampling footprint lands
	            // on the flower/background boundary and blends misaligned samples
	            // (this mod's GL path also gives them wrong constant-mid-depth MVs,
	            // compounding it). Neither existing term can see them — MV magnitude
	            // is tiny (1-2px), and the 8x8 tile histogram can't resolve a
	            // 4-pixel flower. Force them FULLY reactive so the SDK renders them
	            // from the current spatial sample (no accumulation → no streak).
	            // Two independent signals, both from the 3x3 neighborhood:
	            //   colorEdge — the RGB BOUNDING-BOX diagonal of the window (max
	            //               per-channel range). Hue-agnostic: catches pink tulips
	            //               (sat ~0.31 — a saturation-based test misses them)
	            //               as well as red/yellow/blue flowers, because any
	            //               flower hue differs strongly from grass. Grass-texture
	            //               noise (spread ~0.15-0.25) and desaturated stone-vs-
	            //               grass edges (~0.29) stay below the 0.45 threshold;
	            //               a large uniform surface (wool wall interior) has
	            //               spread ~0.
	            //   alphaEdge — a HARD 0<->1 alpha transition (an alpha-tested
	            //               silhouette against transparent background). Water/
	            //               clouds write partial alpha across the whole window
	            //               and never span the full 0.5. (Only meaningful if the
	            //               scene RT's alpha is a real cutout mask — the colorEdge
	            //               term works regardless.)
	            // MOTION GATE (2026-08-06 fix, "static terrain parts while standing
	            // still"): the terms are gated on the CPU-computed uCamMotion flag
	            // AND mvLen > 0.5 — NOT mvLen alone. The idle camera bob shifts the
	            // eye position ~0.01-0.02 blocks/frame, which lands in the clean MV
	            // matrices and produces 0.5-2.5px MVs even when the player is
	            // perfectly still — so a pure mvLen > 0.5 gate was OPEN while
	            // standing still and fired the detector at every flower/leaf/water
	            // edge → fully reactive → those regions dropped temporal
	            // accumulation and read as static/shimmering patches. uCamMotion
	            // (camera position delta > 0.05 blocks OR rotation delta > 0.01 rad,
	            // both measured against the PREVIOUS frame — well above the bob's
	            // ~0.02 block / ~0.002 rad contribution) is false while standing
	            // still, so the detector stays disabled and history accumulates
	            // cleanly; the instant the player walks/turns/falls it engages and
	            // protects flowers/grass exactly as before.
	            // SKY-LIKE pixels are EXCLUDED from the cutout detector's colorEdge
	            // term (2026-08-08 v5, "smoothing the terrain more than the sky"
	            // fix): the detector targets thin FOREGROUND alpha-cutout geometry
	            // (flowers/grass/leaves). Excluded: the depth far plane (isSkyPx —
	            // gradient/sun, depth >= 0.999; already excluded in v4) AND bright-
	            // desaturated pixels (clouds/snow silhouettes — clouds carry REAL
	            // depth below 0.999 at y≈192, so the depth test alone misses them;
	            // their white-vs-blue edges have spread > 0.45 and CORRECT real-
	            // depth MVs, and the cutout was forcing them fully reactive during
	            // ANY motion (bob/walk/pan → mvLen > 0.5) → blocky clouds while the
	            // terrain around them accumulated → "terrain smoother than sky").
	            // Excluded pixels keep their correct MVs + the MV ramp. alphaEdge
	            // (a real 0<->1 alpha-cutout silhouette — white flowers, leaves)
	            // stays active for sky-like pixels so genuine thin geometry is
	            // still protected. (isSkyLike is computed above with the 3x3
	            // window, before the MV store.)
	            bool alphaEdge = (aMax - aMin) > 0.5;
	            bool colorEdge = colorSpread > 0.45;
	            float cutoutReactive = (uCamMotion > 0.0 && mvLen > 0.5 && (alphaEdge || (colorEdge && !isSkyLike))) ? 1.0 : 0.0;
	            reactive = max(reactive, cutoutReactive);

	            // T&C-STYLE AUTO-REACTIVE (2026-08-06, "particles/translucents
	            // ghost while the camera is still"): the MV field is CAMERA-ONLY
	            // — particles, animated foliage, mobs and potion effects move
	            // independently of the camera, so their pixels get ~0 MV and the
	            // SDK keeps accumulating stale history at the OLD position (the
	            // vertical pink streaks in the flower-field screenshot). This term
	            // is the AMD "transparency & composition" idea adapted to the
	            // single-pass MV-gen: compare the CURRENT color at p against the
	            // PREVIOUS frame's color at the REPROJECTED position (prevUv — the
	            // same matrices that produced the MV). A large color difference
	            // that motion compensation cannot explain = the content genuinely
	            // changed = fully reactive (render spatially, no accumulation).
	            //
	            // GL-path TRUST GATE (mvLen < 1.5): the MVs use constant mid-depth
	            // (ndcZ=0), so prevUv is only EXACT where the camera is (nearly)
	            // still at this pixel's depth. Without the gate, walking would
	            // reproject near terrain to the wrong prevUv → static terrain
	            // would "change" → the whole near screen turns reactive (the
	            // pixelated-when-moving regression). Far content reprojects
	            // near-exactly and has small mvLen, so it stays T&C-active even
	            // while walking; near-content motion is covered by the cutout +
	            // MV-magnitude terms instead.
	            //
	            // 5x5 SEARCH WINDOW (not 3x3): the idle camera bob shifts the
	            // whole image ~1px WITHOUT appearing in the MVs (bob lives in the
	            // view matrix, which the clean MV matrices exclude). A 3x3 window
	            // at a sharp edge would find the shifted edge and fire on static
	            // content every bob cycle (edge shimmer). A 5x5 window covers the
	            // bob shift, so the same-surface sample is found and the diff
	            // collapses to ~0. Real changes (a particle vs. the grass behind
	            // it) differ from ALL 25 samples regardless of bob.
	            // When REAL per-pixel depth is active (uUseDepth=1, auto-detected
	            // by the depth probe), the reprojection is exact at every distance
	            // and the T&C comparison is trustworthy even during camera motion
	            // (VK-path parity) — the mvLen gate only guards the constant-mid-
	            // depth fallback, whose prevUv is exact only when the camera is ~still.
	            if (uTCActive > 0 && (uUseDepth > 0 || mvLen < 1.5)) {
	                bool noPrev = prevClip.w <= 0.0
	                    || prevUv.x < 0.0 || prevUv.x > 1.0
	                    || prevUv.y < 0.0 || prevUv.y > 1.0;
	                if (noPrev) {
	                    // Never visible last frame (disoccluded / behind camera):
	                    // no history can exist — fully reactive.
	                    reactive = 1.0;
	                } else if (!isSkyLike) {
	                    // SKY GUARD (2026-08-08 v6): the comparison is skipped for
	                    // sky-like pixels (isSkyLike — depth far plane or bright-
	                    // desaturated). The 5x5 window was sized for a ~1px idle-
	                    // bob shift, but at high focal lengths the bob rotation
	                    // produces 3-8 render px — beyond the ±2 px window — and
	                    // sun/cloud edges (high contrast against a uniform sky)
	                    // misfire into reactive ~0.9 → never accumulate → jagged
	                    // sky while textured terrain edges pass (noise matches
	                    // in-window). Sky MVs are exact, so the gentler sky MV
	                    // ramp governs them; the old white/blue + screen-Y guard
	                    // was the narrow version of this same idea.
	                    vec3 curRgb = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
	                    float curLuma = dot(curRgb, vec3(0.2126, 0.7152, 0.0722));
	                    ivec2 prevTexel = clamp(
	                            ivec2(prevUv * vec2(uRenderSize)),
	                            ivec2(0), uRenderSize - 1);
	                        float minDiff = 1e9;
	                        for (int dy = -2; dy <= 2; dy++) {
	                            for (int dx = -2; dx <= 2; dx++) {
	                                ivec2 q = clamp(prevTexel + ivec2(dx, dy),
	                                    ivec2(0), uRenderSize - 1);
	                                vec3 pc = max(vec3(0.0), texelFetch(uPrevColor, q, 0).rgb);
	                                float pl = dot(pc, vec3(0.2126, 0.7152, 0.0722));
	                                minDiff = min(minDiff, abs(curLuma - pl));
	                                // Early exit: a single matching sample means
	                                // the change is explained by reprojection.
	                                if (minDiff <= 0.18) break;
	                            }
	                            if (minDiff <= 0.18) break;
	                        }
	                        // PARTIAL reactivity (review fix 2026-08-06): a hard
	                        // 1.0 on EVERY real change would make animated/
	                        // translucent surfaces (water, lava, portals, rain)
	                        // permanently spatial — they lose temporal
	                        // accumulation entirely and read noisier. Ramp with
	                        // the diff magnitude (AMD's auto-reactive does the
	                        // same with autoReactiveThreshold/Scale): subtle
	                        // animation → partial history retention; strong
	                        // changes (particles, mobs, disoccluded content)
	                        // → near-full discard. Disocclusion (noPrev above)
	                        // keeps the hard 1.0 — no history can exist there.
	                        reactive = max(reactive, smoothstep(0.18, 0.50, minDiff) * 0.9);
	                }
	            }

	            imageStore(uOutputReactive, p, vec4(reactive, 0.0, 0.0, 0.0));
	        }
	        """;

	    // REAL-DEPTH COPY compute shader (2026-08-06, "pixelated when moving" fix):
	    // reads the RenderTarget's depth texture (any depth format, any
	    // convention) and writes a NON-REVERSED (0=near, 1=far) copy into the
	    // MANAGED staging R32F, which copyDepthToShared then glCopyImageSubData's
	    // into the shared D3D12-visible R32F. The MV-gen's uUseDepth=1 path and
	    // the FSR4 SDK both expect non-reversed depth — the convention conversion
	    // happens HERE so neither consumer needs to know the source convention.
	    private static final String DEPTH_COPY_CS = """
	        #version 430 core
	        layout(local_size_x = 8, local_size_y = 8) in;
	        layout(binding = 0) uniform sampler2D uInputDepth;
	        layout(binding = 1, r32f) writeonly uniform image2D uOutputDepth;
	        uniform ivec2 uRenderSize;
	        uniform int uReversedZ;  // 1 = source stores reversed-Z (0 at far plane)
	        void main() {
	            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;
	            float d = texelFetch(uInputDepth, p, 0).r;
	            d = (uReversedZ > 0) ? (1.0 - d) : d;
	            imageStore(uOutputDepth, p, vec4(clamp(d, 0.0, 1.0), 0.0, 0.0, 0.0));
	        }
	        """;
	    private int depthCopyProg;
	    private boolean depthCopyCompiled;
	    private int dc_uRenderSize, dc_uReversedZ;
	    // REAL-DEPTH auto-detect state (2026-08-06): probeFsr4Depth() samples the
	    // RenderTarget depth on the first few FSR4 frames. If it holds real
	    // variation (the "reads 0" finding predates the readback fixes — the VK
	    // path proved 26.1.2 WRITES the framebuffer depth), FSR4 switches to
	    // per-pixel depth (uUseDepth=1): accurate MVs at all distances + SDK
	    // disocclusion + exact T&C reprojection — the actual cure for the
	    // "pixelated when moving" history-rejection. If the depth is genuinely
	    // unwritten (no spread / unreadable), we stay on constant mid-depth
	    // (current behavior) — the probe is strictly a quality upgrade, never a
	    // regression risk.
	    private boolean fsr4DepthProbeDone;
	    private boolean fsr4RealDepthAvailable;
	    private boolean fsr4DepthReversed;
	    private int fsr4DepthProbeFrame;

	    private int mvGenProg;
	    private boolean mvGenCompiled;
	    private boolean mvGenDiagLogged;
	    private int mv_uRenderSize, mv_uInvCurrVP, mv_uPrevVP, mv_uJitter, mv_uPrevJitter, mv_uFarDepth, mv_uDeviceToViewDepth, mv_uFarViewZ, mv_uUseDepth;
	    private int mv_uShadingThreshold;
	    private int mv_uTCActive;
    private int mv_uCamMotion;
    private int mv_uCamReactive;
    private int mv_uConstNdcZ;
    // One-shot for the experimental near-depth motion-MV toggle (2026-08-30).
    private boolean fsr4NearDepthLogged;
	    // T&C auto-reactive (2026-08-06): previous frame's scene color, compared
	    // against the current frame at the reprojected position to catch content
	    // that moves independently of the camera (particles, mobs, translucents).
	    private int fsr4PrevColorTex = -1;
	    // STALE-PREV-COLOR GUARD (2026-08-06, "stopped edge smoothing"): set false
	    // on every skip/suspension path (pre-check spike, fence timeout, FSR1
	    // fallback) and true right after the per-frame snapshot copy. uTCActive
	    // gates on it, so after a skip episode the T&C comparison is disabled for
	    // one frame instead of comparing against a snapshot that may be many
	    // frames old / from a different camera position — a stale compare fires
	    // reactive everywhere (the SDK then discards ALL history → spatial
	    // output = the reported "edge smoothing stopped even when standing still").
	    private boolean fsr4PrevColorFresh;
	    private int mvClearFbo;
	    private final float[] mvMatBuf = new float[16];
	    private final Matrix4f mvTmpMat = new Matrix4f();
	    private final float[] mvDeviceToViewDepth = new float[4];
	    private float mvFarViewZ;

	    // Reactive mask compute shader for FSR4

    // Debug logging: only active when debug mode is on (config.debugMode > 0),
    // throttled to every 600 frames (~10s) using timingFrameCount. The debug
    // block creates 3 GL textures + does multiple glGetTextureSubImage readbacks
    // + heavy logging — gated on debug mode so production runs pay ZERO cost.
    private boolean shouldDebug() {
        return config.debugMode > 0 && timingFrameCount % 600 == 0;
    }
    // Reusable resources for debug texture readback
    private int debugFbo = -1;
    private java.nio.ByteBuffer debugPboBuf; // allocated lazily

    /**
     * Direct texture readback. Uses glGetTextureSubImage (GL 4.5) when available,
     * otherwise falls back to FBO glReadPixels via GL_COLOR_ATTACHMENT0 (GL 4.3).
     * The fallback is REQUIRED: Minecraft 26.1.2 runs a GL 4.4 context, so the
     * 4.5-only path returns null for every non-RGBA8 texture and all diagnostics
     * would read as "failed".
     *
     * CRITICAL: the buffer MUST be native byte order. GL writes little-endian
     * floats on x86; a default (big-endian) ByteBuffer + getFloat() turns every
     * non-zero value into a denormal (~0.0000) — that is the real reason all
     * historical float readbacks showed 0! (0.5 little-endian = 00 00 00 3F,
     * read big-endian = 5.6e-44 ≈ 0.0000.)
     *
     * Note: all textures read here are COLOR formats (R32F/RG16F/R8/RGBA8) — the
     * FBO fallback attaches to GL_COLOR_ATTACHMENT0. NEVER GL_DEPTH_ATTACHMENT:
     * attaching an R32F color texture to GL_DEPTH_ATTACHMENT makes the FBO
     * incomplete (that is what the historical "FBO not complete for depth
     * readback" log line meant).
     */
    private float[] readTexPixels(int tex, int x, int y, int fmt, int type, int channels) {
        try {
            int prevPackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_PACK_BUFFER_BINDING);
            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, 0);
            try {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(channels * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
                int prevReadFb = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
                int fb = debugFbo;
                if (fb == -1) {
                    fb = GL43C.glGenFramebuffers();
                    debugFbo = fb;
                }

                // Drain any stale GL error so the post-read check is trustworthy.
                while (GL43C.glGetError() != GL43C.GL_NO_ERROR) { }

                boolean readOk = false;
                if (org.lwjgl.opengl.GL.getCapabilities().OpenGL45) {
                    buf.clear();
                    GL45.glGetTextureSubImage(tex, 0, x, y, 0, 1, 1, 1, fmt, type, buf);
                    readOk = (GL43C.glGetError() == GL43C.GL_NO_ERROR);
                    // Depth textures reject color-format reads (GL_RED) with
                    // GL_INVALID_OPERATION — retry as a depth read. (This is
                    // why the depth probe "readback failed" on 4.5+ contexts.)
                    if (!readOk && fmt == GL43C.GL_RED) {
                        while (GL43C.glGetError() != GL43C.GL_NO_ERROR) { }
                        buf.clear();
                        GL45.glGetTextureSubImage(tex, 0, x, y, 0, 1, 1, 1,
                            GL43C.GL_DEPTH_COMPONENT, GL43C.GL_FLOAT, buf);
                        readOk = (GL43C.glGetError() == GL43C.GL_NO_ERROR);
                    }
                } else {
                    // GL 4.3/4.4 fallback: FBO + glReadPixels. Color formats (R32F/
                    // RG16F/R8/RGBA8) attach to GL_COLOR_ATTACHMENT0; if that is
                    // incomplete, retry as a DEPTH format (Minecraft's RenderTarget
                    // depth texture may be DEPTH_COMPONENT32F etc.).
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fb);
                    // Detach any leftover DEPTH_ATTACHMENT from a previous depth
                    // readback — a stale depth texture attached alongside a color
                    // texture would keep the FBO complete-but-ambiguous, and if the
                    // color attach below fails the depth retry must start clean.
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                        GL43C.GL_TEXTURE_2D, 0, 0);
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                        GL43C.GL_TEXTURE_2D, tex, 0);
                    if (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE) {
                        buf.clear();
                        GL43C.glReadPixels(x, y, 1, 1, fmt, type, buf);
                        readOk = (GL43C.glGetError() == GL43C.GL_NO_ERROR);
                    } else if (fmt == GL43C.GL_RED) {
                        // Probably a depth-format texture: read via GL_DEPTH_ATTACHMENT.
                        // CRITICAL: detach COLOR_ATTACHMENT0 FIRST — a depth texture
                        // left on a color attachment keeps the FBO permanently
                        // incomplete (GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT), so the
                        // DEPTH-only retry would still fail. This exact bug made the
                        // depth probe read "readback failed" on every 3.3/4.4 context
                        // (26.1 instance) — real depth never engaged.
                        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                            GL43C.GL_TEXTURE_2D, 0, 0);
                        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                            GL43C.GL_TEXTURE_2D, tex, 0);
                        if (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE) {
                            buf.clear();
                            GL43C.glReadPixels(x, y, 1, 1, GL43C.GL_DEPTH_COMPONENT, GL43C.GL_FLOAT, buf);
                            readOk = (GL43C.glGetError() == GL43C.GL_NO_ERROR);
                        }
                    }
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFb);
                }

                // CRITICAL: a silent GL failure returns the zero-filled buffer —
                // indistinguishable from real data. Only trust the read if the
                // GL error state is clean.
                if (!readOk) return null;

                buf.rewind();
                float[] out = new float[channels];
                for (int i = 0; i < channels; i++) out[i] = buf.getFloat(i * 4);
                return out;
            } finally {
                if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, prevPackBuf);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // IRIS JITTER AUTO-PROBE (2026-08-18)
    // ------------------------------------------------------------------

    // Reads a 32x32 RGBA8 block from a GL texture (renderTex — the pack's
    // composite at scaled res). Returns null on any GL failure.
    private byte[] readTexBlockRgba8(int tex, int x, int y, int w, int h) {
        // (2026-08-19) NEVER use an FBO + glReadPixels here: the jitter probe
        // runs at renderLevel(TAIL) with Iris's framebuffer state live, and
        // attaching the scaled proxy's color texture to our own READ framebuffer
        // while the pack still has it bound faulted the AMD GL driver
        // (0xC0000005 in atio6axx.dll — hs_err_pid22160 Java frames:
        // glReadPixels <- readTexBlockRgba8 <- irisJitterProbe). glGetTexImage
        // reads the texture directly with NO framebuffer involvement, so it is
        // immune to the caller's FBO state.
        // See diagnosticReadbackThisFrame: every block readback is a pipeline
        // flush (~26ms measured for a 16x16 block), which the GPU-saturation
        // guards would otherwise read as a saturation spike. Mark the frame so
        // they ignore it.
        diagnosticReadbackThisFrame = true;
        try {
            int prevPackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_PACK_BUFFER_BINDING);
            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, 0);
            try {
                // DRAIN STALE ERRORS FIRST (2026-08-24): glGetError() is global —
                // an error left by an earlier pass (shader compile, FSR4 interop,
                // another mod) made every subsequent read here look failed
                // ("READ_FAIL" on all probes in the 19:26 session). Drain before,
                // so only a FRESH error from THIS read fails it.
                while (GL43C.glGetError() != GL43C.GL_NO_ERROR) { /* drain */ }
                if (org.lwjgl.opengl.GL.getCapabilities().OpenGL45) {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                        .order(java.nio.ByteOrder.nativeOrder());
                    buf.clear();
                    GL45.glGetTextureSubImage(tex, 0, x, y, 0, w, h, 1,
                        GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, buf);
                    if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return null;
                    byte[] out = new byte[w * h * 4];
                    buf.rewind();
                    buf.get(out);
                    return out;
                }
                // GL 4.3/4.4 fallback: glGetTexImage (whole texture) + block copy.
                // (2026-08-25) glGetTexImage/glGetTexLevelParameteri read the
                // texture BOUND to the target — passing the texture NAME as the
                // target enum (as this code did since the 08-19 switch) is
                // GL_INVALID_ENUM, so every read since then has failed: all
                // [TERRAIN-PROBE] reads read READ_FAIL in the 08-24 sessions,
                // and the Iris jitter probe silently ran its fail-fast
                // "assumes vanilla jitter" verdict. Bind the texture to
                // GL_TEXTURE_2D on the active unit, restore the previous
                // binding afterwards (no FBO involved — the 08-19 driver-fault
                // class is untouched).
                while (GL43C.glGetError() != GL43C.GL_NO_ERROR) { /* drain stale */ }
                int prevTex2d = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
                GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, tex);
                try {
                    int tw = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_WIDTH);
                    int th = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_HEIGHT);
                    if (tw <= 0 || th <= 0) return null;
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(tw * th * 4)
                        .order(java.nio.ByteOrder.nativeOrder());
                    buf.clear();
                    GL43C.glGetTexImage(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, buf);
                    if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return null;
                    // Extract the block (row-major RGBA8 in storage order). Both
                    // probe samples come from the SAME source on consecutive frames,
                    // so orientation cancels out in the shift search.
                    byte[] out = new byte[w * h * 4];
                    if (x < 0 || y < 0 || x + w > tw || y + h > th) return null;
                    for (int row = 0; row < h; row++) {
                        buf.position((y + row) * tw * 4 + x * 4);
                        buf.get(out, row * w * 4, w * 4);
                    }
                    return out;
                } finally {
                    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex2d);
                }
            } finally {
                if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, prevPackBuf);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // Mean absolute per-channel difference between block a and block b (RGBA8
    // w*h) at integer shift (dx,dy): b is sampled at (x+dx, y+dy) against a at
    // (x,y), over the overlap region. 0..255 scale. -1 on mismatch.
    private float irisProbeMad(byte[] a, byte[] b, int w, int h, int dx, int dy) {
        if (a == null || b == null || a.length != w * h * 4 || b.length != w * h * 4) return -1f;
        int x0 = Math.max(0, -dx), x1 = Math.min(w, w - dx);
        int y0 = Math.max(0, -dy), y1 = Math.min(h, h - dy);
        if (x1 <= x0 || y1 <= y0) return -1f;
        long sum = 0;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            int rowA = y * w * 4;
            int rowB = (y + dy) * w * 4;
            for (int x = x0; x < x1; x++) {
                int ia = rowA + x * 4;
                int ib = rowB + (x + dx) * 4;
                sum += Math.abs((a[ia] & 0xFF) - (b[ib] & 0xFF))
                     + Math.abs((a[ia + 1] & 0xFF) - (b[ib + 1] & 0xFF))
                     + Math.abs((a[ia + 2] & 0xFF) - (b[ib + 2] & 0xFF));
                count += 3;
            }
        }
        return count > 0 ? (float) sum / count : -1f;
    }

    // Variance of a block's luma (0..255 scale) — used to pick a textured probe
    // location (flat sky gradients don't move measurably with sub-pixel jitter).
    private float irisProbeVariance(byte[] b, int w, int h) {
        if (b == null || b.length != w * h * 4) return 0f;
        int n = w * h;
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += 0.299 * (b[i * 4] & 0xFF) + 0.587 * (b[i * 4 + 1] & 0xFF) + 0.114 * (b[i * 4 + 2] & 0xFF);
        }
        mean /= n;
        double var = 0;
        for (int i = 0; i < n; i++) {
            double l = 0.299 * (b[i * 4] & 0xFF) + 0.587 * (b[i * 4 + 1] & 0xFF) + 0.114 * (b[i * 4 + 2] & 0xFF);
            var += (l - mean) * (l - mean);
        }
        return (float) (var / n);
    }

    /**
     * IRIS JITTER AUTO-PROBE (2026-08-18).
     *
     * The generic Iris mode feeds the pack's composite to the FSR2/3 temporal
     * pipelines, which assume the input was rendered at the reported jitter
     * offsets. Most packs consume the vanilla projection (gbufferProjection)
     * that captureAndJitter modifies — but a pack that rebuilds its own
     * projection renders unjittered, and telling FSR2/3 a jitter that never
     * happened misaligns the temporal filter (ghosting/smear). Super Resolution
     * solves this with per-pack JSON (JitterSourceConfig). We solve it by
     * MEASURING: read a textured 32x32 block of the composite on two
     * still-camera frames whose jitter offsets differ by ~1 render px; if the
     * block physically shifted, the pack is jittered and the full temporal
     * contract holds; if the block stays static across several clean pairs,
     * the pack is jitter-blind and onRenderBegin zeroes the jitter (spatial
     * accumulation, no temporal reconstruction).
     *
     * Cost: two 32x32 RGBA8 readbacks over ~0.3s, once per session per pack.
     */
    private void irisJitterProbe(int renderTex, int rw, int rh) {
        if (irisJitterProbeDone) return;
        // FSR4 included (2026-08-20): FSR4's temporal reconstruction needs the
        // SAME jitter-blind verdict as FSR2/3 — a pack that ignores the vanilla
        // projection jitter renders unjittered, and feeding the SDK jitter
        // offsets that don't match the render misaligns history (no edge
        // smoothing / ghosting). Zeroing jitter when blind degrades FSR4 to
        // spatial-quality upscale (honest), never broken.
        if (!(isFsr2() || isFsr3() || isFsr4())) return;
        if (renderTex == -1 || rw < 64 || rh < 64) return;
        int s = IRIS_PROBE_SIZE;
        try {
            if (!irisProbeArmed) {
                // Not armed: wait for a genuinely still camera, then pick the
                // most textured candidate block (entity motion / grass sway in a
                // low-texture block would confound the shift search).
                if (!fsr23CamStillExact) return;
                int[][] cands = {
                    {rw / 2, rh / 2},
                    {rw * 2 / 3, rh * 2 / 3},
                    {rw * 3 / 8, rh * 3 / 8},
                    {rw * 5 / 8, rh * 1 / 2},
                    {rw * 1 / 2, rh * 3 / 4}
                };
                if (!irisProbeLocChosen) {
                    int bestX = cands[0][0], bestY = cands[0][1];
                    float bestVar = -1f;
                    for (int[] c : cands) {
                        int cx = Math.max(0, Math.min(rw - s, c[0]));
                        int cy = Math.max(0, Math.min(rh - s, c[1]));
                        byte[] blk = readTexBlockRgba8(renderTex, cx, cy, s, s);
                        if (blk == null) {
                            if (irisProbeReadFailed()) return;
                            continue;
                        }
                        float v = irisProbeVariance(blk, s, s);
                        if (v > bestVar) { bestVar = v; bestX = cx; bestY = cy; }
                    }
                    irisProbeX = bestX;
                    irisProbeY = bestY;
                    irisProbeLocChosen = true;
                }
                byte[] blk = readTexBlockRgba8(renderTex, irisProbeX, irisProbeY, s, s);
                if (blk == null) {
                    if (irisProbeReadFailed()) return;
                    irisProbeLocChosen = false;
                    return;
                }
                if (irisProbeVariance(blk, s, s) < 30f) {
                    // Too flat at the chosen location (or the read failed) —
                    // re-pick on the next still frame. Cap so we never loop.
                    irisProbeLocChosen = false;
                    if (++irisProbeAttempts > IRIS_PROBE_MAX_ATTEMPTS) {
                        irisJitterProbeDone = true;
                        FSRMod.LOGGER.info("[IRIS-JITTER] probe gave up after {} attempts (no textured static region) — assuming pack consumes vanilla jitter (captureAndJitter fired)",
                            irisProbeAttempts);
                    }
                    return;
                }
                irisProbeBlock0 = blk;
                irisProbeJx0 = jitterX;
                irisProbeJy0 = jitterY;
                irisProbeArmed = true;
                return;
            }

            // Armed: wait for a still frame whose jitter differs enough from the
            // first sample's (some consecutive Halton pairs are nearly equal).
            if (!fsr23CamStillExact) return;
            float jd = Math.abs(jitterX - irisProbeJx0) + Math.abs(jitterY - irisProbeJy0);
            if (jd < 0.25f) return;

            byte[] blk1 = readTexBlockRgba8(renderTex, irisProbeX, irisProbeY, s, s);
            if (blk1 == null) { if (irisProbeReadFailed()) return; irisProbeArmed = false; return; }

            float zeroMad = irisProbeMad(irisProbeBlock0, blk1, s, s, 0, 0);
            if (zeroMad < 0f) { irisProbeArmed = false; return; }

            // Scene changed too much between samples (entity walked in, cloud
            // shadow, lighting flash) — the pair is unusable. Re-arm with the
            // new block as the baseline and try again.
            if (zeroMad > 8.0f) {
                irisProbeBlock0 = blk1;
                irisProbeJx0 = jitterX;
                irisProbeJy0 = jitterY;
                if (++irisProbeAttempts > IRIS_PROBE_MAX_ATTEMPTS) {
                    irisJitterProbeDone = true;
                    FSRMod.LOGGER.info("[IRIS-JITTER] probe gave up after {} attempts (scene too dynamic) — assuming pack consumes vanilla jitter",
                        irisProbeAttempts);
                }
                return;
            }

            // Search the best integer shift in -2..2. A jittered render shows a
            // clear MAD minimum at the true shift; an unjittered (or flat) one
            // shows no improvement over (0,0).
            float bestMad = zeroMad;
            int bestDx = 0, bestDy = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    float m = irisProbeMad(irisProbeBlock0, blk1, s, s, dx, dy);
                    if (m >= 0f && m < bestMad) { bestMad = m; bestDx = dx; bestDy = dy; }
                }
            }
            int mag = Math.max(Math.abs(bestDx), Math.abs(bestDy));
            if (mag >= 1 && bestMad < zeroMad * 0.8f) {
                irisJitterProbeDone = true;
                irisJitterBlind = false;
                FSRMod.LOGGER.info("[IRIS-JITTER] probe: pack CONSUMES vanilla jitter — composite shifted by ({},{})px on jitter delta ({},{}) render px; MAD {}" +
                        " -> {} (frame {})",
                    bestDx, bestDy,
                    String.format(java.util.Locale.ROOT, "%.2f", jitterX - irisProbeJx0),
                    String.format(java.util.Locale.ROOT, "%.2f", jitterY - irisProbeJy0),
                    String.format(java.util.Locale.ROOT, "%.2f", zeroMad),
                    String.format(java.util.Locale.ROOT, "%.2f", bestMad),
                    timingFrameCount);
                return;
            }

            // Static pair with no measurable shift — a blind vote.
            if (++irisProbeBlindVotes >= IRIS_PROBE_BLIND_VOTES) {
                irisJitterProbeDone = true;
                irisJitterBlind = true;
                FSRMod.LOGGER.warn("[IRIS-JITTER] probe: pack is JITTER-BLIND — composite did not move across {} static pairs (jitter deltas up to {} render px); zeroing jitter for spatial accumulation (sticky for session)",
                    irisProbeBlindVotes,
                    String.format(java.util.Locale.ROOT, "%.2f", jd));
                return;
            }
            // Otherwise re-arm for another pair.
            irisProbeBlock0 = blk1;
            irisProbeJx0 = jitterX;
            irisProbeJy0 = jitterY;
            if (++irisProbeAttempts > IRIS_PROBE_MAX_ATTEMPTS) {
                irisJitterProbeDone = true;
                FSRMod.LOGGER.info("[IRIS-JITTER] probe hit the attempt cap ({}) without a verdict — assuming pack consumes vanilla jitter",
                    irisProbeAttempts);
            }
        } catch (Throwable t) {
            irisJitterProbeDone = true;
            FSRMod.LOGGER.warn("[IRIS-JITTER] probe threw: {} — assuming pack consumes vanilla jitter", t.toString());
        }
    }

	    // Runtime override: DH detected forces FSR1 (DH depth not available to FSR2/3)
    private boolean dhActive;

    // FG alternation: alternate between presenting real frame and FG interpolated frame
    private boolean fgFlipState;
    private int fgCycleCounter;
    private int fgRealFramesSaved;
    private long lastFrameTimeNs;
    private float lastFrameDeltaMs = 16.6f;  // inter-frame ms — also the FSR4/FSR3 frameTimeDelta (2026-08-08)
    private boolean fgSkipNextRender;

    // TARGET-FPS AUTO-QUALITY (2026-08-08): render-loop frame-time EMA feeding
    // the auto-quality adjuster (updateAutoQuality). Measured at the top of
    // onRenderBegin for ALL renderers (before the Iris/VulkanMod early returns);
    // the adjuster nudges config.qualityScale in 0.05 steps on a ~2s cooldown to
    // hit config.targetFps (0 = off). FG modes, Iris, menus/pause, and the title
    // screen are excluded. Changes route through applyAutoScale() (the same
    // light path as the quality slider) so FSR4 uses the dynamic-res pool-only
    // recreate instead of a full context teardown.
    private long autoPrevFrameNs;
    private float autoEmaFrameMs;
    private long lastAutoAdjustMs;
    private static final float AUTO_Q_EMA_ALPHA = 0.10f;   // ~10-frame window
    // Min time between steps. 1500ms (2026-08-08): 2 confirmations complete in
    // ~3s and steps can't stack faster than ~3s apart — still far below the
    // oscillation period that caused v1's every-2s zooming, while a sustained
    // storm starts reacting ~1s sooner (the previous 2000ms + 2-streak meant
    // the FIRST tweak took ~4s of sustained low fps).
    private static final long AUTO_Q_INTERVAL_MS = 1500L;  // min time between steps
    // Wide hysteresis + confirmation streak (2026-08-08 v2, "zooming in by the
    // second" fix): v1 used a tight ±6% band and acted on a single reading — a
    // 0.05 step on this iGPU changes fps by more than the band, so it raised,
    // fps dropped, it lowered, fps rose → a LIMIT CYCLE of 0.05 steps every 2s
    // (each one a full FSR4 pool recreate + history reset = visible pops). Now
    // the band is ±10% and a change requires AUTO_Q_CONFIRM_INTERVALS (2)
    // CONSECUTIVE out-of-band readings in the same direction (~4s), so a single
    // measurement wobble or a transient recreate hiccup can't trigger.
    private static final float AUTO_Q_LOW = 0.90f;         // below target*0.90 → lower quality
    private static final float AUTO_Q_HIGH = 1.10f;        // above target*1.10 → raise quality
    private static final float AUTO_Q_STEP = 0.05f;
    private static final int AUTO_Q_CONFIRM_INTERVALS = 2;
    private int autoLastDirection;      // 0 none, -1 lowering, +1 raising
    private int autoOutOfBandStreak;
    // Plateau guard (2026-08-08, review + v2): if repeated quality-lowering never
    // reaches the target, the target is UNREACHABLE (external cap such as MC's
    // framerate limit, or fixed costs that dwarf render-scale savings — FSR4's
    // display-res round trip on an iGPU). Grinding to the 25% floor hurts quality
    // for zero gain, so after AUTO_Q_MAX_LOWER_STREAK consecutive lowers without
    // recovery we hold for a LONG time (5 min) before retrying — and the same
    // hold engages immediately if the floor (0.25) is reached while still below.
    private int autoLowerStreak;
    private long autoPlateauHoldUntilMs;
    private static final int AUTO_Q_MAX_LOWER_STREAK = 4;
    private static final long AUTO_Q_PLATEAU_RETRY_MS = 300_000L; // 5 min hold

    // Jittered projection matrix (set per-frame from renderLevel projection)
    private final Matrix4f lastProj = new Matrix4f();
    private final Matrix4f lastProjJittered = new Matrix4f();  // Jittered version for MV generation

    // VP matrix tracking for motion vector generation
    private final Matrix4f currViewProj = new Matrix4f();
    private final Matrix4f prevViewProj = new Matrix4f();
	    private boolean hasPrevFrame;
	
	    // Previous camera position for camera delta in motion vector generation
	    private double prevCamPosX, prevCamPosY, prevCamPosZ;

	    // Motion-adaptive jitter EMA (FSR2/3): slow EMAs of the camera position and
	    // orientation; the deviation from them detects sustained translation (walking/
	    // sprinting) and rotation (looking around) while the periodic idle bob oscillates
	    // around the EMA center and cancels. Consumed in onRenderBegin's FSR2/3 jitter
	    // block (2026-08-06 "still shaking" fix).
	    private double fsr23EmaCamX, fsr23EmaCamY, fsr23EmaCamZ;
	    private final Quaternionf fsr23EmaCamQ = new Quaternionf();
	    private boolean fsr23EmaCamInit;
	    // Instantaneous per-frame camera motion (fast-response jitter shrink).
	    private double fsr23PrevCamPosX, fsr23PrevCamPosY, fsr23PrevCamPosZ;
	    private boolean fsr23PrevCamPosInit;
	    // Sky/cloud still-camera accumulation gate (2026-08-06, "sky is blocky"
	    // fix): computed in the FSR2/3 jitter block from the SAME EMA deviation
	    // signals as the motion-adaptive jitter. When the camera is genuinely
	    // still (idle bob only — deviation well below walking/looking), the
	    // ACCUMULATE shader lets sky/cloud/sun pixels accumulate like terrain
	    // (uCamStill=1): constant-depth MVs are exact when the camera doesn't
	    // move, so history aligns and the 4x-upscaled cloud/sun edges reconstruct
	    // from sub-pixel jitter samples instead of staying blocky. Thresholds:
	    // camDev < 0.05 blocks (walking steady-state lag is ~0.65) AND dRot <
	    // 0.02 rad (bob ~0.002-0.005; a sustained look drives the EMA lag > 0.02).
	    private boolean fsr23CamStill;
	    private boolean fsr23CamStillExact;
	    // OUTPUT-SHAKE PROBE (2026-08-08): one-shot burst of ~45 samples (every
	    // other frame) starting at fsr2FrameCount==300, reading the PRESENTED FSR2
	    // output texture (post-RCAS) at hand/center/sky texels. If the output
	    // oscillates with the jitter period while standing still, the shake lives
	    // in the accumulate/blend; if flat, it is downstream or the hand/HUD.
	    private int fsr23OutputProbeFrames = -1;
	    private float fsr23CamReactive;
	    private long fsr23LastMotionNanos;   // frame-time source for per-tick normalization
    // v2 (2026-08-06, "nothing changed" fix): previous frame's camera rotation,
    // for the PER-FRAME rotation delta in the camStill computation below. v1's
    // EMA-only signal took ~30 frames to converge after any mouse look AND was
    // gated on camera translation (excluded walking entirely) - in real play
    // uCamStill almost never engaged. The per-frame delta engages within 1 frame
    // of the mouse stopping.
    private final Quaternionf fsr23PrevCamRot = new Quaternionf();
    // Edge-triggered [CAMSTILL] transition log (min 2s between lines so a
    // threshold-oscillating camera can't spam).
    private boolean fsr23CamStillLogged;
    private long lastCamStillLogMs;
    // Fast-motion jitter verification throttle (2026-08-07): the [FAST-JITTER]
    // log fires at most once per 120 frames (~2s) to avoid spam during sustained
    // sprint/fly motion. Tracks the timingFrameCount of the last emission.
    private int lastFastMotionDiagFrame;
	    // FSR4 cutout-detector camera-motion gate (2026-08-06): the previous
	    // frame's camera rotation quaternion, used to measure real player motion
	    // (position + rotation deltas) so the flower/grass cutout detector can
	    // stay OFF while the idle bob produces 0.5-2.5px MVs. NaN = no previous
	    // frame yet (first frame counts as moving so the detector engages safely).
	    private double prevCamQX = Double.NaN, prevCamQY, prevCamQZ, prevCamQW;
	    private boolean fsr4CamMoving;
	    // Camera-motion reactive floor (2026-08-13, FSR2/3 uCamReactive parity):
	    // 0..0.9 floor uploaded as uCamReactive. Computed in
	    // generateFSR4MotionVectors from the same per-frame deltas as uCamMotion,
	    // translation normalized to per-tick via measured frame time (FSR2/3's
	    // proven FPS-independent units). fsr4PrevMotionNanos feeds the dt.
	    private float fsr4CamReactive;
	    private long fsr4PrevMotionNanos;

    // Reusable float arrays (avoid heap alloc every frame)
    private final float[] jitterResult = new float[2];

    // Reusable matrix/quaternion for view matrix construction (avoid alloc per frame)
    private final Matrix4f tmpView = new Matrix4f();
    private final Quaternionf tmpQuat = new Quaternionf();

    public FSRProcessor(FSRConfig config) {
        this.config = config;
        this.jitterManager = new JitterManager();
    }

    private boolean isFsr2() {
        return !isIntelGpu() && !dhActive && config.version != null && config.version == FSRConfig.FSRVersion.FSR2;
    }

    private boolean isFsr3() {
        return !isIntelGpu() && !dhActive && config.version != null
            && (config.version == FSRConfig.FSRVersion.FSR3_UPS
                || config.version == FSRConfig.FSRVersion.FSR3_FG
                // FSR4-WITHOUT-SDK FALLBACK (2026-08-14, Linux compatibility):
                // the AMD FSR4 SDK is Windows/DX12-only, so on Linux a FSR4
                // selection routes here — the mod's own GLSL FSR3 temporal
                // engine (jitter + MV gen + reactive mask + temporal
                // accumulation, the closest cross-platform FSR4-class
                // upscaler). isFsr4() is false in this case, so every
                // SDK-specific block (D3D12 pool, dispatch, notifications)
                // stays inert and the FSR3 temporal pipeline handles the
                // frame end-to-end. Also covers a Windows box where the SDK
                // DLLs are missing.
                || (config.version == FSRConfig.FSRVersion.FSR4 && !fsr4SdkSupported()));
    }

    private boolean isFrameGen() {
        return !isIntelGpu() && !dhActive && config.version != null && config.version == FSRConfig.FSRVersion.FSR3_FG;
    }

    private boolean isFrameGenDx12() {
        return !isIntelGpu() && !dhActive && config.version != null && config.version == FSRConfig.FSRVersion.FSR3_FG_DX12;
    }

    /**
     * True when frame generation should actually RUN — the version is
     * FSR3_FG_DX12 and it has not been switched off for the session.
     *
     * Distinct from {@link #isFrameGenDx12()} on purpose: that method is the
     * pure VERSION check and also gates the FSR4 context/pool init
     * (`isFsr4() || isFrameGenDx12()`). Making it return false when FG is
     * disabled would skip FSR4 init entirely and drop FSR3_FG_DX12 to the FSR1
     * spatial fallback. So the version check stays true and only the
     * FG-SPECIFIC steps (SDK init, param stash, double-present) consult this,
     * which degrades FSR3_FG_DX12 to plain FSR4 upscaling — the desired
     * behaviour when FG is switched off.
     *
     * Before 2026-08-29 `fg4PermanentlyDisabled` (set by
     * noteD3D12RuntimeFailure after FG_MAX_RUNTIME_FAILURES) was read ONLY by
     * isFrameGenAvailable() — i.e. by the settings screen — so the "too many
     * consecutive failures, disabling FG DX12" escalation never actually
     * disabled anything.
     */
    private boolean fgActive() {
        // !fsr4HardDisabled (2026-08-30, "freezes at the first frame"): FG
        // interpolates from the FSR4 upscale output (pool 4) on the SAME D3D12
        // device. When FSR4 hard-disables (5 consecutive dispatch failures =
        // device presumed removed/broken), FG must stop too — leaving it active
        // makes it hammer the broken device every frame (nativeFinish's 5000ms
        // fence wait) while the session renders FSR1: the render thread stalls
        // ~5s per frame = the observed freeze with the game "running fine in
        // the background". A hard-disabled session degrades to the D3D12-free
        // FSR1 spatial render; a deliberate settings change (markDirty) clears
        // fsr4HardDisabled, which re-arms both FSR4 and FG.
        // !fgIgpuUnsupported (2026-08-30, "still completely frozen"): on AMD
        // iGPUs the FG SDK context removes the D3D12 device at the first frame
        // (no crash can be avoided — even the bilinear bypass fails because the
        // device is gone before it runs). FG is never created there; the session
        // runs plain real FSR4 instead.
        return isFrameGenDx12() && !fg4PermanentlyDisabled && !fsr4HardDisabled && !fgIgpuUnsupported;
    }

    /**
     * FG_DX12 is not viable on AMD integrated GPUs (2026-08-30, "still
     * completely frozen while the game runs fine in the background"). The FG
     * SDK's ffxCreateContext removes the D3D12 device at the first frame when it
     * coexists with the FSR4 context on these parts (Radeon 860M) — verified by
     * three builds: FSR4 SDK dispatch crashes then the device is removed;
     * pre-armed bilinear bypass still fails because the device is already gone
     * (CPU-only CreateRootSignature returns DXGI_ERROR_DEVICE_REMOVED 0x887a0005)
     * before it ever runs. Called from the FSR4 init-success sites (GL + VKMod):
     * keeps the FG context from ever being created, so the session runs plain
     * real FSR4 (real temporal upscale, no crash). Discrete AMD GPUs
     * (isAmdIntegratedGpu false) keep real FSR4 + FG. Cleared on a deliberate
     * settings change (markDirty) so switching to another version re-evaluates.
     */
    /**
     * Pre-arm the FSR4 bilinear bypass for Frame Generation on AMD iGPUs
     * (2026-08-31, "make FSR3 FG DX12 work with shaders").
     *
     * On an integrated AMD GPU the FSR4 SDK's {@code g_ffxDispatch} throws
     * {@code 0xe06d7363} deterministically once an FG {@code ffxCreateContext}
     * exists on the device — and that throw itself removes the D3D12 device, so
     * arming the bypass AFTER the crash (the SEH fall-back) is too late: every
     * subsequent D3D12 call fails and the session collapses to the FSR1 spatial
     * fallback with FG dead.
     *
     * {@code Fsr4Native.armBypass()} is already implemented natively for exactly
     * this (its own comment says "Java pre-arms this flag BEFORE the first
     * dispatch") but no Java caller survived the FG re-arm refactor, so the
     * pre-arm was dead code and the SDK dispatch ran and crashed every session.
     * Arming up front means {@code g_ffxDispatch} is never invoked: the device
     * stays healthy, the bilinear upscale fills pool 4, and FG interpolates
     * normally. Discrete AMD GPUs and FG-off sessions keep the real FSR4 SDK.
     *
     * Must be called AFTER {@link #maybeDisableFgOnIgpu()} so {@code fgActive()}
     * reflects whether FG will actually initialize, and after a successful
     * initFsr4 so the pool textures the bypass writes into exist.
     */
    private void maybePreArmBypassForFgIgpu() {
        if (!fgActive()) return;
        if (!Fsr4Native.isAmdIntegratedGpu()) return;
        Fsr4Native.armBypass();
        if (!fsr4BypassNotified) {
            fsr4BypassNotified = true;
            FSRMod.LOGGER.warn("[FG_DX12] FSR4 SDK upscale pre-empted on this AMD iGPU — using the "
                + "bilinear upscale so the FG context cannot remove the D3D12 device. "
                + "Frame Generation stays ACTIVE (upscale quality is bilinear, not FSR4).");
        }
    }

    private void maybeDisableFgOnIgpu() {
        if (isFrameGenDx12() && Fsr4Native.isAmdIntegratedGpu()) {
            // RE-ARM OVERRIDE (2026-08-30, "make the framegen work properly"):
            // the user reports FSR3_FG_DX12 is compatible with this GPU. When
            // config.fsr3FgAllowIgpuDx12 is ON (default), do NOT set the
            // fgIgpuUnsupported block — let FG init and run. The rest of the
            // fail-degradation chain (fg4PermanentlyDisabled after
            // FG_MAX_RUNTIME_FAILURES, the fence-stall watchdog, and
            // fsr4HardDisabled) still guards a genuine device-removal by
            // degrading to plain real FSR4 — never a crash-loop or freeze.
            if (config.fsr3FgAllowIgpuDx12)
                return;
            fgIgpuUnsupported = true;
            if (!fgIgpuUnsupportedNotified) {
                fgIgpuUnsupportedNotified = true;
                FSRMod.LOGGER.warn("[FG_DX12] Frame Generation DX12 is not supported on this AMD "
                    + "integrated GPU — the FG SDK removes the D3D12 device when it runs "
                    + "alongside the FSR4 upscaler. Running plain FSR4 instead (smooth "
                    + "temporal upscaling, no frame doubling).");
            }
        }
    }

    private boolean isFsr4() {
        return !isIntelGpu() && !dhActive && config.version != null
            && config.version == FSRConfig.FSRVersion.FSR4
            // Only the real SDK path counts as FSR4. When the SDK is
            // unavailable (Linux — the FSR4 SDK is Windows/DX12-only — or
            // missing DLLs) isFsr4() is false and isFsr3() picks up the
            // selection with the mod's FSR3 temporal engine.
            && fsr4SdkSupported();
    }

    private boolean isTemporalUpscaler() {
        return isFsr2() || isFsr3() || isFsr4() || isFrameGenDx12();
    }

    /**
     * True when this frame is handled by the FSR1 spatial fallback instead of
     * the FSR4/FSR2/FSR3 temporal pipelines: FSR1 configured, DH active, FSR4
     * suspended by the GPU-saturation guard, or FSR4/FG selected but not yet
     * initialized. Used by onRenderBegin (zero the jitter — the spatial path
     * has no de-jitter pass) and by onSceneRendered (route the dispatch).
     * Kept as one helper so the two sites can never drift apart.
     */
    /**
     * Calm frames required before FSR4 can resume from a suspension — grows
     * with repeated rapid storm episodes (see fsr4RapidEpisodes field doc).
     */
    private int fsr4CalmFramesNeeded() {
        return Math.min(FSR4_WORLD_CALM_FRAMES * (1 + fsr4RapidEpisodes), 60);
    }

    /**
     * Record a suspension of the flappable class (pre-check spikes, sync
     * timeouts, dispatch failures). If it follows the last resume within
     * FSR4_RAPID_WINDOW_MS, the episode count grows so the next resume
     * requires more calm — the blink->park transition.
     */
    private void fsr4RecordSuspension() {
        long now = System.currentTimeMillis();
        if (fsr4ResumeAtMs != 0 && now - fsr4ResumeAtMs < FSR4_RAPID_WINDOW_MS) {
            fsr4RapidEpisodes = Math.min(fsr4RapidEpisodes + 1, 5);
        } else {
            fsr4RapidEpisodes = 0;
        }
    }

    private boolean spatialFallbackActive() {
        return config.version == FSRConfig.FSRVersion.FSR1 || dhActive || fsr4Suspended
            || fsr4HardDisabled   // (2026-08-30) hard-disable must route to FSR1 so the frame renders FRESH — the crash-loop freeze fix
            || ((isFsr4() || isFrameGenDx12()) && !fsr4Initialized)
            || fsr4ParityBypassActive();
    }

    /**
     * PARITY BYPASS (2026-08-06, "FSR4 only gains FPS below 40%" fix): at
     * >=90% render scale FSR4's upscale is at most 1.11x, but its fixed
     * display-res round trip (D3D12 dispatch + external-memory output copy +
     * GL/D3D12 sync) costs ~8-12ms on an iGPU — more than the render savings
     * at 90% (19% fewer pixels). The temporal upscaler cannot win at
     * near-parity scales, so route through the cheap FSR1 blit+RCAS instead
     * (no D3D12, no external memory, no fence). FSR4 stays initialized, so
     * dropping the scale below the threshold re-engages the temporal pipeline
     * instantly. Being part of spatialFallbackActive() keeps the two consumers
     * consistent: the jitter zeroing in onRenderBegin (the spatial path has no
     * de-jitter pass) and the FSR1 routing in onSceneRendered.
     * GL-path only — VulkanMod has its own render-scale/blit machinery.
     */
    private boolean fsr4ParityBypassActive() {
        if (!isFsr4() || isFrameGenDx12()) return false;
        if (VulkanModCompat.isActive()) return false;
        if (!config.fsr4ParityBypass) return false;
        return scaledWidth >= displayWidth * 0.9f
            && scaledHeight >= displayHeight * 0.9f;
    }

    // Previous frame's parity-bypass state. A false->true transition is free
    // (the FSR1 path handles presentation); true->false (scale dropped below
    // the threshold) must force a clean temporal history rebuild —
    // hasPrevFrame/prevViewProj froze while bypassed, so reprojecting that
    // stale history on the first FSR4 dispatch would ghost for a frame.
    private boolean fsr4ParityBypassPrev;

    /**
     * Sends an in-game chat message (same channel as the mod's FSR
     * enable/disable notifications). Player may be null briefly during world
     * load, so guard. Messages are yellow so they stand out from regular chat.
     */
    private void notifyFsr4Fallback(String msg) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("\u00a7e[FSR] " + msg + "\u00a7r"),
                    false);
            }
            FSRMod.LOGGER.info("FSR4 fallback notification: {}", msg);
        } catch (Throwable t) {
            // Notification is best-effort — never let it disturb the render path.
        }
    }

    private static Boolean vulkanAvailable = null;

    private static boolean checkVulkanAvailable() {
        // Cache the result — only attempt Vulkan init once (at first frame).
        // Without caching, systems without Vulkan would trigger a failed
        // delay-load + SEH exception on every frame.
        if (vulkanAvailable != null) return vulkanAvailable;
        if (!VulkanInterop.isAvailable()) {
            vulkanAvailable = false;
            return false;
        }
        try {
            String info = VulkanInterop.getDeviceInfo();
            vulkanAvailable = info != null && !info.isEmpty();
        } catch (Exception e) {
            vulkanAvailable = false;
        }
        return vulkanAvailable;
    }

    public void setProjection(Matrix4f proj) {
        lastProj.set(proj);
    }

    /**
     * Called from the @ModifyArg on the world RenderSystem.setProjectionMatrix
     * invocation inside renderLevel. Saves the UNJITTERED projection (used to
     * build the view-projection matrices for motion-vector generation) and
     * returns a jittered COPY that becomes the active GPU projection, so the
     * scene actually renders with the sub-pixel Halton jitter that the FSR2/3
     * accumulate shader de-jitters. Returns the original unchanged when jitter
     * does not apply (non-temporal mode, outside a frame, or bad dimensions).
     */
    public Matrix4f captureAndJitter(Matrix4f proj) {
        // Always capture the clean projection for MV / camera-param extraction.
        lastProj.set(proj);

        if (!isTemporalUpscaler()) {
            lastProjJittered.set(proj);  // No jitter, but keep lastProjJittered in sync
            return proj;
        }
        if (!insideFrame) {
            lastProjJittered.set(proj);
            return proj;
        }
        float[] jit = clipSpaceJitter();
        if (jit == null) {
            lastProjJittered.set(proj);
            return proj;
        }

        // Apply jitter as a translation in clip space (matches Purescale's proven approach).
        // This is different from adding to m02/m12 — translation affects m30/m31/m32 and is
        // automatically handled by the inverse view-projection matrix, so the MV shader can
        // use raw UV coordinates without manual de-jittering.
        Matrix4f jittered = new Matrix4f();
        jittered.translation(jit[0], jit[1], 0.0f).mul(proj);
        lastProjJittered.set(jittered);  // Save jittered version for MV generation
        jitterAppliedThisFrame = true;
        return jittered;
    }

    /**
     * Current frame's Halton jitter as a clip-space translation {px, py} in
     * NDC units (2*jitter/renderSize), or null when jitter is inactive or
     * zeroed (non-temporal mode, bad dimensions, or the FSR1 spatial fallback
     * zeroed it in onRenderBegin). Shared by {@link #captureAndJitter} (world
     * projection) and {@link #jitterHandProjection} (first-person hand) so the
     * two sites can never drift apart.
     */
    private float[] clipSpaceJitter() {
        if (scaledWidth < 16 || scaledHeight < 16) return null;
        float px = jitterX * 2.0f / scaledWidth;
        float py = jitterY * 2.0f / scaledHeight;
        if (px == 0.0f && py == 0.0f) return null;
        return new float[] {px, py};
    }

    /**
     * Applies the same clip-space translation jitter as {@link #captureAndJitter}
     * to an arbitrary projection matrix — used for the first-person hand's
     * projection. In 26.1.2 the hand renders INSIDE renderLevel (after the world)
     * but uploads its matrix through a DIFFERENT path than the world:
     * {@code hud3dProjectionMatrixBuffer.getBuffer(Projection)} (a Projection
     * object) instead of {@code levelProjectionMatrixBuffer.getBuffer(Matrix4f)}
     * which {@code captureAndJitter} hooks. The hand was therefore rendered
     * UNJITTERED while the world shifted ±0.5px per frame — FSR's temporal
     * reconstruction could not align the hand region with history, so the SDK
     * discarded it and the raw spatial upscale showed through as jagged edges
     * on the fist and held items while terrain stayed smooth.
     *
     * Jittering the hand matrix shifts it with the world, so the whole frame
     * satisfies the SDK's uniform-jitter expectation. Deliberately does NOT
     * touch {@code jitterAppliedThisFrame} — that guard belongs to the world
     * projection (already applied by the time the hand uploads) and must not
     * block this separate matrix.
     *
     * Known limitation (not a regression): the hand is static in view space, so
     * its reprojection MV ≈ 0 and hand bob/swing animation motion is absent
     * from the MV field — expect minor smear on the hand during swing, inherent
     * to FSR temporal integration (same as every FSR2/4 game integration).
     */
    public void jitterHandProjection(Matrix4f m) {
        if (!isTemporalUpscaler()) return;
        if (!insideFrame) return;
        float[] jit = clipSpaceJitter();
        if (jit == null) return;  // non-temporal, bad dims, or spatial fallback zeroed the jitter
        Matrix4f jittered = new Matrix4f();
        jittered.translation(jit[0], jit[1], 0.0f).mul(m);
        m.set(jittered);
    }

    /**
     * VulkanMod renders the world with its OWN copy of the projection matrix
     * (VRenderSystem.projection), which its GameRendererMixin captures from the
     * ORIGINAL unjittered local variable via @Local at the
     * RenderSystem.setProjectionMatrix INVOKE — NOT from the vanilla GPU buffer
     * slice. Our @ModifyArg on ProjectionMatrixBuffer.getBuffer only replaces
     * the ARGUMENT (so the vanilla buffer slice is jittered) but the local var
     * keeps the original matrix, so VulkanMod's chunk renderer
     * (LevelRendererMixin -> WorldRenderer.renderSectionLayer -> applyMVP)
     * renders the world UNJITTERED while the FSR4 SDK is told jitter=(prevJitter).
     * Phantom jitter: the SDK de-jitters a frame that was never jittered -> output
     * shifts every frame (shake, amplified by motion) and history reprojection
     * mismatches -> SDK discards history -> spatial-only (pixelated, no temporal
     * edge smoothing — the exact reported symptom).
     *
     * Fix: after VulkanMod's GameRendererMixin runs (our mixin handler has a
     * LOWER mixin priority so it applies AFTER theirs at the same INVOKE),
     * overwrite VRenderSystem.projection with our jittered matrix so the world
     * actually renders jittered, matching what the SDK receives.
     */
    // Cached reflection handles for the VulkanMod projection override (avoid a
    // class hierarchy scan every frame). Filled lazily, guarded by try/catch so
    // a VulkanMod rename degrades to a no-op (logged once) instead of a crash.
    // 26.1.2-era VulkanMod: static field 'projection' (Matrix4f).
    // 0.6.8+1.21.11: applyProjectionMatrix(Matrix4f) + getProjectionMatrix()
    // (MappedBuffer) — see also vkModApplyProjection/vkModGetProjection.
    private static java.lang.reflect.Field vkModProjectionField;
    private static java.lang.reflect.Method vkModApplyProjection;
    private static java.lang.reflect.Method vkModGetProjection;		    private static boolean vkModProjectionOverrideFailedLogged;
		    private static boolean vkModProjectionOverrideFiredLogged;	    private boolean vulkanModMvProjLogged;
		    private static int vkModProjectionOverrideFires = 0;
		    private static int vkModProjectionOverrideSkips = 0;
		    private static int vkModProjectionOverrideLogTicker = 0;
		    private static boolean vkModProjectionAfterWriteLogged;
		    public static boolean vkModProjectionAfterRenderLogged;
	    private boolean vulkanModForceSpatialLogged;
	    private boolean fsr4ExtremeScaleWarned;
	    // VKMod cam-motion tracking (2026-08-08 "terrain still smears" fix): the
	    // no-real-depth MV-gen mode needs a CPU translation signal because its
	    // constant-depth MVs are ~0 during a walk (far-plane assumption) — the
	    // SDK would trust them and smear. eyeSpeed is FPS-independent (blocks/sec;
	    // per-frame deltas shrink at high FPS); rotDelta is the frame-to-frame
	    // rotation in radians. Same source as the GL path's uCamMotion flag.
	    private boolean hasPrevVkCam;
	    private double prevVkCamX, prevVkCamY, prevVkCamZ;
	    private final Quaternionf prevVkCamRot = new Quaternionf();
	    private long lastVkCamNano;

    public void applyVulkanModProjectionOverride() {
        // isLoaded(), NOT isActive(): VulkanMod replaces the GL pipeline whenever
        // it is loaded, so its chunk renderer reads VRenderSystem.projection
        // regardless of whether OUR D3D12 external-memory interop is live
        // (isActive() additionally requires VulkanInterop + external memory — if
        // those failed we'd fall back to FSR1 anyway, but the guard must not
        // depend on it). When jitter is zeroed the world renders unjittered and
        // the SDK gets jitter=0 — consistent — so no override needed either way.
        if (!VulkanModCompat.isLoaded()) return;
		        // Only override when we actually applied jitter this frame. When jitter is
		        // zeroed (FSR1 spatial fallback / suspended / not initialized), VulkanMod's
		        // own captured matrix is correct (unjittered == what the SDK expects).
		        if (!jitterAppliedThisFrame) {
		            // DIAG (2026-08-09): count skips so a phantom-jitter diagnosis can
		            // distinguish "override never fires" from "override fires but the
		            // render ignores it". Logged periodically below.
		            vkModProjectionOverrideSkips++;
		            maybeLogVkModOverrideDiag();
		            return;
		        }
		        vkModProjectionOverrideFires++;
        try {
            if (vkModProjectionField == null) {
                Class<?> vrs = Class.forName("net.vulkanmod.vulkan.VRenderSystem");
                // 1.21.11 port: VulkanMod 0.6.8+1.21.11 replaced the static
                // Matrix4f 'projection' field with a MappedBuffer projectionMatrix
                // + applyProjectionMatrix(Matrix4f). We cache the METHOD instead
                // of the field; the old 26.1.2-era field name is tried first and
                // gracefully degrades (the write still works via applyProjectionMatrix).
                try {
                    vkModProjectionField = vrs.getField("projection");
                } catch (NoSuchFieldException e) {
                    vkModProjectionField = null;
                }
                vkModApplyProjection = vrs.getMethod("applyProjectionMatrix", Matrix4f.class);
                vkModGetProjection = vrs.getMethod("getProjectionMatrix");
            }
            Matrix4f curProj = null;
            if (vkModProjectionField != null) {
                Object cur = vkModProjectionField.get(null);
                if (cur instanceof Matrix4f m) curProj = m;
            } else {
                // .21.11: read back through getProjectionMatrix() -> MappedBuffer.buffer
                Object mb = vkModGetProjection.invoke(null);
                if (mb != null) {
                    java.nio.ByteBuffer bb = (java.nio.ByteBuffer) mb.getClass().getField("buffer").get(mb);
                    if (bb != null) {
                        curProj = new Matrix4f().set(bb.asFloatBuffer());
                    }
                }
            }
            if (curProj == null) {
                if (!vkModProjectionOverrideFailedLogged) {
                    vkModProjectionOverrideFailedLogged = true;
                    FSRMod.LOGGER.warn("FSR4: VulkanMod projection override failed: no Matrix4f projection available");
                }
                return;
            }
            // Jitter VulkanMod's OWN matrix in place (translation in clip space,
            // identical to captureAndJitter) instead of substituting our vanilla
            // lastProjJittered — VulkanMod may have built its projection with an
            // infinite far plane (CameraMixin.infiniteZFar), and preserving its
            // exact matrix avoids any far-plane/fog divergence. The clip-space
            // jitter offset is what the FSR4 SDK receives, so only the translation
            // must match — the near/far content is irrelevant to the SDK's
            // output-grid alignment.
            float[] jit = clipSpaceJitter();
            if (jit == null) return;
            Matrix4f jittered = new Matrix4f();
            jittered.translation(jit[0], jit[1], 0.0f).mul(curProj);
            if (vkModProjectionField != null) {
                vkModProjectionField.set(null, jittered);
            } else {
                vkModApplyProjection.invoke(null, jittered);
            }		            if (!vkModProjectionOverrideFiredLogged) {
		                vkModProjectionOverrideFiredLogged = true;
		                FSRMod.LOGGER.info("VKMod projection override: applied jitter=({}, {}) render-px to VRenderSystem.projection — world renders jittered, matching the FSR4 SDK's jitterOffset",
		                    String.format(java.util.Locale.ROOT, "%.4f", jitterX),
		                    String.format(java.util.Locale.ROOT, "%.4f", jitterY));
		            }
		            // DIAG two-point trace: log the field right after we write it
		            // (one-shot per session is enough — it proves the write value).
		            if (!vkModProjectionAfterWriteLogged) {
		                vkModProjectionAfterWriteLogged = true;
		                logVkModProjectionState("after-override");
		            }
		            maybeLogVkModOverrideDiag();
		        } catch (Throwable t) {
            // VulkanMod absent or field renamed — degrade to a no-op (the vanilla
            // buffer slice is jittered but VulkanMod ignores it, so the world
            // renders unjittered = the phantom-jitter shake returns). Log once so
            // a silent regression is detectable.
            if (!vkModProjectionOverrideFailedLogged) {
                vkModProjectionOverrideFailedLogged = true;
                FSRMod.LOGGER.warn("FSR4: VulkanMod projection override failed ({}: {}) — world renders unjittered, temporal quality degraded", t.getClass().getSimpleName(), t.getMessage());		            }
		        }
		    }

		    /**
		     * DIAG (2026-08-09): logs the CURRENT VRenderSystem.projection field's
		     * clip-space jitter translation (m30/m31 after T*jitter * P) at a named
		     * point in the frame. Called right after the override writes the field
		     * ("after-override") and again from the main-pass mixin AFTER the world
		     * rendered ("after-render"). If after-render shows ~0 while
		     * after-override showed the jitter → something re-captured/overwrote
		     * the field mid-frame (VulkanMod's own GameRendererMixin or a second
		     * projection upload). If after-render still shows the jitter → the
		     * field survives but the draw ignores it (global-buffer UBO path).
		     */
		    public void logVkModProjectionState(String tag) {
		        try {
		            if (vkModProjectionField == null) {
		                Class<?> vrs = Class.forName("net.vulkanmod.vulkan.VRenderSystem");
		                try {
		                    vkModProjectionField = vrs.getField("projection");
		                } catch (NoSuchFieldException e) {
		                    vkModProjectionField = null;
		                }
		                vkModApplyProjection = vrs.getMethod("applyProjectionMatrix", Matrix4f.class);
		                vkModGetProjection = vrs.getMethod("getProjectionMatrix");
		            }
		            Matrix4f m = null;
		            if (vkModProjectionField != null) {
		                Object cur = vkModProjectionField.get(null);
		                if (cur instanceof Matrix4f mm) m = mm;
		            } else {
		                Object mb = vkModGetProjection.invoke(null);
		                if (mb != null) {
		                    java.nio.ByteBuffer bb = (java.nio.ByteBuffer) mb.getClass().getField("buffer").get(mb);
		                    if (bb != null) m = new Matrix4f().set(bb.asFloatBuffer());
		                }
		            }
		            if (m != null) {
		                // m20/m21 mirror the clip-space jitter translation
		                // (m20=-jx*m23, m21=-jy*m23 with m23=-1 for perspective).
		                // m30/m31 are 0 in BOTH jittered and plain matrices.
		                FSRMod.LOGGER.info("[DIAG] VKMod projection {}: m20={} m21={} (jitter px {:.4f},{:.4f})",
		                    tag, String.format(java.util.Locale.ROOT, "%.5f", m.m20()),
		                    String.format(java.util.Locale.ROOT, "%.5f", m.m21()),
		                    jitterX, jitterY);
		            } else {
		                FSRMod.LOGGER.info("[DIAG] VKMod projection {}: no Matrix4f available (1.21.11 apply/get path)",
		                    tag);
		            }
		        } catch (Throwable t) {
		            FSRMod.LOGGER.info("[DIAG] VKMod projection {}: read failed ({}: {})",
		                tag, t.getClass().getSimpleName(), t.getMessage());
		        }
		    }

		    /**
		     * DIAG (2026-08-09): periodically logs whether the VulkanMod projection
		     * override fires every frame (fires vs skips), so a phantom-jitter
		     * diagnosis can tell "override never runs" from "override runs but the
		     * render ignores it". Every 600 frames (~10s), plus a final line when
		     * the total exceeds a threshold.
		     */
		    private void maybeLogVkModOverrideDiag() {
		        vkModProjectionOverrideLogTicker++;
		        if (vkModProjectionOverrideLogTicker >= 600) {
		            vkModProjectionOverrideLogTicker = 0;
		            FSRMod.LOGGER.info("[DIAG] VKMod projection override: fires={} skips={} (jitterAppliedThisFrame gate) — fires>>0 means the field is written every frame",
		                vkModProjectionOverrideFires, vkModProjectionOverrideSkips);
		        }
		    }

		    /**
		     * Rebuilds {@link #lastProj} from the current camera + window so camera
		     * parameters (near/far/fov) and the view-projection matrices used for
		     * motion-vector generation stay valid. This replaces the removed projection
		     * hook (the @ModifyArg on the world projection crashed mixin apply in
		     * 26.1.2). Uses the base FOV from options (the per-frame fovMultiplier from
		     * sprinting/effects is ignored — negligible for MV scale) and a render-
		     * distance-derived far plane. Wrapped in try/catch so any API mismatch
		     * falls back to the previous matrix instead of crashing the render thread.
		     */
    private void updateProjectionFromCamera() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.gameRenderer == null) return;
            Camera camera = client.gameRenderer.getMainCamera();
            if (camera == null) return;
            int w = displayWidth > 0 ? displayWidth : 1;
            int h = displayHeight > 0 ? displayHeight : 1;
            float aspect = (float) w / (float) h;

            float fovDeg = 70.0f;
            try { fovDeg = client.options.fov().get().floatValue(); } catch (Throwable ignored) {}
            int renderDist = 12;
            try { renderDist = client.options.renderDistance().get(); } catch (Throwable ignored) {}
            float far = Math.max(64.0f, renderDist * 16.0f * 4.0f);

            lastProj.identity();
            lastProj.perspective((float) Math.toRadians(fovDeg), aspect, 0.05f, far);
            lastProjJittered.set(lastProj);  // Keep jittered in sync (jitter is 0 here anyway)
        } catch (Throwable ignored) {
            // Keep the previous lastProj on any failure.
        }
    }

    /**
     * True when {@link #lastProj} holds a real perspective projection (near/far
     * both sane). The VKMod MV-gen must use the REAL projection captured by
     * {@link #captureAndJitter} during renderLevel (it fires under VulkanMod —
     * the dispatch near/far proves it), NOT the {@link #updateProjectionFromCamera}
     * rebuild: the rebuilt matrix (near=0.05, far=renderDist*64) differs ~10x in
     * mid-depth from the render's real near=0.5/far=40920, and the constant-depth
     * (ndcZ=0) MV-gen maps its assumption to z~0.1 vs z~1.0 — a ~10x parallax
     * error that saturated the reactive mask and discarded ALL history (the
     * shake + pixelated-sun regression). Rebuild only as a fallback when the
     * captured matrix is garbage (historical: far=-986895.06).
     */
    private boolean isSaneProjection() {
        float a = lastProj.m22();
        float b = lastProj.m23();
        float near = b / (a - 1.0f);
        float far = b / (a + 1.0f);
        // NOTE: far is deliberately NOT bounded above — VulkanMod's CameraMixin
        // can build an infinite-far projection (far -> +Inf/NaN), and the far
        // plane barely affects the ndcZ=0 constant-depth MV reprojection
        // (mid-depth z ~ 2*near for large far). The guard's real job is
        // rejecting garbage like the historical far=-986895.06 (negative far
        // fails far > near; NaN fails the NaN check).
        return !Float.isNaN(near) && !Float.isNaN(far)
            && near > 0.0f && near <= 1000.0f
            && far > near;
    }

    private void restoreDefaultTarget() {
        Minecraft client = Minecraft.getInstance();
        if (mainRenderTarget != null) {
            RenderTarget current = client.getMainRenderTarget();
            if (current != mainRenderTarget) {
                ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            }
        }
        insideFrame = false;
    }

    public void ensureCorrectRenderTarget(RenderTarget caller) {
        Minecraft client = Minecraft.getInstance();
        if (renderTarget == null || mainRenderTarget == null) return;
        if (caller == renderTarget && caller != mainRenderTarget) {
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
        }
    }

    public void onRenderBegin(int windowWidth, int windowHeight) {
        long nowNs = System.nanoTime();
        // AUTO-QUALITY FPS EMA (2026-08-08): frame time = time since the
        // PREVIOUS onRenderBegin call (the whole render+present loop). Runs for
        // every renderer — this point is reached before the Iris/VulkanMod early
        // returns. Ignores huge gaps (alt-tab, load stalls) so one hitch doesn't
        // tank the EMA and trigger a spurious quality drop.
        if (autoPrevFrameNs != 0) {
            float frameMs = (nowNs - autoPrevFrameNs) / 1_000_000.0f;
            if (frameMs > 0.0f && frameMs < 1000.0f) {
                autoEmaFrameMs = autoEmaFrameMs <= 0.0f
                    ? frameMs
                    : autoEmaFrameMs + AUTO_Q_EMA_ALPHA * (frameMs - autoEmaFrameMs);
            }
            // FSR4/FSR3 frameTimeDelta FIX (2026-08-08, "warping should not
            // happen in any FSR mode"): the FSR3.1 manual says frameTimeDelta
            // is "used within the temporal component of the auto-exposure
            // feature... for tuning of the history accumulation". The dispatch
            // previously measured (nanoTime - t0Frame) INSIDE onRenderBegin —
            // the intra-frame elapsed time (<1ms, clamped to 1.0), i.e. dt=1.0
            // instead of the inter-frame ~16.6ms at 60fps. A 16x error in the
            // history-accumulation tuning is a prime warp/smear candidate on
            // the FSR3.1 fallback path (RDNA 3.5 iGPU). Use the REAL inter-frame
            // delta here (same source the auto-quality EMA uses).
            lastFrameDeltaMs = Math.max(1.0f, Math.min(100.0f, frameMs));
        }
        autoPrevFrameNs = nowNs;
        t0Frame = nowNs;
        timingFrameCount++;

        // BLIT AUDIT (2026-08-29, "every second it blinks" — one BLACK frame,
        // HUD included, only with a shader pack, FSR2/3/4 but not FSR1).
        //
        // A fully black presented frame means the vanilla blitToScreen either
        // did not run at all, or blitted an empty target. Nothing in the mod
        // cancels it (presentDirect() always returns false), so the only way to
        // see it is to COUNT the main-target blits per frame. The counter still
        // holds the PREVIOUS frame's value here, so a zero = the previous frame
        // was never presented and the back buffer went to the screen undrawn.
        // Throttled: a sustained storm must not spam the log.
        // mainRenderTarget != null guard: without it every menu / title /
        // no-world frame reports a "black frame" (the mod is simply not engaged
        // and never touches any render target).
        if (timingFrameCount > 3 && config.enabled && mainPresentBlits == 0
                && mainRenderTarget != null
                && System.currentTimeMillis() - lastBlitAuditLogMs >= 250L) {
            lastBlitAuditLogMs = System.currentTimeMillis();
            FSRMod.LOGGER.warn("[BLIT-AUDIT] previous frame #{} had ZERO main-target blits (black frame) — version={} irisActive={} irisSwap={} upscaled={} pipelineReady={} fsr2Frame={} fsr4Init={} fsr4Susp={} fsr4Skips={} renderTarget={} mainRT={}",
                timingFrameCount - 1, config.version, irisActiveThisFrame, irisSwapActive,
                lastFrameUpscaled, pipelineReady, fsr2FrameCount, fsr4Initialized, fsr4Suspended,
                fsr4SkipFrames,
                renderTarget != null ? renderTarget.width + "x" + renderTarget.height : "null",
                mainRenderTarget != null ? mainRenderTarget.width + "x" + mainRenderTarget.height : "null");
        }
        mainPresentBlits = 0;

        updateAutoQuality();

        // Stale deferred FG generate: if it survived to the next frame, the
        // presentDirect blit hook never consumed it (caller mismatch or aborted
        // frame) — log once so a silent regression to single-present is visible.
        if (fgGeneratePending && !fgStalePendingLogged) {
            fgStalePendingLogged = true;
            FSRMod.LOGGER.warn("FG_DX12: deferred FG generate was never consumed (frame aborted or "
                + "presentDirect caller mismatch) — falling back to single present; double-present disabled");
        }
        fgGeneratePending = false;

        // WORLD-JOIN RESET (2026-08-24): a null->non-null level flip means the
        // user joined a world/dimension — give the scaled swap a fresh chance.
        try {
            boolean levelNullNow = Minecraft.getInstance().level == null;
            if (prevLevelNull && !levelNullNow) {
                if (irisSwapBrokenLatch || brokenCompositeVotes > 0) {
                    FSRMod.LOGGER.info("[IRIS-COMPOSITE] world joined — clearing broken-swap latch (votes={})", brokenCompositeVotes);
                    if (irisSwapBrokenLatch) {
                        showBrokenCompositeToast(false);
                    }
                }
                irisSwapBrokenLatch = false;
                brokenCompositeVotes = 0;
                irisSwapBrokenLatchLogged = false;
                worldJoinedAtMs = System.currentTimeMillis();
                // Iris rebuilds its whole pipeline on a world join — the one
                // moment a broken composite can appear. Sample densely for a
                // few seconds afterwards (see armTerrainProbeBurst).
                armTerrainProbeBurst();
            }
            prevLevelNull = levelNullNow;
        } catch (Throwable ignored) {
        }

        if (!config.enabled || isMac()) {
            // IRIS/PHOTONICS DISABLE (2026-08-23, "base game black corner + voxel
            // edges when FSR off"): this early return sits BEFORE the block that
            // clears photonicsProxyActive/irisSwapActiveStatic/windowScaleActive
            // (~2731), so toggling FSR off while the swap was active left the
            // Photonics mixin redirect stale: it kept returning proxy dims
            // (1092x683) while Iris rendered the pack at native 1680x1050 —
            // Photonics sized its GI buffers for the BID, the pack sampled a
            // wrong-size GI texture = black corner + blocky voxel edges in the
            // BASE game. Clear the full swap state here, on every disabled
            // frame, so Photonics re-allocates at window size and Iris renders
            // native (base game is base game).
            irisSwapActive = false;
            irisSwapActiveStatic = false;
            photonicsProxyActive = false;
            photonicsBuffersAligned = true;
            windowScaleActive = false;
            // VKMod teardown on FSR disable (2026-08-06 fix for "freezes when I
            // turn off FSR"): the scaled framebuffer + end() hook kept running
            // (their gates never checked config.enabled), so Copy2 kept
            // presenting the STALE last FSR4 output every frame while the
            // pipeline stopped dispatching — the display froze on the last
            // upscaled frame. Schedule a swapchain recreate back to native res
            // (the end() hook's blit branch presents the scaled framebuffer
            // safely during the transition frame) and drop the FSR4 copy path.
            // Shutting down the FSR4 context also lets the re-enable path do a
            // clean re-init (which re-arms fsr4VkModActive).
            if (VulkanModCompat.isLoaded()) {
                // VKMod-FSR4 path ONLY (isActive): tear down the D3D12 context so
                // re-enable does a clean re-init (which re-arms fsr4VkModActive;
                // leaving it initialized but de-activated would strand the end()
                // hook on the blit branch forever). GL-path FSR4 users keep the
                // pool — re-enable resumes instantly (their pre-existing behavior).
                if (VulkanModCompat.isActive()) {
                    if (fsr4Initialized) {
                        Fsr4Native.shutdownFsr4();
                        fsr4Initialized = false;
                        fsr4AsyncPending = false;
                        fsr4Suspended = false;
                        fsr4ConsecutiveSkips = 0;
                        VulkanModCompat.fsr4VkModActive = false;
                    }
                }
                // Render-scale users only: schedule the swapchain recreate back to
                // native (the scaled framebuffer must go away with FSR off).
                // Non-render-scale users have no scaled framebuffer — no recreate.
                if (lastVkModEnabled && VulkanModCompat.renderScaleSwapped()) {
                    VulkanModCompat.scheduleRenderScaleUpdate();
                }
                lastVkModEnabled = false;
            }
            restoreDefaultTarget();
            return;
        }
        if (windowWidth <= 0 || windowHeight <= 0) {
            restoreDefaultTarget();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // NOTE: in-game screens (chat, inventory, pause, options) are NOT
        // excluded — the world keeps rendering behind them every frame, and the
        // FSR design composites every GUI pass (HUD, chat, screens) on top of
        // the upscaled scene at native resolution in mainRT. Skipping screens
        // made the world render at NATIVE res while chatting (no render
        // scaling → resolution pop + heavy GPU load on iGPUs — reported
        // 2026-08-04 "no upscaling happens when i am in chat"). The level==null
        // guard covers the title/loading screens where no world exists.
        if (client.level == null) {
            // Fresh FSR4 init-deferral state for the next world load (2026-08-05
            // v5): a stale fsr4InitDeferStartMs from a previous deferred episode
            // would make the 6s grace appear already elapsed on world re-entry
            // (quit-to-title + rejoin), force-initializing FSR4 mid-storm — the
            // exact crash this deferral exists to prevent.
            fsr4InitDeferStartMs = 0;
            fsr4InitDeferFrames = 0;
            fsr4InitEmaMs = 0.0f;
            lastInitFrameNanos = 0;
            restoreDefaultTarget();
            return;
        }
        RenderSystem.assertOnRenderThread();

        if (insideFrame) {
            restoreDefaultTarget();
            return;
        }
        insideFrame = true;
        worldRenderedThisFrame = false;
        jitterAppliedThisFrame = false;
        fgSecondPresentPending = false;  // stale pending from an aborted frame
        fgExtraGenFramesToPresent = 0;
        fgGeneratePending = false;       // stale deferred generate from an aborted frame
        t0Begin = System.nanoTime();

        // FSR4-FALLBACK IN-GAME NOTIFICATION (2026-08-06): edge-triggered chat
        // message when FSR4 suspends to the FSR1 spatial fallback (GPU
        // saturation / fence timeouts / dispatch failure) or hard-disables, and
        // when it comes back. Reads the state settled by LAST frame's
        // onSceneRendered — onRenderBegin runs before the world renders, so the
        // transition is observed here once (not every frame of the episode).
        // Cooldown: a storm can flip suspend/resume repeatedly; cap to one
        // message per 10s so chat doesn't spam during elytra + chunk streaming.
        // Only meaningful when FSR4/FG is the configured version.

        // LINUX FSR4 FALLBACK NOTICE (2026-08-14): the AMD FSR4 SDK is
        // Windows/DX12-only — on Linux a FSR4 selection transparently runs the
        // mod's own FSR3 temporal engine (isFsr3() routing; isFsr4() is false
        // here so the SDK blocks below stay inert). Log it once per session so
        // the behavior is discoverable instead of looking like a silent
        // version switch. isFsr4() is false, so this must test the raw config
        // value + SDK support, not isFsr4().
        if (!fsr4SdkFallbackLogged && !isMac()
            && config.version == FSRConfig.FSRVersion.FSR4 && !fsr4SdkSupported()) {
            fsr4SdkFallbackLogged = true;
            FSRMod.LOGGER.warn("FSR4: AMD FSR4 SDK unavailable on this platform (Windows/DX12-only) — "
                + (isLinux() ? "running the FSR3 temporal engine as the Linux-compatible FSR4 path"
                             : "running the FSR3 temporal engine instead"));
        }

        // DIMENSION-CHANGE DETECTION (2026-08-22, "going into the End freezes
        // and crashes"): a client.level object swap while in-game = dimension
        // change (overworld→End etc.). Voxy destroys + recreates its entire
        // pipeline and Iris re-patches shader state — a multi-second GPU storm
        // (log-verified: ~9s of pipeline rebuild on End entry) — and the FSR4
        // resume probe sees the transition's light loading frames as "queue
        // drained", re-arming FSR4 right into the new dimension's chunk
        // streaming → the atio6axx.dll hard-freeze class. Force-suspend FSR4
        // to FSR1 and start the hold window; the resume gate below refuses to
        // re-arm until the hold elapses. Runs BEFORE the notify state machine
        // so the fallback notification reflects the suspension. Title→world
        // and world→title also swap the object; the early return above handles
        // the level==null side, and the stale old level makes the next in-game
        // level look like a swap — which is exactly the world-load storm
        // protection we want there too.
        if ((isFsr4() || isFrameGenDx12()) && config.enabled) {
            Object levelNow = client.level;
            if (levelNow != fsr4LastLevelObj) {
                boolean wasInGame = fsr4LastLevelObj != null;
                fsr4LastLevelObj = levelNow;
                if (wasInGame && levelNow != null) {
                    fsr4DimChangeAtMs = System.currentTimeMillis();
                    fsr4Suspended = true;
                    fsr4SuspendedAtMs = fsr4DimChangeAtMs;
                    fsr4ProbeCleanStreak = 0;
                    fsr4FirstCleanProbeMs = 0;
                    fsr4PreCheckSkips = 0;
                    fsr4FenceTimedOutPrevFrame = false;
                    fsr4WorldCalmStreak = 0;
                    fsr4ResumeAtMs = 0;
                    hasPrevFrame = false;
                    fsr2FrameCount = 0;
                    if (shouldDebug()) {
                        FSRMod.LOGGER.info("FSR4: dimension change detected — FSR1 for {}ms (transition storm)", FSR4_DIM_CHANGE_HOLD_MS);
                    }
                }
            }
        }

        if ((isFsr4() || isFrameGenDx12()) && config.enabled) {
            boolean suspendedNow = fsr4Suspended;
            boolean hardDisabledNow = fsr4HardDisabled;
            boolean fallbackActiveNow = suspendedNow || hardDisabledNow;
            boolean fallbackWasActive = lastNotifiedFsr4Fallback;
            long nowMs = System.currentTimeMillis();
            boolean cooldownElapsed = nowMs - lastFsr4FallbackNotifyMs >= FSR4_FALLBACK_NOTIFY_COOLDOWN_MS;
            // STATE MACHINE (review fix 2026-08-06): the flag MUST update on
            // EVERY transition, not only when a message is sent — if a transition
            // lands inside the cooldown and the flag stayed stale, a restore
            // (or a second fallback) could be swallowed forever (e.g. suspend
            // fires at t=0, restore at t=2s is throttled and the flag stays
            // true → a later 'FSR4 restored' never fires because no further
            // transition exists). Gate only the MESSAGE + timestamp on the
            // cooldown; always track the current fallback state.
            if (fallbackActiveNow != fallbackWasActive) {
                if (cooldownElapsed) {
                    if (fallbackActiveNow) {
                        // FSR4 → FSR1 transition.
                        notifyFsr4Fallback(hardDisabledNow
                            ? "FSR4 disabled for this session (D3D12 dispatch failures) - FSR1 active"
                            : "FSR4 fell back to FSR1 (GPU saturated) - auto-resumes when the queue drains");
                    } else {
                        // FSR1 → FSR4 restore.
                        notifyFsr4Fallback("FSR4 restored");
                    }
                    lastFsr4FallbackNotifyMs = nowMs;
                }
                lastNotifiedFsr4Fallback = fallbackActiveNow;
            }
        }

        displayWidth = windowWidth;
        displayHeight = windowHeight;

        // CONSUME DEFERRED TOGGLE (2026-08-21, "freezes and crashes when
        // toggling FSR on/off with a shaderpack" fix): the toggle key handler
        // in FSRMod.onClientTick runs on the CLIENT thread; markDirty() tears
        // down FSR4/FG D3D12 contexts + GL resources — calling it
        // synchronously races the render thread's mid-dispatch reads and causes
        // a hard freeze. Consume it HERE, on the RENDER thread at frame start,
        // BEFORE any rendering or dispatch. Runs before scaleChangeQueued since
        // a toggle implies a full rebuild.
        // Tracks whether an explicit rebuild already ran this frame, so the
        // pack-toggle scale-change check further down does not redo the work.
        boolean rebuiltThisFrame = false;
        if (toggleDirtyQueued) {
            toggleDirtyQueued = false;
            markDirty();
            rebuiltThisFrame = true;
        }

        // CONSUME DEFERRED CONFIG DIRTY (2026-08-23, "switching FSR versions
        // makes terrain disappear until a quality change" fix): the settings
        // screen's version pills / iris slider / VulkanMod toggle / FSR1-under-
        // Iris toggle / Async-FSR4 toggle set configDirtyQueued instead of
        // calling markDirty() from the CLIENT thread. Consume it here on the
        // RENDER thread at frame start, BEFORE any rendering or dispatch —
        // markDirty() tears down the render target + FSR4/FG contexts + pool,
        // and running that teardown concurrently with the render thread's
        // mid-dispatch reads corrupted the frame (deleted texture IDs reused →
        // terrain read from a wrong texture → black until a later clean
        // recreate). The normal flow below then recreates everything for the
        // newly selected version at the correct size.
        if (configDirtyQueued) {
            configDirtyQueued = false;
            FSRMod.LOGGER.info("[SWITCH-DIAG] consumeConfigDirty — version={} qualityScale={} irisScale={} enabled={}",
                config.version, config.qualityScale, config.irisQualityScale, config.enabled);
            markDirty();
            rebuiltThisFrame = true;
        }

        // CONSUME DEFERRED SCALE CHANGE (2026-08-21, "see through the ground on
        // drastic quality changes" fix): the quality slider sets
        // scaleChangeQueued instead of calling applyAutoScale() synchronously
        // from the GUI (client) thread. Consume it HERE, on the RENDER thread at
        // frame start, BEFORE any rendering or dispatch — applyAutoScale() then
        // runs its teardown (renderTarget destroy, texture deletes, FSR4/FG
        // shutdown, pool destroy) with no concurrent reader, and the normal
        // flow below recreates everything at the new scale. This is the same
        // safe position updateAutoQuality() calls applyAutoScale() from.
        if (scaleChangeQueued) {
            scaleChangeQueued = false;
            applyAutoScale();
            rebuiltThisFrame = true;
        }

        float scale;
        boolean irisActive = IrisCompat.isShaderPackInUse();
        // PACK-ACTIVATION FRAME LOCK (2026-08-19): capture the pack state ONCE
        // per frame here — onSceneRendered/onRenderEnd reuse this value instead
        // of re-evaluating (see the field doc: a mid-frame flip tore the frame
        // and faulted the AMD GL driver).
        irisActivePrevFrame = irisActiveThisFrame;
        irisActiveThisFrame = irisActive;
        if (irisActive) {
            scale = config.getIrisScale();
        } else {
            scale = config.getScale();
        }
        scaledWidth = Math.max(16, Math.round(windowWidth * scale));
        scaledHeight = Math.max(16, Math.round(windowHeight * scale));

        // PACK-TOGGLE SCALE CHANGE (2026-08-29, "disabling and re-enabling the
        // shader pack makes the terrain disappear the first time").
        //
        // The effective scale flips between config.getIrisScale() (pack on,
        // 0.6 -> 1008x630) and config.getScale() (pack off, 0.65 -> 1092x683),
        // so toggling the pack changes scaledWidth/Height WITHOUT going through
        // any of the three rebuild paths — markDirty() (config change),
        // applyAutoScale() (quality slider) or onResize() (window resize).
        // Nothing re-sized the pipelines, so:
        //   - the FSR4 D3D12 context stayed at the old size and REFUSED to
        //     dispatch: "[FSR4-PATH] context 1092x683->1680x1050 != scene
        //     1008x630->1680x1050 — FSR4 dispatch skipped (FSR1 until recreate)";
        //   - the FSR1 fallback pipeline stayed at the old size and upscaled the
        //     new-size scene with STALE UVs -> a magnified corner crop, which is
        //     what "the terrain disappeared" actually was (same failure class as
        //     the documented corner-zoom bugs).
        // Rebuild here whenever the effective render size changed and no other
        // path already did it this frame.
        // lastAppliedScale* starts at -1, so the first frame only ESTABLISHES
        // the baseline instead of triggering a spurious rebuild.
        boolean scaleDimsChanged = lastAppliedScaleW > 0
            && (scaledWidth != lastAppliedScaleW || scaledHeight != lastAppliedScaleH);
        lastAppliedScaleW = scaledWidth;
        lastAppliedScaleH = scaledHeight;
        if (config.enabled && scaleDimsChanged && !rebuiltThisFrame) {
            FSRMod.LOGGER.info("[SCALE-SYNC] effective render size changed to {}x{} (pack={} scale={}) — rebuilding pipelines",
                scaledWidth, scaledHeight, irisActiveThisFrame, scale);
            applyAutoScale();
        }

        // IRIS COMPOSITE VIEWPORT FIX: when render scale is active and an
        // Iris pack is in use, the Window must report proxy dims so Iris
        // builds its pipeline and composite at the correct size.
        windowScaleActive = (scale < 1.0f && config.enabled && client.level != null);

        // PACK-ACTIVATION HOLD (2026-08-23, "blinking every second" fix): see
        // the fsr4PackActivateHoldLogged field doc. Runs on the flip to active
        // only (the pack-deactivate flip is benign — the load drops). Must run
        // AFTER irisActiveThisFrame is captured for this frame (above).
        if ((isFsr4() || isFrameGenDx12()) && config.enabled
                && irisActiveThisFrame && !irisActivePrevFrame) {
            long packNow = System.currentTimeMillis();
            fsr4DimChangeAtMs = packNow;          // resume probe must wait out the hold
            fsr4Suspended = true;
            fsr4SuspendedAtMs = packNow;
            fsr4ProbeCleanStreak = 0;
            fsr4FirstCleanProbeMs = 0;
            fsr4PreCheckSkips = 0;
            fsr4WorldCalmStreak = 0;
            fsr4EmaWorldMs = 0.0f;
            fsr4FenceTimedOutPrevFrame = false;
            hasPrevFrame = false;
            fsr2FrameCount = 0;
            if (fsr4PackActivateHoldLogged == 0) {
                fsr4PackActivateHoldLogged = System.currentTimeMillis();
                if (shouldDebug()) {
                    FSRMod.LOGGER.info("FSR4: shader-pack activation — holding FSR1 for {}ms (pipeline load), reseeding saturation baseline",
                        FSR4_DIM_CHANGE_HOLD_MS);
                }
            }
        }

        // PACK-SWITCH HOLD (2026-08-25): same protection for switching packs WHILE
        // a pack stays active. A pack change (Eclipse -> BSL) keeps irisActive=true
        // so the activation-flip guard above never fires, yet Iris tears down +
        // rebuilds its composite pipeline and Photonics re-allocates GI buffers —
        // concurrent GL churn that faulted the AMD GL driver when FSR4's cross-API
        // D3D12 path re-enabled in that window. Detect the switch via the pack name
        // (IrisCompat.getCurrentShaderPackName — public API / config), force-suspend
        // FSR4 (which also defers any in-flight re-init) and hold the resume for the
        // DIM_CHANGE window so the rebuild settles before any D3D12 work. Reads
        // irisActiveThisFrame (captured above) so a pack turning OFF is skipped
        // (extant framerate drop, no rebuild); the value is captured ONCE this frame
        // (frame-lock convention from 2026-08-19).
        if (irisActive) {
            String packNameNow = IrisCompat.getCurrentShaderPackName();
            boolean switchDetected = fsr4PackNamePrev != null
                && !fsr4PackNamePrev.isEmpty()
                && packNameNow != null
                && !packNameNow.equals(fsr4PackNamePrev);
            if (switchDetected && (isFsr4() || isFrameGenDx12()) && config.enabled) {
                long nowSwitch = System.currentTimeMillis();
                fsr4DimChangeAtMs = nowSwitch;
                fsr4Suspended = true;
                fsr4SuspendedAtMs = nowSwitch;
                fsr4ProbeCleanStreak = 0;
                fsr4FirstCleanProbeMs = 0;
                fsr4PreCheckSkips = 0;
                fsr4WorldCalmStreak = 0;
                fsr4EmaWorldMs = 0.0f;
                fsr4FenceTimedOutPrevFrame = false;
                hasPrevFrame = false;
                fsr2FrameCount = 0;
                if (fsr4PackSwitchLogged == 0) {
                    fsr4PackSwitchLogged = nowSwitch;
                    FSRMod.LOGGER.info("FSR4: shader-pack switch {} -> {} — holding FSR1 for {}ms (pipeline rebuild), reusing the dimension-change hold",
                        fsr4PackNamePrev, packNameNow, FSR4_DIM_CHANGE_HOLD_MS);
                }
            }
            fsr4PackNamePrev = packNameNow;
        } else {
            // Pack off — the next time a pack is active is a fresh activation
            // (handled by the flip guard above); forget the previous name so the
            // first activation doesn't look like a "switch" from a stale name.
            fsr4PackNamePrev = null;
        }

        // IRIS GENERIC TEMPORAL MODE (2026-08-18): a shader pack in use does
        // NOT mean "no mainRT swap" anymore. When the selected version is
        // FSR2/FSR3/FSR4 (temporal upscalers) we fall through to the shared
        // scaled-render-target path below: Iris sizes its gbuffers from
        // mainRT.width/height and draws the final composite into mainRT's
        // color texture, so the swap scales the pack's ENTIRE pipeline with
        // zero per-pack config (Super Resolution needs a JSON compat tier for
        // this; we get it for free from the vanilla boundary). Jitter flows
        // through the same captureAndJitter hook (Iris consumes the vanilla
        // projection) and the auto-probe verifies it empirically.
        //
        // FSR4 joins the swap (2026-08-20): its GL dispatch copies renderTex
        // (the scaled proxy's composite) into the D3D12 pool and upscales
        // render->display like the non-Iris GL path — no Vulkan needed (the
        // FSR4 5-texture pool is direct D3D12->GL via
        // GL_HANDLE_TYPE_D3D12_RESOURCE_EXT). The pack's depth does not land
        // in the proxy depth, so probeFsr4Depth gates uUseDepth off (constant-
        // depth MVs), same as FSR2/3 under a pack. FSR1 keeps the legacy Iris
        // behavior (no swap) by default: FSR1's GL post-process has reproduced
        // an AMD driver crash on some Iris composites. FSR3_FG also keeps the
        // old path because its alternate-frame display machinery is not wired
        // for the swap yet. FSR1 can opt into the scaled proxy through
        // config.fsr1IrisExperimental for testing.
        irisSwapActive = false;
        irisSwapActiveStatic = false;
        photonicsProxyActive = false;
        // Explicit DH gate: dhActive is computed further down (post-VKMod
        // block), so the field may be stale on the very first frame of a
        // session with Distant Horizons loaded. DH + Iris + scaled swap is an
        // unsupported combination (DH renders its LOD terrain into its own
        // framebuffers, bypassing the proxy); keep the legacy Iris path.
        // PHOTONICS: Photonics (mod id "photonics") is an Iris extension whose
        // ColorFramebuffers use Window size (resolutionSupplier = Window.get-
        // Width/Height, confirmed via javap on ColorFramebuffer.lambda$new$0).
        // PhotonicsColorFramebufferMixin intercepts Supplier.get() inside
        // updatePerFrame() and redirects to the proxy dimensions when the swap
        // is active, so the raytracing buffers match the proxy (no 2x crop).
        // Iris temporal path is fully enabled for Photonics; only DH is excluded.
        boolean irisFsr1Experimental = irisActive && config.enabled
            && config.version == FSRConfig.FSRVersion.FSR1
            && config.fsr1IrisExperimental
            && !DistantHorizonsCompat.isLoaded()
            && scale < 1.0f && client.level != null;
        // PHOTONICS ALIGNMENT VERDICT (2026-08-23): the ColorFramebuffer mixin
        // probe sets photonicsBuffersAligned=false when Photonics allocates at
        // window size despite the redirect — self-disable the swap (native
        // pass-through, no corner crop) instead of showing a wrong image.
        boolean photonicsBlock = PhotonicsCompat.isLoaded() && !photonicsBuffersAligned;
        // BROKEN-COMPOSITE LATCH: when the pack's scaled composite provably lost
        // its terrain (pixel probe), render native instead — never show the crop.
        if (irisSwapBrokenLatch && !irisSwapBrokenLatchLogged) {
            irisSwapBrokenLatchLogged = true;
            FSRMod.LOGGER.warn("[IRIS-SCALED] broken-composite latch active — native pass-through this world (change quality or rejoin to retry the scaled path)");
        }
        boolean irisTemporal = irisActive && config.enabled
            && !DistantHorizonsCompat.isLoaded()
            && !photonicsBlock
            && !irisSwapBrokenLatch
            && (isFsr2() || (isFsr3() && !isFrameGen()) || isFsr4() || isFrameGenDx12() || irisFsr1Experimental)
            && scale < 1.0f && client.level != null;
        if (irisActive && !irisTemporal) {
            if (!renderScaleStatusLogged) {
                renderScaleStatusLogged = true;
                com.fsr2mod.compat.RenderScaleCompat.logStatus();
            }
            originalMainRenderTarget = client.getMainRenderTarget();
            mainRenderTarget = originalMainRenderTarget;
            jitterX = 0.0f;
            jitterY = 0.0f;
            return;
        }
        if (irisTemporal) {
            irisSwapActive = true;
            irisSwapActiveStatic = true;
            photonicsProxyActive = true;
            photonicsProxyW = scaledWidth;
            photonicsProxyH = scaledHeight;
            scaledWidthForWindow = scaledWidth;
            scaledHeightForWindow = scaledHeight;
            if (!irisTemporalLogged) {
                irisTemporalLogged = true;
                String irisUpscaler = isFsr2() ? "2" : (isFsr3() ? "3" : (isFsr4() ? "4" : (isFrameGenDx12() ? "3 FG DX12" : "1")));
                if (config.version == FSRConfig.FSRVersion.FSR1) {
                    FSRMod.LOGGER.warn("[IRIS] experimental FSR1 path enabled — EASU/RCAS will run on the shader-pack composite; disable it if the AMD driver crashes");
                }
                FSRMod.LOGGER.info("[IRIS-SCALED] shader pack + FSR{} selected — scaled mainRT swap active: pack renders through the scaled proxy ({}x{} -> {}x{}), FSR{} upscales the composite at the vanilla boundary",
                    irisUpscaler,
                    scaledWidth, scaledHeight, displayWidth, displayHeight,
                    irisUpscaler);
            }
        }

        // VulkanMod path: Vulkan replaces OpenGL entirely. No render target swap,
        // no viewport change, no jitter — VulkanMod's own swapchain-based rendering
        // handles the scene. FSR4 interop is handled by VulkanMod mixins + native code.
        // Other FSR modes (FSR1/2/3) are not available when VulkanMod is active
        // since the GL compute shader pipelines won't work without an OpenGL context.
        //
        // Render-scale state is set for ANY loaded VulkanMod (pure Vulkan blit —
        // no external-memory/D3D12 interop needed), BEFORE the isActive() gate:
        // isActive() requires external-memory support + native DLL, but the
        // scaled-framebuffer + blit works with just the mod loaded.
        // Declared at method scope: the isActive() block below also reads it.
        //
        // Render scale engages ONLY while in-game (client.level != null): the
        // scaled main framebuffer must apply to the 3D world, NOT menus/title/
        // world-select GUI. VulkanMod's DefaultMainPass renders EVERYTHING, so a
        // swap that's active at the title screen makes the whole UI render at
        // 480x300 ("everything is upscaled including the title screen", 2026-08-05).
        boolean vkRenderScale = VulkanModCompat.isLoaded()
            && config.vulkanModRenderScale && config.qualityScale < 1.0f
            && client.level != null;
        // When the render-scale STATE changes (world flip OR the desired scale
        // itself — e.g. the quality slider moved, or the toggle flipped), ask
        // VulkanMod to recreate its swapchain + main pass next frame
        // (Renderer.scheduleSwapChainUpdate -> recreateSwapChain ->
        // mainPass.onResize -> createResources -> our mixin FIELD hook re-evaluates
        // the scaled-framebuffer swap with the new state).
        //
        // THIS is what makes the quality slider work: without it, changing
        // qualityScale only updated the config — the framebuffer stayed at the old
        // scale while the end() hook switched to the D3D12 FSR4 path, so the
        // scaled content was never presented ("the quality slider did nothing",
        // 2026-08-05 log 17:59:09 — FSR4 initialized at native res against the
        // stale 480x300 framebuffer).
        //
        // World-flip recreates are gated on renderScaleConfigured() so FSR4-only
        // VulkanMod users don't get a full recreateSwapChain + waitIdle GPU drain
        // at every world enter/exit. A scale-value change always recreates (it
        // implies the user touched the render-scale settings).
        boolean inGameNow = client.level != null;
        float desiredScale = (config.vulkanModRenderScale && config.qualityScale < 1.0f)
            ? config.qualityScale : 1.0f;
        boolean scaleChanged = Math.abs(desiredScale - lastVkModScale) > 0.001f;
        // Enabled-flip tracking (2026-08-06): re-enabling FSR must schedule a
        // recreate too — the disable path scheduled one back to native, so the
        // scaled framebuffer is gone; without this flip the render scale never
        // re-engages after disable → enable.
        boolean enabledChanged = config.enabled != lastVkModEnabled;
        boolean flipNeedsRecreate = VulkanModCompat.renderScaleConfigured()
            && (inGameNow != lastVkModInGame || enabledChanged);
        if (VulkanModCompat.isLoaded() && (scaleChanged || flipNeedsRecreate)) {
            lastVkModInGame = inGameNow;
            // Debounce: world-flips are rare so they always recreate; scale
            // changes (slider drag) are throttled to one per debounce window so
            // a drag doesn't queue a recreateSwapChain (waitIdle GPU drain) on
            // every frame.
            long nowMs = System.currentTimeMillis();
            if (flipNeedsRecreate
                    || nowMs - lastVkModScheduleMs >= VK_MOD_RECREATE_DEBOUNCE_MS) {
                lastVkModScheduleMs = nowMs;
                // CRITICAL: lastVkModScale must update ONLY when the recreate is
                // actually scheduled. If it updated unconditionally (as before),
                // a change throttled by the debounce window was ABSORBED: a fast
                // drag 0.25→0.8 within 500ms scheduled only the 0.25→0.3 step and
                // once the drag settled at 0.8, scaleChanged was already false
                // (lastVkModScale==0.8) — the final value was NEVER recreated
                // (review fix 2026-08-05). With the update inside the schedule
                // branch, scaleChanged stays true while a change is pending, and
                // the settled value schedules the moment the window elapses.
                lastVkModScale = desiredScale;
                lastVkModEnabled = config.enabled;
                VulkanModCompat.scheduleRenderScaleUpdate();
            }
        }
        if (VulkanModCompat.isLoaded()) {
            VulkanModCompat.setRenderScale(vkRenderScale ? config.qualityScale : 1.0f);
            if (vkRenderScale && !vulkanModRenderScaleLogged) {
                vulkanModRenderScaleLogged = true;
                FSRMod.LOGGER.info("VulkanMod render-scale ACTIVE: qualityScale={} — scaled main framebuffer + bilinear blit upscale ({}x{} -> {}x{})",
                    config.qualityScale,
                    Math.max(1, Math.round(displayWidth * config.qualityScale)),
                    Math.max(1, Math.round(displayHeight * config.qualityScale)),
                    displayWidth, displayHeight);
            }
            // EXTREME-SCALE WARN (2026-08-06): at qualityScale 0.25 the FSR4
            // SDK is asked for a 4x upscale — far beyond its ~1.5-3x design
            // envelope, so edges stay soft/blocky and the sun looks pixelated
            // even with perfect temporal accumulation. The GL mode the user
            // compared against ran at 0.65 (1.54x). Warn once so a future
            // session doesn't re-derive this from a screenshot.
            if (vkRenderScale && config.qualityScale < 0.4f && !fsr4ExtremeScaleWarned) {
                fsr4ExtremeScaleWarned = true;
                FSRMod.LOGGER.warn("FSR4 render scale {:.2f} = {:.1f}x upscale — beyond FSR4's ~1.5-3x design envelope; edges stay soft/pixelated (the GL path looked smooth at 0.65 / 1.54x). Raise qualityScale to 0.5-0.65 for comparable quality.",
                    config.qualityScale, 1.0 / config.qualityScale);
            }
        }

        if (VulkanModCompat.isActive()) {
            originalMainRenderTarget = client.getMainRenderTarget();
            mainRenderTarget = originalMainRenderTarget;
            // TEMPORAL JITTER for the VKMod FSR4 path (2026-08-05 fix for "no
            // quality change / no edge smoothing"): jitter was previously
            // zeroed here AND the dispatch passed (0,0), so the FSR4 SDK had
            // zero sub-pixel sample diversity → it degraded to a spatial
            // reconstruction (output looked like FSR1/bilinear — no temporal
            // edge smoothing, unlike OpenGL mode which feeds Halton jitter).
            // The scene renders jittered through the SAME vanilla projection
            // hook the GL path uses (captureAndJitter @ModifyArg on
            // ProjectionMatrixBuffer.getBuffer in renderLevel — VulkanMod
            // consumes the vanilla projection buffer, so the jittered matrix
            // reaches its shaders). The dispatch below passes the PREVIOUS
            // frame's jitter (prevJitterX/Y, captured here BEFORE the advance)
            // because the D3D12 pool holds the PREVIOUS frame's scene, which
            // was rendered with the previous jitter — same alignment contract
            // as the GL path (clean MVs + matching jitter to the SDK). Zeroed
            // for the spatial fallback (FSR1 config / FSR4 suspended / not yet
            // initialized) — the spatial path has no de-jitter pass.
            // REVIEW FIX (2026-08-05): gate on isFsr4() specifically, NOT
            // isTemporalUpscaler() — FSR2/3 under VKMod are NOT supported (GL
            // compute pipelines need a GL context) and fall back to the bilinear
            // blit, which has no de-jitter pass; advancing jitter there would
            // jitter the world projection and the blit would present a ±0.5px
            // shake (regression — jitter was previously zeroed unconditionally).
            // Also require fsr4Initialized so the brief pre-init window (FSR1
            // spatial fallback) renders unjittered — the first dispatch is
            // reset=true so the jitter transition is clean.
            //
            // Set scaledWidth/Height to the ACTUAL VKMod render dims so
            // clipSpaceJitter (jitterX*2/scaledWidth, read by captureAndJitter
            // during renderLevel) converts against the true render size: when
            // render scale is OFF, scaledWidth would otherwise be
            // round(display*qualityScale) while the scene renders at display
            // size → wrong clip-space jitter magnitude vs the render-pixel
            // jitter the SDK receives (review fix).
            float vkScaleNow = VulkanModCompat.getRenderScale();
            if (config.vulkanModRenderScale && vkScaleNow < 1.0f) {
                scaledWidth = Math.max(16, Math.round(displayWidth * vkScaleNow));
                scaledHeight = Math.max(16, Math.round(displayHeight * vkScaleNow));
            } else {
                scaledWidth = displayWidth;
                scaledHeight = displayHeight;
            }		            if ((isFsr4() || isFrameGenDx12()) && fsr4Initialized && !fsr4Suspended
		                    // JITTER A/B TEST (2026-08-08, "warping should not happen in
		                    // any FSR mode" diagnostic): debugMode >= 5 ZEROES the jitter
		                    // on BOTH sides — the render (captureAndJitter + the
		                    // VRenderSystem.projection override both read jitterX/Y, so
		                    // zeroing here means the world renders unjittered) AND the
		                    // jitterOffset passed to the FSR4 SDK (prevJitterX/Y copy the
		                    // zeroed values). If the "warp on camera move" DISAPPEARS
		                    // with debugMode=5, the render/SDK jitter contract is the
		                    // culprit (phantom jitter — one side jittered, the other
		                    // not). If it PERSISTS, the warp is MV/depth/accumulation
		                    // related, not jitter. Tradeoff in the test: no jitter =
		                    // no temporal sub-pixel diversity, so edges alias when still
		                    // — that is EXPECTED and tells you which symptom is which.
		                    //
		                    // JITTER AMPLIFIER (2026-08-09, "is the render ACTUALLY
		                    // jittered?" diagnostic): debugMode >= 8 scales the
		                    // Halton jitter by 8 (≈2px instead of 0.25px) on BOTH
		                    // sides (render override + SDK offset both read
		                    // jitterX/Y). A 2px shift on ANY world content produces
		                    // a massive color-diff vs the SDK-side-only jitter — the
		                    // color frame-diff probe then reads decisive numbers even
		                    // on flat-ish regions: >8 = render carries the jitter,
		                    // ~0 = phantom jitter. Only meaningful with the
		                    // 5-block COLOR FRAME-DIFF probe; the SDK handles the
		                    // oversized offset (it just de-jitters by the same value).
		                    && (config.debugMode < 5 || config.debugMode >= 8)) {
		                prevJitterX = jitterX;
		                prevJitterY = jitterY;
		                jitterManager.next(scaledWidth, displayWidth, jitterResult);
		                // MOTION-ADAPTIVE JITTER (2026-08-15, "shaking a bit when i
		                // moved" fix): the VKMod FSR4 path previously always passed
		                // the full ±0.5 render-px Halton jitter. When the camera
		                // moves, FSR4 renders spatially (reactive mask + constant-
		                // depth MVs can't track translation), and the full-amplitude
		                // jitter oscillates on those fresh frames = visible shake.
		                // Mirror the FSR2/3 motion-adaptive shrink: per-frame camera
		                // deltas drive the amplitude down to 40% during deliberate
		                // motion (walk/sprint/pan), while the idle bob (0.01-0.02
		                // blocks, 0.002-0.005 rad) stays below the ramp and keeps
		                // FULL amplitude standing still — edge smoothing preserved
		                // where accumulation actually works. 40% floor keeps enough
		                // sub-pixel diversity that FSR4's accumulation still
		                // reconstructs edges during motion (a full kill would alias).
		                float vkModMotionFactor = 0.0f;
		                try {
		                    Camera vkJitCam = client.gameRenderer.getMainCamera();
		                    if (vkJitCam != null) {
		                        Vec3 vkPos = vkJitCam.position();
		                        org.joml.Quaternionf vkRot = new org.joml.Quaternionf(vkJitCam.rotation());
		                        double vkDPos = 0.0;
		                        double vkDRot = 0.0;
		                        if (vkModPrevCamInit) {
		                            double dpx = vkPos.x - vkModPrevCamX;
		                            double dpy = vkPos.y - vkModPrevCamY;
		                            double dpz = vkPos.z - vkModPrevCamZ;
		                            vkDPos = Math.sqrt(dpx * dpx + dpy * dpy + dpz * dpz);
		                            vkDRot = 2.0 * Math.acos(Math.min(1.0, Math.abs(vkModPrevCamRot.dot(vkRot))));
		                        }
		                        vkModPrevCamX = vkPos.x; vkModPrevCamY = vkPos.y; vkModPrevCamZ = vkPos.z;
		                        vkModPrevCamRot.set(vkRot);
		                        vkModPrevCamInit = true;
		                        // Walking ~0.07 blocks/frame (60fps), sprint ~0.1+;
		                        // idle bob 0.01-0.02. Rotation bob 0.002-0.005 rad,
		                        // sustained look 0.01+.
		                        vkModMotionFactor = (float) Math.min(1.0, Math.max(0.0,
		                            Math.max((vkDPos - 0.02) / 0.08, (vkDRot - 0.006) / 0.014)));
		                    }
		                } catch (Throwable ignored) {
		                }
		                float vkModAmpScale = 1.0f - 0.6f * vkModMotionFactor;
		                jitterX = jitterResult[0] * vkModAmpScale;
		                jitterY = jitterResult[1] * vkModAmpScale;
		                if (config.debugMode >= 8) {
		                    jitterX *= 8.0f;
		                    jitterY *= 8.0f;
		                }
		            } else {
		                jitterX = 0.0f;
		                jitterY = 0.0f;
		            }

            // FSR4: initialize D3D12 pool + context + external VkImages.
            // Must happen here (not in onSceneRendered) because the FSR4 init
            // section after the Iris/VulkanMod checks is unreachable from here.
            // FSR3_FG_DX12 also uses the D3D12 pool (FSR4 upscale feeds FG).
            //
            // RENDER SCALE + FSR4 COMBINED (2026-08-05): previously FSR4 init was
            // skipped while render scale was configured — the bilinear blit was
            // the "upscaler", and it looked like FSR1 (it WAS a LINEAR blit,
            // worse than FSR1). Now BOTH can be active: the scene renders into
            // the scaled framebuffer (renderW/renderH = scaled dims) and the FSR4
            // SDK does the actual temporal upscale to display size (dispW/dispH).
            // The D3D12 pool already supports render-size input → display-size
            // output (the GL path's pool was 1092x683 -> 1680x1050).
            if ((isFsr4() || isFrameGenDx12()) && Fsr4Native.isAvailable()) {
                fsr4Available = true;
                // Render dims = the scaled framebuffer when render scale is
                // configured (the mixin's createResources computes these the same
                // way: Math.round(display * getRenderScale())), else display.
                float vkScale = VulkanModCompat.getRenderScale();
                int renderW = (config.vulkanModRenderScale && vkScale < 1.0f)
                    ? Math.max(1, Math.round(displayWidth * vkScale))
                    : displayWidth;
                int renderH = (config.vulkanModRenderScale && vkScale < 1.0f)
                    ? Math.max(1, Math.round(displayHeight * vkScale))
                    : displayHeight;
                int dispW = displayWidth;
                int dispH = displayHeight;

                // UNPARK (2026-08-25): re-selecting FSR4/FG after a parked
                // context (user switched away to FSR1/2/3 and back). When the
                // parked SDK context + pool match the current size, reuse them
                // — ZERO cross-API work. The VKMod path uses the D3D12-only
                // pool (no GL imports); unparkFsr4Context checks the pool type
                // + size, and on mismatch tears the parked context down so the
                // needsRecreate path below re-inits fresh.
                if (unparkFsr4Context(renderW, renderH, dispW, dispH, false)) {
                    fsr4Available = true;
                    // Parked context reused — skip the whole init path.
                }

                long now = System.currentTimeMillis();
                boolean needsRecreate = !fsr4Initialized
                    || fsr4ContextRenderW != renderW || fsr4ContextRenderH != renderH
                    || fsr4ContextDisplayW != dispW || fsr4ContextDisplayH != dispH;

                // HARD-DISABLE GATE (2026-08-30, "runs then completely freezes"
                // — VKMod crash-loop fix): a hard-disabled (5 consecutive dispatch
                // crashes) session MUST NOT rebuild the D3D12 device + pool. Each
                // rebuild after an SDK C++-exception crash was the render-thread
                // hang on a wedged AMD iGPU (diag log ended mid-recreate with no
                // follow-up ensureInitialized). The latch is cleared by a
                // deliberate markDirty()/applyAutoScale() change (the user
                // retrying).
                if (needsRecreate && !fsr4HardDisabled) {
                    if (now - lastFsr4ContextChange < FSR4_DEBOUNCE_MS) {
                        // Too soon since last recreation — skip
                    } else {
                        lastFsr4ContextChange = now;
                        if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; fsr4AsyncPending = false; fsr4Suspended = false; fsr4BypassNotified = false; fsr4ConsecutiveSkips = 0; VulkanModCompat.fsr4VkModActive = false; } fsr4InitDeferStartMs = 0; fsr4InitDeferFrames = 0;

                        try {
                            if (sharedTexMgr == null) sharedTexMgr = new SharedTextureManager();
                            // D3D12-only pool allocation — safe without GL context (VulkanMod)
                            boolean poolOk = sharedTexMgr.allocateFsr4PoolD3D12(renderW, renderH, dispW, dispH);
                            if (poolOk) {
                                boolean ok = Fsr4Native.initFsr4(renderW, renderH, dispW, dispH);
                                if (ok) {
                                    fsr4Initialized = true;
                                    syncNativeDiagnostics();
                                    // FG on this AMD iGPU removes the D3D12 device at the
                                    // first frame (the FG SDK context cannot coexist with the
                                    // FSR4 context) — disable FG for the session and run plain
                                    // real FSR4 (no FG context, no crash, real temporal quality).
                                    maybeDisableFgOnIgpu();
                                    // Pre-arm the bilinear bypass before the first dispatch when FG
                                    // is live on an AMD iGPU (see maybePreArmBypassForFgIgpu).
                                    maybePreArmBypassForFgIgpu();
                                    fsr4FramesSkipped = 0;
                                    fsr4ConsecutiveSkips = 0; fsr4Suspended = false;
                                    // DELIBERATE LATCH PRESERVATION (2026-08-30): a
                                    // successful reinit must NOT clear the hard-disable
                                    // counters. The crash-loop this latch stops is "crash
                                    // -> device+pool rebuild -> (successful reinit) ->
                                    // crash -> ..." — if the reinit cleared
                                    // fsr4DispatchFailCount, the 5-strike escalation
                                    // never latches, needsRecreate stays open, and the
                                    // expensive rebuild (the actual render-thread hang
                                    // on a wedged AMD iGPU) keeps firing forever. The
                                    // counters clear only on a genuinely good dispatch
                                    // (~5972) or a deliberate markDirty()/applyAutoScale().
                                    fsr4FenceTimedOutPrevFrame = false; fsr4ProbeCleanStreak = 0; fsr4PreCheckSkips = 0; fsr4EmaWorldMs = 0.0f;
                                    fsr4ContextRenderW = renderW; fsr4ContextRenderH = renderH;
                                    fsr4ContextDisplayW = dispW; fsr4ContextDisplayH = dispH;
                                    FSRMod.LOGGER.info("FSR4: VulkanMod init OK ({}x{} -> {}x{})", renderW, renderH, dispW, dispH);

                                    // Init external VkImages ONCE after pool/context creation.
                                    // These are only recreated when needsRecreate fires (resize).
                                    // Pass render dims + display dims SEPARATELY (2026-08-05):
                                    // with render scale active the pool input textures are
                                    // scaled-sized and the output is display-sized — FSR4
                                    // upscales render→display.
                                    VulkanModCompat.initExternalImages(renderW, renderH, dispW, dispH);
                                    // Import input from D3D12 pool color (index 0) into VK.
                                    // Then import output from pool (D3D12→VK) as well.
                                    // Both use the D3D12→VK import pattern, avoiding the
                                    // VK→D3D12 OpenSharedHandle path that crashes on AMD
                                    // Radeon 860M (ACCESS_VIOLATION + heap corruption).
                                    VulkanModCompat.importInputFromD3D12Pool(
                                        sharedTexMgr.getColorAllocPtr(), 0);  // pool index 0 = COLOR
                                    VulkanModCompat.importOutputFromD3D12Pool(
                                        sharedTexMgr.getOutputAllocPtr(), 4);  // pool index 4 = OUTPUT

                                    // REAL FSR4 INPUTS (2026-08-05): import the pool's
                                    // depth (1), MV (2) and reactive (3) textures as
                                    // VkImages aliasing the D3D12 shared memory. The VK
                                    // MV-gen compute shader (recordVkModMvGen in the
                                    // main-pass end() hook) writes depth=0.5 + motion
                                    // vectors + reactive mask into them each frame, so
                                    // the REAL FSR4 SDK dispatch has valid inputs.
                                    // WITHOUT this, the pool inputs stayed empty and
                                    // "FSR4" under VulkanMod was a 1:1 CopyResource
                                    // passthrough (the pipeline-test bypass) that looked
                                    // like FSR1.
                                    // VkFormat: R32_SFLOAT=100, R16G16_SFLOAT=88, R8_UNORM=9
                                    // REVIEW FIX (2026-08-05): capture the import
                                    // results — fsr4VkModActive must only be set when
                                    // ALL pool-texture imports succeed. A silent
                                    // JNI_FALSE (importPoolTextureAsVkImage returns
                                    // false, doesn't throw) would otherwise route the
                                    // mixin to the FSR4-scaled path whose natives bail
                                    // on null images → no blit fallback → unpresented
                                    // swapchain in render-scale mode.
                                    boolean depthOk = VulkanModCompat.importPoolTextureAsVkImage(
                                        sharedTexMgr.getColorAllocPtr(), 1, 0, 100);  // DEPTH
                                    boolean mvOk = VulkanModCompat.importPoolTextureAsVkImage(
                                        sharedTexMgr.getColorAllocPtr(), 2, 1, 88);   // MV
                                    boolean reactiveOk = VulkanModCompat.importPoolTextureAsVkImage(
                                        sharedTexMgr.getColorAllocPtr(), 3, 2, 9);    // REACTIVE

                                    // FSR3_FG_DX12 step 2 (2026-08-15): import pool 5
                                    // (FG_OUTPUT — the D3D12 FG SDK writes it, our VK
                                    // copy reads it for the second present) and pool 6
                                    // (FG_PRESENT — our VK copy writes the real+GUI
                                    // frame, the SDK reads it as presentColor) as
                                    // DISPLAY-size VkImages. VkFormat R8G8B8A8_UNORM=37.
                                    // Not fatal if they fail: FG falls back to
                                    // single-present (step-1 behavior) via
                                    // vkModFgPresentImagesReady=false.
                                    vkModFgPresentImagesReady = false;
                                    if (fgActive() && Fg4Native.isAvailable()) {
                                        boolean fgOutOk = VulkanModCompat.importFgPoolTextureAsVkImage(
                                            sharedTexMgr.getColorAllocPtr(), 5, 0, 37);  // FG_OUTPUT
                                        boolean fgPresentOk = VulkanModCompat.importFgPoolTextureAsVkImage(
                                            sharedTexMgr.getColorAllocPtr(), 6, 1, 37);  // FG_PRESENT
                                        vkModFgPresentImagesReady = fgOutOk && fgPresentOk;
                                        if (!vkModFgPresentImagesReady && shouldDebug()) {
                                            FSRMod.LOGGER.warn("FG_DX12: pool 5/6 VkImage import failed (out={} present={}) — single-present only", fgOutOk, fgPresentOk);
                                        }
                                    }

                                    // REVIEW FIX (2026-08-05): the pool was just
                                    // (re)created + re-imported — its content is
                                    // undefined until the first end() hook records
                                    // Copy1 + MV-gen into it. Clear the populated
                                    // flag so the dispatch gate above holds for the
                                    // first frame after init/recreate (otherwise a
                                    // stale true from a previous pool would let the
                                    // first dispatch read garbage).
                                    VulkanModCompat.fsr4InputPopulated = false;
                                    // Render-scale + FSR4: mark the real-FSR4 path
                                    // active so the mixin's end() hook uses
                                    // recordVkModFsr4ScaledCopy instead of the
                                    // bilinear blit (2026-08-05). ONLY when all three
                                    // pool-texture imports succeeded (review fix) —
                                    // otherwise the mixin keeps the bilinear blit
                                    // fallback (correct image, just not FSR4 quality).
                                    VulkanModCompat.fsr4VkModActive = depthOk && mvOk && reactiveOk;
                                    if (!VulkanModCompat.fsr4VkModActive && shouldDebug()) {
                                        FSRMod.LOGGER.warn("FSR4: VulkanMod pool-texture import failed (depth={} mv={} reactive={}) — keeping bilinear render-scale blit", depthOk, mvOk, reactiveOk);
                                    }

                                    // FSR3_FG_DX12 under VULKANMOD (2026-08-15, step 1
                                    // + step 2): init the FG SDK context now that the D3D12
                                    // pool + FSR4 context exist. The dispatch gate accepts
                                    // isFrameGenDx12() and the mixin/UIHelper fires the FG
                                    // run after the relevant pool content is valid.
                                    // STEP 2 (double-present): on the render-scale path the
                                    // GUI paints into the swapchain AFTER the FSR4 copies
                                    // (native-res UI) — the real frame + GUI is captured
                                    // from the swapchain into pool 6 post-GUI, so
                                    // presentColor = pool 6 and HUDLessColor = pool 4 (FSR4
                                    // output, no GUI) — the SDK extracts the HUD from the
                                    // diff and composites it onto generated frames (GL
                                    // parity). On the NON-scaled path the GUI is baked into
                                    // pool 4 at 1:1 (Copy1 captures the swapchain), so
                                    // presentColor = pool 4 directly.
                                    if (fgActive() && Fg4Native.isAvailable()
                                        && VulkanModCompat.isActive()) {
                                        boolean vkFgScaledPath = VulkanModCompat.renderScaleSwapped();
                                        // Scaled path: pool 6 (presentColor) is captured from the
                                        // swapchain AFTER the GUI paints; non-scaled: presentColor
                                        // is pool 4 directly (GUI baked in at 1:1).
                                        vkModFgNeedsCapture = vkFgScaledPath;
                                        vkModFgCapturedThisFrame = false;
                                        boolean fgOk = Fg4Native.nativeInit(
                                            renderW, renderH, dispW, dispH,
                                            sharedTexMgr.getColorAllocPtr(),
                                            vkFgScaledPath ? 6 : 4,  // presentColor: pool 6 (real+GUI) scaled / pool 4 (FSR4 out) non-scaled
                                            1,  // depth
                                            2,  // motion vectors
                                            5,  // FG output
                                            4,  // HUDLessColor = FSR4 output (no GUI)
                                            Fg4Native.getFgDllDir());
                                        if (fgOk) {
                                            fg4Initialized = true;
                                            // A successful FG SDK init proves FG works
                                            // now — clear any stale permanent-disable so
                                            // the recovered state actually engages (the
                                            // latch is already cleared on deliberate
                                            // rebuilds; this is the belt-and-suspenders
                                            // for any other path that set it).
                                            fg4PermanentlyDisabled = false;
                                            fg4InitFailed = false;
                                            fg4InitFailCount = 0;
                                            fgRuntimeFailCount = 0;
                                            fgStallConsecutive = 0;
                                            FSRMod.LOGGER.info("FG_DX12: Fg4Native init OK");
                                        } else {
                                            fg4Initialized = false;
                                            fg4InitFailCount++;
                                            if (fg4InitFailCount >= FG_MAX_INIT_FAILURES) {
                                                fg4InitFailed = true;
                                                FSRMod.LOGGER.warn("FG_DX12: Fg4Native init failed {} times — FG disabled for this session (plain FSR4); a settings change re-arms it", FG_MAX_INIT_FAILURES);
                                            } else {
                                                FSRMod.LOGGER.warn("FG_DX12: Fg4Native init failed ({}/{}) — will retry on next FSR4 init", fg4InitFailCount, FG_MAX_INIT_FAILURES);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            FSRMod.LOGGER.warn("FSR4: VulkanMod pool/init failed: {}", e.getMessage());
                        }
                    }
                }
            }

            // FSR4 temporal dispatch — processes the scene captured in the input
            // VkImage by the previous frame's endFrame copy. This is a one-frame
            // delay: on frame N, we process frame N-1's scene and display the
            // FSR4 result on frame N's swapchain (copied by endFrame's
            // output → swapchain path).
            // Camera params come from lastProj which was saved by setProjection()
            // during the previous frame's renderLevel(HEAD) — matching the
            // previous frame's scene in the input VkImage.
            if ((isFsr4() || isFrameGenDx12()) && fsr4Available && fsr4Initialized
                && !fsr4HardDisabled  // (2026-08-30) a hard-disabled (crash-loop) session must not re-arm the D3D12 dispatch
                // Render-scale + FSR4 COMBINED (2026-08-05): the old
                // !renderScaleActive() gate here made render-scale mode purely
                // bilinear (the "FSR4 looks like FSR1" bug — it WAS a LINEAR
                // blit). With render scale active the pool input is scaled-sized
                // and the SDK upscales render→display; the mixin now records
                // recordVkModFsr4ScaledCopy. Dispatch must run in BOTH modes.
                && sharedTexMgr != null && sharedTexMgr.getColorAllocPtr() != 0
                && Math.abs(lastProj.m11()) > 0.001f
                // REVIEW FIX (2026-08-05): gate on pool[0] having been
                // populated at least once. On the VERY FIRST frame, Copy1
                // (scene -> pool color) hasn't run yet — it is recorded later
                // in the same frame's end() hook — so the input VkImage is
                // undefined memory. Dispatching would feed the FSR4 SDK garbage
                // input and present the garbage result via Copy2 (a one-frame
                // flash at world join). recordVkModMvGen / the fence store also
                // happen in end(), so after the first end() the pool is valid
                // and the bounded fence wait below orders the D3D12 read.
                && VulkanModCompat.fsr4InputPopulated) {

                float a = lastProj.m22();
                float b = lastProj.m23();
                float cameraNear = b / (a - 1.0f);
                float cameraFar = b / (a + 1.0f);
                // CRITICAL camera guard (2026-08-05): under VulkanMod the
                // projection hook does NOT reliably capture the world projection,
                // so lastProj can be garbage — the diag log showed near=0.50
                // far=-986895.06 (cameraNear passed the old near-only guard and
                // the SDK got an absurd far plane → broken history accumulation).
                // Guard BOTH bounds: near must be positive and sane, far must be
                // positive and < 1e7 (a far plane of millions is a broken
                // matrix, not a real render distance).
                if (Float.isNaN(cameraNear) || Float.isNaN(cameraFar)
                        || cameraNear <= 0.0f || cameraNear > 1000.0f
                        || cameraFar <= 0.0f || cameraFar > 1e7f
                        || cameraFar <= cameraNear) {
                    cameraNear = 0.1f;
                    cameraFar = 1000.0f;
                }
                float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
                float cameraFovY = (float) (2.0 * Math.atan(tanHalfFovY));
                float deltaTime = lastFrameDeltaMs;  // inter-frame ms (2026-08-08 fix — was intra-frame ~1.0)
                int frameIndex = fsr2FrameCount;
                boolean reset = fsr2FrameCount == 0;

	                // Bounded wait for the previous frame's swapchain copy to
	                // complete (VulkanMod's per-frame fence, NOT a full queue
	                // drain). On timeout the GPU queue is saturated — skip THIS
	                // frame's dispatch quietly; the displayed image stays the
	                // previous frame's FSR4 output.
	                boolean copyReady = VulkanModCompat.waitForFrameCopy();

	                boolean dispatched = false;
	                if (copyReady) {
	                    dispatched = VulkanModCompat.dispatchFsr4VkMod(
	                        prevJitterX, prevJitterY,  // jitter the pooled (previous) frame's scene was rendered with — FSR4 needs the sub-pixel sample offsets to temporally accumulate (was hardcoded 0,0 → spatial-only output, "no edge smoothing")
	                        cameraFar, cameraNear, cameraFovY,
	                        deltaTime, frameIndex, reset,
	                        sharedTexMgr.getColorAllocPtr(), 1,  // DEPTH index
	                        sharedTexMgr.getColorAllocPtr(), 2,  // MV index
	                        sharedTexMgr.getColorAllocPtr(), 3,  // REACTIVE index
	                        sharedTexMgr.getOutputAllocPtr(), 4  // OUTPUT index
	                    );
	                }

	                if (!dispatched && !copyReady) {
	                    // GPU queue saturated — skip this frame's dispatch quietly
	                    // (transient; the free queue resumes next frame).
	                } else if (!dispatched) {
	                    // VKMOD DISPATCH-FAILURE ESCALATION (2026-08-30, "FSR3 FG DX12
	                    // runs then completely freezes" — VulkanMod path). The GL
	                    // dispatch site mirrors this exact block; without it a
	                    // genuinely broken FSR4 SDK (g_ffxDispatch throws 0xe06d7363
	                    // — MSVC C++ exception, caught by SEH → dispatch returns
	                    // false) was silently ignored here: fsr4DispatchFailCount
	                    // never incremented, fsr4HardDisabled never set, the
	                    // dispatch re-armed every frame, and each crash triggered a
	                    // full D3D12 device + pool rebuild (needsRecreate) which on
	                    // a wedged AMD iGPU hangs the render thread permanently
	                    // while the server thread keeps ticking. Escalate the same
	                    // way the GL path does: count consecutive failures and
	                    // hard-disable to FSR1 after the threshold, routing through
	                    // the D3D12-free spatial render.
	                    fsr4DispatchFailCount++;
	                    if (fsr4DispatchFailCount >= FSR4_MAX_CONSECUTIVE_DISPATCH_FAILURES) {
	                        fsr4HardDisabled = true;
	                        if (shouldDebug()) {
	                            FSRMod.LOGGER.warn("FSR4: {} consecutive VKMod dispatch failures — D3D12 path presumed broken, disabling FSR4 for this session (FSR1)", fsr4DispatchFailCount);
	                        }
	                    } else if (shouldDebug()) {
	                        FSRMod.LOGGER.warn("FSR4: VKMod dispatch failed ({}/{}) — suspending FSR4 (FSR1 spatial until the broken path is cleared)", fsr4DispatchFailCount, FSR4_MAX_CONSECUTIVE_DISPATCH_FAILURES);
	                    }
	                    lastFsr4DispatchFail = System.currentTimeMillis();
	                    fsr4Suspended = true;
	                    fsr4SuspendedAtMs = System.currentTimeMillis();
	                    fsr4FirstCleanProbeMs = 0;
	                    fsr4WorldCalmStreak = 0;
	                    hasPrevFrame = false;   // temporal history from a broken dispatch is garbage
	                    fsr2FrameCount = 0;
	                }

	                if (dispatched) {
	                    // NO waitIdle here: the D3D12 dispatch was submitted async
	                    // and runs on the GPU while the scene renders. The D3D12
	                    // output wait moved to VulkanModMainPassMixin (before it
	                    // records Copy2 output -> swapchain), where the dispatch
	                    // has already overlapped the whole scene render — so the
	                    // wait is ~0ms (the VKMod equivalent of the GL path's
	                    // fsr4Async overlap).
	                    // FSR3_FG_DX12 under VulkanMod (2026-08-15): stash the FG
	                    // prepare params for the pooled (previous-frame) scene the
	                    // dispatch just processed. The FG run fires from the
	                    // mixin/UIHelper after waitForD3D12Output(), when pool 4 is
	                    // fully written. Camera = the PREVIOUS frame's camera
	                    // (onRenderBegin runs before renderLevel updates it) —
	                    // consistent with lastProj/prevJitter feeding this dispatch.
	                    if (fgActive() && fg4Initialized) {
	                        // Frame-start reset of the GUI-capture flag: the capture
	                        // (onVkModGuiPainted, during the GUI phase) sets it true
	                        // LATER in this frame; presentVkModFgFrame (endFrame)
	                        // checks it. Resetting here (before the GUI) makes the
	                        // guard read "was the swapchain captured THIS frame" —
	                        // frames where GuiRenderer.render never runs skip FG
	                        // rather than feeding the SDK a stale pool 6.
	                        vkModFgCapturedThisFrame = false;
	                        stashVkModFgParams();
	                    }
	                    fsr2FrameCount++;
	                    hasPrevFrame = true;
	                    // Recovered — clear the failure counters so a single
	                    // transient dispatch failure (followed by many good frames)
	                    // can never accumulate to a hard-disable. VKMod has no GL
	                    // resume-probe, so a successful dispatch must also resume
	                    // immediately instead of staying suspended.
	                    fsr4DispatchFailCount = 0;
	                    fsr4Suspended = false;
	                }
	            }

	            if (config.version != FSRConfig.FSRVersion.FSR4 && !isFrameGenDx12() && !vulkanModFallbackLogged) {
                // One-shot: this line previously ran EVERY frame under VulkanMod
                // (the block above returns on every path, so the caller can't
                // tell), spamming the log dozens of times per second.
                vulkanModFallbackLogged = true;
                if (vkRenderScale) {
                    FSRMod.LOGGER.info("VulkanMod render-scale active (FSR{}) — qualityScale drives the scaled framebuffer + bilinear blit upscale",
                        config.version == FSRConfig.FSRVersion.FSR1 ? "1" :
                        config.version == FSRConfig.FSRVersion.FSR2 ? "2" : "3");
                } else {
                    FSRMod.LOGGER.info("VulkanMod active: only FSR4 upscaling is supported (FSR{}) — falling back to pass-through",
                        config.version == FSRConfig.FSRVersion.FSR1 ? "1" :
                        config.version == FSRConfig.FSRVersion.FSR2 ? "2" : "3");
                }
            }
            return;
        }

        // Distant Horizons: LOD terrain uses separate framebuffers with own depth
        // textures. FSR2/3 temporal reconstruction needs unified depth — DH depth
        // is not available. Auto-fallback to FSR1 (spatial upscale) for correct visuals.
        dhActive = DistantHorizonsCompat.isLoaded();
        if (dhActive) {
            DistantHorizonsCompat.logStatus();
            if (isTemporalUpscaler()) {
                FSRMod.LOGGER.warn("Distant Horizons detected with FSR2/3 temporal upscaler — DH depth not available, switching to FSR1");
            }
        }

        // Photonics: an Iris extension (voxel raytracing) that allocates its own
        // ColorFramebuffers at WINDOW size — see the irisTemporal gate comment
        // above. Its presence disables the scaled-proxy Iris swap entirely;
        // log once on first frame so a Photonics session is diagnosable.
        if (PhotonicsCompat.isLoaded()) {
            PhotonicsCompat.logStatus();
        }

        // Non-Iris path: use ScaledRenderTarget proxy so Voxy and other mods
        // see native dimensions while rendering goes to scaled textures.
        // GATED: when FSR is disabled (config.enabled=false), skip the proxy
        // entirely — the game should render at native resolution. Without
        // this gate, qualityScale<1.0 still creates a proxy and sets it as
        // mainRT, causing the UI to render at proxy resolution.
        if (!config.enabled && originalMainRenderTarget != null) {
            mainRenderTarget = originalMainRenderTarget;
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
        }
        if (config.enabled) {
        originalMainRenderTarget = client.getMainRenderTarget();
        mainRenderTarget = originalMainRenderTarget;

        // IRIS DEPTH-BUFFER VERSION (2026-08-29): bump the version whenever the
        // underlying scene target is (re)created, and hand it to the proxy. A
        // recreate at UNCHANGED dimensions (the FSR version-switch case) is
        // otherwise invisible to Iris — see the sceneTargetVersion field doc.
        // The very first creation also bumps 0 -> 1, which is desirable: Iris
        // initialises its cachedDepthBufferVersion to 0, so the first real
        // proxy (version 1) is guaranteed to be adopted.
        if (renderTarget == null || renderTarget.width != scaledWidth || renderTarget.height != scaledHeight) {
            if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
            renderTarget = new TextureTarget("fsr-scene", scaledWidth, scaledHeight, true);
            sceneTargetVersion++;
        }

        proxyTarget = new ScaledRenderTarget(
            scaledWidth, scaledHeight,
            renderTarget.getColorTexture(),
            renderTarget.getColorTextureView(),
            renderTarget.getDepthTexture(),
            renderTarget.getDepthTextureView(),
            originalMainRenderTarget,  // resize() forwards here (monitor-move fix)
            sceneTargetVersion
        );

	        ((MinecraftClientAccessor) client).setMainRenderTarget(proxyTarget);

	        // Set viewport to scaled resolution so the 3D scene renders at lower res
	        // (proxy reports actual scaled dims, but some mods like Voxy read
	        // window dimensions — this viewport override ensures correct pixel count).
	        // Iris included (2026-08-18): with the swap active the pack's composite
	        // blits into the scaled proxy, so the GL viewport must match or the
	        // final pass writes at the wrong offset.
	        if (scaledWidth > 0 && scaledHeight > 0) {
	            GL43C.glViewport(0, 0, scaledWidth, scaledHeight);
	        }
        } // end config.enabled gate (proxy creation)

if (isTemporalUpscaler()) {
	            prevJitterX = jitterX;
	            prevJitterY = jitterY;
	            jitterManager.next(scaledWidth, displayWidth, jitterResult);
	            // Sub-pixel Halton jitter applied to ALL temporal upscalers (FSR2/3/4).
	            // The mixin hook (GameRendererMixin.jitterLevelProjection) applies this
	            // offset to the world projection in clip space; the same offset is
	            // passed to the GL compute MV-gen shaders (via uJitter) for NDC
	            // de-jitter, to the GL ACCUMULATE shader for history sample alignment,
	            // and to the FSR4 SDK dispatch for output-grid alignment.
	            //
	            // Previously the FSR4 SDK path forced jitter=(0,0) because depth/MV
	            // were not reaching the D3D12 device (SHARE_VERIFY depthC=0.0,
	            // DEBUG_MV 0/9), and jitter without valid history reprojection caused
	            // shimmer. With depth now flowing via DEPTH_COPY_CS + staging +
	            // glCopyImageSubData, MV gen using per-pixel depth (uUseDepth=1),
	            // and prevViewProj being saved each frame, the SDK has the data it
	            // needs and can reconstruct sub-pixel detail from Halton samples.
	            // Without this jitter, FSR4 output is effectively a spatial upscale
	            // (pixelated at low render scales) — no temporal super-resolution.
            // GL temporal pipelines (FSR2/3): jitter amplitude in RENDER pixels.
            // The 2026-08-06 "no edge smoothing" fix: the previous cap bounded the
            // DISPLAY-space amplitude at 0.6 px, which at 25% scale (420x263 ->
            // 1680x1050) crushed the jitter to ±0.15 render px (0.6*0.25) — 30% of
            // FSR2's standard ±0.5 render-px Halton spread. The temporal filter
            // averages 12+ accumulated samples that are all within ±0.3 render px
            // of each other: the average ≈ the current spatial sample, so the
            // upscale looked like a plain spatial upscale — "smoothing is gone".
            // Edge smoothing IS the jitter spread: FSR2 reconstructs sub-pixel
            // detail by blending samples taken at well-separated sub-pixel
            // offsets. Now allow up to 1.5 display px (min(0.5, 1.5*scale)) —
            // at 25% that's ±0.375 render px (75% of standard, 2.5x the old
            // spread); at ≥33% it's the full ±0.5 render px. The rejected-pixel
            // shake the old 0.6 cap was hiding is mostly gone anyway (clamp
            // damping 0.35 blends misaligned history as blur instead of
            // discarding it), so the cap can afford to be generous again.
            // FSR4 keeps full amplitude (its SDK de-jitters internally and
            // benefits from the sub-pixel coverage).
            if (isFsr2() || isFsr3()) {
                float jitterAmp = (float) Math.min(0.5, JITTER_DISPLAY_PX_MAX * scaledWidth / displayWidth);
                // Motion-adaptive jitter (2026-08-06, "still shaking" fix): with
                // constant-depth MVs (no real depth on GL), camera TRANSLATION makes
                // reprojection diverge at depth edges -> history rejected -> the fresh
                // jittered frame shows through. At 25% scale the jitter is ±0.375
                // render px = ±1.5 DISPLAY px — visible shake exactly while moving
                // (log: ACCUM_WEIGHT B collapses to the 0.167 isNewSample floor during
                // motion). Shrink the amplitude with sustained translation: motion
                // frames barely use sub-pixel diversity anyway, so the smoothing cost
                // is negligible while the perceived shake roughly halves (1.5 -> 0.75
                // display px at 25%). The signal is the
                // camera's deviation from a slow EMA position — the periodic idle bob
                // oscillates around the EMA center and cancels; walking/sprinting
                // diverges within a few frames. Rotation MVs are depth-independent
                // (correct at constant depth), so pure looking doesn't reject history
                // and keeps full jitter.
                Camera jitCam = client.gameRenderer.getMainCamera();
                Vec3 camPosNow = jitCam.position();
                Quaternionf camRotNow = jitCam.rotation();
                if (!fsr23EmaCamInit) {
                    fsr23EmaCamX = camPosNow.x; fsr23EmaCamY = camPosNow.y; fsr23EmaCamZ = camPosNow.z;
                    fsr23EmaCamQ.set(camRotNow);
                    fsr23EmaCamInit = true;
                } else {
                    fsr23EmaCamX = fsr23EmaCamX * 0.9 + camPosNow.x * 0.1;
                    fsr23EmaCamY = fsr23EmaCamY * 0.9 + camPosNow.y * 0.1;
                    fsr23EmaCamZ = fsr23EmaCamZ * 0.9 + camPosNow.z * 0.1;
                    fsr23EmaCamQ.nlerp(camRotNow, 0.1f).normalize();
                }
                double dCamX = camPosNow.x - fsr23EmaCamX;
                double dCamY = camPosNow.y - fsr23EmaCamY;
                double dCamZ = camPosNow.z - fsr23EmaCamZ;
                double camDev = Math.sqrt(dCamX * dCamX + dCamY * dCamY + dCamZ * dCamZ);
                double dRot = 2.0 * Math.acos(Math.min(1.0, Math.abs(fsr23EmaCamQ.dot(camRotNow))));
                // Translation and rotation both drive history rejection (translation via
                // constant-depth parallax divergence; rotation empirically too — the log
                // couldn't distinguish which). Shrink jitter when EITHER deviates.
                float motionFactor = (float) Math.min(1.0, Math.max(camDev / 0.25, dRot / 0.12));
                // Still-camera gate for sky/cloud accumulation (2026-08-06 v2,
                // "nothing changed" fix): v1 required camDev<0.05 (walking's
                // steady-state EMA lag is ~0.65, so walking always excluded) AND
                // the EMA deviation dRot<0.02 (any recent mouse look kept dRot
                // above 0.02 for ~30 frames of EMA convergence). The log showed the
                // camera in motion for nearly the whole test session -> uCamStill
                // almost never engaged -> the sky stayed blocky. v2 measures the
                // PER-FRAME rotation delta instead (engages the frame after the
                // mouse stops) and DROPS the translation gate: the sky is at
                // infinity, so its screen position only changes with rotation -
                // walking with a steady mouse leaves it fixed on screen, and the
                // ACCUMULATE shader forces sky reprojection to identity (mv=0) so
                // the mid-depth translation MVs can't misalign it. The EMA term
                // stays as a slow-look backstop (a continuous pan pushes the EMA
                // lag past 0.04 rad even at low per-frame deltas).
                double dRotFrame = 2.0 * Math.acos(Math.min(1.0, Math.abs(fsr23PrevCamRot.dot(camRotNow))));
                fsr23PrevCamRot.set(camRotNow);
                // Instantaneous per-frame translation (no EMA lag — camDev stays low
                // for ~10 frames after motion starts, during which the shake would
                // otherwise persist at full amplitude).
                double dPosFrame = 0.0;
                if (fsr23PrevCamPosInit) {
                    double dpx = camPosNow.x - fsr23PrevCamPosX;
                    double dpy = camPosNow.y - fsr23PrevCamPosY;
                    double dpz = camPosNow.z - fsr23PrevCamPosZ;
                    dPosFrame = Math.sqrt(dpx * dpx + dpy * dpy + dpz * dpz);
                }
                fsr23PrevCamPosX = camPosNow.x; fsr23PrevCamPosY = camPosNow.y; fsr23PrevCamPosZ = camPosNow.z;
                fsr23PrevCamPosInit = true;
                // PER-TICK NORMALIZATION (2026-08-07): dPosFrame is the PER-RENDER-FRAME
                // camera delta (~0.072 blocks/frame at 60fps walking), but the camReactive
                // floor and fast-jitter thresholds below were calibrated in PER-TICK space
                // (walking 0.216 blocks/tick = 4.3 m/s; sprint 0.28). Feeding frame-space
                // deltas into tick-space thresholds made the floor read 0.04 instead of
                // 0.30 and the fast stage's >0.12 gate never fire at 60fps (log 2026-08-07:
                // camReactive=0.000 on 100/130 samples during M=5-18px motion bursts; zero
                // DIAG_FAST lines all session). Convert with the measured frame duration so
                // the behavior is FPS-independent.
                long fsr23NowNanos = System.nanoTime();
                double fsr23DtSec = (fsr23LastMotionNanos == 0L) ? 1.0 / 60.0
                    : Math.min(0.25, Math.max(0.001, (fsr23NowNanos - fsr23LastMotionNanos) / 1e9));
                fsr23LastMotionNanos = fsr23NowNanos;
                double fps = (fsr23DtSec > 0.0) ? Math.min(240.0, Math.max(10.0, 1.0 / fsr23DtSec)) : 60.0;
                double tickNorm = fps / 20.0; // frames per tick
                double dPosTick = dPosFrame * tickNorm;
                fsr23CamStill = dRotFrame < 0.012 && dRot < 0.025;  // EMA backstop: a 0.004 rad/frame sustained pan (14°/s sky survey) would otherwise keep the lag at ~0.036 < 0.04 and smear clouds through the loose clamp
                // uCamStillExact (2026-08-07, "shaking only when standing still" fix):
                // uCamStill dropped its TRANSLATION gate (sky-only reasoning - sky has
                // zero parallax, translation doesn't move it). Terrain is different: the
                // idle bob is real translation whose near-content parallax (0.5-4px at
                // z<10) the constant-depth (z~100) MVs underestimate (~0.05px), so the
                // history read oscillates with the bob, the box clamp rejects it, and the
                // jittered current frame shows through = the standing-still shake. When
                // the camera is GENUINELY still (per-tick translation < 0.08: bob 0.03-0.06,
                // crouch-walk 0.108, walk 0.216; per-frame rotation < 0.006 rad: bob
                // 0.002-0.005; EMA rotation backstop < 0.02 for slow sustained pans) force
                // mv=0 for ALL pixels -> exact history alignment, full temporal accumulation.
                // EMA backstop 0.015 (reviewer 2026-08-07): 0.02 left ~30 frames of residual
                // wobble after stopping a mouse look (EMA lag decay 0.036*0.9^n < 0.02);
                // 0.015 halves that to ~11 frames while a sustained 14 deg/s pan still
                // disengages (steady-state lag ~0.036 >> 0.015) and bob stays ~0.005.
                fsr23CamStillExact = dPosTick < 0.08 && dRotFrame < 0.006 && dRot < 0.015;
                // Camera-motion reactive floor (2026-08-07, "fix the shaking" fix): the
                // reactive mask derives from constant-depth MVs (z~100 blocks) that
                // UNDERESTIMATE near content's true screen motion -> walking only reaches
                // reactive 0.04-0.15 (log frame 1824) while history is misaligned by up to
                // ~10x the computed MV -> ~90% misaligned history stayed blended = smear +
                // edge wobble (the shake), worst at speed. The CPU knows the camera is
                // moving exactly (per-frame deltas). Floor the reactive mask on known
                // motion so ACCUMULATE shifts the blend toward the de-jittered (stable)
                // current frame and the lock stops cementing misaligned history. TRANSLATION
                // only (rotation MVs are depth-independent/exact at constant depth; fast
                // pans are already caught by the MV ramp). Bob is ~0.01-0.02 blocks and
                // ~0.002 rad/frame -> thresholds start above that; 0.9 cap keeps a little
                // history for stability even at sprint. Zero standing still = bit-identical
                // to the pre-fix blend.
                double camReactiveVal = 0.0;
                if (fsr23PrevCamPosInit) {
                    camReactiveVal = Math.min(0.9, Math.max(
                        (dPosTick - 0.05) / 0.55,   // per-TICK: walking 0.216 -> 0.30; sprint 0.28 -> 0.42; elytra 1.0 -> 0.9 cap
                        (dRotFrame - 0.04) / 0.05)); // extreme turns only (>=2.3 deg/frame); pans are depth-exact, MV ramp + fast-jitter cover them
                    camReactiveVal = Math.max(0.0, camReactiveVal);
                }
                fsr23CamReactive = (float) camReactiveVal;
                if (fsr23CamStill && !fsr23CamStillLogged
                        && System.currentTimeMillis() - lastCamStillLogMs > 2000) {
                    fsr23CamStillLogged = true;
                    lastCamStillLogMs = System.currentTimeMillis();
                    FSRMod.LOGGER.info("[CAMSTILL] sky/cloud accumulation ENGAGED frame={} dRotFrame={} dRot={}",
                        timingFrameCount, String.format("%.4f", dRotFrame), String.format("%.4f", dRot));
                } else if (!fsr23CamStill) {
                    fsr23CamStillLogged = false;
                }
                // Motion jitter capped at a FIXED DISPLAY-space amount (1.0 px) rather
                // than a flat fraction of the stationary amplitude (2026-08-06 v2,
                // "less shaking but edges still jagged" fix): the flat 0.4 floor cut
                // the walking jitter to ±0.15 render px at 25% — too little sub-pixel
                // spread to reconstruct edges while walking (jagged). Interpolating
                // toward min(jitterAmp, 1.0*scale) keeps ±0.25 render px (±1.0 display
                // px, 50% of FSR2's standard ±0.5 render-px spread) during motion at
                // 25% — edges reconstruct while shake stays a third below the pre-adaptive
                // ±1.5 — and at ≥50% scale the cap converges to the stationary amplitude,
                // so walking keeps FULL reconstruction there (the old flat factor also
                // over-shrunk at 65%). Reviewer: 0.75 px was still marginal (barely above
                // the v1 value that read as jagged); 1.0 px is the balance point.
                float motionJitterAmp = (float) Math.min(jitterAmp, 1.0 * scaledWidth / displayWidth);
                float jitterAmpMotion = jitterAmp + (motionJitterAmp - jitterAmp) * motionFactor;
                // Fast-motion stage (2026-08-07, "shaking everywhere" fix): the v2
                // floor of 1.0 display px is still visible once history is rejected —
                // sprinting/flying drives the MV ramp (smoothstep(0.5,16)) to
                // reactive >0.5 on most pixels, so sub-pixel jitter provides NO edge
                // reconstruction and only shows as per-frame oscillation on the
                // fresh frames (log: M=8-14 render px, weight at the 0.18 floor).
                // Drop toward ~0.05 render px (0.2 display px — imperceptible) when
                // the camera moves faster than walking. Thresholds are per-FRAME
                // (blocks/rad per frame), which correlates with the per-frame MV
                // magnitude that actually drives the reactive mask: 0.12 blocks/frame
                // ≈ 30-fps walking (60-fps walking ~0.07 stays under → full jitter,
                // edges reconstruct); the observed shake case (0.3-0.47, e.g. sprint/
                // fall at 25-50 fps) gets a 0.36-0.7 factor. Also covers teleports/
                // dimension changes, where the first ~30 frames have no history at all.
                float fastFactor = 0.0f;
                if (fsr23PrevCamPosInit) {
                    fastFactor = (float) Math.min(1.0, Math.max(0.0,
                        Math.max((dPosTick - 0.06) / 0.30, (dRotFrame - 0.01) / 0.03))); // 0.30 ramp: walking 0.216 -> 0.52 (partial kill, keeps edge reconstruction); sprint 0.28 -> 0.73; elytra 1.0 -> 1.0 full kill

                    if (fastFactor > 0.0f) {
                        float nearZeroAmp = 0.05f;
                        jitterAmpMotion = jitterAmpMotion + (nearZeroAmp - jitterAmpMotion) * fastFactor;
                    }
                    // FAST-MOTION VERIFICATION (2026-08-07): one-shot log when the
                    // fast stage engages beyond 50% — proves the shrink is active
                    // during sprint/fly/teleport (the DIAG line only samples standing-
                    // still frames). Throttled to 2s to avoid spam during sustained
                    // motion.
                    if (fastFactor > 0.5f
                            && timingFrameCount - lastFastMotionDiagFrame > 120) {
                        lastFastMotionDiagFrame = timingFrameCount;
                        FSRMod.LOGGER.info("[FAST-JITTER] engaged fastFactor={} dPos={} dRot={} amp={} frame={}",
                            String.format("%.2f", fastFactor),
                            String.format("%.4f", dPosFrame),
                            String.format("%.4f", dRotFrame),
                            String.format("%.3f", jitterAmpMotion),
                            timingFrameCount);
                    }
                }
                float ampScale = (float) (jitterAmpMotion / 0.5);
                jitterResult[0] *= ampScale;
                jitterResult[1] *= ampScale;
                // Fast-motion diag (2026-08-07): log the fastFactor + amp so
                // the regular [DIAG] cadence shows whether the shrink engages.
                if (timingFrameCount % 60 == 0 && fastFactor > 0.01f) {
                    FSRMod.LOGGER.info("[DIAG_FAST] Frame {}: fastFactor={} jitterAmpMotion={} dPosFrame={} dRotFrame={} dPosTick={}",
                        timingFrameCount,
                        String.format("%.3f", fastFactor),
                        String.format("%.3f", jitterAmpMotion),
                        String.format("%.4f", dPosFrame),
                        String.format("%.4f", dRotFrame),
                        String.format("%.4f", dPosTick));
                }
            }
            jitterX = jitterResult[0];
            jitterY = jitterResult[1];

            // IRIS JITTER-BLIND OVERRIDE (2026-08-18): the auto-probe in
            // onSceneRendered measures whether the pack actually consumes the
            // vanilla projection jitter (some packs rebuild their own projection
            // and render unjittered). If it doesn't, feeding FSR2/3 the jitter
            // values would make the temporal filter sample history at wrong
            // offsets -> ghosting/smearing. Degrade to unjittered accumulation
            // (spatial-quality upscale, no temporal reconstruction) — the same
            // honest tradeoff Super Resolution's generic tier makes, but we
            // MEASURE it instead of assuming from a per-pack config.
            // IRIS JITTER-BLIND VERDICT: once the probe classifies the pack
            // as jitter-blind, zero jitter for this session. The old
            // periodic re-probe (60s) was removed (2026-08-23, "blinking after
            // a few minutes" fix): the re-probe cleared irisJitterBlind AND
            // re-armed irisJitterProbeDone, so every 60s the verdict could
            // flip (blind→not-blind→blind...) — each flip toggled jitter
            // between zeroed and active, producing a visible quality blink.
            // The one-shot probe (on fresh install or reinit) is sufficient;
            // a sticky verdict prevents mid-session oscillation.
            if (irisSwapActive && irisJitterBlind) {
                jitterX = 0.0f;
                jitterY = 0.0f;
            }

            // FSR1 SPATIAL FALLBACK has NO de-jitter pass — it upscales the
            // scene as-is. If the render carried the ±0.5px Halton jitter, the
            // upscaled output would physically shift every frame = visible shake
            // while standing still (reported 2026-08-04: shake started exactly
            // when the FSR1 fallback took over after an FSR4 suspension). Zero
            // the jitter so the spatial path sees a clean (unjittered) render;
            // FSR4 resumes with normal jitter on the next non-suspended frame.
            // NOTE: inside isTemporalUpscaler() only fsr4Suspended and
            // !fsr4Initialized can be true (FSR1/dhActive never reach here).
            // saturationDegraded (2026-08-07): the GPU-saturation degraded path
            // is blitSceneToMain — also a de-jitter-less spatial blit — but
            // while config is FSR2/FSR3_UPS the render above carries the
            // temporal jitter, so a degraded frame presented the ±1.5
            // display-px oscillation (half of the "still shaking"). Zero it
            // there too. Last frame's value (computed in onSceneRendered) — a
            // one-frame lag at the degraded↔full transition is masked by the
            // guard's hasPrevFrame history reset. GATED to the modes the guard
            // actually routes to the blit: FSR3_FG explicitly ignores
            // degradation (keeps its temporal pipeline), and FSR4/FG_DX12 never
            // reach the guard (saturationDegraded would be a stale frozen value
            // after an in-game FSR2→FSR4 switch) — zeroing their jitter would
            // only hurt temporal reconstruction.
            if (spatialFallbackActive()
                    || (saturationDegraded && (isFsr2() || (isFsr3() && !isFrameGen())))) {
                jitterX = 0.0f;
                jitterY = 0.0f;
            }

            if (config.debugMode > 0 && timingFrameCount % 60 == 0) {
	                // FSR4-mode extras: whether the output is REAL FSR4 accumulation
	                // vs the SILENT bilinear bypass (which reads as jagged diagonals
	                // at every scale — no temporal reconstruction — and is invisible
	                // in the log because the bypass dispatch returns true every frame
	                // after it arms). isBypassActive() is a native flag read (~free)
	                // gated to debug; realDepth is the probe verdict. This makes the
	                // real-vs-bypass-vs-realDepth state visible on every DIAG frame.
	                String fsr4State = isFsr4()
	                    ? String.format(java.util.Locale.ROOT, " realDepth=%s bypass=%s nearDepth=%s",
	                        fsr4RealDepthAvailable ? "1" : "0",
	                        (Fsr4Native.isAvailable() && Fsr4Native.isBypassActive()) ? "1" : "0",
	                        config.fsr4NearDepthMotion ? "1" : "0")
	                    : "";
	                FSRMod.LOGGER.info("[DIAG] Frame {}: jitter=({},{}) prev=({},{}) scale={} render={}x{} display={}x{} frameIdx={} camStill={}{}",
	                    timingFrameCount, jitterX, jitterY, prevJitterX, prevJitterY, scale,
	                    scaledWidth, scaledHeight, displayWidth, displayHeight, fsr2FrameCount, fsr23CamStill, fsr4State);
	            }

	            if (!pipelineReady) {
	                if (isFsr2()) {
	                    if (fsr2Pipeline == null) fsr2Pipeline = new FSR2Pipeline();
	                    fsr2Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
	                } else if (isFsr3()) {
	                    if (fsr3Pipeline == null) fsr3Pipeline = new FSR3Pipeline();
	                    fsr3Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
	                }
	                pipelineReady = true;
	            }
		        } else {
		            jitterX = 0.0f;
		            jitterY = 0.0f;
		        }

		        // Initialize shared texture manager for FSR output.
		        // The single-texture (Vulkan-backed) path is only for FSR1/2/3 output injection.
	        // The FSR4 5-texture pool uses direct D3D12→GL (GL_HANDLE_TYPE_D3D12_RESOURCE_EXT)
	        // and does NOT require Vulkan.
	        // TEST: Only enable shared memory for FSR4; FSR2/FSR3 use native glCopyImageSubData
	        // to match reference JAR behavior (isolates shared memory as flicker cause).
	        if (isFsr4() && checkVulkanAvailable()) {
	            boolean needsRecreate = sharedTexMgr == null || !sharedTexMgr.isValid()
	                || sharedTexMgr.getWidth() != displayWidth || sharedTexMgr.getHeight() != displayHeight;
	            if (needsRecreate) {
	                // FIRST-INTEROP DEFERRAL (2026-08-18, GL toggle-on crash fix):
	                // the Vulkan-allocation -> GL external-memory import here
	                // (getSharedHandle + glImportMemoryWin32HandleEXT +
	                // glTextureStorageMem2DEXT) is the SAME cross-API driver-crash
	                // class as the SDK init below (AMD Radeon 860M faults when
	                // external-memory handle imports run while deep storm work is
	                // still queued — the user's "crashes when I open a world with
	                // FSR off and turn it on" report: silent GPU TDR ~10s after an
	                // in-world enable, no crash report, no hs_err). The SDK init
	                // defers until calm, but this block ran UNCONDITIONALLY on
	                // enable, so a mid-storm toggle imported the Vulkan memory
	                // into GL on a saturated queue. Defer the FIRST creation with
	                // the SDK init's own calm gate + grace (shared
	                // fsr4InitDeferStartMs — both creations wait together and
	                // proceed together; the SDK block below owns the timer reset).
	                // Resize recreates (FSR4 already running, queue calm) keep the
	                // immediate behavior.
	                boolean deferInterop = (sharedTexMgr == null || !sharedMemoryActive)
	                    && !isFsr4InitCalm();
	                if (deferInterop && fsr4InitDeferStartMs == 0) {
	                    fsr4InitDeferStartMs = System.currentTimeMillis();
	                }
                if (deferInterop
                    && fsr4InitDeferStartMs != 0
                    && System.currentTimeMillis() - fsr4InitDeferStartMs >= fsr4EffectiveInitGraceMs()) {
	                    deferInterop = false;  // grace elapsed — proceed (the SDK
	                                           // block below resets the timer)
	                }
	                if (!deferInterop) {
	                    if (sharedTexMgr != null) sharedTexMgr.destroy();
	                    try {
	                        // Drain the GL queue BEFORE the cross-API import —
	                        // same documented mitigation as the SDK init below
	                        // (AGENTS.md: driver faults on imports during deep
	                        // storm work).
	                        GL43C.glFinish();
	                        if (sharedTexMgr == null) sharedTexMgr = new SharedTextureManager();
	                        sharedTexMgr.create(displayWidth, displayHeight);
	                        sharedTexMgr.attachToMainRT(mainRenderTarget);
	                        sharedMemoryActive = true;
	                    } catch (Exception e) {
	                        FSRMod.LOGGER.warn("Shared memory unavailable, falling back: {}", e.getMessage());
	                        if (sharedTexMgr != null) sharedTexMgr.destroy();
	                        sharedMemoryActive = false;
	                    }
	                }
	            }
	        } else {
	            // Non-FSR4 or Vulkan not available — ensure sharedTexMgr exists for FSR4 pool
	            if (sharedTexMgr == null) {
	                sharedTexMgr = new SharedTextureManager();
	            }
	            sharedMemoryActive = false;
		        }

		        // Initialize FSR4 context (D3D12 compute via AMD FSR SDK).
        // CRITICAL: Only initialize when FSR4 (or FSR3_FG_DX12, which needs the
        // same D3D12 pool for FG interpolation) is selected in config — creating
        // the D3D12 device alongside an active OpenGL context destabilizes the
        // AMD driver on RDNA 3.5 iGPUs, causing TDR / driver crashes.
        if (isFsr4() || isFrameGenDx12()) {
            if (Fsr4Native.isAvailable()) {
                fsr4Available = true;
                int renderW = scaledWidth;
                int renderH = scaledHeight;
                int dispW = displayWidth;
                int dispH = displayHeight;

                // UNPARK (2026-08-25): re-selecting FSR4/FG after a parked
                // context (user switched away to FSR1/2/3 and back). When the
                // parked SDK context + pool match the current size, reuse them
                // — ZERO cross-API work. The GL path needs the GL-imported
                // pool textures; unparkFsr4Context checks the pool type + size,
                // and on mismatch tears the parked context down so the
                // needsRecreate path below re-inits fresh.
                if (unparkFsr4Context(renderW, renderH, dispW, dispH, true)) {
                    fsr4Available = true;
                    // Parked context reused — skip the whole init path.
                }

                long now = System.currentTimeMillis();
                boolean needsRecreate = !fsr4Initialized
                    || fsr4ContextRenderW != renderW || fsr4ContextRenderH != renderH
                    || fsr4ContextDisplayW != dispW || fsr4ContextDisplayH != dispH;

                // HARD-DISABLE GATE (2026-08-30, "runs then completely freezes"
                // — VKMod crash-loop fix): a hard-disabled (5 consecutive dispatch
                // crashes) session MUST NOT rebuild the D3D12 device + pool. Each
                // rebuild after an SDK C++-exception crash was the render-thread
                // hang on a wedged AMD iGPU (diag log ended mid-recreate with no
                // follow-up ensureInitialized). The latch is cleared by a
                // deliberate markDirty()/applyAutoScale() change (the user
                // retrying).
                if (needsRecreate && !fsr4HardDisabled) {
                    if (now - lastFsr4ContextChange < FSR4_DEBOUNCE_MS) {
                        // Too soon since last recreation — skip
                    } else {
                        // FIRST-INIT DEFERRAL (2026-08-05 v5): the world-load
                        // storm saturates the GPU queue, and running the
                        // cross-API init then (external-memory handle imports +
                        // D3D12 device creation) faults the AMD GL driver
                        // (atio6axx.dll 0xC0000005 — crashes only when LOADING
                        // with FSR4 on; loading with FSR1 and switching later
                        // never crashes). Defer the FIRST init until the frame
                        // EMA is calm, with a grace so FSR4 always engages
                        // eventually. While the deferred, the FSR1 spatial
                        // fallback renders — the proven crash-free path.
                        //
                        // SIZE-CHANGE FAST RE-INIT (2026-08-23, "zoomed into a
                        // corner" fix): this deferral must apply ONLY to the
                        // genuine FIRST init of a session (context never
                        // created — fsr4ContextRenderW == 0). A re-init after
                        // a DELIBERATE scale change (applyAutoScale/markDirty
                        // tore the context + pool down) must not wait on the
                        // frame EMA or the 25s grace: the 12:09 session log
                        // showed the iris-quality slider change parking FSR4 on
                        // FSR1 for 24s (12:10:07 change -> 12:10:31 init) with
                        // the scene rendering at the NEW size the whole time —
                        // the exact window in which any mis-sized dispatch
                        // crops/picks the image (the corner-zoom class).
                        // Mid-session re-inits are safe (the world is already
                        // loaded; the storm EMA is meaningless for them).
                        boolean deferFsr4Init = !fsr4Initialized
                            && fsr4ContextRenderW == 0 && !isFsr4InitCalm();
                        // DIMENSION-CHANGE HOLD (2026-08-22): also defer the
                        // first init inside the hold window — the transition
                        // storm makes isFsr4InitCalm() unreliable right at the
                        // swap (the EMA is stale from the old dimension).
                        if (!deferFsr4Init && fsr4DimChangeAtMs != 0
                            && System.currentTimeMillis() - fsr4DimChangeAtMs < FSR4_DIM_CHANGE_HOLD_MS) {
                            deferFsr4Init = true;
                        }
                        if (deferFsr4Init && fsr4InitDeferStartMs == 0) {
                            fsr4InitDeferStartMs = System.currentTimeMillis();
                        }
                        if (deferFsr4Init
                            && System.currentTimeMillis() - fsr4InitDeferStartMs >= fsr4EffectiveInitGraceMs()) {
                            // Grace elapsed — force init even if still heavy;
                            // never leave the user on FSR1 indefinitely.
                            deferFsr4Init = false;
                            fsr4InitDeferStartMs = 0;
                            if (shouldDebug()) {
                                FSRMod.LOGGER.info("FSR4: load-storm grace elapsed — force-initializing");
                            }
                        }
                        if (deferFsr4Init) {
                            // Skip init THIS frame (retried every frame —
                            // lastFsr4ContextChange is NOT touched, so the
                            // debounce never blocks the retry).
                            fsr4InitDeferFrames++;
                            if (shouldDebug() && (fsr4InitDeferFrames % 240) == 1) {
                                FSRMod.LOGGER.info("FSR4: deferring init (load storm, EMA={:.1f} ms, deferred {} frames)", fsr4InitEmaMs, fsr4InitDeferFrames);
                            }
                        } else {
                            fsr4InitDeferStartMs = 0;
                            fsr4InitDeferFrames = 0;
                            lastFsr4ContextChange = now;
                            if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; fsr4AsyncPending = false; fsr4Suspended = false; fsr4BypassNotified = false; fsr4ConsecutiveSkips = 0; VulkanModCompat.fsr4VkModActive = false; } fsr4InitDeferStartMs = 0; fsr4InitDeferFrames = 0;
                            try {
                                // Drain the GL queue BEFORE the cross-API pool import:
                                // the AMD GL driver faults when external-memory handle
                                // imports / texture creation run while deep storm work
                                // is still queued. One-time cost (init only). Inside the
                                // try so any fault shares the init exception handler.
                                GL43C.glFinish();
                                if (sharedTexMgr != null) {
                                    sharedTexMgr.destroyFsr4Pool();
                                }
                                boolean poolOk = sharedTexMgr != null && sharedTexMgr.allocateFsr4Pool(renderW, renderH, dispW, dispH);
                            if (!poolOk) {
                                FSRMod.LOGGER.warn("FSR4: allocateFsr4Pool failed");
                            } else {
                                boolean ok = Fsr4Native.initFsr4(renderW, renderH, dispW, dispH);
                                if (ok) {
                                    fsr4Initialized = true;
                                    syncNativeDiagnostics();
                                    // FG on this AMD iGPU removes the D3D12 device at the
                                    // first frame — disable FG and run plain real FSR4.
                                    maybeDisableFgOnIgpu();
                                    // Pre-arm the bilinear bypass before the first dispatch when FG
                                    // is live on an AMD iGPU (see maybePreArmBypassForFgIgpu).
                                    maybePreArmBypassForFgIgpu();
                                    fsr4FramesSkipped = 0; // reset skip counter on successful reinit
                                    fsr4ConsecutiveSkips = 0; fsr4Suspended = false;
                                    // DELIBERATE LATCH PRESERVATION (2026-08-30): a
                                    // successful reinit must NOT clear the hard-disable
                                    // counters. The crash-loop this latch stops is "crash
                                    // -> device+pool rebuild -> (successful reinit) ->
                                    // crash -> ..." — if the reinit cleared
                                    // fsr4DispatchFailCount, the 5-strike escalation
                                    // never latches, needsRecreate stays open, and the
                                    // expensive rebuild (the actual render-thread hang
                                    // on a wedged AMD iGPU) keeps firing forever. The
                                    // counters clear only on a genuinely good dispatch
                                    // (~5972) or a deliberate markDirty()/applyAutoScale().
                                    fsr4FenceTimedOutPrevFrame = false; fsr4ProbeCleanStreak = 0; fsr4PreCheckSkips = 0; fsr4EmaWorldMs = 0.0f;
                                    fsr4InitEmaMs = 0.0f; lastInitFrameNanos = 0;  // init-deferral EMA no longer needed
                                    fsr4ContextRenderW = renderW; fsr4ContextRenderH = renderH;
                                    fsr4ContextDisplayW = dispW; fsr4ContextDisplayH = dispH;
                                    FSRMod.LOGGER.info("FSR4: init OK ({}x{} -> {}x{})", renderW, renderH, dispW, dispH);

                                    // FSR3_FG_DX12: init the FG SDK context. FG interpolates
                                    // the FSR4 upscaled output (pool 4) into pool index 5.
                                    // INIT RETRY BUDGET: fg4InitFailed latches only after
                                    // FG_MAX_INIT_FAILURES attempts (transient first-frame
                                    // driver hiccups retry on the next FSR4 reinit).
                                    if (fgActive() && !fg4InitFailed && Fg4Native.isAvailable()
                                        && !VulkanModCompat.isActive()) {
                                        boolean fgOk = Fg4Native.nativeInit(
                                            renderW, renderH, dispW, dispH,
                                            sharedTexMgr.getColorAllocPtr(),
                                            6,  // presentColor = real frame + GUI (FG_PRESENT)
                                            1,  // depth
                                            2,  // motion vectors
                                            5,  // FG output
                                            4,  // HUDLessColor = FSR4 output (no GUI)
                                            Fg4Native.getFgDllDir());
                                        if (fgOk) {
                                            fg4Initialized = true;
                                            // A successful FG SDK init proves FG works
                                            // now — clear any stale permanent-disable so
                                            // the recovered state actually engages (the
                                            // latch is already cleared on deliberate
                                            // rebuilds; this is the belt-and-suspenders
                                            // for any other path that set it).
                                            fg4PermanentlyDisabled = false;
                                            fg4InitFailed = false;
                                            fg4InitFailCount = 0;
                                            fgRuntimeFailCount = 0;
                                            fgStallConsecutive = 0;
                                            FSRMod.LOGGER.info("FG_DX12: Fg4Native init OK");
                                        } else {
                                            fg4Initialized = false;
                                            fg4InitFailCount++;
                                            if (fg4InitFailCount >= FG_MAX_INIT_FAILURES) {
                                                fg4InitFailed = true;
                                                FSRMod.LOGGER.warn("FG_DX12: Fg4Native init failed {} times — FG disabled for this session (plain FSR4); a settings change re-arms it", FG_MAX_INIT_FAILURES);
                                            } else {
                                                FSRMod.LOGGER.warn("FG_DX12: Fg4Native init failed ({}/{}) — will retry on next FSR4 init", fg4InitFailCount, FG_MAX_INIT_FAILURES);
                                            }
                                        }
                                    }
                                } else {
                                    FSRMod.LOGGER.warn("FSR4: initFsr4 returned false");
                                }
                            }
                        } catch (Exception e) {
                            FSRMod.LOGGER.warn("FSR4 init failed: {}", e.getMessage());
                            if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; fsr4AsyncPending = false; fsr4Suspended = false; fsr4BypassNotified = false; fsr4ConsecutiveSkips = 0; VulkanModCompat.fsr4VkModActive = false; } fsr4InitDeferStartMs = 0; fsr4InitDeferFrames = 0;
                        }
                    }
                }
            }
            } else {
                fsr4Available = false;
            }
        } else {
            // FSR4 not selected — PARK the D3D12 context + pool instead of
            // tearing down (2026-08-25): keep the SDK context + shared pool
            // alive so a re-select at the same size reuses them with ZERO
            // cross-API work (the expensive + crash-prone part: pool allocate
            // + external-memory imports + ffxCreateContext). The D3D12 device
            // already survives switches; parking extends that to the context +
            // pool. Only parks when a context is live (idempotent afterwards).
            if (fsr4Initialized || fg4Initialized) {
                parkFsr4Context();
            }
            if (!fsr4Parked) {
                // Never initialized (or torn down): keep the re-enable safety
                // reset of the size sentinel so a later FSR4 re-enable routes
                // through the FIRST-INIT deferral (calm gate + grace) instead
                // of the immediate cross-API init. While PARKED the sentinel
                // is KEPT — the unpark size check needs the parked dims.
                if (sharedTexMgr != null) {
                    sharedTexMgr.destroyFsr4Pool();
                }
                fsr4ContextRenderW = 0;
                fsr4ContextDisplayW = 0;
            }
            fsr4InitDeferStartMs = 0;
            fsr4InitDeferFrames = 0;
            fsr4Available = false;
        }

        logSlow("onRenderBegin (total)", System.nanoTime() - t0Begin);
        t0BeginEnd = System.nanoTime();
        // Frame-duration EMA for the FSR4 first-init deferral (the init block
        // above runs BEFORE this, so it reads the PREVIOUS frame's duration).
        // Only reached on the GL path — Iris/VulkanMod return earlier, and only
        // the GL path initializes FSR4 (the crash is GL-driver-specific).
        if (lastInitFrameNanos != 0) {
            float initFrameMs = (t0BeginEnd - lastInitFrameNanos) / 1_000_000.0f;
            fsr4InitEmaMs = fsr4InitEmaMs <= 0.0f
                ? initFrameMs
                : fsr4InitEmaMs + FSR4_INIT_EMA_ALPHA * (initFrameMs - fsr4InitEmaMs);
        }
        lastInitFrameNanos = t0BeginEnd;
    }

    /**
     * Whether the FSR4 first-init deferral should allow init this frame.
     * Defer while the frame-duration EMA is heavy (world-load storm). An
     * unseeded EMA (0 — no full frame measured yet) defers too, so the first
     * frames after world load are always deferred regardless of how fast the
     * very first frame was.
     */
    private boolean isFsr4InitCalm() {
        if (fsr4InitEmaMs <= 0.0f) return false;
        return fsr4InitEmaMs <= FSR4_INIT_CALM_MS;
    }

    /**
     * DELIBERATE-SWITCH FAST INIT (2026-08-24): the cross-API creation grace is
     * 25s for a genuine world-load storm, but only ~2s right after a deliberate
     * settings change (version/quality switch) — see the field comment on
     * {@link FSR4_SWITCH_INIT_GRACE_MS}.
     */
    private long fsr4EffectiveInitGraceMs() {
        boolean worldEstablished = worldJoinedAtMs != 0
            && System.currentTimeMillis() - worldJoinedAtMs >= 20000L;
        if (worldEstablished
            && fsr4SwitchConfigAtMs != 0
            && System.currentTimeMillis() - fsr4SwitchConfigAtMs < FSR4_SWITCH_RECENT_MS) {
            return FSR4_SWITCH_INIT_GRACE_MS;
        }
        return FSR4_INIT_GRACE_MS;
    }

    public void applyJitterToMatrix(Matrix4f proj) {
        if (!isTemporalUpscaler()) return;
        if (!insideFrame) return;
        if (jitterAppliedThisFrame) {
            FSRMod.LOGGER.warn("[DIAG] Double jitter prevented: jitterX={} jitterY={} frame={}", jitterX, jitterY, timingFrameCount);
            return;
        }
        jitterAppliedThisFrame = true;
        if (scaledWidth < 16 || scaledHeight < 16) {
            FSRMod.LOGGER.warn("[DIAG] Bad scaled dims {}x{} on frame {} (display {}x{})", scaledWidth, scaledHeight, timingFrameCount, displayWidth, displayHeight);
            return;
        }
        float px = jitterX * 2.0f / scaledWidth;
        float py = jitterY * 2.0f / scaledHeight;
        proj.m02(proj.m02() + px);
        proj.m12(proj.m12() + py);
    }

    // TERRAIN-CONTENT PROBE (2026-08-24): reads three 16x16 RGBA8 blocks from
    // the pack's proxy composite — sky (top-center), mid (horizon), terrain
    // (bottom-center) — and logs mean RGB + luma variance for each. Sky blocks
    // read as flat low-variance blue/white; terrain blocks read as high-variance
    // textured color. If the "terrain" block reads low-variance dark/fog while
    // the sky block is bright, the pack genuinely rendered no terrain into the
    // proxy (the FSR1 fallback is faithful). If BOTH have content, the FSR1
    // blit is the failure. Note: glGetTexImage/glGetTextureSubImage return rows
    // bottom-up (GL convention), so y=0 is the BOTTOM of the image (terrain).
    private void probeTerrainBlocks(String label, int tex, int rw, int rh) {
        if (tex <= 0 || rw < 48 || rh < 48) return;
        int s = 16;
        int x = rw / 2 - s / 2;
        int skyY = rh - 8 - s;      // top of image
        int midY = rh / 2 - s / 2;  // horizon-ish
        int terY = 8;               // bottom of image
        byte[] sky = readTexBlockRgba8(tex, x, skyY, s, s);
        byte[] mid = readTexBlockRgba8(tex, x, midY, s, s);
        byte[] ter = readTexBlockRgba8(tex, x, terY, s, s);
        FSRMod.LOGGER.info("[TERRAIN-PROBE] {} {}x{} skyTop={} mid={} terrain={}",
            label, rw, rh, blockSummary(sky), blockSummary(mid), blockSummary(ter));
    }

    private String blockSummary(byte[] b) {
        if (b == null) return "READ_FAIL";
        int n = b.length / 4;
        if (n == 0) return "EMPTY";
        long r = 0, g = 0, bl = 0;
        for (int i = 0; i < n; i++) {
            r += b[i * 4] & 0xFF; g += b[i * 4 + 1] & 0xFF; bl += b[i * 4 + 2] & 0xFF;
        }
        float[] lv = blockLuma(b);
        if (lv == null) return "READ_FAIL";
        return String.format(java.util.Locale.ROOT, "meanRGB=(%d,%d,%d) luma=%.0f var=%.1f",
            r / n, g / n, bl / n, lv[0], lv[1]);
    }

    /** Mean RGB of a block, or null on bad input. */
    private float[] blockMeanRgb(byte[] b) {
        if (b == null || b.length < 4) return null;
        int n = b.length / 4;
        if (n == 0) return null;
        long r = 0, g = 0, bl = 0;
        for (int i = 0; i < n; i++) {
            r += b[i * 4] & 0xFF; g += b[i * 4 + 1] & 0xFF; bl += b[i * 4 + 2] & 0xFF;
        }
        return new float[] { r / (float) n, g / (float) n, bl / (float) n };
    }

    /** Mean + variance of a block's Rec.601 luma, or null on bad input. */
    private float[] blockLuma(byte[] b) {
        if (b == null || b.length < 4) return null;
        int n = b.length / 4;
        if (n == 0) return null;
        double lum = 0;
        for (int i = 0; i < n; i++) {
            lum += 0.299 * (b[i * 4] & 0xFF) + 0.587 * (b[i * 4 + 1] & 0xFF) + 0.114 * (b[i * 4 + 2] & 0xFF);
        }
        lum /= n;
        double var = 0;
        for (int i = 0; i < n; i++) {
            double l = 0.299 * (b[i * 4] & 0xFF) + 0.587 * (b[i * 4 + 1] & 0xFF) + 0.114 * (b[i * 4 + 2] & 0xFF);
            var += (l - lum) * (l - lum);
        }
        var /= n;
        return new float[] { (float) lum, (float) var };
    }

    /**
     * BROKEN-COMPOSITE DETECTOR (2026-08-24, predicate v2 2026-08-25): reads
     * sky (top-center) and terrain (bottom-center) blocks from the pack's proxy
     * composite every swap-active frame and votes on "the composite has no
     * terrain". Two signatures: (1) void — a visible sky (any brightness above
     * night) over a dark flat void; (2) skygrad — a flat blue-dominant terrain
     * block under a smooth sky (the whole sampled frame is sky gradient, day
     * or dusk). Three consecutive broken votes latch native pass-through via
     * {@link #irisSwapBrokenLatch} (applied in onRenderBegin's irisTemporal
     * gate). Reads failing (driver-hostile) never vote — fail-open.
     */
    private void evaluateProxyContentAndVote(int renderTex, int rw, int rh) {
        if (renderTex <= 0 || rw < 48 || rh < 48) return;
        int s = 16;
        int x = rw / 2 - s / 2;
        byte[] sky = readTexBlockRgba8(renderTex, x, rh - 8 - s, s, s);
        byte[] ter = readTexBlockRgba8(renderTex, x, 8, s, s);
        float[] sl = blockLuma(sky);
        float[] tl = blockLuma(ter);
        // BROKEN-COMPOSITE VOTE PREDICATE (2026-08-25 v2): the original rule
        // (sky>110 && terrain<45 && terrainVar<250) only matched ONE missing-
        // terrain signature — bright NOON sky over a black void — and the
        // 14:26 session showed the composite losing terrain in two shapes it
        // could not see: (a) dusk/overcast sky (luma 79-86, well under 110)
        // over the same black void (frames 34-35), and (b) the whole frame
        // being a flat SKY GRADIENT painted down over the terrain region —
        // terrain block reads bright horizon-blue (luma 104-122, var~1) while
        // the sky above is dark navy (frames 13-19, ~11s of terrain missing
        // with zero votes). Every healthy probe in that session had TEXTURED
        // terrain (variance 34-340); every broken one was flat (var <= 4.4).
        // Signature 1 (void) = a visible sky over a dark flat void — no
        // absolute brightness ceiling (55 still separates day/dusk from
        // night's 26-27).
        // Signature 2 (skygrad) = a flat, blue-dominant terrain block with a
        // smooth sky above and the horizon not darker than the zenith — the
        // sky gradient covers the whole sampled frame (day or dusk: in a real
        // gradient the horizon is the BRIGHTEST part, so terrain >= sky holds
        // for a sky-only composite in either light, while real ground is
        // textured, var 34+, in every healthy probe).
        // Pure classifier in BrokenCompositePredicate (main source set) so it
        // is unit-tested; fail-open: null result never votes broken.
        boolean looksBroken = false;
        String sig = "-";
        if (sl != null && tl != null) {
            float[] tm = blockMeanRgb(ter);
            String cls = BrokenCompositePredicate.classify(sl[0], sl[1], tl[0], tl[1],
                tm != null ? tm[0] : -1f, tm != null ? tm[1] : -1f, tm != null ? tm[2] : -1f);
            if (cls != null) { looksBroken = true; sig = cls; }
        }
        if (looksBroken) brokenCompositeVotes++;
        else brokenCompositeVotes = 0;
        FSRMod.LOGGER.info("[TERRAIN-PROBE] PROXY {}x{} skyTop={} terrain={} brokenVote={}/{} sig={}",
            rw, rh, blockSummary(sky), blockSummary(ter),
            brokenCompositeVotes, BROKEN_COMPOSITE_VOTES_NEEDED, sig);
        if (!irisSwapBrokenLatch && brokenCompositeVotes >= BROKEN_COMPOSITE_VOTES_NEEDED) {
            irisSwapBrokenLatch = true;
            FSRMod.LOGGER.warn("[IRIS-COMPOSITE] pack composite lost its terrain across {} consecutive probes "
                + "(sky-only proxy after pipeline rebuild) — latching NATIVE pass-through for this world",
                brokenCompositeVotes);
            showBrokenCompositeToast(true);
        }
    }

    /**
     * On-screen notification for the broken-composite latch (2026-08-25): the
     * latch silently disables the Iris swap (native pass-through) — without a
     * toast users only see "upscaling stopped" in the log. Fires once on
     * engage and again when a world join clears the latch (scaled path
     * retrying). Vanilla SystemToast (PERIODIC_NOTIFICATION) is safe to show
     * from the render thread and needs no HUD mixin. Best-effort: a toast API
     * mismatch must never crash the render loop.
     */
    private void showBrokenCompositeToast(boolean engaged) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            ToastManager manager = client.getToastManager();
            if (manager == null) return;
            if (engaged) {
                SystemToast.addOrUpdate(manager, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("FSR: Native pass-through"),
                    Component.literal("Shader pack composite lost its terrain — upscaling paused for this world"));
            } else {
                SystemToast.addOrUpdate(manager, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("FSR: Upscaling resumed"),
                    Component.literal("Broken composite cleared — scaled rendering re-enabled"));
            }
        } catch (Throwable t) {
            FSRMod.LOGGER.debug("[IRIS-COMPOSITE] toast skipped: {}", String.valueOf(t));
        }
    }

    /**
     * GPU-saturation degraded mode fallback: single bilinear blit from the
     * scaled scene texture into mainRT (sharpness 0 → no RCAS compute, no EASU).
     * Used while the GL queue is saturated (chunk-streaming storms) so the mod
     * stops adding compute dispatches, barriers and copies to an overloaded
     * queue. Reuses FSR1Pipeline's fixed-function blit path.
     */
    private boolean blitSceneToMain(int renderTex) {
        if (renderTex == -1) return false;
        if (fsr1Pipeline == null) {
            FSRMod.LOGGER.info("FSR1: Initializing pipeline (saturation blit)");
            fsr1Pipeline = new FSR1Pipeline();
        }
        int mainTex = mainRenderTarget != null
            ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : 0;
        if (mainTex == 0) return false;
        try {
            return fsr1Pipeline.dispatch(renderTex, scaledWidth, scaledHeight,
                displayWidth, displayHeight, 0.0f, mainTex);
        } catch (Exception e) {
            FSRMod.LOGGER.error("Saturation blit threw exception", e);
            return false;
        }
    }

    /**
     * Called after 3D level rendering completes but before GUI rendering.
     * Runs the FSR upscale and switches mainRT to full resolution so GUI
     * renders at native resolution (not upscaled).
     */
    public void onSceneRendered() {
        t0Scene = System.nanoTime();
        // Cleared per frame; set by the diagnostic readbacks below (see the
        // diagnosticReadbackThisFrame field doc).
        diagnosticReadbackThisFrame = false;
        // Initialize t0SceneEnd here so the onRenderEnd "gui+present" timing log
        // never reads a stale 0 on early-return paths (was showing a garbage
        // ~269M ms value when FSR1's early return skipped the normal set below).
        t0SceneEnd = t0Scene;
        if (!insideFrame) return;
        // renderLevel(TAIL) fired inside a frame → the world rendered this frame.
        worldRenderedThisFrame = true;
        if (displayWidth <= 0 || displayHeight <= 0) return;
        if (sceneUpscaled) return;

        // IRIS COMPOSITE VIEWPORT FIX: clear Window-override flags EARLY
        // (before any early-return in this method) so the GUI always sees
        // native dims. The flags were set in onRenderBegin and active during
        // Iris's rendering (renderLevel body). Now that Iris is done, the
        // GUI must see the real window size. onRenderEnd also clears as
        // belt-and-suspenders for paths that skip onSceneRendered entirely.
        irisSwapActiveStatic = false;
        windowScaleActive = false;

        // World render time = scene-render entry minus end of onRenderBegin.
        // This isolates the actual level rasterization cost (should be cheap
        // at scaled res) from the FSR/FG pipeline below it.
        // Debug-only: creates a viewport query + formatted string every 60 frames
        // — zero cost in production. SKIPPED under VulkanMod: there is no OpenGL
        // context (Vulkan replaces GL entirely), and GL43C.glGetIntegerv throws
        // "No GLCapabilities instance has been set" — an ExceptionInInitializerError
        // that killed the render thread at world-join when debugMode > 0 with the
        // render-scale POC active (2026-08-05, latest.log line 175).
        if (config.debugMode > 0 && timingFrameCount % 60 == 0
                && !VulkanModCompat.isLoaded()) {
            int[] vp = new int[4];
            GL43C.glGetIntegerv(GL43C.GL_VIEWPORT, vp);
            FSRMod.LOGGER.info(String.format(
                "[DIAG_FRAME] frame=%d worldMs=%.1f vp=%dx%d@(%d,%d) expected=%dx%d",
                timingFrameCount,
                (System.nanoTime() - t0BeginEnd) / 1_000_000.0f,
                vp[2], vp[3], vp[0], vp[1],
                scaledWidth, scaledHeight));
        }

        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();

        // FRAME LOCK (2026-08-19): use the pack state captured in onRenderBegin
        // — re-evaluating here tore the frame when a pack applied mid-frame (the
        // proxy was swapped at qualityScale while this branch ran FSR1 on it;
        // 0xC0000005 in atio6axx.dll, two sessions).
        boolean irisActive = irisActiveThisFrame;

        // PACK-ACTIVATION TRANSITION (2026-08-19): on the frame the pack state
        // flips (activate/deactivate), Iris is mid-setup — shader compilation,
        // Voxy render-pipeline recreation. Dispatching FSR into that window is
        // what faulted the AMD GL driver. Pass the frame through natively; the
        // next frame runs the settled pipeline. One native frame is invisible.
        if (irisActive != irisActivePrevFrame) {
            FSRMod.LOGGER.info("[IRIS] shader-pack state transition (active={}) — skipping FSR this frame (pack setup in flight)", irisActive);
            restoreDefaultTarget();
            return;
        }

        // IRIS COMPOSITE-SIZE PROBE (2026-08-23): while the scaled swap is
        // IRIS COMPOSITE VIEWPORT FIX (2026-08-23): the WindowMixin now
        // reports proxy dims when irisSwapActive, so Iris builds its
        // pipeline and composite at the correct size. This probe CONFIRMS
        // the fix by reading viewport + texture size once at session start.
        if (irisActive && irisSwapActive && !VulkanModCompat.isLoaded()) {
            int[] vp = new int[4];
            GL43C.glGetIntegerv(GL43C.GL_VIEWPORT, vp);
            int compW = -1, compH = -1;
            if (renderTarget != null && renderTarget.getColorTexture() != null) {
                int tex = ((GlTexture) renderTarget.getColorTexture()).glId();
                if (tex != 0) {
                    try {
                        int prev = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
                        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, tex);
                        compW = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_WIDTH);
                        compH = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_HEIGHT);
                        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prev);
                    } catch (Throwable t) {
                        compW = compH = -1;
                    }
                }
            }
            boolean vpOk = (vp[2] == scaledWidth && vp[3] == scaledHeight);
            boolean texOk = (compW == scaledWidth && compH == scaledHeight);
            if (!irisCompositeOkLogged && !irisCompositeMismatchLogged) {
                if (vpOk && texOk) {
                    irisCompositeOkLogged = true;
                    FSRMod.LOGGER.info("[IRIS-COMPOSITE] OK: viewport={}x{} tex={}x{} match proxy={}x{}",
                        vp[2], vp[3], compW, compH, scaledWidth, scaledHeight);
                } else {
                    irisCompositeMismatchLogged = true;
                    FSRMod.LOGGER.warn("[IRIS-COMPOSITE] MISMATCH: viewport={}x{} tex={}x{} vs proxy={}x{} — Window override may not be active yet",
                        vp[2], vp[3], compW, compH, scaledWidth, scaledHeight);
                }
            }
        }

        if (irisActive && !irisSwapActive) {
            // FSR1-under-Iris remains PASS-THROUGH unless the experimental FSR1
            // setting enabled the scaled proxy above (the legacy FSR1 EASU/RCAS
            // GL passes faulted the AMD driver on some Iris composites at a
            // deterministic atio6axx.dll offset). FSR4 and FSR3_FG_DX12 now
            // engage through the scaled proxy like FSR2/3 (see onRenderBegin's
            // irisTemporal gate), so this branch only catches FSR1
            // (non-experimental) + FSR3_FG (GLSL alternation — its
            // alternate-frame machinery is not wired for the swap).
            if (!irisFsr1PassthroughLogged) {
                irisFsr1PassthroughLogged = true;
                FSRMod.LOGGER.info("[IRIS] FSR1 under a shader pack is pass-through (native composite; enable Experimental FSR1 under Iris to test EASU/RCAS, or select FSR2/FSR3/FSR4 for the supported scaled path)");
            }
            RenderTarget actualRT = client.getMainRenderTarget();
            // PACK-RELOAD SAFETY (2026-08-19): if the main render target was
            // replaced between onRenderBegin and here (pack reload recreates it
            // as a new RenderTarget instance), adopt the current target for
            // bookkeeping (restore paths follow the live target).
            if (actualRT != null && actualRT != mainRenderTarget) {
                FSRMod.LOGGER.warn("[IRIS] main render target was recreated mid-frame ({}x{} -> {}x{}) — adopting current target",
                    mainRenderTarget != null ? mainRenderTarget.width : -1,
                    mainRenderTarget != null ? mainRenderTarget.height : -1,
                    actualRT.width, actualRT.height);
                mainRenderTarget = actualRT;
                originalMainRenderTarget = actualRT;
            }
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            sceneUpscaled = true;
            logSlow("onSceneRendered (Iris pass-through)", System.nanoTime() - t0Scene);
            return;
        }

        // IRIS SCALED MODE (2026-08-18): irisSwapActive — the pack rendered
        // through the scaled proxy (gbuffers + composite + final pass all at
        // scaled res because Iris sizes from mainRT). Fall through to the
        // shared GL section below: renderTarget holds the pack's composite and
        // FSR1 uses EASU/RCAS when its experimental opt-in is active, while
        // FSR2/FSR3 use their temporal pipelines, FSR4 uses its D3D12
        // dispatch, and FSR3_FG_DX12 uses the same D3D12 dispatch + the
        // deferred FG generate in presentDirect() (the pack's own depth
        // buffers do not land in the proxy depth, and probeFsr4Depth gates
        // uUseDepth accordingly).

        // VulkanMod path: scene was already captured by recordFrameCopyOps in the
        // endFrame mixin, and FSR4 dispatch runs in onRenderBegin (processing the
        // previous frame's scene). The output VkImage gets copied to the swapchain
        // in the next frame's recordFrameCopyOps. No GL operations needed here.
        if (VulkanModCompat.isActive()) {
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            sceneUpscaled = true;

            // REAL FSR4 INPUTS (2026-08-05): the VK MV-gen compute shader needs
            // the current frame's inverse view-projection + the previous frame's
            // VP to generate motion vectors. The projection hook (captureAndJitter
            // @ModifyArg) DOES fire under VulkanMod and captures the REAL world
            // projection into lastProj (the dispatch near/far at onRenderBegin
            // reads it — real values like 0.5/40920, not the rebuilt 0.05/768).
            // Use THAT captured matrix for MV-gen so the MV projection matches
            // the render's: a rebuilt matrix mismatches mid-depth ~10x (ndcZ=0
            // maps to z~0.1 instead of z~1.0), making translation MVs ~10x too
            // large → the reactive mask saturates → the SDK discards ALL history
            // → spatial-only output (shake + pixelated sun). Rebuild only as a
            // fallback when the captured matrix is garbage (historical
            // far=-986895.06). This runs AFTER renderLevel, so the camera is
            // this frame's camera and lastProj is this frame's clean projection.
            if (!isSaneProjection()) {
                updateProjectionFromCamera();
            }

            Camera vkCam = client.gameRenderer != null ? client.gameRenderer.getMainCamera() : null;
            if (vkCam != null && Math.abs(lastProj.m11()) > 0.001f) {
                if (!vulkanModMvProjLogged) {
                    vulkanModMvProjLogged = true;
                    float a = lastProj.m22();
                    float b = lastProj.m23();
                    float n = b / (a - 1.0f);
                    float f = b / (a + 1.0f);
                    FSRMod.LOGGER.info("VKMod MV-gen projection: near={} far={} (captured {})",
                        String.format(java.util.Locale.ROOT, "%.3f", n),
                        String.format(java.util.Locale.ROOT, "%.3f", f),
                        isSaneProjection() ? "real render capture" : "camera rebuild fallback");
                }	                Vec3 cp = vkCam.position();
	                // Cam-motion inputs for the MV-gen shader (2026-08-08): eye
	                // speed in blocks/sec + rotation delta in radians, from this
	                // frame's camera vs the previous frame's (dt-normalized so the
	                // thresholds are FPS-independent). Idle bob ~0.13 blocks/sec;
	                // walk ~4.3; sprint ~5.6. The shader ramps reactive on the
	                // speed in no-real-depth mode and gates the cutout detector on
	                // it (like the GL path's uCamMotion).
	                long nowNano = System.nanoTime();
	                float dtSec = hasPrevVkCam ? (float) ((nowNano - lastVkCamNano) / 1e9) : 0.0f;
	                if (dtSec < 1e-4f) dtSec = 1e-4f;
	                if (dtSec > 0.5f) dtSec = 0.5f;
	                float eyeSpeed = 0.0f;
	                float rotDelta = 0.0f;
	                if (hasPrevVkCam) {
	                    double dx = cp.x - prevVkCamX;
	                    double dy = cp.y - prevVkCamY;
	                    double dz = cp.z - prevVkCamZ;
	                    eyeSpeed = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / dtSec);
	                    org.joml.Quaternionf qd = new org.joml.Quaternionf(prevVkCamRot)
	                        .conjugate().mul(vkCam.rotation());
	                    rotDelta = qd.angle();
	                }
	                prevVkCamX = cp.x;
	                prevVkCamY = cp.y;
	                prevVkCamZ = cp.z;
	                prevVkCamRot.set(vkCam.rotation());
	                lastVkCamNano = nowNano;
	                hasPrevVkCam = true;
	                // FORCE-SPATIAL A/B (2026-08-09): debugMode >= 6 feeds a huge
	                // uCamMotion so the shader's translation reactive ramp
	                // (smoothstep(0.05, 1.5, uCamMotion)) saturates to 255 for
	                // EVERY pixel — the SDK never accumulates history, even while
	                // barely moving (the case the 0.05-floor build never actually
	                // covered: barely-moving is mostly rotation with eyeSpeed≈0,
	                // so its reactive stayed ~0-39 and it kept accumulating).		                // Verdict: warp gone → the temporal accumulation is the cause;
		                // warp persists → the SDK's spatial path itself warps on this
		                // input/GPU. One-shot log so the session is identifiable.
		                // NOTE (2026-08-09): gated to < 8 so the 8x-jitter diagnostic
		                // (debugMode >= 8, "does the render actually shift?") runs with
		                // NORMAL accumulation + the color-diff probe's static-camera
		                // gate (forced eyeSpeed=999 would silence both).
		                if (config.debugMode >= 6 && config.debugMode < 8) {
		                    eyeSpeed = 999.0f;
	                    if (!vulkanModForceSpatialLogged) {
	                        vulkanModForceSpatialLogged = true;
	                        FSRMod.LOGGER.info("[A/B] debugMode>=6 — forcing reactive=255 everywhere (fully spatial, no temporal accumulation)");
	                    }
	                }
	                VulkanModCompat.setVkModCamMotion(eyeSpeed, rotDelta);
	                // FG present translation gate (2026-08-15): the FG frame is
	                // only shown when the camera is still or rotating (see the
	                // field comment). Store the real eyeSpeed (post-diagnostic-
	                // override so the gate matches the shader's own signal).
	                vkModFgEyeSpeed = eyeSpeed;
	                tmpQuat.set(vkCam.rotation()).conjugate();
                tmpView.identity()
                    .rotation(tmpQuat)
                    .translate(-(float) cp.x, -(float) cp.y, -(float) cp.z);	                // CLEAN (unjittered) projection — GL-parity (2026-08-18): the GL
	                // FSR4 MV_GEN_CS uses lastProj (clean), NOT lastProjJittered —
	                // the scene renders jittered and the SDK receives jitterOffset
	                // separately, so the MVs must carry ONLY true camera motion.
	                // The old JITTERED matrix baked the per-frame jitter delta into
	                // the MV and, since the SDK ALSO gets jitterOffset, the jitter
	                // was double-counted → history samples misplaced every frame →
	                // "the upscaler thinks the player is moving when it's not" +
	                // smearing/shake during motion. prevViewProj is saved from this
	                // clean currViewProj below, so prev is clean too (GL parity).
	                currViewProj.set(lastProj);
	                currViewProj.mul(tmpView);

                currViewProj.invert(mvTmpMat).get(mvMatBuf);
                float[] invCurrVP = new float[16];
                System.arraycopy(mvMatBuf, 0, invCurrVP, 0, 16);
                float[] prevVP = new float[16];
                if (hasPrevFrame) {
                    prevViewProj.get(prevVP);
                } else {
                    // REVIEW FIX (2026-08-05): identity prevVP does NOT yield ~0
                    // MVs — prevUv = world.xy*0.5+0.5 diverges from uv off-center
                    // (world depth ≈ 0.1 for ndcZ=0 near=0.05), giving hundreds of
                    // px of garbage MV on frame 1. Harmless ONLY because the FSR4
                    // SDK is dispatched with reset=true on that frame (history is
                    // discarded, reactive≈1 → pure spatial first frame). Keep the
                    // identity fill; do not "fix" it to a real matrix — the first
                    // frame's MV content is discarded by reset anyway.
                    java.util.Arrays.fill(prevVP, 0.0f);
                    prevVP[0] = prevVP[5] = prevVP[10] = prevVP[15] = 1.0f;  // identity
                }
                VulkanModCompat.setVkModMvGenMatrices(invCurrVP, prevVP);
                prevViewProj.set(currViewProj);
                hasPrevFrame = true;
            }

            logSlow("onSceneRendered (VulkanMod)", System.nanoTime() - t0Scene);
            return;
        }

        // --- Non-Iris path (OpenGL) ---
        if (renderTarget == null) return;
        probeSceneDepth();

            // NOTE: Do NOT call updateProjectionFromCamera() here. captureAndJitter()
        // already captured the exact projection matrix used during rendering
        // (including jitter). updateProjectionFromCamera() rebuilds from camera
        // params and overwrites lastProjJittered, causing MV gen to use stale
        // matrices → wobble + no temporal accumulation. Only rebuild from camera
        // as a fallback when captureAndJitter was not called (e.g. non-temporal
        // mode, VulkanMod path, Iris path).
        // updateProjectionFromCamera();

        // Restore viewport to display resolution (was scaled for 3D rendering)
        GL43C.glViewport(0, 0, displayWidth, displayHeight);

        int renderTex = ((GlTexture)renderTarget.getColorTexture()).glId();
        int depthTex = renderTarget.getDepthTexture() != null
            ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

        ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);

        fsr2OutputTexId = 0;

        // IRIS JITTER AUTO-PROBE (2026-08-18): only in the irisSwapActive mode,
        // during the first moments of engagement. Reads the pack's composite and
        // measures whether it physically shifts by the sent Halton jitter; the
        // verdict (irisJitterBlind) zeroes the jitter in onRenderBegin next
        // frame if the pack ignores it.
        if (irisSwapActive) {
            irisJitterProbe(renderTex, scaledWidth, scaledHeight);
        }

        // BROKEN-COMPOSITE TERRAIN PROBE (2026-08-24, moved here 2026-08-25):
        // votes on the pack's proxy composite having lost its terrain (sky/fog
        // only) after an Iris pipeline rebuild, and latches native pass-through
        // via irisSwapBrokenLatch (checked in onRenderBegin). ORIGINALLY only
        // ran inside the FSR1 spatial fallback — but a sky-only composite is as
        // likely to be upscaled by FSR4/FSR2/FSR3 as blitted by FSR1, and the
        // user's session (FSR4 config, Iris + Photonics swap active) showed the
        // terrain-missing signature (bright sky luma 146 / dark terrain luma 37)
        // with no protection on the FSR4 path. This single shared site runs on
        // EVERY swap-active path (FSR1 fallback, FSR2/3, FSR4 GL dispatch) with
        // the same 2s throttle (probe cap removed 2026-08-25 — see the probe
        // site below).
        boolean probedThisFrame = false;
        // DEBUG-GATED (2026-08-29): the probe is OFF in normal play. One sample
        // costs a ~26ms pipeline flush, and readTexBlockRgba8 reads at
        // renderLevel(TAIL) with Iris's framebuffer state live — the exact
        // situation that faulted the AMD GL driver before (0xC0000005 in
        // atio6axx.dll; see the readTexBlockRgba8 comment). armTerrainProbeBurst()
        // made it fire on the first swap frame after a world join, the most
        // volatile frame there is (Iris pipeline destroyed + recreated, Photonics
        // reallocating, FSR1 initialising) — and the 17:43 crash
        // (exitcode -1073741819, no hs_err, no crash report, the documented
        // signature of that driver fault) landed right after that sample.
        // This matches the project's own precedent: the FSR2/3 OUTPROBE readback
        // burst is debugMode-gated for exactly the same reason.
        // Set debugMode > 0 to re-enable the broken-composite safety net.
        if (irisSwapActive && !VulkanModCompat.isLoaded() && config.debugMode > 0) {
            long probeNow = System.currentTimeMillis();
            if (terrainProbeAtMs < 0) terrainProbeAtMs = probeNow;
            // No probe-count cap (2026-08-25): the original 40-probe ceiling
            // stopped sampling ~80s into a session, so a pipeline rebuild that
            // happened LATER (dimension change, pack switch, FSR version change
            // mid-session) was never voted on. The read is one 16x16 block
            // pair every 2s — negligible cost, and the latch self-stops once
            // native pass-through engages (irisSwapActive drops).
            if (probeNow >= terrainProbeAtMs) {
                // PROBE CADENCE (2026-08-29, "every second it blinks"): a block
                // readback is a ~26ms pipeline flush (see
                // diagnosticReadbackThisFrame), so sampling every 2s FOREVER was
                // a permanent visible hitch guarding a failure that can only
                // occur right after an Iris pipeline rebuild. Dense (2s) while a
                // transition window is armed, a 30s heartbeat otherwise.
                terrainProbeAtMs = probeNow
                    + (probeNow < terrainProbeBurstUntilMs ? 2000L : 30000L);
                terrainProbeCount++;
                evaluateProxyContentAndVote(renderTex, scaledWidth, scaledHeight);
                probedThisFrame = true;
                // See diagnosticReadbackThisFrame: this readback is a pipeline
                // flush, not GPU saturation — keep it out of the sat guards.
                diagnosticReadbackThisFrame = true;
            }
        }

        // FSR4 dispatch (D3D12 compute via AMD FSR SDK unified API).
        // Only runs when FSR4 version is selected — NOT for FSR2/FSR3 (they use
        // their own GL compute-shader pipelines below).
        // NOTE: getColorAllocPtr() != 0 is REQUIRED here (parity with the
        // VulkanMod path). The pool is destroyed + reallocated during reinit
        // (resize/mode switch); if a reinit failed partway (allocateFsr4Pool
        // returned false), the pointer is 0 and dispatching would hand the
        // native layer a freed/null pool → 0xC0000005. Guarding here keeps the
        // FSR1 spatial fallback active until a successful reinit.
        // PARITY BYPASS state tracking: entering bypass (scale pushed to >=90%)
        // simply routes to the FSR1 spatial branch below; leaving bypass forces
        // a clean temporal rebuild so the first FSR4 dispatch doesn't reproject
        // the stale (frozen-while-bypassed) prevViewProj history.
        boolean fsr4ParityNow = fsr4ParityBypassActive();
        if (fsr4ParityNow != fsr4ParityBypassPrev) {
            if (!fsr4ParityNow) {
                hasPrevFrame = false;
                fsr2FrameCount = 0;
            } else {
                // Entering bypass: FSR4 temporal quality is swapped for the cheap
                // FSR1 blit+RCAS at near-parity scale — announce it once (same
                // channel as the fallback notifications; parity flips are rare).
                notifyFsr4Fallback("FSR4 bypassed at >=90% render scale — using FSR1 upscale (faster on iGPUs). Drop the scale below 90% for FSR4");
            }
            fsr4ParityBypassPrev = fsr4ParityNow;
        }
        // SIZE-ALIGNMENT GUARD (2026-08-23, "zoomed into a corner" fix): never
        // let the FSR4 dispatch run while the D3D12 context/pool sizes differ
        // from the current scene dims — glCopyImageSubData clips to the smaller
        // of the two sizes and the SDK upscales with the context-cached render
        // size, so a mismatched pool + fresh-scene copy samples stale UVs and
        // the image zooms into a corner (the documented 2026-08-08 dynamic-res
        // corner-crop class). Suspend for the frame (FSR1 spatial fallback
        // renders fresh); the needsRecreate path above re-aligns the
        // context/pool within the debounce window, and the re-init clears the
        // suspension on success.
        if (fsr4Initialized && (fsr4ContextRenderW != scaledWidth
                || fsr4ContextRenderH != scaledHeight
                || fsr4ContextDisplayW != displayWidth
                || fsr4ContextDisplayH != displayHeight)) {
            if (!fsr4SizeMismatchLogged) {
                fsr4SizeMismatchLogged = true;
                FSRMod.LOGGER.warn("[FSR4-PATH] context {}x{}->{}x{} != scene {}x{}->{}x{} — FSR4 dispatch skipped (FSR1 until recreate)",
                    fsr4ContextRenderW, fsr4ContextRenderH, fsr4ContextDisplayW, fsr4ContextDisplayH,
                    scaledWidth, scaledHeight, displayWidth, displayHeight);
            }
            fsr4Suspended = true;
            fsr4SuspendedAtMs = System.currentTimeMillis();
            fsr4FirstCleanProbeMs = 0;
            fsr4WorldCalmStreak = 0;
            fsr4PreCheckSkips = 0;
            hasPrevFrame = false;
            fsr2FrameCount = 0;
        }

        // HARD SIZE ALIGNMENT on the dispatch (2026-08-23): even if the probe
        // above resumes FSR4 the same frame the guard suspended it, this
        // condition refuses any dispatch whose pool/context size differs from
        // the current scene — the corner-crop can never be produced.
        if (fsr4Available && fsr4Initialized && sharedTexMgr != null
            && sharedTexMgr.getColorAllocPtr() != 0
            && !fsr4HardDisabled
            && !fsr4ParityNow
            && fsr4ContextRenderW == scaledWidth && fsr4ContextRenderH == scaledHeight
            && fsr4ContextDisplayW == displayWidth && fsr4ContextDisplayH == displayHeight
            && (isFsr4() || isFrameGenDx12())) {
            // PATH DIAGNOSTIC (2026-08-20): one-shot log confirming the REAL
            // FSR4 dispatch engaged (vs the FSR1 parity-bypass / suspended /
            // init-failed paths that silently route elsewhere). Includes the
            // Iris state so a shader-pack session is identifiable — under a
            // pack the depth is untrusted (constant-depth MVs) and the jitter
            // may be zeroed by the blind verdict.
            if (!fsr4PathLogged) {
                fsr4PathLogged = true;
                FSRMod.LOGGER.info("[FSR4-PATH] real D3D12 dispatch engaged — irisSwapActive={} irisJitterBlind={} realDepth={} jitter=({},{}) render={}x{} display={}x{}",
                    irisSwapActive, irisJitterBlind, fsr4RealDepthAvailable,
                    String.format(java.util.Locale.ROOT, "%.3f", jitterX),
                    String.format(java.util.Locale.ROOT, "%.3f", jitterY),
                    scaledWidth, scaledHeight, displayWidth, displayHeight);
            }
            long tFsr4Start = System.nanoTime();
            // GPU-SATURATION GUARD (2026-08-05 v2): the AMD GL driver
            // (atio6axx.dll) faults with 0xC0000005 when the cross-API
            // shared-texture work runs on a saturated queue, so this guard now
            // keeps FSR4 off the crash-prone path during storms:
            //   1. PRE-CHECK: worldMs-spike or prior-fence-timeout → skip ALL
            //      cross-API work this frame (before any risky GL op runs);
            //      after 3 consecutive such frames, suspend to FSR1.
            //   2. FENCE TIMEOUT: async mode suspends on the FIRST timeout
            //      (FSR1 fallback — never touches the D3D12-imported textures).
            //      Sync/FG mode keeps the 33ms extended-wait recovery.
            //   3. STICKY RESUME: 2 consecutive drained-queue probes before
            //      re-arming FSR4 (a single probe can be a mid-storm lull).
            //   4. ESCALATION: 5 consecutive dispatch failures hard-disable FSR4
            //      for the session (SEH-caught crashes surface as failures).
            if (fsr4Suspended) {
                // FALLBACK-STATUS LOG (2026-08-23): every 3s while suspended,
                // log the fallback state so a flap is visible in a normal
                // session log (chat notifications are 10s-cooldown-gated and
                // hide the cadence).
                if (System.currentTimeMillis() - lastFsr4FallbackStatusLogMs >= 3000L) {
                    lastFsr4FallbackStatusLogMs = System.currentTimeMillis();
                    FSRMod.LOGGER.info("[FSR4-PATH] still on FSR1 fallback: suspended={} hardDisabled={} suspendedFor={}ms pack={} render={}x{}",
                        fsr4Suspended, fsr4HardDisabled,
                        System.currentTimeMillis() - fsr4SuspendedAtMs,
                        irisActiveThisFrame, scaledWidth, scaledHeight);
                }
                // DEFERRED RESUME-PROBE FENCE (2026-08-06, "never goes back to
                // FSR4 automatically" fix): the fence we poll here was created
                // at the END of the PREVIOUS frame's block (below), so it
                // guards only work submitted through LAST frame — including
                // last frame's scene render, which has since had a full frame
                // (present + this frame's scene render) to complete on the GPU.
                // The old code created a FRESH fence in this block, guarding
                // THIS frame's just-submitted scene render (several ms of GPU
                // time) — the 0ns poll could NEVER signal on a GPU-bound iGPU,
                // the 2-signal clean streak was unreachable, and FSR4 stayed
                // parked on FSR1 until a manual version toggle forced a
                // reinit. Now the 0ns poll signals as soon as the GPU drains
                // past last frame's work — auto-resume works again.
                long probe = fsr4ProbeFence;
                fsr4ProbeFence = 0;
                if (probe != 0) {
                    // Free 0ns poll first (catches the already-drained case at
                    // zero cost), then a throttled bounded wait (every 16th
                    // suspended frame, up to 8ms) so a recovered queue is
                    // detected within ~0.25s while the stall averages
                    // <1ms/frame during heavy saturation.
                    int probeStatus = GL43C.glClientWaitSync(probe, 0, 0L);
                    boolean probeSignaled = probeStatus == GL43C.GL_ALREADY_SIGNALED
                        || probeStatus == GL43C.GL_CONDITION_SATISFIED;
                    // Whether THIS frame ran the throttled bounded wait (0ns poll
                    // failed AND a 16th frame AND dispatch-failure backoff elapsed).
                    boolean boundedWaitRan = !probeSignaled
                        && System.currentTimeMillis() - lastFsr4DispatchFail
                            >= FSR4_DISPATCH_FAIL_BACKOFF_MS
                        && (++fsr4ProbeCounter & 15) == 0;
                    if (boundedWaitRan) {
                        probeStatus = GL43C.glClientWaitSync(probe, 0, 8_000_000L);
                        probeSignaled = probeStatus == GL43C.GL_ALREADY_SIGNALED
                            || probeStatus == GL43C.GL_CONDITION_SATISFIED;
                    }
                    GL43C.glDeleteSync(probe);
                if (System.currentTimeMillis() - lastFsr4DispatchFail
                        < FSR4_DISPATCH_FAIL_BACKOFF_MS) {
                    // Dispatch-failure cooldown still active — no resume yet.
                } else if (probeSignaled) {
                    // A signal (0ns poll OR bounded wait) counts toward the
                    // clean streak; 2 signals resume FSR4. CRITICAL (2026-08-05
                    // v3): 0ns-poll FAILURES on the frames between bounded waits
                    // are NEUTRAL, not resets — on an always-busy iGPU (~90%
                    // util) the 0ns poll essentially never signals (it guards
                    // this frame's just-submitted scene render), so resetting on
                    // every failure made the 2-signal streak unreachable and
                    // FSR4 stayed suspended on FSR1 PERMANENTLY (the reported
                    // "FSR4 just falls back to FSR1" regression). Only a FAILED
                    // bounded wait — the queue couldn't drain within 8ms —
                    // proves genuine saturation and resets the streak. Result:
                    // on normal-busy GPUs the bounded wait signals every 16th
                    // frame, so FSR4 resumes ~0.5s after a storm ends.
                    long nowMs = System.currentTimeMillis();
                    // SUSTAINED-CALM GATE (2026-08-18, storm-flap fix): a stale
                    // streak RESTARTS on each signal whose gap exceeds the calm
                    // span, so the streak can only build when the queue signals
                    // on dense (consecutive) frames. Storm lulls signal the
                    // probe but are separated by multi-second stalls → every
                    // signal restarts the streak → FSR4 stays parked on FSR1
                    // through the whole storm. A genuinely drained queue
                    // signals every frame → streak 2 within ~33ms → resume
                    // ~2 frames after the storm ends. The minimum parked time
                    // is an absolute floor (0 = unknown suspension start never
                    // blocks resume).
                    if (fsr4ProbeCleanStreak == 0
                            || nowMs - fsr4FirstCleanProbeMs > FSR4_CALM_SPAN_MS) {
                        fsr4ProbeCleanStreak = 0;
                        fsr4FirstCleanProbeMs = nowMs;
                    }
                    boolean minParked = fsr4SuspendedAtMs == 0
                        || nowMs - fsr4SuspendedAtMs >= FSR4_SUSPEND_MIN_MS;
                    boolean denseSignals = nowMs - fsr4FirstCleanProbeMs <= FSR4_CALM_SPAN_MS;
                    // DIMENSION-CHANGE HOLD (2026-08-22): do NOT resume inside
                    // the hold window — the transition's light loading frames
                    // make the probe signal ("queue drained") while Voxy's
                    // pipeline rebuild + the new dimension's chunk streaming
                    // are still to come; re-arming then re-dispatches FSR4
                    // into the storm (End-entry hard-freeze). Once the hold
                    // elapses, the normal streak logic applies.
                    boolean dimHoldElapsed = fsr4DimChangeAtMs == 0
                        || nowMs - fsr4DimChangeAtMs >= FSR4_DIM_CHANGE_HOLD_MS;
                    // ADAPTIVE CALM GATE (2026-08-23): even the FENCE path now
                    // requires the sustained world-calm streak — a fence
                    // signal alone (queue drained for 1-2 frames between
                    // storms) used to resume FSR4 straight into the next
                    // burst, producing the blink-per-storm-cycle. Both resume
                    // paths share the calm requirement now.
                    // CALM-DURATION GATE (2026-08-23 v2, "blinking is there but
                    // just less often" fix): the streak is frame-counted, but
                    // 10 frames at 60fps is only ~0.17s — a momentary queue
                    // drain (one calm window between bursts) satisfied the
                    // count and resumed FSR4 straight into the next burst, then
                    // re-suspended within 178ms (log: restored 16:00:50 →
                    // suspendedFor=178ms, repeated every ~3s). Require the
                    // streak to have persisted for REAL time (2.5s) as well as
                    // the frame count, so a resume only fires when the load
                    // genuinely subsided.
                    boolean calmDurationOk = fsr4WorldCalmStartMs != 0
                        && nowMs - fsr4WorldCalmStartMs >= FSR4_WORLD_CALM_MS;
                    if (fsr4WorldCalmStreak >= fsr4CalmFramesNeeded() && calmDurationOk
                        && ++fsr4ProbeCleanStreak >= 2 && minParked && denseSignals && dimHoldElapsed) {
                        fsr4Suspended = false;
                        fsr4ConsecutiveSkips = 0;
                        lastFsr4DispatchFail = 0;
                        fsr4FenceTimedOutPrevFrame = false;
                        fsr4ProbeCleanStreak = 0;
                        fsr4FirstCleanProbeMs = 0;
                        fsr4PreCheckSkips = 0;
                        fsr4WorldCalmStreak = 0;
                        fsr4WorldCalmStartMs = 0;
                        fsr4ResumeAtMs = System.currentTimeMillis();
                        if (shouldDebug()) {
                            FSRMod.LOGGER.info("FSR4: GPU queue drained — resuming FSR4");
                        }
                    }
                } else if (boundedWaitRan) {
                    // Bounded wait ran and FAILED — queue genuinely deep (>8ms
                    // of pending work): reset the clean streak.
                    fsr4ProbeCleanStreak = 0;
                    fsr4FirstCleanProbeMs = 0;
                }
                // else: 0ns poll failed, no bounded wait this frame — neutral.
                // (Do NOT return here — falling through keeps the FSR1 spatial
                // fallback below active for this frame.)
                }
                // Create NEXT frame's probe fence NOW: it marks this frame's
                // scene render (already submitted by renderLevel) and all work
                // submitted before it (the FSR1 fallback below runs after this
                // fence is created, so it is deliberately NOT covered — the
                // probe's job is to measure drain past the scene render, not
                // the cheap fallback). The next frame's probe polls this
                // frame-old fence, which signals as soon as the GPU drains
                // past this frame's work. Flush so the fence command leaves
                // the client buffer immediately. Skipped when the streak just
                // resumed (fsr4Suspended is false again — the consumed fence
                // was already deleted above; no new one is needed while FSR4
                // is active).
                if (fsr4Suspended) {
                    GL43C.glFlush();
                    fsr4ProbeFence = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                }
                // WORLD-CALM RESUME (2026-08-23, "blinking after a few minutes,
                // only fixed by toggling off/on" fix): on an always-busy iGPU
                // (Voxy/C2ME background meshing) the probe fence may NEVER
                // signal cleanly, so the 2-dense-signal resume is unreachable
                // and FSR4 stays parked on FSR1 for the whole session — the
                // user saw a visible quality drop (jagged/blinking) that only
                // a manual toggle fixed. The fence alone is therefore not a
                // sufficient drain signal on this class of machine: also
                // resume after a sustained run of CPU-side CALM world frames
                // (worldMs under the same spike threshold the pre-check uses
                // for the reverse direction) while suspended — the FSR1
                // fallback is cheap, so these frames carry no stall risk, and
                // a genuinely stormy queue produces worldMs spikes that reset
                // the streak. The min-parked and dimension-hold gates are
                // shared with the fence path.
                long nowMsCalm = System.currentTimeMillis();
                boolean minParkedCalm = fsr4SuspendedAtMs == 0
                    || nowMsCalm - fsr4SuspendedAtMs >= FSR4_SUSPEND_MIN_MS;
                boolean dimHoldCalm = fsr4DimChangeAtMs == 0
                    || nowMsCalm - fsr4DimChangeAtMs >= FSR4_DIM_CHANGE_HOLD_MS;
                if (minParkedCalm && dimHoldCalm) {
                    float calmWorldMs = (System.nanoTime() - t0BeginEnd) / 1_000_000.0f;
                    // LIVE CALM BASELINE (2026-08-23): the saturation EMA is
                    // otherwise frozen while suspended (only the FSR4-active
                    // dispatch updates it), so after a pack or load-profile
                    // change the calm threshold would compare against a stale
                    // pack-OFF baseline — the pack's normal frames look like
                    // spikes and FSR4 can never build a calm streak to resume.
                    // Slowly track the current worldMs while suspended (0.25
                    // alpha — a 1-3s storm barely moves it, a sustained new
                    // load settles in a few seconds).
                    if (fsr4EmaWorldMs <= 0.0f) {
                        fsr4EmaWorldMs = Math.max(calmWorldMs, SAT_SPIKE_MIN_MS);
                    } else if (calmWorldMs > fsr4EmaWorldMs) {
                        // UPWARD-ONLY (2026-08-23 v2, "blinking less often but
                        // still there" fix): while suspended the frames are
                        // FSR1-cheap (no cross-API work), so folding them
                        // DOWN drags the baseline below the true FSR4-active
                        // cost — the first resumed frames then read as 1.5x
                        // spikes and re-suspend (log: restored 16:00:50 →
                        // suspendedFor=178ms). Only HEAVIER frames may raise
                        // the baseline while suspended; downward tracking
                        // happens on ACTIVE calm frames at SAT_EMA_ALPHA
                        // (which reflect the real cross-API cost).
                        fsr4EmaWorldMs += SAT_EMA_ALPHA * 0.25f * (calmWorldMs - fsr4EmaWorldMs);
                    }
                    // THRESHOLD-ALIGNMENT (2026-08-23, "blinking again" — the
                    // flap is back fix): the resume threshold must use the SAME
                    // spike Math.max as the active pre-check (1.5x/12ms under
                    // irisSwapActive). Before this, resume gated on 2.0x/20ms
                    // while the pre-check suspended at 1.5x/12ms — a frame at
                    // 1.6x of the EMA passed the resume gate (calm) but was a
                    // spike to the active path, so FSR4 resumed and immediately
                    // re-suspended (log: restored 15:43:06 → suspendedFor=7ms).
                    float calmSpikeMult = irisSwapActive ? 1.5f : SAT_SPIKE_MULT;
                    float calmSpikeMin  = irisSwapActive ? 12.0f : SAT_SPIKE_MIN_MS;
                    // Require a HYSTERESIS MARGIN: to resume, the frame must
                    // be CALM relative to the spike threshold, not merely below
                    // the relaxed non-Iris 2.0x. Use 0.8x of the spike threshold
                    // so the resumed state starts below the suspend line and
                    // small jitter around it cannot flap.
                    float calmThresh = Math.max(0.8f * calmSpikeMult * fsr4EmaWorldMs, calmSpikeMin);
                    if (calmWorldMs < calmThresh) {
                        if (fsr4WorldCalmStreak == 0) {
                            fsr4WorldCalmStartMs = nowMsCalm;
                        }
                        // CALM-DURATION GATE (2026-08-23 v2): same real-time
                        // requirement as the fence path — a frame count alone
                        // (10 frames = 0.17s at 60fps) resumes into the next
                        // burst; 2.5s of sustained calm is the requirement.
                        if (++fsr4WorldCalmStreak >= fsr4CalmFramesNeeded()
                            && nowMsCalm - fsr4WorldCalmStartMs >= FSR4_WORLD_CALM_MS) {
                            fsr4WorldCalmStreak = 0;
                            fsr4WorldCalmStartMs = 0;
                            fsr4Suspended = false;
                            fsr4ResumeBlendRemaining = FSR4_RESUME_BLEND_FRAMES;
                            fsr4ConsecutiveSkips = 0;
                            lastFsr4DispatchFail = 0;
                            fsr4FenceTimedOutPrevFrame = false;
                            fsr4ProbeCleanStreak = 0;
                            fsr4FirstCleanProbeMs = 0;
                            fsr4PreCheckSkips = 0;
                            fsr4ResumeAtMs = System.currentTimeMillis();
                            if (shouldDebug()) {
                                FSRMod.LOGGER.info("FSR4: world render calm for {}/{} while suspended — resuming FSR4", FSR4_WORLD_CALM_FRAMES, FSR4_WORLD_CALM_MS);
                            }
                        }
                    } else {
                        fsr4WorldCalmStreak = 0;
                        fsr4WorldCalmStartMs = 0;
                    }
                }
            }
            if (!fsr4Suspended) {
            // DRIVER-CRASH PRE-CHECK (2026-08-05 v2, atio6axx.dll): gate the
            // cross-API work BEFORE it runs — a fence timeout alone is not
            // enough because the crash-prone copies + MV-gen execute BEFORE the
            // fence wait, so the first storm frame would still run them (the
            // driver faults fast: 7 crashes today, 3 within 3 minutes). Two
            // signals: (a) the previous frame's GL fence timed out
            // (fsr4FenceTimedOutPrevFrame — set on the timeout paths below), and
            // (b) the world-render time spiked (worldMs > 2x its calm-baseline
            // EMA and > 20ms), measured here from t0BeginEnd — the same signal
            // the SAT guard's EMA (updated later in this method) tracks. On a
            // spike we skip ALL cross-API work (no copies, no MV gen, no
            // dispatch, no drain — the drain's output copy is also a
            // shared-texture GL op) and present the previous FSR4 output. This
            // applies to BOTH async and sync/FG modes.
            float fsr4WorldMs = (System.nanoTime() - t0BeginEnd) / 1_000_000.0f;
            // FSR4-private calm baseline (see field comment — the shared SAT EMA
            // never updates on FSR4 frames). COLD-START FIX (2026-08-06, "went
            // to fsr1 at world-join"): the old code seeded the EMA only on a
            // CALM frame and treated every cold frame above the 20ms floor as a
            // spike — on a GPU-bound machine (iGPU + Voxy/C2ME/Create at
            // 2580x1080) NO frame is calm, so the EMA never seeded, every frame
            // re-entered cold-start, and FSR4 suspended to FSR1 after
            // FSR4_PRE_SUSPEND_AFTER (3) frames — permanently, until a quiet
            // episode. Now the FIRST sample seeds the baseline unconditionally
            // (spikes cannot fire until a baseline exists), so a stormy machine
            // settles into a storm-appropriate baseline within a few frames and
            // FSR4 stays active through ordinary chunk streaming.
            boolean fsr4FirstSample = fsr4EmaWorldMs <= 0.0f;
            if (fsr4FirstSample) {
                // Seed immediately — even a storm frame is the machine's real
                // baseline right now; do NOT treat it as a spike.
                fsr4EmaWorldMs = Math.max(fsr4WorldMs, SAT_SPIKE_MIN_MS);
            }
            // POST-RESUME BASELINE ABSORB (2026-08-23 v2, "blinking is there but
            // just less often" fix): the EMA was trained on FSR1-CHEAP suspended
            // frames (upward-only tracking while suspended), so right after a
            // resume the baseline can still sit below the true FSR4-active
            // frame cost — the first active frames would read as 1.5x spikes
            // (log: restored 16:00:50 → suspendedFor=178ms). During the resume
            // grace, fold the measured ACTIVE worldMs into the baseline so the
            // spike line reflects the real resumed cost, and only a genuine
            // deviation above it can re-suspend. 0.85 keeps a margin below the
            // frame so the threshold stays ~1.3x of the observed active cost.
            if (!fsr4FirstSample && fsr4ResumeAtMs != 0
                && System.currentTimeMillis() - fsr4ResumeAtMs < FSR4_RESUME_GRACE_MS) {
                fsr4EmaWorldMs = Math.max(fsr4EmaWorldMs, fsr4WorldMs * 0.85f);
            }
            // IRIS-AWARE GUARD (2026-08-21, atio6axx.dll hard-freeze with Physics Mod Pro):
            // Under a shader pack the cross-API D3D12 work is riskier — the
            // pack's own GL passes already stress the AMD driver, and GPU-compute
            // mods (Physics Mod) compete for the same GPU. The default 2×/20ms
            // threshold misses sustained-high-but-not-spikey load that still
            // With the driver under sustained load, tighten the SPIKE
            // THRESHOLD to 1.5×12/ms under iris (spike capture, not
            // suspend count) — the crash is so severe (no hs_err, no
            // crash report — the JVM dies) that over-caution is the
            // only safe policy. Suspend-after is 3 on BOTH paths
            // (FSR4_PRE_SUSPEND_AFTER) per the 2026-08-23 relax below.
            boolean fsr4Iris = irisSwapActive;
            float spikeMult = fsr4Iris ? 1.5f : SAT_SPIKE_MULT;
            float spikeMin  = fsr4Iris ? 12.0f : SAT_SPIKE_MIN_MS;
            // SUSPEND-AFTER RELAX (2026-08-23, "blinking again"): under iris,
            // suspend-after was 2 — Photonics' routine 1-2 frame GI bursts at
            // ~1.5x of the EMA are NORMAL frame-rate (user: "framerate is
            // stable"), so a 2-consecutive-spike rule suspended FSR4 on every
            // burst. Now 3 (the non-Iris value): a single 1-2 frame burst
            // rides through, a sustained storm still suspends within ~0.5s
            // (3 spikes at 60fps). The HYSTERESIS resume (0.8x spike threshold)
            // is what makes a resume durable under the same bursts.
            int suspendAfter = PhotonicsCompat.isLoaded() ? 5 : FSR4_PRE_SUSPEND_AFTER;
            // DIAGNOSTIC-READBACK IMMUNITY (2026-08-29, "every second it blinks"):
            // a synchronous probe readback costs ~26ms and was tripping this
            // spike test every 2s -> the FSR4 dispatch was skipped on every
            // probe frame (the presented image froze and T&C was gated off).
            // The stall is self-inflicted, not GPU saturation — see the
            // diagnosticReadbackThisFrame field doc.
            boolean fsr4SatSpike = !diagnosticReadbackThisFrame
                && fsr4WorldMs > Math.max(spikeMult * fsr4EmaWorldMs, spikeMin);
            if (fsr4FenceTimedOutPrevFrame || fsr4SatSpike) {
                // FSR4 SKIP DIAGNOSTIC (2026-08-29, "every second it blinks"):
                // this branch is the only place a frame can be presented WITHOUT
                // an FSR4 dispatch while FSR4 stays "active" — see the
                // fsr4SkipFrames field doc. Throttled to 4 lines/second so a
                // per-frame skip storm stays readable.
                fsr4SkipFrames++;
                if (System.currentTimeMillis() - lastFsr4SkipLogMs >= 250L) {
                    lastFsr4SkipLogMs = System.currentTimeMillis();
                    FSRMod.LOGGER.info("[FSR4-SKIP] #{} reason={} worldMs={}ms ema={}ms thr={}ms preCheckSkips={}/{} suspended={} irisSwap={} fenceWait={}ms frameEma={}ms",
                        fsr4SkipFrames, fsr4FenceTimedOutPrevFrame ? "fence" : "sat",
                        String.format(java.util.Locale.ROOT, "%.1f", fsr4WorldMs),
                        String.format(java.util.Locale.ROOT, "%.1f", fsr4EmaWorldMs),
                        String.format(java.util.Locale.ROOT, "%.1f", Math.max(spikeMult * fsr4EmaWorldMs, spikeMin)),
                        fsr4PreCheckSkips + 1, suspendAfter, fsr4Suspended, irisSwapActive,
                        String.format(java.util.Locale.ROOT, "%.1f", fsr4LastFenceWaitMs),
                        String.format(java.util.Locale.ROOT, "%.1f", autoEmaFrameMs));
                }
                // One-frame cooldown, NOT a latch — clear the flag so a
                // sustained storm alternates (skip / full / skip) instead of
                // freezing the gate (the old latch bug).
                fsr4FenceTimedOutPrevFrame = false;
                // After a few consecutive storm frames, route to FSR1 so the
                // presented image stays FRESH — a frozen last-FSR4 frame for a
                // sustained storm (elytra flight through chunks) is worse than
                // FSR1 quality. The sticky 2-probe resume restores FSR4 when
                // the queue drains.
                // POST-RESUME GRACE (2026-08-23): right after a resume, a
                // single spike frame (or a short burst) must not instantly
                // re-suspend — that is the flap that makes any resume useless.
                // The frame still skips cross-API work (the proven crash
                // protection), but does not count toward suspension during the
                // grace window.
                boolean inResumeGrace = fsr4ResumeAtMs != 0
                    && System.currentTimeMillis() - fsr4ResumeAtMs < FSR4_RESUME_GRACE_MS;
                if (!inResumeGrace && ++fsr4PreCheckSkips >= suspendAfter) {
                    fsr4RecordSuspension();
                    fsr4Suspended = true;
                    fsr4SuspendedAtMs = System.currentTimeMillis();
                    fsr4FirstCleanProbeMs = 0;
                    fsr4PreCheckSkips = 0;
                    fsr4WorldCalmStreak = 0;
                    fsr4WorldCalmStartMs = 0;
                    hasPrevFrame = false;   // clean history rebuild on resume
                    fsr2FrameCount = 0;
                }
                // This frame's prev-color snapshot is NOT taken (we return before
                // the copy) — mark it stale so next frame's T&C compare is gated
                // off (a snapshot from before the skip episode is from a different
                // camera position; comparing against it fires reactive everywhere).
                fsr4PrevColorFresh = false;
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR4 saturation skip)", System.nanoTime() - t0Scene);
                return;
            }
            // Calm frame — update the FSR4 EMA baseline (calm frames only).
            // A diagnostic-readback frame is excluded too: folding a 30ms
            // self-inflicted stall into the baseline would inflate it and blunt
            // the guard against REAL saturation (see diagnosticReadbackThisFrame).
            if (fsr4FirstSample) {
                fsr4EmaWorldMs = fsr4WorldMs;
            } else if (!diagnosticReadbackThisFrame) {
                fsr4EmaWorldMs += SAT_EMA_ALPHA * (fsr4WorldMs - fsr4EmaWorldMs);
            }
            fsr4PreCheckSkips = 0;
            // NOTE: No frame-time guard here. The GL sync fence (50ms timeout) and
            // D3D12 fence wait (2000ms) handle real hangs. A pre-dispatch timeout
            // caused false fallback to FSR1 on the first frame (FSR4 init takes
            // ~500ms, exceeding any reasonable guard) and on heavy scenes.
            // The FSR4 SDK handles late frames gracefully via deltaTime.

            // ASYNC FSR4 OVERLAP: drain the PREVIOUS frame's deferred dispatch.
            // When config.fsr4Async is on (non-FG), the last dispatch was submitted
            // without waiting; its fence is drained here — the wait is ~0ms because
            // the FSR4 GPU work has been running on the D3D12 queue concurrently
            // with the present + this frame's scene render. Copy its output into
            // mainRT so the GUI/present THIS frame shows a valid upscaled image
            // (the previous frame's — the ~1 frame input latency tradeoff). Must
            // run BEFORE the color copy below, which overwrites the shared input
            // texture that the in-flight dispatch is still reading.
            boolean asyncDrained = false;
            if (config.fsr4Async && !isFrameGenDx12() && fsr4AsyncPending) {
                Fsr4Native.waitIdle();
                fsr4AsyncPending = false;
                copyFsr4OutputToMainRT();
                asyncDrained = true;
            }

            // Copy scene color from renderTarget to shared D3D12 texture
            // (RGBA8 → RGBA8: compatible formats, glCopyImageSubData is fastest).
            // No memory barrier needed before the copy — GL orders copies after
            // the render pass that wrote renderTex; the GL_ALL_BARRIER_BITS below
            // (before the fence/D3D12 read) is the one that matters.
            if (renderTex != -1) {
                GL43C.glCopyImageSubData(
                    renderTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    sharedTexMgr.getColorTexId(), GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    scaledWidth, scaledHeight, 1
                );
            }

            // Copy depth to the shared D3D12 texture (FBO-clear the managed staging
            // R32F to constant 0.5 mid-depth, then glCopyImageSubData to the shared
            // texture — the only write path proven reliable on this driver).
            // OPTIMIZATION: the depth is a CONSTANT 0.5 (never changes between
            // frames), so fill it ONCE per pool allocation instead of every frame —
            // saves a full-screen R32F copy + FBO state churn on every frame. The
            // pool pointer changes whenever the pool is recreated (resize/mode
            // switch), which re-triggers the fill automatically.	            // REAL-DEPTH auto-detect (2026-08-06): probe the RenderTarget depth
	            // on the first frames. If real, per-pixel depth flows every frame
	            // (accurate MVs at ALL distances → the SDK stops rejecting history
	            // during motion → no more "pixelated when moving"); the constant
	            // 0.5 fill remains the once-per-pool fallback when the probe finds
	            // no real content.
	            probeFsr4Depth(depthTex);
	            if (depthTex != -1 && sharedTexMgr.getDepthTexId() != 0) {
	                if (fsr4RealDepthAvailable) {
	                    copyDepthToShared(depthTex, sharedTexMgr.getDepthTexId());
	                } else if (sharedTexMgr.getColorAllocPtr() != fsr4DepthFilledForPool) {
	                    copyDepthToShared(depthTex, sharedTexMgr.getDepthTexId());
	                    fsr4DepthFilledForPool = sharedTexMgr.getColorAllocPtr();
	                }
	                // The glCopyImageSubData above wrote the shared depth; MV-gen
	                // reads it as a texture — GL needs a texture-update barrier
	                // between the write and the shader read (the ALL barrier after
	                // the MV-gen dispatch is too late).
	                GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_UPDATE_BARRIER_BIT);
	            }

            // Compute camera parameters from projection matrix.
            // For standard OpenGL projection:
            //   m22 = -(far + near) / (far - near)
            //   m23 = -2 * far * near / (far - near)
            // Solving: near = b / (a - 1),  far = b / (a + 1)
            float a = lastProj.m22();
            float b = lastProj.m23();
            float cameraNear = b / (a - 1.0f);   // was b/(a+1) — that gave FAR, not NEAR
            float cameraFar = b / (a + 1.0f);    // was b/(a-1) — that gave NEAR, not FAR
            if (Float.isNaN(cameraNear) || Float.isNaN(cameraFar) || cameraNear <= 0.0f) {
                cameraNear = 0.1f;
                cameraFar = 1000.0f;
            }
            float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
            float cameraFovY = (float) (2.0 * Math.atan(tanHalfFovY));
            // deltaTime in MILLISECONDS for FSR4/FSR3 SDK
            float deltaTime = lastFrameDeltaMs;  // inter-frame ms (2026-08-08 fix — was intra-frame ~1.0)
            int frameIndex = fsr2FrameCount;
            boolean reset = !hasPrevFrame || fsr2FrameCount == 0;

            // Debug: log FSR4 camera and dispatch parameters every 60 frames
            if (shouldDebug()) {
                float mvScaleX = 1.0f / scaledWidth;
                float mvScaleY = 1.0f / scaledHeight;
                FSRMod.LOGGER.info(String.format(
                    "[DEBUG_FSR4] frame=%d reset=%s near=%.2f far=%.2f fovY=%.1fdeg tanHalf=%.4f"
                    + " proj22=%.4f proj23=%.4f dt=%.1fms render=%dx%d mvScale=(%.6f,%.6f)"
                    + " hasPrev=%s jitter=(%.3f,%.3f) prevJitter=(%.3f,%.3f)",
                    frameIndex, reset, cameraNear, cameraFar,
                    Math.toDegrees(cameraFovY), tanHalfFovY,
                    a, b, deltaTime, scaledWidth, scaledHeight,
                    mvScaleX, mvScaleY, hasPrevFrame,
                    jitterX, jitterY, prevJitterX, prevJitterY));
            }

	            // Generate motion vectors AND reactive mask into shared textures before FSR4 dispatch.
	            // Read the SHARED depth texture (already filled by copyDepthToShared above with
	            // either real depth or the constant mid-depth fallback) — NOT the raw renderTarget
	            // depth, which reads 0 everywhere in 26.1.2 and would map every pixel to ndcZ=-1
	            // (near plane), exaggerating MVs during translation and breaking history accumulation.
	            if (depthTex != -1 && sharedTexMgr.getMvTexId() != 0 && sharedTexMgr.getReactiveTexId() != 0) {
	                generateFSR4MotionVectors(sharedTexMgr.getDepthTexId(), sharedTexMgr.getMvTexId(), sharedTexMgr.getReactiveTexId(), renderTex);

	                // T&C auto-reactive (2026-08-06): snapshot THIS frame's scene
	                // color into the prev-color texture for NEXT frame's MV-gen
	                // comparison (the dispatch above read the PREVIOUS frame's
	                // snapshot at the reprojected position). Runs AFTER the dispatch
	                // and BEFORE the glMemoryBarrier(GL_ALL) below — the ALL
	                // barrier covers this copy's write so next frame's texelFetch
	                // sees it (GL requires a texture-update barrier between a
	                // texture write and a later shader read).
	                if (renderTex != -1 && fsr4PrevColorTex != -1) {
	                    GL43C.glCopyImageSubData(
	                        renderTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
	                        fsr4PrevColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
	                        scaledWidth, scaledHeight, 1
	                    );
	                    // Snapshot is now fresh for next frame's T&C compare.
	                    fsr4PrevColorFresh = true;
	                }
	            }

	            // Reactive mask is now generated inside the MV gen shader — no separate dispatch needed.

	            // Consolidated memory barrier: covers all GL compute work (depth copy,
	            // MV gen, reactive mask) before D3D12 reads shared textures.
	            // Single GL_ALL_BARRIER_BITS replaces the per-dispatch barriers that
	            // were removed from individual helper methods, saving driver overhead.
	            GL43C.glMemoryBarrier(GL43C.GL_ALL_BARRIER_BITS);

	            // GL sync: fence + client wait (8ms cap).
	            // glFlush() is REQUIRED — without it the fence sync may never
	            // complete because commands sit in the driver's internal buffer.
	            //
	            // The cap was 50ms (3+ frames at 60fps) — with GPU-heavy mods
	            // (Voxy's compute meshing queues work on the SAME GL context
	            // ahead of our copies), glClientWaitSync blocks until ALL queued
	            // GL work completes, turning a deep Voxy queue into a 50ms stall
	            // every frame. 8ms caps the worst case at half a frame; on
	            // timeout the graceful skip path below presents the previous
	            // frame instead of freezing.
            GL43C.glFlush();
            long sync = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            // Fence wait policy (2026-08-05 v4): UNIFIED for async and sync modes.
            // The 8ms cap is NOT a fixed 8ms block — glClientWaitSync returns
            // EARLY (~1-3ms) when the queue is short, so the cap only bites when
            // the queue is genuinely deep. A 0ns poll is deliberately NOT used:
            // the fence guards THIS frame's scene copies + MV-gen compute (~1-3ms
            // of GPU time), which a 0ns poll could never see complete → every
            // frame would time out and FSR4 would never dispatch. Async mode used
            // to differ here — cheap-skip + suspend after 2 timeouts with NO
            // extended recovery wait — which on this GPU-bound machine (90% util,
            // scene render alone routinely exceeds the 8ms cap) parked FSR4 on
            // FSR1 during ordinary chunk streaming ("FSR4 only works with async
            // off"). Sync mode's policy — one 33ms extended re-wait on the FIRST
            // timeout of an episode, cheap-skips after, suspend only after
            // FSR4_SKIP_SUSPEND_AFTER (6) consecutive timeouts — is proven
            // crash-free on this driver and keeps FSR4 active through those same
            // ordinary timeouts, so async now uses it VERBATIM. The pre-dispatch
            // worldMs pre-check + cooldown gate remain the primary storm defense
            // for both modes (that is what stopped the atio6axx.dll crashes).
            // ASYNC FENCE CAP (2026-08-06, review-corrected): a SINGLE 32ms cap
            // for async instead of the two-stage (8ms base + 33ms extended
            // re-wait). glClientWaitSync returns WHEN SIGNALED, not at the
            // timeout — so the old extended wait consumed only the actual drain
            // time, and for a steady render in the 8-33ms band it re-armed every
            // frame (fsr4ConsecutiveSkips reset on success): the 8ms base always
            // timed out first, wasting that stage. One 32ms cap is strictly
            // better: healthy renders still return in 1-3ms, a 10-30ms render
            // returns at its own drain time (no wasted base stage), and only a
            // genuinely >32ms queue (render slower than ~30 FPS) times out → the
            // quiet skip below → suspension to FSR1 (the GPU cannot keep up
            // anyway). Sync/FG keep the two-stage policy (8ms + 33ms first-
            // timeout recovery) because same-frame output matters there.
            // ADAPTIVE FENCE CAP (2026-08-23, "it is not GPU saturation" — the
            // user was right): a FIXED 32ms async cap misreads any GPU-bound
            // but STEADY render (iGPU + Iris pack at 840x525 sustains ~30fps=
            // 33ms/frame) as saturation on EVERY frame: the fence measures
            // this frame's GL copies + MV-gen, which complete at the render's
            // own cadence (~33ms), so a smooth 30fps sits above the cap forever
            // → every frame times out → 6-consecutive-skips suspension → the
            // 12:38 log's 1-3s suspend/resume flap (restore 12:39:06 →
            // suspendedFor=11ms at 12:39:08 → 7ms at 12:39:14…) at stable fps
            // with no Voxy. The cap must FOLLOW the inter-frame EMA
            // (autoEmaFrameMs, measured every frame in onRenderBegin): floor
            // 32ms async / 8ms sync (the atio6axx crash-protection floor),
            // grown to 1.5x the sustained frame time. A smooth slow render
            // never times out; only a genuine spike (frame time jumping above
            // the EMA) still exceeds it → quiet skip → 6-skip suspension
            // stays as storm safety.
            float fenceEmaMs = autoEmaFrameMs > 0.0f ? autoEmaFrameMs : 16.0f;
            long fenceTimeoutNs = (config.fsr4Async && !isFrameGenDx12())
                ? (long) (Math.max(32.0f, fenceEmaMs * 1.5f) * 1_000_000L)
                : (long) (Math.max(8.0f, fenceEmaMs * 1.5f) * 1_000_000L);
            long tWaitStart = System.nanoTime();
            int waitStatus = GL43C.glClientWaitSync(sync, 0, fenceTimeoutNs);
            float fenceWaitMs = (System.nanoTime() - tWaitStart) / 1_000_000.0f;

            // Drain any GL errors that surfaced when the fence flushed the MV-gen
            // dispatch to the GPU. AMD reports compute errors ASYNCHRONOUSLY — the
            // error for MV gen's glDispatchCompute appears at the NEXT glGetError
            // after the fence completes, and if FSR1's RCAS fallback runs next it
            // would be misattributed to rcas-dispatch (GL_INVALID_OPERATION 0x502
            // → EASU permanently disabled at world-join). Consuming them here keeps
            // each pipeline's error attribution honest. (Do NOT delete sync here —
            // the sync-mode first-timeout extended re-wait below still uses it.)
            while (GL43C.glGetError() != GL43C.GL_NO_ERROR) { /* drain MV-gen errors */ }

            if (waitStatus != GL43C.GL_ALREADY_SIGNALED && waitStatus != GL43C.GL_CONDITION_SATISFIED) {
                fsr4FramesSkipped++;
                fsr4ConsecutiveSkips++;

                // UNIFIED TIMEOUT HANDLING (async + sync, 2026-08-05 v4): ONLY
                // the FIRST timeout of an episode attempts the expensive
                // extended wait — a single 33ms re-wait recovers a one-off
                // hiccup (ordinary chunk streaming on this GPU-bound machine)
                // and lets FSR4 dispatch this frame, preserving temporal
                // quality. On the SECOND and later consecutive timeouts we skip
                // cheaply — re-waiting 33ms every frame would stack ~40ms of
                // CPU blocking per frame for up to 6 frames before suspension
                // (~250ms of pure stall = the <10 FPS the user saw).
                // Persistently saturated GPUs are handled by Tier 2 suspension
                // after FSR4_SKIP_SUSPEND_AFTER consecutive timeouts.
                if (fsr4ConsecutiveSkips == 1) {
                    if (config.fsr4Async && !isFrameGenDx12()) {
                        // ASYNC (2026-08-06): NO 33ms extended re-wait — the
                        // 32ms single cap above IS the long wait. A timeout here
                        // means the queue is genuinely >32ms deep (render slower
                        // than ~30 FPS; the GPU cannot keep up regardless), so
                        // re-waiting would only stack more CPU stall. Skip THIS
                        // frame's dispatch quietly: async accepts 1-frame latency
                        // and the previous FSR4 output is already in mainRT, so
                        // the display keeps a real FSR4 frame. The skip counts
                        // toward FSR4_SKIP_SUSPEND_AFTER (6) so a sustained storm
                        // degrades to FSR1 for freshness.
                        fsr4FenceTimedOutPrevFrame = true;
                        fsr4PrevColorFresh = false;
                        sceneUpscaled = true;
                        logSlow("onSceneRendered (FSR4 async skip)", System.nanoTime() - t0Scene);
                        GL43C.glDeleteSync(sync);
                        return;
                    }
                    if (shouldDebug()) {
                        FSRMod.LOGGER.warn("FSR4: GL sync timeout, extending wait once to drain queue");
                    }
                    waitStatus = GL43C.glClientWaitSync(sync, 0, 33_000_000L);
                    if (waitStatus == GL43C.GL_ALREADY_SIGNALED || waitStatus == GL43C.GL_CONDITION_SATISFIED) {
                        fsr4ConsecutiveSkips = 0;
                        fsr4FenceTimedOutPrevFrame = false;  // fence succeeded — full path OK
                        // fell through to dispatch below
                    } else {
                        // Even the extended wait timed out — count this as the
                        // first skip for suspension purposes and fall through to
                        // the shared cheap-skip path.
                        fsr4FenceTimedOutPrevFrame = true;
                        // Prev-color snapshot stale (returned before the copy).
                        fsr4PrevColorFresh = false;
                        sceneUpscaled = true;
                        logSlow("onSceneRendered (FSR4 skip)", System.nanoTime() - t0Scene);
                        GL43C.glDeleteSync(sync);
                        return;
                    }
                } else {
                    // Tier 2: GPU persistently behind (fast movement + chunk
                    // streaming + Voxy meshing). After FSR4_SKIP_SUSPEND_AFTER
                    // consecutive timeouts, suspend FSR4 → FSR1 spatial fallback
                    // (no GL→D3D12 fence, cannot stall). The probe at the top of
                    // this block auto-resumes FSR4 once the queue drains.
                    // Logged once per episode, debug-only.
                    if (fsr4ConsecutiveSkips == FSR4_SKIP_SUSPEND_AFTER && shouldDebug()) {
                        FSRMod.LOGGER.warn("FSR4: {} consecutive GL sync timeouts — GPU saturated, suspending FSR4 (FSR1 spatial fallback until queue drains)", fsr4ConsecutiveSkips);
                    }
                    // POST-RESUME FENCE GRACE (2026-08-24, session-log
                    // "restore → suspendedFor=6ms/383ms" flap fix): the resume
                    // grace covered the pre-check path only — a world-calm
                    // resume into a still-saturated queue could hit 6
                    // consecutive fence timeouts within a few frames and
                    // re-suspend before the resumed dispatch ever got a fair
                    // run (log-verified: restored 10:58:38 → suspendedFor=6ms;
                    // restored 10:59:01 → suspendedFor=383ms, repeated). Within
                    // the grace window the gate still SKIPS the cross-API work
                    // (the atio6axx crash protection — never risk the copies
                    // on a deep queue), but the skip does not count toward
                    // suspension; the counter resets on the first drained
                    // frame's successful dispatch.
                    boolean fenceInResumeGrace = fsr4ResumeAtMs != 0
                        && System.currentTimeMillis() - fsr4ResumeAtMs < FSR4_RESUME_GRACE_MS;
                    if (!fenceInResumeGrace && fsr4ConsecutiveSkips >= FSR4_SKIP_SUSPEND_AFTER) {
                        fsr4RecordSuspension();
                        fsr4Suspended = true;
                        fsr4SuspendedAtMs = System.currentTimeMillis();
                        fsr4FirstCleanProbeMs = 0;
                        fsr4WorldCalmStreak = 0;
                        // Drop stale temporal history: on resume the SDK must
                        // rebuild history from scratch (reset=true), not
                        // reproject against a prevViewProj/frameIndex frozen
                        // mid-suspension — that would ghost/smear for a frame
                        // after every saturation episode.
                        hasPrevFrame = false;
                        fsr2FrameCount = 0;
                    }
                    fsr4FenceTimedOutPrevFrame = true;
                    // Prev-color snapshot stale (returned before the copy).
                    fsr4PrevColorFresh = false;
                    sceneUpscaled = true;
                    logSlow("onSceneRendered (FSR4 skip)", System.nanoTime() - t0Scene);
                    GL43C.glDeleteSync(sync);
                    return;
                }
            } else {
                fsr4ConsecutiveSkips = 0;
                fsr4FenceTimedOutPrevFrame = false;  // fence succeeded — full path OK
            }
            GL43C.glDeleteSync(sync);
            // Queue-depth instrument (2026-08-29, "FSR4 output isn't at its full
            // potential"): the fence wait IS the GPU queue depth for this frame,
            // and it is the number the adaptive cap has to track. autoEmaFrameMs
            // is a CPU-side inter-frame time and can sit far BELOW the real queue
            // depth when GPU-bound, so a cap derived from it alone is
            // systematically too small. Log both, throttled, to size the fix.
            fsr4LastFenceWaitMs = fenceWaitMs;
            if (System.currentTimeMillis() - lastBlitAuditLogMs >= 250L) {
                lastBlitAuditLogMs = System.currentTimeMillis();
                FSRMod.LOGGER.info("[FSR4-SYNC] fenceWait={}ms cap={}ms frameEma={}ms worldMs={}ms timedOut={} consecutiveSkips={}",
                    String.format(java.util.Locale.ROOT, "%.1f", fenceWaitMs),
                    String.format(java.util.Locale.ROOT, "%.1f", fenceTimeoutNs / 1_000_000.0f),
                    String.format(java.util.Locale.ROOT, "%.1f", autoEmaFrameMs),
                    String.format(java.util.Locale.ROOT, "%.1f",
                        (System.nanoTime() - t0BeginEnd) / 1_000_000.0f),
                    fsr4FenceTimedOutPrevFrame, fsr4ConsecutiveSkips);
            }

            // BLACK-INPUT SANITY CHECK (2026-08-18): the fence above guarantees the
            // scene-color copy into the shared texture has executed, so a throttled
            // readback can tell whether the cross-API input path is alive. On failure
            // it hard-disables FSR4 (FSR1 next frame) — never present a frozen/black
            // frame while the game runs fine behind it.
            if (++fsr4SharedCheckCounter % FSR4_SHARED_CHECK_INTERVAL == 0
                    && checkFsr4SharedInput(renderTex)) {
                // Cross-API input broken — this frame keeps the last presented
                // output (stale but not black); FSR1 takes over from next frame.
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR4 black-input disable)", System.nanoTime() - t0Scene);
                return;
            }

            // Dispatch FSR4 via D3D12 with (poolPtr, index) pairs.
            // JITTER PASS-THROUGH (2026-08-30 "fsr 4 edges are jagged when
            // diagonal" fix — restored after the (0,0) band-aid): the FSR4 SDK
            // MUST receive the current frame's render jitter to align its
            // history reprojection AND to reconstruct sub-pixel detail from the
            // well-separated Halton samples. Passing (0,0) while the render
            // jitters ±0.5 render px makes the SDK assume every frame is on the
            // same grid: its history reads are off by the per-frame jitter
            // delta (up to ~1 render px = up to 4 display px at 25% scale) —
            // beyond the neighborhood clamp, so history is rejected and the
            // output degrades toward a spatial upscale = jagged diagonals. The
            // pool holds the CURRENT frame's color (copied above), so the
            // current (jitterX, jitterY) is the matching offset. The Y SIGN is
            // fixed at the native boundary (fsr4_context.cpp negates
            // jitterOffset.y — DirectX NDC is +Y down, our OpenGL render is
            // +Y up); with that in place the raw values are correct. MV_GEN_CS
            // de-jitters with clean camera matrices, so MVs stay true-motion
            // (the context is created WITHOUT
            // FFX_UPSCALE_ENABLE_MOTION_VECTORS_JITTER_CANCELLATION).
            boolean dispatched = Fsr4Native.dispatchFsr4(
                jitterX, jitterY,     // current frame's render jitter (native negates Y for the DX convention)
                cameraFar, cameraNear, cameraFovY,
	                deltaTime, frameIndex, reset,
                sharedTexMgr.getColorAllocPtr(), 0,
                sharedTexMgr.getDepthAllocPtr(), 1,
                sharedTexMgr.getMvAllocPtr(), 2,
                sharedTexMgr.getReactiveAllocPtr(), 3,
                sharedTexMgr.getOutputAllocPtr(), 4
            );

            if (!dispatched) {
                // BYPASS GUARD (2026-08-30, "make FSR3 FG DX12 work with
                // shaders"): when the native FSR4 SDK upscale throws an MSVC C++
                // exception against a live FG ffxCreateContext (Radeon 860M), the
                // native layer arms the bilinear bypass and returns false on the
                // CRASH frame only. The very next dispatch succeeds via bilinear,
                // so pool 4 always holds a valid display-sized upscale for FG to
                // interpolate from. If we treated this single failure like a real
                // D3D12 breakage we would suspend FSR4 and hard-disable to FSR1 —
                // killing FG and defeating the fix. So when the bypass is active
                // we skip suspension, the hard-disable escalation, and the
                // fail-counter; we just hold the previous output this one frame
                // and let the next dispatch (bypass) fill pool 4. Notify once.
                if (Fsr4Native.isBypassActive()) {
                    if (!fsr4BypassNotified) {
                        fsr4BypassNotified = true;
                        FSRMod.LOGGER.warn("FSR4: native SDK upscale incompatible with FG context on this GPU "
                            + "— switched to bilinear upscale; Frame Generation stays ACTIVE");
                    }
                    // BYPASS FAIL-STREAK (2026-08-30, "allocator Reset FAILED
                    // storm → heap corruption 0xC0000374" crash): the very
                    // first bypass failure is the SDK crash frame itself (the
                    // bypass was just armed, its resources don't exist yet) —
                    // lenient by design. But the bilinear bypass dispatch
                    // SUCCEEDS every frame while the D3D12 device is healthy,
                    // so N CONSECUTIVE bypass failures mean the device was
                    // removed/TDR'd (every D3D12 call fails with
                    // E_FAIL/0x887a0005) — no amount of holding the previous
                    // output helps, and the old code spun here forever (the
                    // diag log's repeated allocator Reset E_FAIL + failed pool
                    // recreates) until the process died with heap corruption.
                    // Escalate exactly like the real-dispatch path: suspend +
                    // hard-disable to the D3D12-free FSR1 spatial render.
                    if (++fsr4BypassFailStreak >= FSR4_MAX_CONSECUTIVE_DISPATCH_FAILURES) {
                        fsr4HardDisabled = true;
                        fsr4Suspended = true;
                        fsr4SuspendedAtMs = System.currentTimeMillis();
                        fsr4FirstCleanProbeMs = 0;
                        fsr4WorldCalmStreak = 0;
                        hasPrevFrame = false;  // rebuild temporal history cleanly on resume
                        fsr2FrameCount = 0;
                        FSRMod.LOGGER.warn("FSR4: {} consecutive bypass failures — D3D12 device "
                            + "presumed removed/lost, hard-disabling FSR4 for this session (FSR1)",
                            fsr4BypassFailStreak);
                    }
                    pipelineReady = false;
                    sceneUpscaled = true;   // present current mainRT; next frame's bypass fills pool 4
                    logSlow("onSceneRendered (FSR4 bypass active)", System.nanoTime() - t0Scene);
                    return;
                }
                // CRASH-LOOP DIAGNOSTIC (2026-08-30): unconditional log so we
                // can see in the game log whether the native dispatch-false
                // actually reaches here, and the grace/counter state.
                long _graceAge = fsr4ResumeAtMs != 0 ? System.currentTimeMillis() - fsr4ResumeAtMs : -1;
                FSRMod.LOGGER.warn("FSR4: dispatch returned false — failCount={} inGrace={} graceAge={}ms suspended={}",
                    fsr4DispatchFailCount, (fsr4ResumeAtMs != 0 && _graceAge < FSR4_RESUME_GRACE_MS), _graceAge, fsr4Suspended);
                // POST-RESUME GRACE (2026-08-25, log "restored 19:58:17 →
                // suspendedFor=7ms" flap): the world-calm resume can re-arm
                // FSR4 while the GPU queue is still busy (CPU-side calm world
                // frames ≠ drained queue), and the very first resumed dispatch
                // can fail fast. This was the ONE suspension site NOT covered
                // by the resume grace (the pre-check and fence-timeout paths
                // both have it) — a resume into a busy queue re-suspended
                // within milliseconds every ~3-6s. Within the grace the
                // failure does NOT suspend: the frame keeps the previous FSR4
                // output (stale but valid), the dispatch-failure backoff below
                // still applies, and the hard-disable escalation still fires
                // on a genuinely broken D3D12 path.
                boolean dispInResumeGrace = fsr4ResumeAtMs != 0
                    && System.currentTimeMillis() - fsr4ResumeAtMs < FSR4_RESUME_GRACE_MS;
                if (!dispInResumeGrace) {
                    fsr4RecordSuspension();
                    fsr4Suspended = true;
                    fsr4SuspendedAtMs = System.currentTimeMillis();
                    fsr4FirstCleanProbeMs = 0;
                    fsr4WorldCalmStreak = 0;
                    hasPrevFrame = false;  // rebuild temporal history cleanly on resume
                    fsr2FrameCount = 0;
                }
                lastFsr4DispatchFail = System.currentTimeMillis();
                // Prev-color snapshot is pre-suspension content (different camera
                // position) — stale for T&C. Cleared here so the resume frame's
                // compare is gated off until a fresh snapshot is taken.
                fsr4PrevColorFresh = false;
                // Escalate persistent failures (incl. SEH-caught native crashes)
                // to a session hard-disable — otherwise the 2s backoff probe
                // re-arms a genuinely broken D3D12 path every 2s forever.
                if (++fsr4DispatchFailCount >= FSR4_MAX_CONSECUTIVE_DISPATCH_FAILURES) {
                    fsr4HardDisabled = true;
                    FSRMod.LOGGER.warn("FSR4: {} consecutive dispatch failures — D3D12 path presumed broken, disabling FSR4 for this session (FSR1)", fsr4DispatchFailCount);
                }
                pipelineReady = false;
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR4 fail)", System.nanoTime() - t0Scene);
                return;
            }

            // Recovered — reset the consecutive-failure counters so a single
            // hiccup (followed by many good frames) can never accumulate.
            fsr4DispatchFailCount = 0;
            fsr4BypassFailStreak = 0;

            if (shouldDebug() && (timingFrameCount & 127) == 0) {
                FSRMod.LOGGER.info("[PERF] FSR4 pipeline: total={}ms fenceWait={}ms render={}x{} display={}x{} scale={}",
                    (System.nanoTime() - tFsr4Start) / 1_000_000.0f,
                    fenceWaitMs, scaledWidth, scaledHeight, displayWidth, displayHeight,
                    (float) scaledWidth / displayWidth);
            }

            if (asyncDrained) {
                // ASYNC FSR4 OVERLAP: the previous frame's output is already in
                // mainRT (copied when we drained at the top of this block), so the
                // GUI paints on it and it is presented THIS frame. Defer the wait
                // + output copy for the CURRENT dispatch to the start of the NEXT
                // frame — the 3-6ms of FSR4 GPU work runs concurrently with the
                // present + next scene render instead of stalling the CPU here.
                // Tradeoff: the presented scene is the previous frame's (~1 frame
                // input latency); pixels are identical, just one frame behind.
                fsr4AsyncPending = true;
            } else {
                // First frame (no previous dispatch to drain) and FG mode (needs
                // the same-frame output for HUDLessColor/presentColor) stay fully
                // synchronous: wait for the D3D12 work, then copy to mainRT.
                //
                // FG STALL WATCHDOG (2026-08-29, "FSR3 FG DX12 freezes with a
                // shader pack"): the native Fsr4Context::finish() waits on the
                // D3D12 fence with a 5000ms timeout, so if the D3D12 work never
                // completes the render thread blocks for 5 SECONDS every frame.
                // To the user that is a hard freeze while the server keeps
                // ticking — exactly the reported "it froze while the game ran
                // fine in the background". Time the wait and escalate through
                // the existing D3D12 runtime-failure path: FG is switched off
                // for the session (see fgActive) and the version degrades to
                // plain FSR4 upscaling instead of freezing.
                long tWaitIdleStart = System.nanoTime();
                Fsr4Native.waitIdle();
                float waitIdleMs = (System.nanoTime() - tWaitIdleStart) / 1_000_000.0f;
                if (fgActive() && waitIdleMs >= FG_STALL_MS) {
                    fgStallConsecutive++;
                    FSRMod.LOGGER.warn("[FG_DX12] D3D12 waitIdle stalled {}ms ({} consecutive, limit {}) irisSwap={} fg4Init={}",
                        String.format(java.util.Locale.ROOT, "%.0f", waitIdleMs),
                        fgStallConsecutive, FG_STALL_LIMIT, irisSwapActive, fg4Initialized);
                    if (fgStallConsecutive >= FG_STALL_LIMIT) {
                        noteD3D12RuntimeFailure(String.format(java.util.Locale.ROOT,
                            "D3D12 fence stalled %.0fms on %d consecutive frames", waitIdleMs, fgStallConsecutive));
                    }
                } else if (waitIdleMs < FG_STALL_MS) {
                    fgStallConsecutive = 0;
                }

                // FSR3_FG_DX12: interpolate an in-between frame from the FSR4 upscaled
                // output (pool 4). DOUBLE-PRESENT: the generated FG frame is presented
                // first by the vanilla blitToScreen+swapBuffers, then onAfterSwapBuffers()
                // presents the real frame as a SECOND image via a second blit+swap —
                // doubling display FPS (up to monitor refresh).
            //
            // The actual prepare+generate is DEFERRED to presentDirect() (the
            // blitToScreen HEAD hook) because the FG SDK needs presentColor = the
            // real frame WITH the GUI composited (pool 6) and HUDLessColor = the
            // FSR4 output WITHOUT it (pool 4) — the SDK diffs the two to extract
            // the HUD and re-composites it onto generated frames, so BOTH presents
            // carry the UI instead of only the FG one. Here we stash the prepare
            // params and leave the real FSR4 frame in mainRT for the GUI to paint on.
            if (fgActive() && fg4Initialized) {
                // Camera basis vectors for the FG SDK (world-space). The FSR4
                // path already computed cameraNear/Far/FovY above.
                Camera fgCam = client.gameRenderer.getMainCamera();
                Vec3 fgPos = fgCam.position();
                tmpQuat.set(fgCam.rotation());
                // camera.rotation() is the CAMERA->WORLD rotation (the view matrix
                // uses its conjugate — see Camera.getViewRotationMatrix). The camera
                // looks down local -Z, so world forward = q * (0,0,-1). Using +Z here
                // passed a BACKWARD forward vector to the FG SDK, giving a left-handed
                // (right, up, forward) basis → the SDK's rotation decomposition was
                // inverted → the generated frame warped the wrong way during camera
                // rotation → terrain smearing in FG mode.
                org.joml.Vector3f fwd = new org.joml.Vector3f(0.0f, 0.0f, -1.0f).rotate(tmpQuat).normalize();
                org.joml.Vector3f up  = new org.joml.Vector3f(0.0f, 1.0f, 0.0f).rotate(tmpQuat).normalize();
                org.joml.Vector3f right = new org.joml.Vector3f(1.0f, 0.0f, 0.0f).rotate(tmpQuat).normalize();

                fgFrameID = fsr2FrameCount;
                // FRAME-TIME-DELTA FIX (2026-08-12, "FG choppy on both paths"):
                // fgDeltaNs was System.nanoTime() - t0Frame — the INTRA-frame
                // elapsed time (frame start -> this stash, ~1-8ms of render
                // time), not the INTER-frame delta (~16.6ms at 60fps). The JNI
                // divides it by 1e6 into frameTimeDeltaMs, so the FG SDK was
                // told real frames are 2-16x closer together than they are →
                // wrong interpolation phase → judder. This is the same bug
                // class the FSR4/FSR3 dispatch already fixed via
                // lastFrameDeltaMs (2026-08-08); the FG stash was missed. Use
                // the same inter-frame source, converted to ns.
                fgDeltaNs = (long)(lastFrameDeltaMs * 1_000_000.0f);
                fgJitterX = jitterX;
                fgJitterY = jitterY;
                fgCameraNear = cameraNear;
                fgCameraFar = cameraFar;
                fgCameraFovY = cameraFovY;
                fgCamPosX = (float) fgPos.x; fgCamPosY = (float) fgPos.y; fgCamPosZ = (float) fgPos.z;
                fgCamUpX = up.x; fgCamUpY = up.y; fgCamUpZ = up.z;
                fgCamRightX = right.x; fgCamRightY = right.y; fgCamRightZ = right.z;
                fgCamForwardX = fwd.x; fgCamForwardY = fwd.y; fgCamForwardZ = fwd.z;
                fgReset = !hasPrevFrame || fsr2FrameCount == 0;
                // GL FG TRANSLATION GATE: dt-normalized eye speed (blocks/sec,
                // FPS-independent — same source as the VKMod vkModFgEyeSpeed
                // signal) from consecutive stash positions.
                if (!fgReset && fgHasLastCam && lastFrameDeltaMs > 0.5f) {
                    float dx = fgCamPosX - fgLastCamX;
                    float dy = fgCamPosY - fgLastCamY;
                    float dz = fgCamPosZ - fgLastCamZ;
                    fgEyeSpeed = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / (lastFrameDeltaMs / 1000.0));
                } else {
                    fgEyeSpeed = 0.0f;
                }
                fgLastCamX = fgCamPosX; fgLastCamY = fgCamPosY; fgLastCamZ = fgCamPosZ;
                fgHasLastCam = true;
                fgGeneratePending = true;

                // Real frame (no GUI yet) goes to mainRT for the HUD to paint on.
                copyFsr4OutputToMainRT();
            } else {
                copyFsr4OutputToMainRT();
            }
            } // end async else (synchronous first-frame / FG path)

            // Prime the async state: after EVERY successful dispatch, the next
            // frame's block start drains it (wait ~0ms — the GPU has had the whole
            // present + next scene render to finish it) and copies its output to
            // mainRT. Frame 0 stays sync (no prior dispatch to drain); from frame 1
            // on, the wait never sits on the critical path.
            if (config.fsr4Async && !isFrameGenDx12()) {
                fsr4AsyncPending = true;
                if (!fsr4AsyncLogged) {
                    fsr4AsyncLogged = true;
                    FSRMod.LOGGER.info("FSR4: async overlap ACTIVE — FSR4 wait hidden behind present+next render (~1 frame latency)");
                }
            }

            // CRITICAL: Save currViewProj into prevViewProj so the next frame's
            // MV_GEN_CS can reproject frame N -> frame N+1. The FSR4 path was
            // missing this (FSR2/3 paths do it at lines 1159/1233/1427/1477) —
            // prevViewProj stayed at identity/its initial value forever, so MV gen
            // produced either zero or garbage motion vectors every frame. With no
            // valid reprojection the SDK cannot find/history-blend the previous
            // frame's output, so it fell back to per-frame spatial upscale (no
            // temporal accumulation — exactly the FSR1-looking output reported).
            prevViewProj.set(currViewProj);
            fsr2FrameCount++;
            hasPrevFrame = true;
            // WARM-UP TRANSITION (2026-08-23, residual blink fix):
            // the first few frames after resume have fresh history (no
            // temporal accumulation) — a hard spatial-to-temporal quality
            // jump reads as a blink. Run FSR4 dispatch silently (SDK
            // accumulates history) while the display stays on FSR1 for
            // FSR4_RESUME_BLEND_FRAMES frames, then switch — invisible.
            if (fsr4ResumeBlendRemaining > 0) {
                fsr4ResumeBlendRemaining--;
                if (shouldDebug()) {
                    FSRMod.LOGGER.info("FSR4: warm-up frame {}/{} (FSR4 accumulating, FSR1 display)",
                        FSR4_RESUME_BLEND_FRAMES - fsr4ResumeBlendRemaining,
                        FSR4_RESUME_BLEND_FRAMES);
                }
                if (renderTex != -1) blitSceneToMain(renderTex);
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR4 warm-up)", System.nanoTime() - t0Scene);
                return;
            }
            // VERSION-SWITCH DIAGNOSTIC: confirm FSR4 dispatch completed and
            // output was copied to mainRT (if async, the copy is deferred).
            if (fsr4PostDirtyDiagFrames <= 60) {
                FSRMod.LOGGER.info("[SWITCH-DIAG] FSR4 dispatch OK — async={} asyncPending={} syncCopyToMainRT={}",
                    config.fsr4Async, fsr4AsyncPending, !config.fsr4Async || asyncDrained);
            }
            sceneUpscaled = true;
            logSlow("onSceneRendered (FSR4 total)", System.nanoTime() - t0Scene);
            return;
            } // end if (!fsr4Suspended)
        }

        // --- GPU-saturation guard (pure-GL paths) ---
        // When the GL queue is deep (chunk streaming: DH LOD→real chunk
        // transitions, Voxy/DH meshing on the same context), the render thread
        // stalls inside the world pass waiting for the GPU, so a world render
        // time far above its running average is a reliable saturation signal.
        // While degraded we run only a single bilinear blit below (no compute
        // dispatches / barriers / extra copies) so the mod stops piling work
        // onto an overloaded queue; the temporal pipelines resume with a clean
        // history rebuild once the storm passes (same policy as the FSR4
        // suspension guard, which already covers the cross-API path above).
        // The FSR4 block never falls through to here (it returns on every
        // path), so this guard only governs FSR1/spatial-fallback and
        // FSR2/FSR3_UPS.
        float worldMs = (System.nanoTime() - t0BeginEnd) / 1_000_000.0f;
        boolean firstSample = satEmaWorldMs <= 0.0f;
        // COLD-START SEED FIX (2026-08-07, "all FSR2 edges jagged (terrain +
        // sky) + still shaking" — ROOT CAUSE): satEmaWorldMs was seeded ONLY by
        // the first CALM frame (the else branch below). On an always-busy
        // machine (iGPU + Sodium/Voxy/Create: world pass > 20ms on essentially
        // every frame, e.g. a fresh-world Voxy meshing storm that never settles)
        // no calm frame ever occurred, so firstSample stayed TRUE forever and
        // EVERY frame re-entered cold-start and counted as a spike →
        // saturationDegraded stuck true → FSR2/FSR3 NEVER dispatched → the
        // whole session rendered blitSceneToMain (the FSR1 bilinear upscale:
        // jagged edges on terrain AND sky) while the scene still carried the
        // temporal Halton jitter (isTemporalUpscaler() is config-based, so
        // onRenderBegin kept jittering the render; the blit has no de-jitter →
        // the ±1.5 display-px per-frame oscillation = the "still shaking").
        // Session-log proof: only "FSR1: Initializing pipeline (saturation
        // blit)" ever logged — zero "FSR2: All 6 shaders compiled" lines.
        // Fix: seed the FIRST sample unconditionally (the same cold-start fix
        // the FSR4 pre-check EMA received 2026-08-06) — a stormy machine
        // settles into a storm-appropriate baseline within a few frames, the
        // guard fires only on genuine ~2x-above-baseline spikes, and FSR2/3
        // resume once the queue has any headroom. Spike detection below
        // unchanged.
        if (firstSample) {
            satEmaWorldMs = worldMs;
        }
        // DIAGNOSTIC-READBACK IMMUNITY (2026-08-29, "every second it blinks"):
        // same reasoning as the FSR4 pre-check above — a probe readback is a
        // ~26ms pipeline flush, not GPU saturation, and must not degrade the
        // FSR2/3 temporal pipelines to the blit path every 2s.
        boolean saturationSpike = firstSample
            ? (worldMs > SAT_SPIKE_MIN_MS && !diagnosticReadbackThisFrame)
            : (worldMs > Math.max(SAT_SPIKE_MULT * satEmaWorldMs, SAT_SPIKE_MIN_MS)
               && !diagnosticReadbackThisFrame);
        if (saturationSpike) {
            satConsecutiveClean = 0;
            if (!saturationDegraded && config.debugMode > 0) {
                FSRMod.LOGGER.warn("[DIAG] GPU saturation (world={}ms ema={}ms) — degrading to bilinear blit until queue drains",
                    (int) worldMs, (int) satEmaWorldMs);
            }
            saturationDegraded = true;
        } else {
            if (firstSample) {
                satEmaWorldMs = worldMs;
            } else {
                satEmaWorldMs += SAT_EMA_ALPHA * (worldMs - satEmaWorldMs);
            }
            if (saturationDegraded && ++satConsecutiveClean >= SAT_EXIT_CLEAN_FRAMES) {
                saturationDegraded = false;
                satConsecutiveClean = 0;
                // Clean temporal-history rebuild on resume: the storm frames
                // changed the scene (LOD→real chunk transition), so reprojecting
                // the stale pre-storm history would ghost/smear for a frame
                // after every episode (same policy as the FSR4 suspension
                // guard). Skipped for FSR3_FG — the guard excludes FG from
                // degradation, so FG's own state must stay untouched.
                if (!isFrameGen()) {
                    hasPrevFrame = false;
                    fsr2FrameCount = 0;
                }
                if (config.debugMode > 0) {
                    FSRMod.LOGGER.info("[DIAG] GPU queue drained — resuming full upscale (history reset)");
                }
            }
        }

        // VERSION-SWITCH DIAGNOSTIC (2026-08-24, "terrain disappears when
        // switching FSR versions" root-cause hunt): log the FSR4 dispatch
        // decision for the first 60 frames after a markDirty (version switch),
        // so the exact reason FSR4 isn't dispatching (and the FSR1 fallback is
        // rendering instead) is visible in the game log. This is the gap
        // between "FSR4: init OK" and a real dispatch — the interstitial frames
        // where terrain disappears.
        if (fsr4PostDirtyDiagFrames < 60) {
            fsr4PostDirtyDiagFrames++;
            String reason = "unknown";
            if (!isFsr4() && !isFrameGenDx12()) {
                reason = "not FSR4/FG_DX12 (version=" + config.version + ")";
            } else if (!fsr4Available) {
                reason = "fsr4Available=false";
            } else if (!fsr4Initialized) {
                reason = "fsr4Initialized=false";
            } else if (sharedTexMgr == null) {
                reason = "sharedTexMgr=null";
            } else if (sharedTexMgr.getColorAllocPtr() == 0) {
                reason = "colorAllocPtr=0 (pool not allocated)";
            } else if (fsr4HardDisabled) {
                reason = "fsr4HardDisabled=true";
            } else if (fsr4ParityNow) {
                reason = "parityBypass active (scale>=90%)";
            } else if (fsr4ContextRenderW != scaledWidth || fsr4ContextRenderH != scaledHeight
                || fsr4ContextDisplayW != displayWidth || fsr4ContextDisplayH != displayHeight) {
                reason = "size mismatch: ctx=" + fsr4ContextRenderW + "x" + fsr4ContextRenderH
                    + "->" + fsr4ContextDisplayW + "x" + fsr4ContextDisplayH
                    + " scene=" + scaledWidth + "x" + scaledHeight + "->" + displayWidth + "x" + displayHeight;
            } else if (fsr4Suspended) {
                reason = "fsr4Suspended=true";
            } else {
                reason = "DISPATCHING (conditions met)";
            }
            FSRMod.LOGGER.info("[SWITCH-DIAG] frame {} post-dirty: {} — fsr4Avail={} init={} suspended={} hardDisabled={} poolPtr={} renderTex={} mainRT={}", 
                fsr4PostDirtyDiagFrames, reason, fsr4Available, fsr4Initialized,
                fsr4Suspended, fsr4HardDisabled,
                sharedTexMgr != null ? sharedTexMgr.getColorAllocPtr() : 0,
                renderTex,
                mainRenderTarget != null ? mainRenderTarget.width + "x" + mainRenderTarget.height : "null");
        }

        // FSR4 suspended (GPU saturated) also routes through the FSR1 spatial
        // path — no GL→D3D12 fence, cannot stall. This is the Tier-2 fallback
        // that keeps frames rendering smoothly while moving quickly.
        if (spatialFallbackActive()) {
            // POST-SWITCH TERRAIN PROTECTION (2026-08-24, screenshots confirmed):
            // during the first ~8 frames after a version switch (markDirty), the
            // Iris shader pipeline is re-compiling/re-patching for the new proxy
            // framebuffer. The sky pass (composite/final) is active but the
            // gbuffers programs (terrain rendering) are not yet linked — the
            // proxy composite contains sky + fog but NO terrain chunks. The FSR1
            // fallback reads this incomplete composite and displays the
            // "terrain disappears" glitch. Fix: skip the FSR1 blit for the first
            // few frames after a switch and let the PREVIOUS mainRT content
            // display (the last good frame from before the switch). The vanilla
            // mainRT retains its previous content after the swap-back in
            // onSceneRendered, so the frozen frame is a valid, complete scene.
            if (fsr4PostDirtyDiagFrames <= 8 && renderTex != -1) {
                if (fsr4PostDirtyDiagFrames == 1) {
                    FSRMod.LOGGER.info("[SWITCH-DIAG] FSR1 fallback: skipping blit for {} frames (Iris pipeline recompile)", 8);
                }
                sceneUpscaled = true;
                return;
            }
            if (renderTex == -1) {
                if (fsr4PostDirtyDiagFrames <= 60) {
                    FSRMod.LOGGER.warn("[SWITCH-DIAG] FSR1 fallback: renderTex==-1 (renderTarget destroyed?!) — NO OUTPUT");
                }
                return;
            }
            // Terrain probe + broken-composite vote now run from the SHARED site
            // above (before the FSR4/FSR2/FSR3 branches) so every swap-active
            // path is covered — see the comment there. The FSR1 fallback only
            // needs the post-blit MAIN comparison probe below (probedThisFrame
            // is set by the shared site).
            if (fsr4PostDirtyDiagFrames <= 60) {
                int mainTex = mainRenderTarget != null
                    ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : 0;
                FSRMod.LOGGER.info("[SWITCH-DIAG] FSR1 fallback dispatching — renderTex={} mainTex={} spatialFallbackReason={} satDegraded={}",
                    renderTex, mainTex,
                    isFsr4() && !fsr4Initialized ? "FSR4_NOT_INITIALIZED"
                    : fsr4Suspended ? "FSR4_SUSPENDED"
                    : config.version == FSRConfig.FSRVersion.FSR1 ? "FSR1_SELECTED"
                    : fsr4ParityNow ? "PARITY_BYPASS" : "OTHER",
                    saturationDegraded);
            }
            if (config.debugMode > 0 && timingFrameCount % 60 == 1) {
                long elapsed = System.nanoTime() - t0Frame;
                FSRMod.LOGGER.info("[PERF] Frame time breakdown: total={}ms scene={}x{} render={}x{} scale={}",
                    elapsed / 1_000_000.0f, displayWidth, displayHeight, scaledWidth, scaledHeight,
                    (float)scaledWidth / displayWidth);
            }
            if (fsr1Pipeline == null) {
                FSRMod.LOGGER.info("FSR1: Initializing pipeline");
                fsr1Pipeline = new FSR1Pipeline();
            }
            int mainTex = mainRenderTarget != null
                ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : 0;
            if (mainTex == 0) return;

            try {
                long t0 = System.nanoTime();
                // GPU-saturation degrade: skip the RCAS/EASU compute and present
                // a plain bilinear blit — the cheapest pass that keeps the image
                // correct without adding work to an overloaded GL queue.
                //
                // POST-JOIN STORM BLIT (2026-08-30 "make the framegen work" —
                // storm-crash stabilization): this Radeon 860M access-violations
                // (0xC0000005, the documented AMD-class crash) when a compute
                // dispatch (EASU/RCAS) lands on a queue already saturated by a
                // world-load chunk storm. The saturation EMA lags the storm on
                // the very first frames, and a native GL fault cannot be caught
                // by Java try/catch — both crashes today fired here while the
                // world was still loading chunks (<20s in) and EASU was dis-
                // patched anyway. Force the pure bilinear blit during the whole
                // post-join storm window so no compute hits the saturated queue;
                // the EASU/RCAS path resumes once the world is established.
                boolean stormWindow = worldJoinedAtMs != 0
                    && System.currentTimeMillis() - worldJoinedAtMs < 20000L;
                boolean ok = (saturationDegraded || stormWindow)
                    ? blitSceneToMain(renderTex)
                    : fsr1Pipeline.dispatch(renderTex, scaledWidth, scaledHeight,
                        displayWidth, displayHeight, config.sharpness, mainTex, config.fsr1Easu);
                logSlow("FSR1 dispatch", System.nanoTime() - t0);
                if (!ok) {
                    FSRMod.LOGGER.warn("FSR1 dispatch returned false");
                    return;
                }
                fsr2OutputTexId = 0;
                // POST-BLIT PROBE: only on frames that also probed the proxy,
                // so proxy vs displayed output stay comparable (and we don't
                // read back every frame).
                if (probedThisFrame) {
                    probeTerrainBlocks("MAIN", mainTex, displayWidth, displayHeight);
                }
            } catch (Exception e) {
                FSRMod.LOGGER.error("FSR1 dispatch threw exception", e);
                return;
            }
            sceneUpscaled = true;
            logSlow("onSceneRendered (FSR1 total)", System.nanoTime() - t0Scene);
            return;
        }

        if (isFsr2()) {
            // GPU-saturation degrade (chunk streaming / LOD→real transitions):
            // skip the temporal pipeline and present a plain bilinear upscale.
            // prevViewProj/hasPrevFrame stay pre-storm (not advanced); the guard
            // resets them when the queue drains, giving a clean history rebuild.
            if (saturationDegraded) {
                blitSceneToMain(renderTex);
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR2 saturation blit)", System.nanoTime() - t0Scene);
                return;
            }
            // TEST: skip shared memory for FSR2/FSR3 to match reference JAR behavior
            // if (sharedMemoryActive && sharedTexMgr != null && sharedTexMgr.isValid()) {
            //     sharedTexMgr.attachToPipeline(fsr2Pipeline);
            // }
            if (!pipelineReady || fsr2Pipeline == null || renderTex == -1 || depthTex == -1) return;

            if (timingFrameCount % 60 == 1) {
                // Compute view-space Z params (same as MV_GEN_CS does for FSR4)
                float p22 = lastProj.m22();
                float p23 = lastProj.m23();
                float farDepth = (p22 > 0) ? 0.0f : 1.0f;
                float d0 = (1.0f - p22) * 0.5f;
                float d1 = -p23 * 0.5f;
                float farViewZ = d1 / (farDepth - d0 + 1e-10f);
                FSRMod.LOGGER.info(String.format(
                    "[DIAG_FSR2] frameIdx=%d jitter=(%.3f,%.3f) prev=(%.3f,%.3f) hasPrev=%s camReactive=%.3f camStillExact=%d"
                    + " proj22=%.4f proj23=%.4f farDepth=%.0f farViewZ=%.1f dtod=(%.4f,%.4f)"
                    + " render=%dx%d display=%dx%d",
                    fsr2FrameCount, jitterX, jitterY, prevJitterX, prevJitterY, hasPrevFrame, fsr23CamReactive, (fsr23CamStillExact ? 1 : 0),
                    p22, p23, farDepth, farViewZ, d0, d1,
                    scaledWidth, scaledHeight, displayWidth, displayHeight));
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
                .rotation(tmpQuat)
                .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
	            currViewProj.set(lastProjJittered);  // Use JITTERED projection for MV generation
	            currViewProj.mul(tmpView);

	            // Camera delta for motion vector reprojection (world-space position change)
	            float cameraDeltaX = hasPrevFrame ? (float)(camPos.x - prevCamPosX) : 0.0f;
	            float cameraDeltaY = hasPrevFrame ? (float)(camPos.y - prevCamPosY) : 0.0f;
	            float cameraDeltaZ = hasPrevFrame ? (float)(camPos.z - prevCamPosZ) : 0.0f;
	            prevCamPosX = camPos.x;
	            prevCamPosY = camPos.y;
	            prevCamPosZ = camPos.z;

	            try {
		                long t0 = System.nanoTime();
		                fsr2Pipeline.setDebugMode(config.debugMode);
		                probeFsr4Depth(depthTex);
		                fsr2Pipeline.setUseDepth(fsr4RealDepthAvailable);
		                fsr2Pipeline.setCamStill(fsr23CamStill);
		                fsr2Pipeline.setCamReactive(fsr23CamReactive);
		                fsr2Pipeline.setCamStillExact(fsr23CamStillExact);
		                boolean success = fsr2Pipeline.dispatch(
	                    renderTex, depthTex,
	                    jitterX, jitterY, prevJitterX, prevJitterY,
	                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
	                    1.0f / scaledWidth, 1.0f / scaledHeight,
	                    lastProj, fsr2FrameCount,
	                    config.sharpness,
	                    hasPrevFrame ? prevViewProj : null,
	                    currViewProj,
	                    cameraDeltaX, cameraDeltaY, cameraDeltaZ
	                );
                if (success) {
                    fsr2OutputTexId = fsr2Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                    // OUTPROBE is a diagnostics burst from the 2026-08-08 standing-still
                    // shake investigation (now resolved — the box-anchor fix proved the
                    // presented image is bit-stable). Gated on debugMode: each sample is a
                    // full glMemoryBarrier + 4 texture readbacks (render-thread stall), and
                    // the 45-sample burst should never run in a production session.
                    if (config.debugMode > 0 && fsr2FrameCount == 300 && fsr23OutputProbeFrames == -1) fsr23OutputProbeFrames = 90;
                    if (fsr23OutputProbeFrames > 0) {
                        // BURST-FIX (2026-08-08, 18:18 session took only 1 sample): the decrement
                        // used to sit INSIDE the parity check, so 90->89 left the counter stuck at
                        // an odd value forever ((89 & 1) == 0 is never true) — the burst ended after
                        // one read. Decrement every frame; read on even values = ~45 samples over
                        // ~90 frames (~3-6 Halton cycles), enough to see jitter-period oscillation.
                        fsr23OutputProbeFrames--;
                        if ((fsr23OutputProbeFrames & 1) == 0) {
                        try {
                            if (fsr2OutputTexId != 0) {
                                int w = displayWidth, h = displayHeight;
                                // Hand fist sits ~0.65 screen height; 0.85 is the ground/hotbar reference.
                                // OUTPUT-READBACK FIX (2026-08-08, 17:45 session showed all reads as
                                // 0 / -2147483648): (1) the output texture was written by compute
                                // (imageStore) — reading it without a memory barrier returns
                                // stale/undefined data; GL_ALL_BARRIER_BITS covers both the
                                // glGetTextureSubImage and the FBO glReadPixels paths. (2) readTexPixels
                                // float-decodes its buffer, so GL_UNSIGNED_BYTE data reinterprets the
                                // packed RGBA word as a float. Read as GL_FLOAT (pixel transfer
                                // converts the RGBA8 texture to 0..1 floats) and scale by 255 for the
                                // byte display. (3) null-check: a failed readback logs READBACK FAILED
                                // instead of printing garbage. (4) once-only arming guard so counter
                                // resets can't re-trigger the burst.
                                GL43C.glMemoryBarrier(GL43C.GL_ALL_BARRIER_BITS);
                                float[] hand = readTexPixels(fsr2OutputTexId, w / 2, (int)(h * 0.65f), GL43C.GL_RGBA, GL43C.GL_FLOAT, 4);
                                float[] low  = readTexPixels(fsr2OutputTexId, w / 2, (int)(h * 0.85f), GL43C.GL_RGBA, GL43C.GL_FLOAT, 4);
                                float[] ctr  = readTexPixels(fsr2OutputTexId, w / 2, h / 2, GL43C.GL_RGBA, GL43C.GL_FLOAT, 4);
                                float[] sky  = readTexPixels(fsr2OutputTexId, w / 2, h / 8, GL43C.GL_RGBA, GL43C.GL_FLOAT, 4);
                                if (hand == null || low == null || ctr == null || sky == null) {
                                    FSRMod.LOGGER.info(String.format(
                                        "[OUTPROBE] f=%d jit=(%.3f,%.3f) READBACK FAILED",
                                        fsr2FrameCount, jitterX, jitterY));
                                } else {
                                    FSRMod.LOGGER.info(String.format(
                                        "[OUTPROBE] f=%d jit=(%.3f,%.3f) hand=(%d,%d,%d) low=(%d,%d,%d) ctr=(%d,%d,%d) sky=(%d,%d,%d)",
                                        fsr2FrameCount, jitterX, jitterY,
                                        (int) (hand[0] * 255f), (int) (hand[1] * 255f), (int) (hand[2] * 255f),
                                        (int) (low[0] * 255f), (int) (low[1] * 255f), (int) (low[2] * 255f),
                                        (int) (ctr[0] * 255f), (int) (ctr[1] * 255f), (int) (ctr[2] * 255f),
                                        (int) (sky[0] * 255f), (int) (sky[1] * 255f), (int) (sky[2] * 255f)));
                                }
                            }
                        } catch (Exception probeEx) {
                            FSRMod.LOGGER.warn("[OUTPROBE] failed: {}", probeEx.toString());
                        }
                        } // inner parity if
                    } // outer probe-frame if
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                    sceneUpscaled = true;
                    return;
                }
                logSlow("FSR2 dispatch", System.nanoTime() - t0);
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
                sceneUpscaled = true;
                return;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        if (isFsr3()) {
            // GPU-saturation degrade — same as FSR2. Excludes FSR3_FG (GLSL frame
            // gen): its alternation state machine is left alone during storms and
            // the FG_DX12 cross-API path has its own fence-based guard.
            if (saturationDegraded && !isFrameGen()) {
                blitSceneToMain(renderTex);
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR3 saturation blit)", System.nanoTime() - t0Scene);
                return;
            }
            // TEST: skip shared memory for FSR2/FSR3 to match reference JAR behavior
            // if (sharedMemoryActive && sharedTexMgr != null && sharedTexMgr.isValid()) {
            //     sharedTexMgr.attachToPipeline(fsr3Pipeline);
            // }
            if (!pipelineReady || fsr3Pipeline == null || renderTex == -1 || depthTex == -1) return;

            if (timingFrameCount % 60 == 1) {
                float p22 = lastProj.m22();
                float p23 = lastProj.m23();
                float farDepth = (p22 > 0) ? 0.0f : 1.0f;
                float d0 = (1.0f - p22) * 0.5f;
                float d1 = -p23 * 0.5f;
                float farViewZ = d1 / (farDepth - d0 + 1e-10f);
                FSRMod.LOGGER.info(String.format(
                    "[DIAG_FSR3] frameIdx=%d jitter=(%.3f,%.3f) prev=(%.3f,%.3f) hasPrev=%s camReactive=%.3f camStillExact=%d"
                    + " proj22=%.4f proj23=%.4f farDepth=%.0f farViewZ=%.1f dtod=(%.4f,%.4f)"
                    + " render=%dx%d display=%dx%d",
                    fsr2FrameCount, jitterX, jitterY, prevJitterX, prevJitterY, hasPrevFrame, fsr23CamReactive, (fsr23CamStillExact ? 1 : 0),
                    p22, p23, farDepth, farViewZ, d0, d1,
                    scaledWidth, scaledHeight, displayWidth, displayHeight));
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
            .rotation(tmpQuat)
            .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProjJittered);  // Use JITTERED projection for MV generation
            currViewProj.mul(tmpView);

            // Camera delta for motion vector reprojection
            float cameraDeltaX = hasPrevFrame ? (float)(camPos.x - prevCamPosX) : 0.0f;
            float cameraDeltaY = hasPrevFrame ? (float)(camPos.y - prevCamPosY) : 0.0f;
            float cameraDeltaZ = hasPrevFrame ? (float)(camPos.z - prevCamPosZ) : 0.0f;
            prevCamPosX = camPos.x;
            prevCamPosY = camPos.y;
            prevCamPosZ = camPos.z;


            try {
            long t0 = System.nanoTime();
            fsr3Pipeline.setDebugMode(config.debugMode);
            probeFsr4Depth(depthTex);
            fsr3Pipeline.setUseDepth(fsr4RealDepthAvailable);
            fsr3Pipeline.setCamStill(fsr23CamStill);
            fsr3Pipeline.setCamReactive(fsr23CamReactive);
            fsr3Pipeline.setCamStillExact(fsr23CamStillExact);
            boolean success = fsr3Pipeline.dispatch(
	                    renderTex, depthTex,
	                    jitterX, jitterY, prevJitterX, prevJitterY,
	                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
	                    1.0f / scaledWidth, 1.0f / scaledHeight,
	                    lastProj, fsr2FrameCount,
	                    config.sharpness,
	                    hasPrevFrame ? prevViewProj : null,
	                    currViewProj,
	                    cameraDeltaX, cameraDeltaY, cameraDeltaZ
	                );
                if (success) {
                    fsr2OutputTexId = fsr3Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                    sceneUpscaled = true;
                    return;
                }
                logSlow("FSR3 dispatch", System.nanoTime() - t0);
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
                sceneUpscaled = true;
                return;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        // Copy upscaled output to mainRenderTarget
        if (sharedMemoryActive) {
            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        } else if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
            long t0 = System.nanoTime();
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
            int displayW = displayWidth;
            int displayH = displayHeight;

            // FG: alternate between real frame and interpolated frame
            // On FG frames, dispatch FG and copy interpolated output to mainRT
            // so GUI renders on top of FG content (fixes UI flickering).
            // On real frames, copy the real FSR output.
            if (config.debugMode > 0 && isFrameGen() && timingFrameCount % 60 == 0) {
                FSRMod.LOGGER.info("[DIAG_FG] flipState={} hasPrev={} sharedMem={} frameIdx={} outputTex={}",
                    fgFlipState,
                    fsr3Pipeline != null ? fsr3Pipeline.hasPreviousFrame() : false,
                    sharedMemoryActive,
                    fsr2FrameCount,
                    fsr2OutputTexId);
            }
            if (isFrameGen() && fsr3Pipeline != null && fgFlipState && fsr3Pipeline.hasPreviousFrame()) {
                float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
                fsr3Pipeline.setFgJitter(jitterX, jitterY);
                fsr3Pipeline.setFgTanHalfFovY(tanHalfFovY);

                // Fixed midpoint blend — the FG output represents the frame
                // halfway between the two real frames in the alternating scheme.
                // Dynamic timing-based weight pinned to ~1.0 (lastRealFrameTimeNs
                // was updated every frame), which made FG frames output the
                // current frame unchanged — causing strobing/choppiness.
                fsr3Pipeline.setFgBlendWeight(0.5f);

                long t0Fg = System.nanoTime();
                fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId);
                logSlow("FG dispatch", System.nanoTime() - t0Fg);

                GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                int fgTex = fsr3Pipeline.getFgOutputTexture();
                if (fgTex != 0) {
                    GL43C.glCopyImageSubData(
                        fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                        mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                        displayW, displayH, 1
                    );
                }
            } else {
                GL43C.glCopyImageSubData(
                    fsr2OutputTexId, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    displayW, displayH, 1
                );
            }
            logSlow("glCopyImageSubData (to mainRT)", System.nanoTime() - t0);

            // Always save prev frame data for next FG cycle
            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        }

        // mainRenderTarget is already set — GUI will render to it at full res
        sceneUpscaled = true;
        t0SceneEnd = System.nanoTime();
        logSlow("onSceneRendered (total)", System.nanoTime() - t0Scene);
    }

    public void onRenderEnd() {
        // BLIT AUDIT (2026-08-29): snapshot the upscaled flag before this
        // method clears it — see the lastFrameUpscaled field doc.
        lastFrameUpscaled = sceneUpscaled;
        t0End = System.nanoTime();
        // IRIS COMPOSITE VIEWPORT FIX: ensure Window reports native dims for
        // the GUI regardless of which onSceneRendered path was taken.
        irisSwapActiveStatic = false;
        windowScaleActive = false;
        if (!insideFrame) {
            restoreDefaultTarget();
            return;
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            restoreDefaultTarget();
            return;
        }
        RenderSystem.assertOnRenderThread();

        Minecraft client = Minecraft.getInstance();

        // NOTE: Do NOT call updateProjectionFromCamera() here. See onSceneRendered comment.
        // updateProjectionFromCamera();

        // Iris path: restore mainRT (legacy no-swap mode only). In irisSwapActive
        // mode (2026-08-18) the scene was upscaled in onSceneRendered exactly
        // like the non-Iris GL path — fall through to the sceneUpscaled cleanup
        // below so the GUI/mainRT bookkeeping stays consistent. Uses the frame
        // lock (irisActiveThisFrame) — never re-evaluate the pack state live
        // here (2026-08-19, pack-activation frame tear).
        if (irisActiveThisFrame && !irisSwapActive) {
            if (!sceneUpscaled && mainRenderTarget != null) {
                ((MinecraftClientAccessor) Minecraft.getInstance()).setMainRenderTarget(mainRenderTarget);
            }
            insideFrame = false;
            sceneUpscaled = false;
            return;
        }

        // Scene was already upscaled (and FG warped if active) in onSceneRendered(),
        // and GUI rendered on top at full resolution. Just clean up — Minecraft's
        // normal flipFrame handles presentation.
        if (sceneUpscaled) {
            long frameMs = (System.nanoTime() - t0Frame) / 1_000_000;
            if (config.debugMode > 0 && frameMs >= 8) {
                FSRMod.LOGGER.warn("[TIMING] frame #{} total={}ms (gui+present={}ms)",
                    timingFrameCount, frameMs,
                    (System.nanoTime() - t0SceneEnd) / 1_000_000);
            }
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            insideFrame = false;
            sceneUpscaled = false;
            return;
        }

        // Safety net for the "screens enabled" change: if the world never
        // rendered this frame (dimension-change transition with level briefly
        // non-null but renderLevel not firing), do NOT run the fallback upscale
        // on stale content — just restore the default target.
        if (!worldRenderedThisFrame) {
            restoreDefaultTarget();
            return;
        }

        if (renderTarget == null) {
            restoreDefaultTarget();
            return;
        }

        int renderTex = ((GlTexture)renderTarget.getColorTexture()).glId();
        int depthTex = renderTarget.getDepthTexture() != null
            ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

        ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);

        fsr2OutputTexId = 0;

        // FSR1: non-temporal EASU+RCAS pipeline — writes directly to mainRT.
        // Also handles fallback when FSR4 selected but Vulkan init failed.
        if (config.version == FSRConfig.FSRVersion.FSR1 || dhActive
            || (isFsr4() && !fsr4Initialized)) {
            // FALLBACK DIAGNOSTIC (2026-08-20): one-shot log when FSR4 was
            // SELECTED but this frame is being handled by the FSR1 spatial
            // pipeline — the "FSR4 not working" case. Distinguishes it from a
            // real dispatch ([FSR4-PATH]) and from the parity bypass (its own
            // chat notification). Reasons: init still in flight/deferred,
            // suspended by the GPU-saturation guard, hard-disabled, or pool
            // alloc failed (getColorAllocPtr() == 0).
            if (isFsr4() && !fsr4FallbackPathLogged) {
                fsr4FallbackPathLogged = true;
                FSRMod.LOGGER.warn("[FSR4-PATH] FSR4 selected but running FSR1 spatial — init={} suspended={} hardDisabled={} poolAlloc={} deferFrames={}",
                    fsr4Initialized, fsr4Suspended, fsr4HardDisabled,
                    (sharedTexMgr != null && sharedTexMgr.getColorAllocPtr() != 0),
                    fsr4InitDeferFrames);
            }
            if (renderTex == -1) {
                restoreDefaultTarget();
                insideFrame = false;
                return;
            }
            if (fsr1Pipeline == null) {
                FSRMod.LOGGER.info("FSR1: Initializing pipeline");
                fsr1Pipeline = new FSR1Pipeline();
                fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
            }
            int mainTex = mainRenderTarget != null
                ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : 0;
            if (mainTex == 0) {
                restoreDefaultTarget();
                insideFrame = false;
                return;
            }
            try {
                // POST-JOIN STORM BLIT (same 20s grace as the spatial-fallback
                // FSR1 site above): never dispatch the EASU/RCAS compute on a
                // queue saturated by a world-load chunk storm — that is the
                // documented 0xC0000005 on this iGPU.
                boolean stormWindow = worldJoinedAtMs != 0
                    && System.currentTimeMillis() - worldJoinedAtMs < 20000L;
                boolean ok = stormWindow
                    ? blitSceneToMain(renderTex)
                    : fsr1Pipeline.dispatch(renderTex, scaledWidth, scaledHeight, displayWidth, displayHeight, config.sharpness, mainTex, config.fsr1Easu);
                if (!ok) {
                    FSRMod.LOGGER.warn("FSR1 dispatch returned false - falling back, restoring default target");
                    restoreDefaultTarget();
                    insideFrame = false;
                    return;
                }
                // FSR1 output is already in mainRT — skip copy below
                fsr2OutputTexId = 0;
            } catch (Exception e) {
                FSRMod.LOGGER.error("FSR1 dispatch threw exception", e);
                restoreDefaultTarget();
                insideFrame = false;
                return;
            }
        }

        if (isFsr2()) {
            if (!pipelineReady || fsr2Pipeline == null || renderTex == -1 || depthTex == -1) {
                insideFrame = false;
                return;
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
            .rotation(tmpQuat)
            .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProjJittered);  // Use JITTERED projection for MV generation
            currViewProj.mul(tmpView);

            // Camera delta for motion vector reprojection
            float cameraDeltaX = hasPrevFrame ? (float)(camPos.x - prevCamPosX) : 0.0f;
            float cameraDeltaY = hasPrevFrame ? (float)(camPos.y - prevCamPosY) : 0.0f;
            float cameraDeltaZ = hasPrevFrame ? (float)(camPos.z - prevCamPosZ) : 0.0f;
            prevCamPosX = camPos.x;
            prevCamPosY = camPos.y;
            prevCamPosZ = camPos.z;

            try {
            probeFsr4Depth(depthTex);
            fsr2Pipeline.setUseDepth(fsr4RealDepthAvailable);
            fsr2Pipeline.setCamStill(fsr23CamStill);
            fsr2Pipeline.setCamReactive(fsr23CamReactive);
            fsr2Pipeline.setCamStillExact(fsr23CamStillExact);
            boolean success = fsr2Pipeline.dispatch(
	                    renderTex, depthTex,
	                    jitterX, jitterY, prevJitterX, prevJitterY,
	                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
	                    1.0f / scaledWidth, 1.0f / scaledHeight,
	                    lastProj, fsr2FrameCount,
	                    config.sharpness,
	                    hasPrevFrame ? prevViewProj : null,
	                    currViewProj,
	                    cameraDeltaX, cameraDeltaY, cameraDeltaZ
	                );
                if (success) {
                    fsr2OutputTexId = fsr2Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                }
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        if (isFsr3()) {
            if (!pipelineReady || fsr3Pipeline == null || renderTex == -1 || depthTex == -1) {
                insideFrame = false;
                return;
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
            .rotation(tmpQuat)
            .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProjJittered);  // Use JITTERED projection for MV generation
            currViewProj.mul(tmpView);

            // Camera delta for motion vector reprojection
            float cameraDeltaX = hasPrevFrame ? (float)(camPos.x - prevCamPosX) : 0.0f;
            float cameraDeltaY = hasPrevFrame ? (float)(camPos.y - prevCamPosY) : 0.0f;
            float cameraDeltaZ = hasPrevFrame ? (float)(camPos.z - prevCamPosZ) : 0.0f;
            prevCamPosX = camPos.x;
            prevCamPosY = camPos.y;
            prevCamPosZ = camPos.z;


            try {
            probeFsr4Depth(depthTex);
            fsr3Pipeline.setUseDepth(fsr4RealDepthAvailable);
            fsr3Pipeline.setCamStill(fsr23CamStill);
            fsr3Pipeline.setCamReactive(fsr23CamReactive);
            fsr3Pipeline.setCamStillExact(fsr23CamStillExact);
            boolean success = fsr3Pipeline.dispatch(
	                    renderTex, depthTex,
	                    jitterX, jitterY, prevJitterX, prevJitterY,
	                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
	                    1.0f / scaledWidth, 1.0f / scaledHeight,
	                    lastProj, fsr2FrameCount,
	                    config.sharpness,
	                    hasPrevFrame ? prevViewProj : null,
	                    currViewProj,
	                    cameraDeltaX, cameraDeltaY, cameraDeltaZ
	                );
                if (success) {
                    fsr2OutputTexId = fsr3Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                }
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        if (sharedMemoryActive) {
            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        } else if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();

            // FG fallback (onRenderEnd runs when onSceneRendered didn't dispatch)
            if (isFrameGen() && fsr3Pipeline != null && fgFlipState && fsr3Pipeline.hasPreviousFrame()) {
                float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
                fsr3Pipeline.setFgJitter(jitterX, jitterY);
                fsr3Pipeline.setFgTanHalfFovY(tanHalfFovY);
                fsr3Pipeline.setFgBlendWeight(0.5f);
                long t0 = System.nanoTime();
                fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId);
                GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                int fgTex = fsr3Pipeline.getFgOutputTexture();
                if (fgTex != 0) {
                    GL43C.glCopyImageSubData(
                        fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                        mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                        displayWidth, displayHeight, 1
                    );
                }
                logSlow("glCopyImageSubData (FG -> mainRT)", System.nanoTime() - t0);
            } else {
                long t0 = System.nanoTime();
                GL43C.glCopyImageSubData(
                    fsr2OutputTexId, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    displayWidth, displayHeight, 1
                );
                logSlow("glCopyImageSubData (onRenderEnd)", System.nanoTime() - t0);
            }

            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        }

        long frameMs = (System.nanoTime() - t0Frame) / 1_000_000;
        if (config.debugMode > 0 && frameMs >= 8) {
            FSRMod.LOGGER.warn("[TIMING] frame #{} total={}ms (onRenderEnd={}ms)",
                timingFrameCount, frameMs, (System.nanoTime() - t0End) / 1_000_000);
        }

        // Periodic summary: FSR4 status, frame stats (every 120 frames)
        if (config.debugMode > 0 && timingFrameCount % 120 == 0) {
            String fsr4State = fsr4Available
                ? (fsr4Initialized ? "INIT" : "FALLBACK(fsr4FramesSkipped=" + fsr4FramesSkipped + ")")
                : "UNAVAIL";
            FSRMod.LOGGER.info(String.format(
                "[DEBUG_SUMMARY] frame=%d FSR4=%s sceneTime=%.1fms render=%dx%d version=%s jitter=(%.3f,%.3f)"
                + " hasPrev=%s frameIdx=%d",
                timingFrameCount, fsr4State, frameMs / 1.0f,
                scaledWidth, scaledHeight, config.version,
                jitterX, jitterY, hasPrevFrame, fsr2FrameCount));
        }

        insideFrame = false;
    }

    private void copyFsr4OutputToMainRT() {
        if (mainRenderTarget == null || sharedTexMgr == null) return;
        int outputTex = sharedTexMgr.getOutputTexId();
        if (outputTex == 0) return;
        int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
        if (mainTex == 0) return;
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            outputTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1
        );
    }

    /**
     * True when {@code caller} is the render target the vanilla loop presents.
     * Matches either by identity or by the underlying color texture (a window
     * framebuffer wrapper may share mainRT's texture).
     */
    private boolean isMainPresentTarget(RenderTarget caller) {
        if (caller == null || mainRenderTarget == null) return false;
        if (caller == mainRenderTarget) return true;
        try {
            GpuTexture ct = caller.getColorTexture();
            GpuTexture mt = mainRenderTarget.getColorTexture();
            if (ct != null && ct == mt) return true;
            if (ct instanceof GlTexture && mt instanceof GlTexture) {
                int cid = ((GlTexture) ct).glId();
                int mid = ((GlTexture) mt).glId();
                if (cid != 0 && cid == mid) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ========================================================================
    // FSR3_FG_DX12 under VulkanMod (2026-08-15: step 1 single-present -> step 2
    // double-present)
    // ========================================================================

    /**
     * Stashes the FG prepare params for the pooled (previous-frame) scene the
     * VKMod FSR4 dispatch just processed. The FG run fires from
     * presentVkModFgFrame (Renderer.endFrame TAIL hook) after waitForFrameCopy,
     * when pool 4 — the FSR4 output — and pool 6 (the post-GUI swapchain
     * capture on the scaled path) are both fully written. Camera = the
     * PREVIOUS frame's camera (onRenderBegin runs before renderLevel updates
     * it), consistent with lastProj/prevJitter feeding the dispatch.
     */
    private void stashVkModFgParams() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.gameRenderer == null) return;
            Camera fgCam = client.gameRenderer.getMainCamera();
            if (fgCam == null) return;
            Vec3 fgPos = fgCam.position();
            org.joml.Quaternionf q = new org.joml.Quaternionf(fgCam.rotation());
            org.joml.Vector3f fwd = new org.joml.Vector3f(0.0f, 0.0f, -1.0f).rotate(q).normalize();
            org.joml.Vector3f up  = new org.joml.Vector3f(0.0f, 1.0f, 0.0f).rotate(q).normalize();
            org.joml.Vector3f right = new org.joml.Vector3f(1.0f, 0.0f, 0.0f).rotate(q).normalize();

            // Camera planes from the same lastProj the FSR4 dispatch used.
            float a = lastProj.m22();
            float b = lastProj.m23();
            float nearP = b / (a - 1.0f);
            float farP = b / (a + 1.0f);
            if (Float.isNaN(nearP) || Float.isNaN(farP)
                    || nearP <= 0.0f || nearP > 1000.0f
                    || farP <= 0.0f || farP > 1e7f
                    || farP <= nearP) {
                nearP = 0.1f;
                farP = 1000.0f;
            }
            float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
            float fovY = (float) (2.0 * Math.atan(tanHalfFovY));

            fgFrameID = fsr2FrameCount;
            fgDeltaNs = (long)(lastFrameDeltaMs * 1_000_000.0f);
            fgJitterX = prevJitterX;
            fgJitterY = prevJitterY;
            fgCameraNear = nearP;
            fgCameraFar = farP;
            fgCameraFovY = fovY;
            fgCamPosX = (float) fgPos.x; fgCamPosY = (float) fgPos.y; fgCamPosZ = (float) fgPos.z;
            fgCamUpX = up.x; fgCamUpY = up.y; fgCamUpZ = up.z;
            fgCamRightX = right.x; fgCamRightY = right.y; fgCamRightZ = right.z;
            fgCamForwardX = fwd.x; fgCamForwardY = fwd.y; fgCamForwardZ = fwd.z;
            fgReset = !hasPrevFrame || fsr2FrameCount == 0;
            vkModFgParamsStashed = true;
        } catch (Throwable t) {
            vkModFgParamsStashed = false;
        }
    }

    /**
     * Runs the deferred FG prepare + generate for the VKMod path. Called from
     * the mixin/UIHelper immediately after waitForD3D12Output() succeeds — the
     * point where pool 4 (the FSR4 output) is guaranteed fully written by the
     * D3D12 dispatch. Step 1: single-present — the generated frame stays in
     * pool 5 (verified via the [Fg4Context] generate diag line); nothing reads
     * it yet. Step 2 adds presentation + the pool 6 GUI capture.
     */
    /**
     * Step 1's pre-GUI hook. With step 2 the FG generate moved to the
     * Renderer.endFrame TAIL hook ({@link #presentVkModFgFrame}), which runs
     * AFTER the real frame was presented — that is the only point where pool 6
     * (the swapchain GUI capture) and pool 4 (FSR4 output) are both guaranteed
     * valid and the generated frame can be copied + presented. Kept as a no-op
     * so any stale call site is harmless (the stash is consumed by the endFrame
     * hook instead; a leftover stash is always overwritten by the next frame's
     * stashVkModFgParams).
     */
    public void onVkModFsr4OutputReady() {
        // no-op — see presentVkModFgFrame
    }

    /**
     * FSR3_FG_DX12 step 2 (scaled path): called from endNativeResGui AFTER the
     * GUI painted into the swapchain (real frame + GUI at native res) and
     * BEFORE the PRESENT transition. Records the swapchain -> pool 6 capture
     * into the still-recording main command buffer (the FG SDK's presentColor).
     * The capture executes with the main submission; the generate + present
     * happen in the endFrame hook after waitForFrameCopy.
     */
    public void onVkModGuiPainted(long cmdBuf, long swapchainImage, int w, int h) {
        if (!fgActive() || !fg4Initialized) return;
        if (!vkModFgPresentImagesReady || cmdBuf == 0 || swapchainImage == 0) return;
        try {
            VulkanModCompat.recordVkModSwapchainToFgPresentCopy(cmdBuf, swapchainImage, w, h);
            vkModFgCapturedThisFrame = true;
        } catch (Throwable t) {
            FSRMod.LOGGER.warn("FG_DX12: VulkanMod GUI capture failed: {}", t.toString());
        }
    }

    /**
     * FSR3_FG_DX12 step 2: the extra present after VulkanMod presented the real
     * frame (Renderer.endFrame TAIL). Everything the generated frame needs is
     * valid here:
     *   1. waitForFrameCopy — the main submission (containing the swapchain ->
     *      pool 6 capture on the scaled path, and the FSR4 copies) completed,
     *      so pool 6 = real frame + GUI and pool 4 = FSR4 output.
     *   2. FG prepare + generate (presentColor = pool 6, HUDLessColor = pool 4)
     *      -> pool 5.
     *   3. Acquire a SECOND swapchain image, copy pool 5 into it via the
     *      one-shot command buffer, and present it. Display order: real frame
     *      first (VulkanMod's own present), generated frame second — the
     *      generated frame depicts the midpoint of (real N-1, real N) and is
     *      shown right after real N, so the sequence stays monotonic.
     * Any failure degrades to single-present (real frame only) — never a crash.
     */
    public void presentVkModFgFrame() {
        if (!vkModFgParamsStashed || !fgActive() || !fg4Initialized) return;
        vkModFgParamsStashed = false;
        if (!vkModFgPresentImagesReady) {
            // pool 5/6 imports failed — single-present (step-1 behavior)
            return;
        }
        // Scaled path: the generate's presentColor is pool 6, which only exists
        // if the GUI painted this frame (capture recorded in endNativeResGui).
        if (vkModFgNeedsCapture && !vkModFgCapturedThisFrame) return;
        try {
            // 1. Main submission done (capture + copies executed).
            if (!VulkanModCompat.waitForFrameCopy()) return;

            // 2. FG generate. presentColor = pool 6 (real+GUI, scaled path) or
            //    pool 4 (non-scaled), HUDLessColor = pool 4 — set at init.
            boolean prepared = Fg4Native.nativePrepare(
                fgFrameID, fgDeltaNs,
                fgJitterX, fgJitterY,
                fgCameraNear, fgCameraFar, fgCameraFovY,
                1.0f,  // viewSpaceToMetersFactor
                fgCamPosX, fgCamPosY, fgCamPosZ,
                fgCamUpX, fgCamUpY, fgCamUpZ,
                fgCamRightX, fgCamRightY, fgCamRightZ,
                fgCamForwardX, fgCamForwardY, fgCamForwardZ,
                fgReset);
            if (!prepared) return;
            int vkNumGen = Math.max(1, Math.min(4, config.fgMultiplier));
            boolean generated = Fg4Native.nativeGenerate(vkNumGen);
            // FREEZE FIX: see the GL-path comment in doFrameGenDispatch — if the
            // FG fence never signaled, skip the output copy + present entirely
            // (single-present; the recorded copy would read an unwritten texture).
            boolean finished = Fg4Native.nativeFinish();
            if (!generated || !finished) return;

            // 3. Acquire + copy + present — gated on camera translation.
            if (vkModFgEyeSpeed > 0.5f) {
                // Constant-depth translation MVs are ~0 -> the FG frame would be
                // a static blend of two offset frames (edge smear). Single-present
                // this frame; the SDK state stays fresh because the generate above
                // still ran with a consecutive frameID.
                if (!vkModFgTranslateGateLogged) {
                    vkModFgTranslateGateLogged = true;
                    FSRMod.LOGGER.info("FG_DX12: VulkanMod translation gate ENGAGED (eyeSpeed>0.5 blocks/s) — single-present while moving, 2x when still/rotating");
                }
                return;
            }
            long swapchainId = vkModSwapchainId();
            if (swapchainId == 0) return;
            int idx = VulkanModCompat.acquireVkModFgImage(swapchainId);
            if (idx < 0) return;
            long img = vkModSwapchainImageId(idx);
            if (img == 0) return;
            VulkanModCompat.recordVkModFgOutputCopyOneShot(img, displayWidth, displayHeight);
            long queue = vkModPresentQueueAddress();
            if (queue == 0) return;
            VulkanModCompat.presentVkModFgImage(queue, swapchainId, idx);
            if (!vkModFgDoublePresentLogged) {
                vkModFgDoublePresentLogged = true;
                FSRMod.LOGGER.info("FG_DX12: VulkanMod double-present ACTIVE (real + generated frame per render)");
            }
        } catch (Throwable t) {
            FSRMod.LOGGER.warn("FG_DX12: VulkanMod FG present failed: {}", t.toString());
        }
    }

    /** Current VulkanMod swapchain handle (0 if unavailable). */
    private long vkModSwapchainId() {
        try {
            Class<?> r = Class.forName("net.vulkanmod.vulkan.Renderer");
            Object renderer = r.getMethod("getInstance").invoke(null);
            if (renderer == null) return 0;
            Object sc = r.getMethod("getSwapChain").invoke(renderer);
            if (sc == null) return 0;
            return (long) sc.getClass().getMethod("getId").invoke(sc);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** VkImage handle of the given swapchain image index (0 if unavailable). */
    private long vkModSwapchainImageId(int index) {
        try {
            Class<?> r = Class.forName("net.vulkanmod.vulkan.Renderer");
            Object renderer = r.getMethod("getInstance").invoke(null);
            if (renderer == null) return 0;
            Object sc = r.getMethod("getSwapChain").invoke(renderer);
            if (sc == null) return 0;
            return (long) sc.getClass().getMethod("getImageId", int.class).invoke(sc, index);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Address of VulkanMod's present queue (0 if unavailable). */
    private long vkModPresentQueueAddress() {
        try {
            Class<?> dm = Class.forName("net.vulkanmod.vulkan.device.DeviceManager");
            Object pq = dm.getMethod("getPresentQueue").invoke(null);
            if (pq == null) return 0;
            Object vkQueue = pq.getClass().getMethod("vkQueue").invoke(pq);
            if (vkQueue == null) return 0;
            return (long) vkQueue.getClass().getMethod("address").invoke(vkQueue);
        } catch (Throwable t) {
            return 0;
        }
    }

    public boolean presentDirect(RenderTarget caller) {
        // BLIT AUDIT (2026-08-29, "every second it blinks"): count blits of the
        // MAIN present target. See the mainPresentBlits field doc — a frame that
        // ends with zero is a frame that was never presented. Iris also calls
        // blitToScreen on its own internal targets; the throttled log below
        // reports those so a stray Iris blit (or a blit of an empty target) is
        // distinguishable from the real end-of-frame present.
        boolean mainPresent = isMainPresentTarget(caller);
        if (mainPresent) {
            mainPresentBlits++;
        } else if (System.currentTimeMillis() - lastBlitAuditLogMs >= 250L) {
            lastBlitAuditLogMs = System.currentTimeMillis();
            FSRMod.LOGGER.info("[BLIT-AUDIT] non-main blitToScreen — caller={}x{} irisSwap={} version={}",
                caller != null ? caller.width : -1, caller != null ? caller.height : -1,
                irisSwapActive, config.version);
        }
        // FSR3_FG_DX12: the GUI has now painted onto mainRT (the real FSR4 frame).
        // Run the deferred FG generate here so presentColor can be the real frame
        // WITH the GUI (pool 6, copied from mainRT) while HUDLessColor is the FSR4
        // output WITHOUT it (pool 4) — the SDK composites the HUD onto the generated
        // frame, so BOTH double-presented frames carry the UI. Returning false lets
        // the vanilla blitToScreen proceed, presenting mainRT (now the FG frame).
        if (fgGeneratePending && fg4Initialized && isMainPresentTarget(caller)) {
            fgGeneratePending = false;
            int fgStatus = doFrameGenDispatch();
            if (fgStatus == 0) {
                fgSecondPresentPending = true;
                if (!fgDoublePresentLogged) {
                    fgDoublePresentLogged = true;
                    int totalFrames = Math.max(1, Math.min(4, config.fgMultiplier)) + 1;
                    FSRMod.LOGGER.info("FG_DX12: multi-present ACTIVE (HUDLessColor, {}x) — display FPS = {}× render FPS",
                        totalFrames, totalFrames);
                }
                fgRuntimeFailCount = 0;  // recovered — reset consecutive counter
            } else if (fgStatus == 2) {
                // REAL native FG failure — counts toward permanent disable.
                // mainRT still holds the real frame + GUI; single present.
                fgSecondPresentPending = false;
                noteD3D12RuntimeFailure("FG prepare/generate failed");
            } else {
                // Transient GL fence timeout (status 1): the GL queue was deeper
                // than 8ms (Voxy meshing / world join / fast movement). This is
                // NOT an FG malfunction — single-presenting the real FSR4 frame
                // is graceful, and counting every timeout toward the permanent
                // disable threshold turned FG_DX12 into "just FSR4" after a few
                // busy frames (the reported "FG DX12 acting like FSR4").
                fgSecondPresentPending = false;
            }
        }
        return false;
    }

    /**
     * Runs the deferred FG prepare + generate. Called from presentDirect() after
     * the GUI has painted onto mainRT.
     *
     * @return 0 = success (generated frame copied to mainRT); 1 = transient GL
     *         fence timeout (graceful single-present, NOT a runtime failure);
     *         2 = real native prepare/generate failure (counted toward the
     *         permanent-disable threshold).
     */
    private int doFrameGenDispatch() {
        if (mainRenderTarget == null || sharedTexMgr == null) return 2;
        try {
            // 1. Copy mainRT (real frame + GUI) -> pool 6 (FG_PRESENT).
            copyMainRTToFgPresent();

            // GL sync: fence + client wait (8ms cap) so the D3D12 FG dispatch
            // sees the copy. Cap was 50ms — with Voxy's GPU meshing queueing
            // work on the same GL context, a 50ms blocking wait became a
            // multi-frame spike; 8ms caps the stall at half a frame and the
            // timeout path falls back to single-present (real frame only).
            GL43C.glFlush();
            long sync = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            int waitStatus = GL43C.glClientWaitSync(sync, 0, 8_000_000L);
            if (waitStatus != GL43C.GL_ALREADY_SIGNALED && waitStatus != GL43C.GL_CONDITION_SATISFIED) {
                // FIRST timeout of an episode: attempt the one-time 33ms extended
                // wait so a one-off deep queue (world join, chunk compile) still
                // lets FG generate — recovers FSR4's own policy. Later consecutive
                // timeouts skip cheaply (single-present) instead of stacking
                // 40ms of CPU blocking per frame.
                if (!fgTimeoutLogged) {
                    waitStatus = GL43C.glClientWaitSync(sync, 0, 33_000_000L);
                }
            }
            GL43C.glDeleteSync(sync);
            if (waitStatus != GL43C.GL_ALREADY_SIGNALED && waitStatus != GL43C.GL_CONDITION_SATISFIED) {
                // Log once per episode and only in debug mode — per-frame warn
                // formatting during GPU saturation (fast movement + Voxy meshing)
                // adds measurable overhead and spam. Falls back to single-present
                // (real frame only), which is graceful.
                // NOTE: fgTimeoutLogged must be set UNCONDITIONALLY (not gated on
                // debug) — it is the one-time extended-wait throttle above, and
                // gating it on shouldDebug() meant every timeout re-ran the 33ms
                // wait whenever logging was off (the 41ms-per-frame stall class
                // this fix exists to prevent).
                if (!fgTimeoutLogged) {
                    fgTimeoutLogged = true;
                    if (shouldDebug()) {
                        FSRMod.LOGGER.warn("FG_DX12: GL sync timeout before FG generate, skipping frame");
                    }
                }
                return 1;  // transient — NOT a runtime failure
            }
            fgTimeoutLogged = false;

            // 2. Prepare + generate. presentColor = pool 6 (real+GUI),
            //    HUDLessColor = pool 4 (FSR4 output, no GUI) — set at configure.
            //    numGeneratedFrames = config.fgMultiplier (1-4): the SDK writes
            //    N interpolated frames to pool slots 5,7,8,9.
            boolean prepared = Fg4Native.nativePrepare(
                fgFrameID, fgDeltaNs,
                fgJitterX, fgJitterY,
                fgCameraNear, fgCameraFar, fgCameraFovY,
                1.0f,  // viewSpaceToMetersFactor
                fgCamPosX, fgCamPosY, fgCamPosZ,
                fgCamUpX, fgCamUpY, fgCamUpZ,
                fgCamRightX, fgCamRightY, fgCamRightZ,
                fgCamForwardX, fgCamForwardY, fgCamForwardZ,
                fgReset);
            if (!prepared) return 2;

            int numGen = Math.max(1, Math.min(4, config.fgMultiplier));
            boolean generated = Fg4Native.nativeGenerate(numGen);
            // FREEZE FIX (2026-08-29, "FSR3 FG DX12 runs then completely freezes"):
            // nativeFinish() waits on the FG fence with a 5000ms timeout and now
            // RETURNS whether it completed. If the D3D12 work never finished
            // (wedged queue / TDR), the output texture was never written, and the
            // glCopyImageSubData in copyFgOutputToMainRT() would block the render
            // thread FOREVER on the hung GPU while the server thread keeps tick-
            // ing — the exact reported freeze. Fail hard (counts toward the
            // runtime-failure latch) and single-present the real frame instead.
            boolean finished = Fg4Native.nativeFinish();
            if (!generated || !finished) return 2;

            // 3. Generated frame slot 0 (with HUD) -> mainRT; the vanilla
            //    blitToScreen that follows presents it as the FIRST image.
            //    For numGen > 1, the remaining generated frames + the real
            //    frame are presented by onAfterSwapBuffers().
            fgNumGeneratedThisCycle = numGen;
            fgExtraGenFramesToPresent = numGen - 1;  // extra frames beyond the first
            // GL TRANSLATION GATE (2026-09-02, parity with the VKMod path's
            // vkModFgEyeSpeed gate): while the camera translates faster than
            // FG_TRANSLATION_GATE_SPEED, the generated frame is a static blend
            // of two offset real frames (no true per-pixel translation MVs on
            // the GL path) — edge smear. Copy the GENERATED frame's slot 0 so
            // the SDK's internal state stays warm, then OVERWRITE mainRT with
            // the REAL frame+GUI (pool 6) so the presented image is the real
            // frame — single-present this frame. The generate above still ran
            // with a consecutive frameID, so the next still frame interpolates
            // correctly. Still/rotating = the generated frame presents as
            // before (full frame doubling).
            if (fgEyeSpeed > FG_TRANSLATION_GATE_SPEED) {
                copyFgPresentToMainRT();
                fgExtraGenFramesToPresent = 0;
                if (!fgTranslateGateLogged) {
                    fgTranslateGateLogged = true;
                    FSRMod.LOGGER.info("FG_DX12: GL translation gate ENGAGED (eyeSpeed>{}) — single-present while translating, {}× when still/rotating",
                        FG_TRANSLATION_GATE_SPEED, Math.max(1, Math.min(4, config.fgMultiplier)) + 1);
                }
                return 0;
            }
            copyFgOutputToMainRT();
            return 0;
        } catch (Throwable t) {
            FSRMod.LOGGER.warn("FG_DX12: generate failed: {}", t.toString());
            return 2;
        }
    }

    /**
     * Copies mainRT (real FSR4 frame + GUI) into pool 6 (FG_PRESENT) — the
     * presentColor the FG SDK interpolates from.
     */
    private void copyMainRTToFgPresent() {
        if (mainRenderTarget == null || sharedTexMgr == null) return;
        int presentTex = sharedTexMgr.getFgPresentTexId();
        if (presentTex == 0) return;
        int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
        if (mainTex == 0) return;
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            presentTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1
        );
    }

    /**
     * Copies pool 6 (FG_PRESENT — the real frame WITH the GUI) into mainRT.
     * Used for the SECOND double-present, so the real frame also carries the HUD.
     */
    private void copyFgPresentToMainRT() {
        if (mainRenderTarget == null || sharedTexMgr == null) return;
        int presentTex = sharedTexMgr.getFgPresentTexId();
        if (presentTex == 0) return;
        int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
        if (mainTex == 0) return;
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            presentTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1
        );
    }

    public boolean isFrameGenMode() {
        return isFrameGen();
    }

    public FSR3Pipeline getFsr3Pipeline() {
        return fsr3Pipeline;
    }

    public int getDisplayWidth() {
        return displayWidth;
    }

    public int getDisplayHeight() {
        return displayHeight;
    }

    public void onResize(int newWindowWidth, int newWindowHeight) {
        // FIX: guard against no-op resizes. Without this, spurious resize events
        // (DPI changes, window manager events, GLFW framebuffer-size callbacks
        // firing on every frame) destroy all history textures and reset
        // fsr2FrameCount to 0, making FSR2/3 behave as FSR1 every frame.
        if (newWindowWidth == displayWidth && newWindowHeight == displayHeight) {
            return;
        }
        // MINIMIZE/restore safety (2026-08-06, review fix): GLFW fires
        // framebuffer-size callbacks with 0,0 during minimize/restore
        // transitions. Minecraft's own callback guards those internally
        // (width != 0 && height != 0) but our WindowMixin is @At(TAIL) and
        // fires regardless — so onResize(0,0) is reachable. Zeroing
        // displayWidth/Height would break the pipeline math AND the explicit
        // real-target resize below would resize the GL target to 0×0.
        if (newWindowWidth <= 0 || newWindowHeight <= 0) {
            return;
        }
        displayWidth = newWindowWidth;
        displayHeight = newWindowHeight;
        // MONITOR-MOVE FIX (2026-08-06, "UI cut off except bottom-left"):
        // Minecraft's own resize (onResolutionChanged -> mainRenderTarget.resize)
        // can land on our ScaledRenderTarget proxy while it is swapped into the
        // mainRenderTarget field — previously a no-op, leaving the REAL target at
        // its old size while the window is at the new size. The GUI then renders
        // at the new window size into a stale-size texture and gets clipped.
        // The proxy now forwards resize() to the real target, and this explicit
        // resize here is the belt-and-suspenders for any path where the proxy
        // wasn't in the field when MC resized (harmless no-op if already correct).
        if (originalMainRenderTarget != null
                && (originalMainRenderTarget.width != displayWidth
                    || originalMainRenderTarget.height != displayHeight)) {
            originalMainRenderTarget.resize(displayWidth, displayHeight);
        }
        float scale = config.qualityScale;
        scaledWidth = Math.max(16, Math.round(displayWidth * scale));
        scaledHeight = Math.max(16, Math.round(displayHeight * scale));

        if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
        proxyTarget = null;
        // A resize rebuilds Iris's pipeline at the new size — arm the dense
        // terrain-probe window (see armTerrainProbeBurst).
        armTerrainProbeBurst();
        if (fsr2Pipeline != null) {
            fsr2Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
            fsr2Pipeline.recreateTextures();
        }
        if (fsr3Pipeline != null) {
            fsr3Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
            fsr3Pipeline.recreateTextures();
            fsr3Pipeline.resetFrameGen();
        }
        if (fsr1Pipeline != null) {
            fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
        }
        rcasUpscaleCompiled = false;
        jitterManager.reset();
        // FIX: also reset prevJitter when resetting jitter manager, otherwise
        // MV_GEN computes a bogus jitter delta on the first post-reset frame.
        prevJitterX = 0.0f;
        prevJitterY = 0.0f;
        hasPrevFrame = false;
        fsr2FrameCount = 0;
        pipelineReady = false;
        if (sharedTexMgr != null) {
            sharedTexMgr.destroy();
            sharedMemoryActive = false;
        }
        if (fg4Initialized) {
            Fg4Native.nativeShutdown();
            fg4Initialized = false;
        }
        if (fsr4Initialized) {
            Fsr4Native.shutdownFsr4();
            fsr4Initialized = false;
            fsr4AsyncPending = false;
            VulkanModCompat.fsr4VkModActive = false;
        }
        if (sharedTexMgr != null) {
            sharedTexMgr.destroyFsr4Pool();
        }
        fsr4Available = false;
    }

    /**
     * TARGET-FPS AUTO-QUALITY (2026-08-08): called at the top of every
     * onRenderBegin. When config.targetFps > 0 and the game is actively
     * rendering gameplay (world loaded, no menu/pause, FSR enabled, not a
     * frame-gen mode, not Iris — the render scale doesn't apply there),
     * compares the EMA'd render FPS against the target and nudges
     * config.qualityScale by one 0.05 notch on a ~1.5s cooldown, acting only
     * after 2 consecutive out-of-band readings (~3s) with a ±10% hysteresis
     * band so it settles instead of oscillating (2026-08-08 v2 — the wide band
     * + confirmation streak replaced v1's ±6% single-reading trigger, which
     * limit-cycled every 2s: the 0.05 step moved fps across the whole band).
     * When fps is pinned at the display refresh rate with target >= refresh,
     * the target is unreachable under vsync/frame-cap — do nothing (the status
     * line explains why). Changes go through applyAutoScale() (the same light
     * path as the quality slider); FSR4 tears down + re-inits at the new size
     * (the dynamic-res pool-only recreate was reverted 2026-08-08 — it crops
     * the image).
     */
    private void updateAutoQuality() {
        Minecraft client = Minecraft.getInstance();
        if (config.targetFps <= 0 || !config.enabled) return;
        if (client.level == null) return;              // title / loading
        if (client.screen != null) return;             // menus/pause drop fps — don't thrash quality
        if (IrisCompat.isShaderPackInUse()) return;    // scale comes from irisQualityScale here
        if (isFrameGen() || isFrameGenDx12()) return;  // FG modes already target fps by construction
        if (autoEmaFrameMs <= 0.0f) return;            // no measurement yet

        long nowMs = System.currentTimeMillis();
        float fps = 1000.0f / autoEmaFrameMs;
        if (nowMs < autoPlateauHoldUntilMs) {
            // SOFT HOLD (2026-08-08 v2, review fix): the plateau hold is a guard
            // against grinding to the floor on an unreachable target — NOT a
            // lock. If fps has since recovered into the band (the storm passed,
            // vsync was turned off, the scene lightened), clear the hold so
            // quality can rise back toward the target instead of staying parked
            // low for the full 5 minutes.
            if (fps >= config.targetFps * AUTO_Q_LOW) {
                autoPlateauHoldUntilMs = 0;
                autoLowerStreak = 0;
                if (shouldDebug()) {
                    FSRMod.LOGGER.info("[AUTOQ] fps={:.1f} recovered — clearing plateau hold", fps);
                }
            } else {
                return;
            }
        }
        if (nowMs - lastAutoAdjustMs < AUTO_Q_INTERVAL_MS) return;
        lastAutoAdjustMs = nowMs;

        int refresh = 0;
        try {
            refresh = client.getWindow().getRefreshRate();
        } catch (Throwable ignored) { /* API differences / window closed */ }

        // UNREACHABLE-TARGET HOLD (2026-08-08 v2, the "zooming in" root cause):
        // if fps is pinned at the display refresh rate (vsync) and the target is
        // >= refresh, the target can never be reached. v1 treated this as
        // "headroom" and raised quality to 100% — then the heavier render dropped
        // fps below target and it lowered again → the every-2s 0.05-step limit
        // cycle the user saw as zooming. Now: HOLD and do nothing (the target is
        // simply not achievable while vsync caps fps below it; the user can raise
        // the target or disable vsync). If the target is BELOW refresh and fps is
        // pinned at refresh, there is genuine headroom — fall through to the
        // normal above-target raise.
        //
        // STREAK-RESET BUG FIX (2026-08-08, "it does not tweak the settings at
        // all" + Voxy lag spikes): this branch used to reset autoOutOfBandStreak
        // / autoLastDirection / autoLowerStreak before returning. That wiped the
        // confirmation streak on EVERY calm frame at the refresh ceiling — i.e.
        // exactly the frames between Voxy/chunk-streaming spikes on a vsync'd or
        // frame-capped display (spike → streak 1, calm → reset 0, spike → 1, …)
        // — so a below-target storm could never accumulate the 2 consecutive
        // out-of-band readings required to lower qualityScale, and the feature
        // never acted at all. The reset was also dead logic: in this branch
        // fps ≥ refresh−1 ≥ target−1 > target*0.90, so direction can never be
        // -1 here (fps is never below the lower band); the in-band branch below
        // already resets state when appropriate. Removing the resets lets the
        // streak survive calm-between-spike frames and the feature finally
        // lowers quality during sustained storms.
        if (refresh > 0 && fps >= refresh - 1.0f) {
            if (config.targetFps >= refresh) {
                return;
            }
        }

        float low = config.targetFps * AUTO_Q_LOW;    // lower when below this
        float high = config.targetFps * AUTO_Q_HIGH;  // raise when above this
        float cur = config.qualityScale;
        float next = cur;
        int direction;
        if (fps < low) {
            direction = -1;
            // Proportional lower step: farther below target → bigger step, so a
            // big deficit converges in 1-2 steps instead of 10+.
            float deficit = (low - fps) / low;
            next = cur - AUTO_Q_STEP * (1 + Math.min(3, (int) Math.ceil(deficit / 0.15f)));
        } else if (fps > high) {
            direction = +1;
            next = cur + AUTO_Q_STEP;
        } else {
            direction = 0;
        }

        if (direction == 0) {
            // Within the band — fully stable; reset all confirmation state.
            autoOutOfBandStreak = 0;
            autoLastDirection = 0;
            autoLowerStreak = 0;
            return;
        }
        if (direction != autoLastDirection) {
            autoOutOfBandStreak = 0;   // a direction flip restarts the confirmation
            autoLastDirection = direction;
        }
        autoOutOfBandStreak++;
        if (autoOutOfBandStreak < AUTO_Q_CONFIRM_INTERVALS) return;  // need 2 consecutive

        next = Math.max(0.25f, Math.min(1.0f, next));
        next = Math.round(next / 0.05f) * 0.05f;
        if (Math.abs(next - cur) < 0.001f) {
            // At the bounds: if we're at the 25% floor and STILL below target,
            // the target is unreachable at any render scale (fixed costs
            // dominate) — hold long-term instead of retrying forever.
            if (direction < 0 && cur <= 0.2501f) {
                autoPlateauHoldUntilMs = nowMs + AUTO_Q_PLATEAU_RETRY_MS;
                if (shouldDebug()) {
                    FSRMod.LOGGER.info("[AUTOQ] at 25% floor, fps={:.1f} still below target {} — unreachable, holding {}s",
                        fps, config.targetFps, AUTO_Q_PLATEAU_RETRY_MS / 1000);
                }
            }
            autoOutOfBandStreak = 0;
            return;
        }

        // Plateau guard: repeated lowers without recovery → unreachable target.
        if (direction < 0) {
            autoLowerStreak++;
            if (autoLowerStreak >= AUTO_Q_MAX_LOWER_STREAK) {
                autoPlateauHoldUntilMs = nowMs + AUTO_Q_PLATEAU_RETRY_MS;
                autoLowerStreak = 0;
                autoOutOfBandStreak = 0;
                if (shouldDebug()) {
                    FSRMod.LOGGER.info("[AUTOQ] fps={:.1f} still below target {} after {} lowers — unreachable, holding {}s",
                        fps, config.targetFps, AUTO_Q_MAX_LOWER_STREAK, AUTO_Q_PLATEAU_RETRY_MS / 1000);
                }
                return;
            }
        } else {
            autoLowerStreak = 0;
        }

        config.qualityScale = next;
        config.save();
        applyAutoScale();
        autoOutOfBandStreak = 0;   // fresh confirmation after acting
        if (shouldDebug()) {
            FSRMod.LOGGER.info("[AUTOQ] target={} fps={:.1f} scale={}->{} refresh={}",
                config.targetFps, fps, cur, next, refresh);
        }
    }

    /**
     * Arms the dense (2s) terrain-probe sampling window for 10s and schedules a
     * sample on the very next frame. Called at the only moments a broken
     * composite can actually appear — world join, FSR version switch / toggle,
     * and window resize — each of which rebuilds Iris's pipeline.
     * Outside a window the probe falls back to a 30s heartbeat: one block
     * readback costs ~26ms of pipeline flush (see diagnosticReadbackThisFrame),
     * so sampling every 2s forever bought a permanent visible hitch to guard a
     * failure that only ever occurs right after a rebuild.
     */
    private void armTerrainProbeBurst() {
        long now = System.currentTimeMillis();
        terrainProbeBurstUntilMs = now + 10000L;
        // SETTLE DELAY (2026-08-29): never read back on the frame that follows a
        // rebuild — wait out the Iris pipeline destroy/recreate, Photonics
        // reallocation and FSR (re)init storm first. The 10s burst window still
        // covers the aftermath, which is when a broken composite would show.
        terrainProbeAtMs = now + 4000L;
    }

    /**
     * Light-weight scale change (2026-08-08): the same resize path a quality-
     * slider drag takes. Recomputes the scaled render target + pipelines and
     * resets temporal history, then tears down FSR4/FG like markDirty() does —
     * the next onRenderBegin's needsRecreate check re-inits at the new size.
     * (The DYNAMIC_RESOLUTION pool-only branch that used to skip the teardown
     * was reverted 2026-08-08: the SDK dispatch reads the context-cached render
     * size, so a resized pool was sampled with stale UVs -> magnified corner
     * crop.) FG context IS torn down (it holds stale dims); the auto-adjuster
     * excludes FG modes, so this only matters for a manual slider drag in FG mode.
     */
    /**
     * Deferred scale change from the settings-screen quality slider (2026-08-21,
     * "see through the ground on drastic quality changes" fix). The slider
     * callback runs on the CLIENT thread; applyAutoScale() tears down GL/D3D12
     * resources, and calling it synchronously from the GUI thread raced the
     * render thread's mid-dispatch reads (see scaleChangeQueued field doc).
     * This only sets a thread-safe flag; onRenderBegin consumes it at frame
     * start on the RENDER thread and calls applyAutoScale() there. Calling
     * applyAutoScale() directly from onRenderBegin (render thread) stays safe
     * and is what updateAutoQuality() does.
     */
    public void requestScaleChange() {
        scaleChangeQueued = true;
    }

    /**
     * Deferred markDirty from the FSR toggle key (2026-08-21, "freezes and
     * crashes when toggling FSR on/off with a shaderpack" fix). The toggle
     * handler in FSRMod.onClientTick runs on the CLIENT thread; markDirty()
     * tears down FSR4/FG D3D12 contexts + GL resources — calling it
     * synchronously races the render thread's mid-dispatch reads and causes
     * a hard freeze. This only sets a thread-safe flag; onRenderBegin
     * consumes it at frame start on the RENDER thread.
     */
    public void requestToggleDirty() {
        toggleDirtyQueued = true;
        // Force pipeline recreate + history reset on the next frame (same
        // reasoning as requestConfigDirty — the toggle changes config.enabled
        // mid-frame, and the interstitial frame dispatches the wrong pipeline).
        pipelineReady = false;
        hasPrevFrame = false;
        fsr2FrameCount = 0;
    }

    /**
     * Deferred markDirty from the settings screen (2026-08-23, "switching FSR
     * versions makes terrain disappear until a quality change" fix). The
     * version pills and toggles in SettingsScreen run on the CLIENT (GUI)
     * thread; markDirty() tears down GL/D3D12 resources and must run on the
     * RENDER thread (same reason as requestToggleDirty). This only sets a
     * thread-safe flag; onRenderBegin consumes it at frame start next to
     * toggleDirtyQueued.
     *
     * ALSO forces pipelineReady = false NOW, on the CLIENT thread, so the
     * VERY NEXT onRenderBegin (before the deferred markDirty consumption
     * fires — the interstitial frame where config.version is already the new
     * value but the old pipeline state still holds) forces a fresh pipeline
     * setup at the correct dimensions. Without this, that interstitial frame
     * dispatches FSR2 with stale setup (zero/uninitialized renderWidth),
     * produces garbage output, copies it to mainRT, AND poisons the temporal
     * history with that garbage — every subsequent accumulated frame blends
     * from the bad frame → terrain stays black/garbage until a quality
     * change resets hasPrevFrame.
     */
    public void requestConfigDirty() {
        configDirtyQueued = true;
        // Thread-safe: pipelineReady is only written in onRenderBegin (render
        // thread), but setting it false here is safe — it means the next render
        // frame WILL hit !pipelineReady and call setup(), exactly the desired
        // outcome. No mutex needed for a boolean.
        pipelineReady = false;
        // Also force a temporal-history reset NOW: the CURRENT frame (which
        // started with the old version but whose onSceneRendered sees the NEW
        // version and may dispatch the wrong pipeline for one frame) will
        // produce garbage output, and accumulate reads it as history in
        // subsequent frames. Force-resetting means the next frame treats the
        // interstitial frame's output as irrecoverable junk and starts fresh.
        hasPrevFrame = false;
        fsr2FrameCount = 0;
    }

    public void applyAutoScale() {
        // IRIS-AWARE SCALE (2026-08-29): this used to hardcode
        // config.qualityScale, so any rebuild that ran while a pack was active
        // sized everything for the NON-Iris scale (0.65 -> 1092x683) while the
        // frame then rendered at the Iris scale (0.6 -> 1008x630). That is how
        // the FSR4 D3D12 context ended up stuck at 1092x683 and refused to
        // dispatch after a pack toggle ("context ... != scene ... — FSR1 until
        // recreate"), leaving the FSR1 fallback to upscale a mismatched scene.
        // Matches onRenderBegin's own expression exactly.
        float scale = irisActiveThisFrame ? config.getIrisScale() : config.getScale();
        scaledWidth = Math.max(16, Math.round(displayWidth * scale));
        scaledHeight = Math.max(16, Math.round(displayHeight * scale));
        lastAppliedScaleW = scaledWidth;
        lastAppliedScaleH = scaledHeight;
        if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
        proxyTarget = null;
        if (fsr2Pipeline != null) {
            fsr2Pipeline.deleteAllTextures();
        }
        if (fsr3Pipeline != null) {
            fsr3Pipeline.deleteAllTextures();
            fsr3Pipeline.resetFrameGen();
        }
        if (fsr1Pipeline != null) {
            fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
        }
        rcasUpscaleCompiled = false;
        pipelineReady = false;
        jitterManager.reset();
        prevJitterX = 0.0f;
        prevJitterY = 0.0f;
        fsr2FrameCount = 0;
        hasPrevFrame = false;
        sceneUpscaled = false;
        fgFlipState = false;
        fgSecondPresentPending = false;
        fgInSecondPresent = false;
        fgExtraGenFramesToPresent = 0;
        fgGeneratePending = false;
        fsr4AsyncPending = false;
        fsr4PrevColorFresh = false;
        // Tear down the FSR4 context + pool like markDirty() does: the scene now
        // renders at the NEW scale while the pool/context are still at the old
        // size, and a mismatched Copy1/dispatch would crop the image (the
        // corner-zoom class of bug). With the pool gone, the dispatch guard
        // (getColorAllocPtr() != 0) blocks and the spatial FSR1 fallback renders
        // until the next onRenderBegin's needsRecreate re-inits at the new size.
        // DELIBERATE CHANGE RESETS THE FG PERMANENT-DISABLE LATCH (2026-08-29,
        // "FSR3 FG DX12 says it is only running a spatial upscaler after a
        // quality change"): noteD3D12RuntimeFailure latches
        // fg4PermanentlyDisabled after FG_MAX_RUNTIME_FAILURES, the latch is
        // never reset, AND it gates the FG re-init (fgActive()->
        // !fg4PermanentlyDisabled) — a catch-22. During a quality-change
        // transition storm (context teardown + re-init + Iris recompile) a few
        // transient prepare/generate failures or waitIdle stalls rack up to the
        // threshold and permanently kill FG for the session, degrading
        // FG_DX12 to FSR1 spatial. A deliberate settings change is the user
        // retrying — clear the latch so this rebuild's re-init actually runs FG
        // again. noteD3D12RuntimeFailure re-disables it if the failures are
        // genuine (self-limiting).
        fg4PermanentlyDisabled = false;
        fg4InitFailed = false;
        fg4InitFailCount = 0;
        fgRuntimeFailCount = 0;
        fgStallConsecutive = 0;
        // teardownFsr4Context() also tears down a PARKED context (2026-08-25):
        // the parked SDK context references the pool textures, so destroying
        // the pool under it would be a use-after-free on the next unpark.
        teardownFsr4Context();
        syncNativeDiagnostics();
    }

    /**
     * Clears the auto-quality hold/streak state — called by the settings screen
     * whenever the target changes, so a new target takes effect immediately
     * instead of waiting out a 5-minute unreachable-target hold.
     */
    public void resetAutoQuality() {
        autoPlateauHoldUntilMs = 0;
        autoLowerStreak = 0;
        autoOutOfBandStreak = 0;
        autoLastDirection = 0;
    }

    /**
     * Live status readout for the settings screen (target-FPS row).
     *
     * Also explains WHY the auto-adjuster is currently not moving the slider
     * (2026-08-08, "it does not tweak the settings at all"): a vsync/frame-cap
     * at the refresh rate with target >= refresh makes the target unreachable
     * by design (no render-scale can exceed the cap), and an active plateau
     * hold means repeated lowering never recovered — both now show in the
     * status line instead of silently doing nothing.
     */
    public String getAutoQualityStatus() {
        if (config.targetFps <= 0) return null;
        float fps = autoEmaFrameMs > 0.0f ? 1000.0f / autoEmaFrameMs : 0.0f;
        String s = "Target " + config.targetFps + " — measured " + Math.round(fps)
            + " fps @ " + Math.round(config.qualityScale * 100) + "%";
        if (autoPlateauHoldUntilMs > System.currentTimeMillis()) {
            s += " (holding — quality couldn't reach target, retrying in "
                + ((autoPlateauHoldUntilMs - System.currentTimeMillis()) / 1000) + "s)";
        } else {
            int refresh = 0;
            try {
                refresh = Minecraft.getInstance().getWindow().getRefreshRate();
            } catch (Throwable ignored) { /* API differences / window closed */ }
            if (refresh > 0 && fps >= refresh - 1.0f && config.targetFps >= refresh) {
                s += " (capped at " + refresh + "Hz vsync/frame-limit — target unreachable; disable vsync or lower the target)";
            }
        }
        return s;
    }

    /**
     * PARK (2026-08-25): switch the live FSR4/FG D3D12 SDK contexts + shared
     * pool to the parked state — kept alive but unused. Called when the user
     * switches AWAY from FSR4/FG_DX12 to a non-D3D12 version, so a re-select
     * at the same size reuses them with ZERO cross-API work (pool allocate +
     * external-memory imports + ffxCreateContext — the expensive + crash-prone
     * part of a fresh init). The D3D12 device itself already survives switches
     * (disposeD3D12 only runs at full destroy()); parking extends that survival
     * to the SDK context + pool. The parked size stays in fsr4ContextRenderW/H
     * + DisplayW/H for the unpark size check. Idempotent.
     */
    private void parkFsr4Context() {
        if (!fsr4Initialized && !fg4Initialized && !fsr4Parked && !fg4Parked) return;
        if (shouldDebug()) {
            FSRMod.LOGGER.info("FSR4: parking D3D12 context + pool ({}x{} -> {}x{}) — kept alive for instant re-select",
                fsr4ContextRenderW, fsr4ContextRenderH, fsr4ContextDisplayW, fsr4ContextDisplayH);
        }
        fsr4Parked = fsr4Parked || fsr4Initialized;
        fg4Parked = fg4Parked || fg4Initialized;
        fsr4Initialized = false;
        fg4Initialized = false;
        fsr4AsyncPending = false;
        fsr4Suspended = false;
        fsr4ConsecutiveSkips = 0;
        VulkanModCompat.fsr4VkModActive = false;
        // Native contexts + pool stay alive (NO shutdownFsr4 / NO destroyFsr4Pool).
        fsr4InitDeferStartMs = 0;
        fsr4InitDeferFrames = 0;
        fsr4Available = false;
    }

    /**
     * UNPARK (2026-08-25): re-select FSR4/FG after a parked context. If the
     * parked SDK context + shared pool match the current size (and the pool
     * type matches this renderer — GL-imported textures for the GL path,
     * D3D12-only for VKMod), reuse them: restore fsr4Initialized (and
     * fg4Initialized when FG mode is re-selected), reset temporal history +
     * dispatch state, and return TRUE so the caller skips the entire init
     * path — ZERO cross-API work (the expensive + crash-prone part).
     * Otherwise the parked context is stale (size changed while away, or the
     * renderer switched): tear it down properly (SDK context before pool,
     * then destroy the pool) and return FALSE so the caller runs the normal
     * init path. The parked dims are the unpark size check — they are NOT
     * reset on park (only on this teardown, so a re-init routes through the
     * first-init deferral).
     */
    private boolean unparkFsr4Context(int renderW, int renderH, int dispW, int dispH,
                                      boolean requireGlImports) {
        if (!fsr4Parked) return false;
        boolean sizeOk = fsr4ContextRenderW == renderW && fsr4ContextRenderH == renderH
            && fsr4ContextDisplayW == dispW && fsr4ContextDisplayH == dispH;
        boolean poolTypeOk = sharedTexMgr != null
            && sharedTexMgr.fsr4PoolHasGlImports() == requireGlImports;
        // FG_DX12 re-select after a PLAIN-FSR4 park: the FG SDK context was
        // never created (only the FSR4 context is parked), so it needs its own
        // init — fall through to the full init path (which creates both).
        boolean fgOk = !isFrameGenDx12() || fg4Parked;
        if (sizeOk && poolTypeOk && fgOk) {
            if (shouldDebug()) {
                FSRMod.LOGGER.info("FSR4: reused parked D3D12 context ({}x{} -> {}x{}) — no cross-API init",
                    renderW, renderH, dispW, dispH);
            }
            fsr4Parked = false;
            fsr4Initialized = true;
            // FG context parked alongside — restore only when FG mode is
            // re-selected (it references pool textures that stay alive).
            if (isFrameGenDx12() && fg4Parked) {
                fg4Parked = false;
                fg4Initialized = true;
            }
            // Reset temporal history + dispatch state — the parked context
            // holds stale history from before the switch-away. Fresh start:
            // the first dispatch is reset=true (SDK clears internal history).
            fsr2FrameCount = 0;
            hasPrevFrame = false;
            prevJitterX = 0.0f;
            prevJitterY = 0.0f;
            fsr4FramesSkipped = 0;
            fsr4ConsecutiveSkips = 0;
            fsr4Suspended = false;
            fsr4DispatchFailCount = 0;
            fsr4HardDisabled = false;
            fsr4FenceTimedOutPrevFrame = false;
            fsr4ProbeCleanStreak = 0;
            fsr4PreCheckSkips = 0;
            fsr4EmaWorldMs = 0.0f;
            fsr4AsyncPending = false;
            fsr4SizeMismatchLogged = false;
            fsr4InitEmaMs = 0.0f;
            lastInitFrameNanos = 0;
            // VKMod path: the D3D12 pool + external VkImages were kept alive
            // across the park (only the SDK context's internal history is
            // stale, and the reset=true first dispatch clears it), so re-arm
            // the active flag the init path would have set. The input-populated
            // gate is cleared so the first post-unpark dispatch waits for a
            // fresh Copy1 from the end() hook (the pool holds a pre-park frame).
            if (!requireGlImports) {
                VulkanModCompat.fsr4VkModActive = true;
                VulkanModCompat.fsr4InputPopulated = false;
            }
            syncNativeDiagnostics();
            return true;
        }
        // Size changed (or renderer changed) while parked — the parked
        // context is stale. Tear it down properly (context before pool),
        // then let the caller run the normal init path.
        if (shouldDebug()) {
            FSRMod.LOGGER.info("FSR4: parked context unusable ({}x{} -> {}x{} vs {}x{} -> {}x{}, gl={}) — tearing down, full re-init",
                fsr4ContextRenderW, fsr4ContextRenderH, fsr4ContextDisplayW, fsr4ContextDisplayH,
                renderW, renderH, dispW, dispH, requireGlImports);
        }
        if (fg4Parked) {
            Fg4Native.nativeShutdown();
            fg4Parked = false;
        }
        Fsr4Native.shutdownFsr4();
        fsr4Parked = false;
        fsr4Initialized = false;
        if (sharedTexMgr != null) sharedTexMgr.destroyFsr4Pool();
        fsr4ContextRenderW = 0;
        fsr4ContextDisplayW = 0;
        return false;
    }

    /**
     * Full teardown of the FSR4/FG D3D12 context + pool, handling BOTH the
     * live and the parked state (SDK context before pool — the documented
     * shutdown order). Used on deliberate re-creates (scale change) and at
     * full destroy().
     */
    private void teardownFsr4Context() {
        if (fg4Initialized || fg4Parked) {
            Fg4Native.nativeShutdown();
            fg4Initialized = false;
            fg4Parked = false;
        }
        if (fsr4Initialized || fsr4Parked) {
            Fsr4Native.shutdownFsr4();
            fsr4Initialized = false;
            fsr4Parked = false;
            fsr4AsyncPending = false;
            fsr4Suspended = false;
            fsr4ConsecutiveSkips = 0;
            VulkanModCompat.fsr4VkModActive = false;
        }
        if (sharedTexMgr != null) sharedTexMgr.destroyFsr4Pool();
        fsr4InitDeferStartMs = 0;
        fsr4InitDeferFrames = 0;
        fsr4Available = false;
    }

    public void markDirty() {
        // IRIS-AWARE SCALE (2026-08-29): while a pack is active the scene target
        // is sized from the Iris scale — onRenderBegin uses config.getIrisScale()
        // for exactly this (see the `scale` computation further down). markDirty()
        // always used config.qualityScale instead, so under a pack it computed the
        // WRONG target dimensions (qualityScale 0.65 -> 1092x682 instead of
        // irisScale 0.6 -> 1008x630). Unnoticed while the target was destroyed
        // unconditionally; it is also the value the keep-or-recreate decision
        // below compares against, so it has to be right.
        // Uses the FRAME-LOCKED pack state (irisActiveThisFrame, captured at the
        // end of the previous onRenderBegin) rather than a live IrisCompat call —
        // a mid-frame pack flip must never be observed here (2026-08-19,
        // pack-activation frame tear).
        // Matches onRenderBegin's own expression exactly (no `config.enabled`
        // term there either), so the keep-or-recreate comparison below is made
        // against the dimensions the frame is actually about to use.
        float scale = irisActiveThisFrame ? config.getIrisScale() : config.getScale();
        scaledWidth = Math.max(16, Math.round(displayWidth * scale));
        scaledHeight = Math.max(16, Math.round(displayHeight * scale));

        // KEEP THE SCENE TARGET WHEN THE SWITCH DOES NOT RESIZE IT (2026-08-29,
        // "switching the FSR version makes the terrain disappear until a quality
        // change" fix — the actual root cause).
        //
        // The scaled scene target is just a color + depth buffer pair at
        // scaledWidth x scaledHeight. The selected FSR version does not affect it
        // in any way, but markDirty() destroyed and recreated it on EVERY switch.
        // Iris holds the main depth texture as its depthtex0 / gbuffer depth
        // attachment and only re-adopts it when RenderTargets#resizeIfNeeded sees
        // a new depthBufferVersion or new dimensions — and a version switch
        // changes NEITHER (the proxy never bumps the version; see
        // ScaledRenderTarget). So after a switch the pack depth-tested the world
        // against a CLOSED texture: the terrain failed the depth test and only
        // the sky drew. A quality change altered the dimensions, took Iris's
        // sizeChanged branch, rebound everything — "terrain comes back when I
        // play with the quality settings".
        //
        // Keeping the target when the size is unchanged means a version switch
        // touches only the FSR pipelines: no GL texture churn, and Iris never
        // loses its depth attachment. A genuine resize (window or quality change)
        // still destroys and recreates it, and the bumped sceneTargetVersion
        // guarantees Iris re-adopts depth even if a same-size recreate ever slips
        // through by another route.
        boolean keepSceneTarget = renderTarget != null
            && renderTarget.width == scaledWidth && renderTarget.height == scaledHeight;
        if (!keepSceneTarget) {
            if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
        }
        proxyTarget = null;
        // A version switch / toggle rebuilds the pipelines — arm the dense
        // terrain-probe window in case the pack's composite comes back broken.
        armTerrainProbeBurst();
        if (shouldDebug()) {
            FSRMod.LOGGER.info("[SWITCH-DIAG] markDirty — version={} scale={} target={}x{} keepSceneTarget={} sceneTargetVersion={}",
                config.version, scale, scaledWidth, scaledHeight, keepSceneTarget, sceneTargetVersion);
        }
        if (fsr2Pipeline != null) {
            fsr2Pipeline.deleteAllTextures();
        }
        if (fsr3Pipeline != null) {
            fsr3Pipeline.deleteAllTextures();
            fsr3Pipeline.resetFrameGen();
        }
        if (fsr1Pipeline != null) {
            fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
        }
        rcasUpscaleCompiled = false;
        pipelineReady = false;
        jitterManager.reset();
        prevJitterX = 0.0f;
        prevJitterY = 0.0f;
        fsr2FrameCount = 0;
        hasPrevFrame = false;
        sceneUpscaled = false;
        // Reset the Iris jitter-blind verdict so a toggle always restores
        // full temporal accumulation.
        irisJitterBlind = false;
        irisJitterProbeDone = false;
        irisProbeArmed = false;
        irisProbeLocChosen = false;
        irisProbeAttempts = 0;
        irisProbeBlindVotes = 0;
        irisProbeBlock0 = null;
        fgFlipState = false;
        fgSecondPresentPending = false;
        fgInSecondPresent = false;
        fgExtraGenFramesToPresent = 0;
        fgGeneratePending = false;
        fsr4AsyncPending = false;
        syncNativeDiagnostics();

        // VERSION-SWITCH DIAGNOSTIC: reset the post-dirty frame counter so the
        // dispatch-decision log fires for the first 60 frames after this switch.
        fsr4PostDirtyDiagFrames = 0;
        // DELIBERATE-SWITCH FAST INIT: record when the user changed settings so
        // the FSR4 init grace below uses the short 2s window instead of the
        // 25s load-storm one.
        fsr4SwitchConfigAtMs = System.currentTimeMillis();

        // DELIBERATE CHANGE RESETS THE FG PERMANENT-DISABLE LATCH (same
        // reasoning as applyAutoScale): the user changing the version/quality is
        // a retry, so let this rebuild re-run FG. Without this, a transition-
        // storm latch (noteD3D12RuntimeFailure -> fg4PermanentlyDisabled) made
        // FG_DX12 stick on FSR1 spatial for the rest of the session and showed
        // "Too many runtime failures" in the settings screen.
        fg4PermanentlyDisabled = false;
        fg4InitFailed = false;
        fg4InitFailCount = 0;
        fgRuntimeFailCount = 0;
        fgStallConsecutive = 0;
        // Same retry semantics for the FSR4 dispatch-failure latch (2026-08-30,
        // "runs then completely freezes" fix): a deliberate version switch is the
        // user retrying, so the next re-init must be allowed to run FSR4 again.
        fsr4HardDisabled = false;
        fsr4DispatchFailCount = 0;
        // The AMD-iGPU FG-disable latch (same retry semantics — a deliberate
        // change re-evaluates; only FG_DX12 + iGPU re-disables it).
        fgIgpuUnsupported = false;
        fgIgpuUnsupportedNotified = false;
        // CRITICAL: tear down FSR4/FG DX12 contexts so a version switch
        // (e.g. FSR4 -> FSR3_FG_DX12 in the settings screen) actually
        // re-initializes the correct pipeline. Without this, needsRecreate
        // stays false (same dims, still initialized) and the newly selected
        // FG mode never starts — the old FSR4 path keeps running instead.
        //
        // PARK-INSTEAD-OF-TEARDOWN (2026-08-25): when switching AWAY from the
        // D3D12 modes (FSR4/FG_DX12) to FSR1/2/3, PARK the SDK context + pool
        // (keep alive, unused) instead of destroying them — a re-select at the
        // same size unparks with ZERO cross-API work (the expensive +
        // crash-prone part: pool allocate + external-memory imports +
        // ffxCreateContext). The unpark size check + pool-type check live in
        // unparkFsr4Context(); when the size changed while parked it tears the
        // parked context down and the init path re-creates fresh.
        //
        // Note the LIVE-context teardown below only fires for a genuinely live
        // context (fsr4Initialized): a PARKED context (fsr4Parked) is left
        // alone on a switch BACK to FSR4 so the init block unparks it — tearing
        // it down here would defeat the park. A switch WITHIN the D3D12 family
        // (FSR4 <-> FSR3_FG_DX12) tears down the LIVE context so the newly
        // selected FG mode actually starts.
        boolean d3d12Selected = isFsr4() || isFrameGenDx12();
        if (d3d12Selected) {
            if (fg4Initialized) {
                Fg4Native.nativeShutdown();
                fg4Initialized = false;
            }
            if (fsr4Initialized) {
                Fsr4Native.shutdownFsr4();
                fsr4Initialized = false;
                fsr4AsyncPending = false;
                VulkanModCompat.fsr4VkModActive = false;
                if (sharedTexMgr != null) {
                    sharedTexMgr.destroyFsr4Pool();
                }
            }
            // fsr4Parked (returning to FSR4): leave parked — the init block
            // unparks (size match) or tears down + re-inits (size changed).
        } else if (fsr4Initialized || fg4Initialized || fsr4Parked || fg4Parked) {
            // Switching away to FSR1/2/3: park (keep context + pool alive).
            parkFsr4Context();
        }
        fsr4Available = false;
    }

    public void destroy() {
        fgSecondPresentPending = false;
        fgInSecondPresent = false;
        fgExtraGenFramesToPresent = 0;
        fgGeneratePending = false;
        if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
        proxyTarget = null;
        if (rcasUpscaleProg != -1) { GL43C.glDeleteProgram(rcasUpscaleProg); rcasUpscaleProg = -1; }
        if (rcasUpscaleTex != -1) { GL43C.glDeleteTextures(rcasUpscaleTex); rcasUpscaleTex = -1; }
        if (rcasUpscaleVao != -1) { GL43C.glDeleteVertexArrays(rcasUpscaleVao); rcasUpscaleVao = -1; }
        if (rcasUpscaleFbo != -1) { GL43C.glDeleteFramebuffers(rcasUpscaleFbo); rcasUpscaleFbo = -1; }
        if (fsr2Pipeline != null) fsr2Pipeline.destroy();
        if (fsr3Pipeline != null) fsr3Pipeline.destroy();
        if (fsr1Pipeline != null) fsr1Pipeline.destroy();
        if (fsr4StagingDepth != -1) { GL43C.glDeleteTextures(fsr4StagingDepth); fsr4StagingDepth = -1; }
        if (fsr4StagingMv != -1) { GL43C.glDeleteTextures(fsr4StagingMv); fsr4StagingMv = -1; }
        if (fsr4StagingReactive != -1) { GL43C.glDeleteTextures(fsr4StagingReactive); fsr4StagingReactive = -1; }	        if (fsr4ScCurrTex != -1) { GL43C.glDeleteTextures(fsr4ScCurrTex); fsr4ScCurrTex = -1; }
	        if (fsr4ScPrevTex != -1) { GL43C.glDeleteTextures(fsr4ScPrevTex); fsr4ScPrevTex = -1; }
	        if (fsr4PrevColorTex != -1) { GL43C.glDeleteTextures(fsr4PrevColorTex); fsr4PrevColorTex = -1; }
        // CRITICAL: Shutdown order matters for D3D12 resource lifetime:
        //   1. Destroy FSR4 SDK context (ffxDestroyContext)
        //   2. Release D3D12 shared resources (pool textures)
        //   3. Dispose D3D12 device (must be LAST — pool textures are
        //      child resources; releasing them after device destruction
        //      accesses freed memory and crashes with 0xC0000409)
        // teardownFsr4Context() also tears down a PARKED context (2026-08-25)
        // — a context kept alive by the park-on-switch-away feature must not
        // leak at mod shutdown, and the pool must not be released under it.
        teardownFsr4Context();
        if (sharedTexMgr != null) {
            sharedTexMgr.destroy();
        }
        sharedTexMgr = null;
        if (Fsr4Native.isAvailable()) {
            Fsr4Native.disposeD3D12();
        }
        // CRITICAL: Terminate the Vulkan device/instance EXPLICITLY here, while the
        // JVM/Fabric shutdown hook is still running and fsrvk.dll is still loaded.
        // If we leave Vulkan teardown to the DLL's static destructors (VulkanDevice
        // singleton), it runs during DLL unload / JVM teardown on a partially-torn-
        // down driver, which crashes the process at exit (EXCEPTION_ACCESS_VIOLATION
        // / 0xC0000409). terminate() is SEH-guarded and a safe no-op when Vulkan was
        // never initialized (e.g. pure-D3D12 FSR4 path). Must also always run before
        // the JVM unloads fsrvk.dll.
        if (VulkanInterop.isAvailable()) {
            VulkanInterop.destroyExternalImages();
            VulkanInterop.terminate();
        }
        sharedMemoryActive = false;
    }

    private boolean isIrisActive() {
        return IrisCompat.isShaderPackInUse();
    }

    private boolean ensureUpscaleShaders() {
        if (rcasUpscaleCompiled) return true;
        if (rcasUpscaleProg != -1) { GL43C.glDeleteProgram(rcasUpscaleProg); rcasUpscaleProg = -1; }

        String vertexSrc = "#version 430 core\nvoid main(){int x=gl_VertexID&1;int y=(gl_VertexID>>1)&1;gl_Position=vec4(float(x)*2.0-1.0,float(y)*2.0-1.0,0.0,1.0);}";
        String fsSrc = "#version 430 core\nuniform sampler2D uInput;uniform ivec2 uInputSize;uniform ivec2 uOutputSize;uniform float uSharpness;layout(location=0)out vec4 fragColor;void main(){ivec2 p=ivec2(gl_FragCoord.xy);if(p.x>=uOutputSize.x||p.y>=uOutputSize.y){discard;}vec2 outUv=(vec2(p)+0.5)/vec2(uOutputSize);vec2 inPx=1.0/vec2(uInputSize);vec3 c=textureLod(uInput,outUv,0.0).rgb;if(uSharpness<0.001){fragColor=vec4(c,1.0);return;}vec3 t=textureLod(uInput,outUv+vec2(0,-1)*inPx,0.0).rgb;vec3 b=textureLod(uInput,outUv+vec2(0,1)*inPx,0.0).rgb;vec3 l=textureLod(uInput,outUv+vec2(-1,0)*inPx,0.0).rgb;vec3 r=textureLod(uInput,outUv+vec2(1,0)*inPx,0.0).rgb;vec3 d=max(abs(t-c),abs(b-c));d=max(d,abs(l-c));d=max(d,abs(r-c));float dMax=max(d.r,max(d.g,d.b));if(dMax<1e-7){fragColor=vec4(c,1.0);return;}float sharp=float(int(uSharpness*7.0+0.5))/8.0;vec3 w=clamp(1.0-sharp*d/dMax,0.0,1.0);vec3 sharpend=(c*4.0+t+b+l+r)*(1.0/8.0);sharpend=c+w*(sharpend-c);fragColor=vec4(clamp(sharpend,0.0,1.0),1.0);}";

        int vs = GL43C.glCreateShader(GL43C.GL_VERTEX_SHADER);
        GL43C.glShaderSource(vs, vertexSrc);
        GL43C.glCompileShader(vs);
        if (GL43C.glGetShaderi(vs, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            GL43C.glDeleteShader(vs);
            rcasUpscaleCompiled = false;
            return false;
        }

        int fs = GL43C.glCreateShader(GL43C.GL_FRAGMENT_SHADER);
        GL43C.glShaderSource(fs, fsSrc);
        GL43C.glCompileShader(fs);
        if (GL43C.glGetShaderi(fs, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            GL43C.glDeleteShader(vs);
            GL43C.glDeleteShader(fs);
            rcasUpscaleCompiled = false;
            return false;
        }

        rcasUpscaleProg = GL43C.glCreateProgram();
        GL43C.glAttachShader(rcasUpscaleProg, vs);
        GL43C.glAttachShader(rcasUpscaleProg, fs);
        GL43C.glLinkProgram(rcasUpscaleProg);
        if (GL43C.glGetProgrami(rcasUpscaleProg, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
            GL43C.glDeleteProgram(rcasUpscaleProg);
            rcasUpscaleProg = -1;
        }
        GL43C.glDeleteShader(vs);
        GL43C.glDeleteShader(fs);

        rcasUpscaleCompiled = rcasUpscaleProg != -1;
        if (rcasUpscaleCompiled) {
            if (rcasUpscaleVao == -1) rcasUpscaleVao = GL43C.glGenVertexArrays();
            if (rcasUpscaleFbo == -1) rcasUpscaleFbo = GL43C.glGenFramebuffers();
        }
        return rcasUpscaleCompiled;
    }

    // FSR4 scratch staging textures (MANAGED, non-shared). The depth/MV/reactive
    // compute shaders write into these instead of the D3D12-imported shared
    // textures, then glCopyImageSubData stages them into the shared textures.
    // Evidence (SHARE_VERIFY): imageStore into the imported (external-memory)
    // GL texture does NOT persist to the memory the D3D12 device reads — depth
    // written as 0.5 read back as 0.0 through every readback path — while the
    // color texture, written via glCopyImageSubData, works. So we route ALL
    // FSR4 inputs through the proven glCopyImageSubData path.
    private int fsr4StagingDepth = -1;   // R32F
    private int fsr4StagingMv = -1;      // R16G16F
    private int fsr4StagingReactive = -1; // R8
    // Shading-change history (tile luminance ratio) — RG8, non-shared (MV-gen only)
    private int fsr4ScCurrTex = -1;      // RG8
    private int fsr4ScPrevTex = -1;      // RG8
    private int fsr4StagingW, fsr4StagingH;
    // Pool pointer whose constant-0.5 depth has already been filled into the
    // shared depth texture (see the once-only fill in onSceneRendered).
    private long fsr4DepthFilledForPool;

    /**
     * Ensure the staging textures exist for the current render dimensions.
     * Returns true if all are valid.
     */
    private boolean ensureFsr4Staging() {
        if (fsr4StagingW == scaledWidth && fsr4StagingH == scaledHeight
            && fsr4StagingDepth != -1 && fsr4StagingMv != -1 && fsr4StagingReactive != -1
            && fsr4ScCurrTex != -1 && fsr4ScPrevTex != -1) {
            return true;
        }
        if (fsr4StagingDepth != -1) GL43C.glDeleteTextures(fsr4StagingDepth);
        if (fsr4StagingMv != -1) GL43C.glDeleteTextures(fsr4StagingMv);
        if (fsr4StagingReactive != -1) GL43C.glDeleteTextures(fsr4StagingReactive);
        if (fsr4ScCurrTex != -1) GL43C.glDeleteTextures(fsr4ScCurrTex);
        if (fsr4ScPrevTex != -1) GL43C.glDeleteTextures(fsr4ScPrevTex);
        fsr4StagingDepth = fsr4StagingMv = fsr4StagingReactive = -1;
        fsr4ScCurrTex = fsr4ScPrevTex = -1;

        if (scaledWidth < 16 || scaledHeight < 16) return false;

        fsr4StagingDepth = GL43C.glGenTextures();
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4StagingDepth);
        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_R32F, scaledWidth, scaledHeight);

        fsr4StagingMv = GL43C.glGenTextures();
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4StagingMv);
        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RG16F, scaledWidth, scaledHeight);

        fsr4StagingReactive = GL43C.glGenTextures();
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4StagingReactive);
        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_R8, scaledWidth, scaledHeight);

        fsr4ScCurrTex = GL43C.glGenTextures();
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4ScCurrTex);
        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RG8, scaledWidth, scaledHeight);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);	        fsr4ScPrevTex = GL43C.glGenTextures();
	        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4ScPrevTex);
	        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RG8, scaledWidth, scaledHeight);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);

	        // T&C auto-reactive prev-color texture (2026-08-06): RGBA8 copy of the
	        // scene color taken AFTER each MV-gen dispatch (Java: glCopyImageSubData
	        // from renderTex). Sampled by binding 6 at the reprojected position to
	        // detect content that moves independently of the camera. NEAREST filter
	        // — the shader uses explicit texelFetch at integer offsets, and the
	        // 5x5 search window does its own interpolation.
	        fsr4PrevColorTex = GL43C.glGenTextures();
	        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4PrevColorTex);
	        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RGBA8, scaledWidth, scaledHeight);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
	        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);

	        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
	        // Zero the shading-change history so the first frame reads prevRatio=0
	        // instead of garbage (avoids a one-frame reactive spike).
	        GL44C.glClearTexImage(fsr4ScCurrTex, 0, GL43C.GL_RG, GL43C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
	        GL44C.glClearTexImage(fsr4ScPrevTex, 0, GL43C.GL_RG, GL43C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
	        // Clear prev-color to zero with a NULL data pointer (2026-08-06 crash
	        // fix): glClearTexImage with a NON-null ByteBuffer treats the pointer
	        // as a GL_PIXEL_UNPACK_BUFFER offset — at world-load Minecraft binds a
	        // PBO for chunk texture uploads, so the heap address of a wrapped byte
	        // array becomes a garbage offset and atio6axx.dll faults with
	        // EXCEPTION_ACCESS_VIOLATION (NULL+0x10) inside the call. All sibling
	        // clears pass null; this one must too. The content is never read
	        // before the per-frame copy populates it anyway (uTCActive=0 until
	        // hasPrevFrame, and the copy runs every FSR4 frame after MV-gen).
	        GL44C.glClearTexImage(fsr4PrevColorTex, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE,
	            (java.nio.ByteBuffer) null);
	        fsr4StagingW = scaledWidth;
	        fsr4StagingH = scaledHeight;
	        return true;
	    }	    private boolean ensureDepthCopyShader() {
	        if (depthCopyCompiled) return true;
	        if (depthCopyProg != 0) { GL43C.glDeleteProgram(depthCopyProg); depthCopyProg = 0; }
	        depthCopyProg = ShaderBinaryCache.compileCompute(DEPTH_COPY_CS);
	        if (depthCopyProg == 0) {
	            FSRMod.LOGGER.error("FSR4 depth copy shader compilation failed — keeping constant depth");
	            fsr4RealDepthAvailable = false;
	            fsr4DepthProbeDone = true;
	            return false;
	        }
	        dc_uRenderSize = GL43C.glGetUniformLocation(depthCopyProg, "uRenderSize");
	        dc_uReversedZ = GL43C.glGetUniformLocation(depthCopyProg, "uReversedZ");
	        depthCopyCompiled = true;
	        return true;
	    }

	    /**
     * One-shot REAL-DEPTH probe (2026-08-06, "pixelated when moving" fix):
     * samples the RenderTarget's depth texture at 9 points over the first few
     * frames. Called from the FSR4 AND the FSR2/FSR3 dispatch sites — the
     * result gates ALL THREE pipelines' depth use (FSR2/3 DEPTH_CLIP +
     * ACCUMULATE read uUseDepth from this). fsr4DepthProbeFrame is per-CALL,
     * not per-frame, so with up to 2 sites/frame the frame-2/150 probes fire
     * on whichever call reaches them first — harmless for a one-shot
     * readback of the just-rendered frame. The historical "depth reads 0" finding predates the
	     * readback fixes (FBO-incomplete depth attach + big-endian buffers) and
	     * the VKMod path later PROVED 26.1.2 writes the framebuffer depth — so
	     * re-probe properly. Decision rules:
	     *   - unreadable / zero spread (min≈max, incl. all-0 or all-1) → depth is
	     *     genuinely unwritten or cleared → stay on constant mid-depth (safe);
	     *   - sky (top rows) ≈ 1, terrain (bottom) ≈ 0 → NON-REVERSED;
	     *   - sky ≈ 0, terrain ≈ 1 → REVERSED-Z (converted by DEPTH_COPY_CS).
	     */
    /**
     * BLACK-INPUT SANITY CHECK: verifies the scene color actually reached the
     * D3D12-imported shared texture (the cross-API copy path). Voxy's async GL
     * work can race these copies and make them silently fail — the shared
     * texture stays black while the scene has content, and FSR4's output
     * freezes. Returns true when the failure streak just crossed the threshold
     * (FSR4 hard-disabled — caller should skip this frame's dispatch and let
     * the FSR1 spatial fallback take over next frame).
     */
    private boolean checkFsr4SharedInput(int renderTex) {
        if (sharedTexMgr == null || sharedTexMgr.getColorTexId() == 0 || renderTex == -1) return false;
        int cx = scaledWidth / 2, cy = scaledHeight / 2;
        // Scene center (managed texture — reliable readback).
        float[] scenePx = readTexPixels(renderTex, cx, cy, GL43C.GL_RGBA, GL43C.GL_FLOAT, 4);
        if (scenePx == null) return false;
        // Shared color center via the managed verify-copy — direct glReadPixels of
        // external-memory textures lies on this driver (the SHARE_VERIFY history).
        int verify = GL43C.glGenTextures();
        try {
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, verify);
            GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RGBA8, scaledWidth, scaledHeight);
            GL43C.glCopyImageSubData(sharedTexMgr.getColorTexId(), GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                verify, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0, scaledWidth, scaledHeight, 1);
            float[] sharedPx = readTexPixels(verify, cx, cy, GL43C.GL_RGBA, GL43C.GL_FLOAT, 4);
            if (sharedPx == null) return false;
            float sceneLuma = 0.2126f * scenePx[0] + 0.7152f * scenePx[1] + 0.0722f * scenePx[2];
            float sharedLuma = 0.2126f * sharedPx[0] + 0.7152f * sharedPx[1] + 0.0722f * sharedPx[2];
            if (sceneLuma > 0.03f && sharedLuma < 0.004f) {
                if (++fsr4SharedBlackStreak >= FSR4_SHARED_BLACK_MAX) {
                    fsr4HardDisabled = true;
                    fsr4SharedBlackStreak = 0;
                    if (shouldDebug()) {
                        FSRMod.LOGGER.warn(String.format(
                            "FSR4: shared input read black for %d consecutive checks while the scene has content (sceneLuma=%.2f sharedLuma=%.4f) — cross-API copies failing (Voxy GL conflict?). Disabling FSR4 for this session (FSR1).",
                            FSR4_SHARED_BLACK_MAX, sceneLuma, sharedLuma));
                    }
                    notifyFsr4Fallback("FSR4 disabled for this session (shared input black — likely a Voxy GL conflict) - FSR1 active");
                    return true;
                }
            } else {
                fsr4SharedBlackStreak = 0;
            }
            return false;
        } finally {
            GL43C.glDeleteTextures(verify);
        }
    }

    /**
     * Scene-depth capture probe (2026-08-30, "add a runtime capture probe" —
     * vanilla-GL jagged-diagonals diagnosis). probeFsr4Depth only ever sees the
     * SCALED proxy (renderTarget) depth texture, which reads uniform-far on the
     * vanilla path — that latches constant-mid-depth MVs and leaves diagonals
     * jagged. This probe runs at the very top of onSceneRendered (right after
     * the world render) and compares TWO depth sources to locate the real one
     * in the ACTUAL 1.21.11 GL renderer (the in-tree net/minecraft sources are
     * a 26.x FrameGraph decompile and do NOT match the build):
     *   1. the CURRENT bound GL_DRAW_FRAMEBUFFER — whatever the world actually
     *      rendered depth into this frame (read raw via glReadPixels, format-
     *      agnostic, captures the live FBO attachment);
     *   2. the proxy depth texture glId (same texture probeFsr4Depth reads via
     *      readTexPixels) for correlation.
     * Logs both grids' min/max/sky/terrain + the render viewport and FBO sizes.
     * Decides: if the LIVE FBO holds real depth (wide span, a near sample) but
     * the proxy depth is uniform-far, the vanilla world renders into a separate
     * depth surface → real depth is capture-able via a per-frame depth blit/copy
     * into the proxy depth (or direct MV-gen feed). If BOTH read uniform-far,
     * the world genuinely writes no reachable depth on vanilla. Debug-only: a
     * depth glReadPixels is the same ~26ms pipeline flush as the other probes.
     * Run on camera/still-nature cadence frames: first frames + every 240.
     */
    private static String fmt(float f) {
        return Float.isNaN(f) ? "NaN" : String.format(java.util.Locale.ROOT, "%.3f", f);
    }

    // NaN-safe average of up to 3 values (fail-soft for the proxy grid reads).
    private static float avg3(float a, float b, float c) {
        float[] vs = { a, b, c };
        float s = 0.0f; int n = 0;
        for (float v : vs) { if (!Float.isNaN(v)) { s += v; n++; } }
        return n == 0 ? Float.NaN : s / n;
    }

    private void probeSceneDepth() {
        if (config.debugMode <= 0) return;
        if (renderTarget == null) return;
        // CRASH HARDENING (2026-08-30, 0xC0000005 during FSR1 world-load):
        // reading the LIVE draw-FBO's depth via glReadPixels mid-GPU-saturation
        // faults this AMD iGPU's GL driver (the atio6axx.dll class) — a native
        // fault try/catch cannot catch. The prior build fired at frames 2-6, i.e.
        // inside the world-load storm (log: FSR1 init at frame #323, no
        // [SCENE-DEPTH] line ever printed = the first point read faulted).
        //
        // Never probe until the world has been joined for >= 20s (the load storm
        // has fully drained — the established-world gate the FSR4 init path
        // already trusts), and only on a sparse cadence after that.
        long joined = worldJoinedAtMs;
        if (joined == 0 || System.currentTimeMillis() - joined < 20000L) return;
        if (fsr2FrameCount < 180) {
            // Two settling samples after the world is established, then settle.
            if (fsr2FrameCount != 180 && fsr2FrameCount != 240) return;
        } else if (fsr2FrameCount % 600 != 0) {
            // Thereafter every 600th frame (~10s).
            return;
        }
        try {
            int prevReadFb = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
            try {
                int drawFb = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);
                // The default framebuffer (0) depth cannot be safely read back on
                // every GL driver — skip rather than risk a second fault. We need
                // the world's real FBO depth, which binds non-zero when the world
                // renders into an offscreen target (the proxy path).
                if (drawFb <= 0) {
                    FSRMod.LOGGER.info("[SCENE-DEPTH] skip frame#{} — drawFb={} (default framebuffer depth not safely readable)", fsr2FrameCount, drawFb);
                    return;
                }
                // Only single-sampled FBOs support direct depth glReadPixels;
                // a multisample depth needs a resolve blit first (skip for now).
                if (GL43C.glGetFramebufferAttachmentParameteri(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                        GL43C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL43C.GL_NONE) {
                    FSRMod.LOGGER.info("[SCENE-DEPTH] skip frame#{} — live FBO {} has no depth attachment", fsr2FrameCount, drawFb);
                    return;
                }
                int[] vp = new int[4];
                GL43C.glGetIntegerv(GL43C.GL_VIEWPORT, vp);  // x, y, w, h of world render
                int vw = Math.max(1, vp[2]);
                int vh = Math.max(1, vp[3]);
                int depthTex = renderTarget != null && renderTarget.getDepthTexture() != null
                    ? ((GlTexture) renderTarget.getDepthTexture()).glId() : -1;

                // This readback is a full pipeline flush — keep it out of the
                // GPU-saturation guards (same rule as the terrain probe).
                diagnosticReadbackThisFrame = true;

                // 1) Live draw-FBO depth via glReadPixels (captures the real
                // depth attachment the world wrote, regardless of its format).
                GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, drawFb);
                float[] rbk = new float[9];
                for (int i = 0; i < 9; i++) {
                    int px = (int) (vw * (0.15 + 0.35 * (i % 3)));
                    int py = (int) (vh * (0.15 + 0.35 * (i / 3)));
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(4)
                        .order(java.nio.ByteOrder.nativeOrder());
                    GL43C.glReadPixels(px, py, 1, 1, GL43C.GL_DEPTH_COMPONENT, GL43C.GL_FLOAT, buf);
                    if (GL43C.glGetError() != GL43C.GL_NO_ERROR) { break; }
                    rbk[i] = buf.getFloat(0);
                }

                // 2) Proxy depth texture grid for correlation.
                float[] prx = new float[9];
                for (int i = 0; i < 9; i++) {
                    if (depthTex == -1) { prx[i] = Float.NaN; continue; }
                    int px = (int) (scaledWidth * (0.15 + 0.35 * (i % 3)));
                    int py = (int) (scaledHeight * (0.15 + 0.35 * (i / 3)));
                    float[] v = readTexPixels(depthTex, px, py, GL43C.GL_RED, GL43C.GL_FLOAT, 1);
                    prx[i] = (v == null) ? Float.NaN : v[0];
                }

                float minL = 1e9f, maxL = -1e9f;
                for (float v : rbk) { if (!Float.isNaN(v)) { minL = Math.min(minL, v); maxL = Math.max(maxL, v); } }
                float minP = 1e9f, maxP = -1e9f;
                for (float v : prx) { if (!Float.isNaN(v)) { minP = Math.min(minP, v); maxP = Math.max(maxP, v); } }

                float lSky = avg3(rbk[0], rbk[1], rbk[2]);
                float lTer = avg3(rbk[6], rbk[7], rbk[8]);
                float pSky = avg3(prx[0], prx[1], prx[2]);
                float pTer = avg3(prx[6], prx[7], prx[8]);

                FSRMod.LOGGER.info("[SCENE-DEPTH] frame#{} viewport={}x{} drawFb={} proxy={}x{} | LIVE span {} (min={} max={} sky={} ter={}) | PROXY span {} (min={} max={} sky={} ter={})",
                    fsr2FrameCount, vw, vh, drawFb,
                    renderTarget != null ? renderTarget.width : -1,
                    renderTarget != null ? renderTarget.height : -1,
                    fmt(maxL - minL), fmt(minL), fmt(maxL), fmt(lSky), fmt(lTer),
                    fmt(maxP - minP), fmt(minP), fmt(maxP), fmt(pSky), fmt(pTer));
                FSRMod.LOGGER.info("[SCENE-DEPTH] live samples {} {} {} {} {} {} {} {} {}",
                    fmt(rbk[0]), fmt(rbk[1]), fmt(rbk[2]),
                    fmt(rbk[3]), fmt(rbk[4]), fmt(rbk[5]),
                    fmt(rbk[6]), fmt(rbk[7]), fmt(rbk[8]));
            } finally {
                GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFb);
            }
        } catch (Throwable t) {
            FSRMod.LOGGER.info("[SCENE-DEPTH] probe failed: {}", t.toString());
        }
    }

    private void probeFsr4Depth(int depthTex) {
        if (fsr4DepthProbeDone || depthTex == -1) return;
        fsr4DepthProbeFrame++;
        // Probe TWICE: at frame 2 (depth settled after the first frames) and —
        // if the first attempt was rejected — once more at frame ~150 (~2.5s),
        // so a machine that genuinely writes depth self-heals when the world
        // was still loading (sky-dominated frame) at the first probe. A second
        // rejection or a readback failure latches constant mid-depth forever.
        boolean firstProbe = fsr4DepthProbeFrame == 2;
        boolean retryProbe = fsr4DepthProbeFrame == 150;
        if (!firstProbe && !retryProbe) return;
        float[] vals = new float[9];
        for (int i = 0; i < 9; i++) {
            int px = (int) (scaledWidth * (0.15 + 0.35 * (i % 3)));
            int py = (int) (scaledHeight * (0.15 + 0.35 * (i / 3)));
            float[] v = readTexPixels(depthTex, px, py, GL43C.GL_RED, GL43C.GL_FLOAT, 1);
            if (v == null) {
                FSRMod.LOGGER.info("FSR4 depth probe: readback failed — keeping constant mid-depth MVs");
                fsr4DepthProbeDone = true;
                return;
            }
            vals[i] = v[0];
        }
        float min = 1e9f, max = -1e9f, sum = 0.0f;
        for (float v : vals) { min = Math.min(min, v); max = Math.max(max, v); sum += v; }
        float topAvg = (vals[0] + vals[1] + vals[2]) / 3.0f;      // sky rows
        float botAvg = (vals[6] + vals[7] + vals[8]) / 3.0f;      // terrain rows
        // STRICT REALITY GATE (2026-08-06, "certain terrain parts look
        // static/shimmery while standing still" fix): the old 0.05 span
        // threshold was FAR too lenient — the 16:29 session's buffer measured
        // min=0.968 max=1.000 (mean=0.989, i.e. 98.9% at the far/clear plane)
        // and STILL enabled uUseDepth=1. MC 26.1.2's GL RenderTarget depth
        // texture is essentially never written (documented in AGENTS.md), so
        // that "variation" was a sky-vs-clear sliver. The SDK then received
        // depth ≈0/1 everywhere — broken disocclusion, AND the T&C
        // reprojection trust gate (bypassed when uUseDepth=1) fired reactive on
        // STATIC terrain because prevUv reprojected to garbage positions →
        // terrain regions turned spatial (the report). Real geometry spans the
        // whole range in either convention (reversed: sky≈0/near≈1; non-
        // reversed: near≈0/far≈1), so require (a) a real span >= 0.30 AND
        // (b) at least one sample meaningfully BELOW the far plane
        // (min < 0.90) — an empty buffer (all ≈1.0) fails both. Constant
        // mid-depth remains the proven-stable fallback.
        if (max - min < 0.30f || min > 0.90f) {
            FSRMod.LOGGER.info("FSR4 depth probe: no real geometry (min={} max={}) — using constant mid-depth MVs{}",
                String.format(java.util.Locale.ROOT, "%.3f", min),
                String.format(java.util.Locale.ROOT, "%.3f", max),
                firstProbe ? " (one re-probe in ~2.5s)" : " (latched)");
            // Latch only on the retry — the first rejection may be a world-load
            // artifact (mostly-sky frame), so allow the one re-probe above.
            if (!firstProbe) fsr4DepthProbeDone = true;
            // FG-without-depth warning (2026-08-13): FG's disocclusion runs
            // entirely on depth (the FSR 4.0.1 prepare API has no reactive/
            // disocclusion input to compensate), so with a uniform depth buffer
            // FG interpolates across object edges → spikes during motion even
            // though the FSR4 base frames stay smooth. Tell the user once.
            if (isFrameGenDx12() && !fgNoDepthWarned) {
                fgNoDepthWarned = true;
                FSRMod.LOGGER.warn("FG_DX12: no real depth available on the GL path — FG interpolates across object edges (edge spikes during motion). Plain FSR4 does not have this artifact; the spikes are the FG interpolated frames, not the FSR4 upscale.");
            }
            return;
        }
	        // Non-reversed: sky(far)≈1 > terrain(near)≈0. Reversed: sky(far)≈0 < terrain≈1.
	        fsr4DepthReversed = topAvg < botAvg;
	        fsr4RealDepthAvailable = true;
	        fsr4DepthProbeDone = true;
	        FSRMod.LOGGER.info("FSR4 depth probe: REAL per-pixel depth (reversed={}, sky={} terrain={}, mean={}) — enabling uUseDepth=1",
	            fsr4DepthReversed,
	            String.format(java.util.Locale.ROOT, "%.3f", topAvg),
	            String.format(java.util.Locale.ROOT, "%.3f", botAvg),
	            String.format(java.util.Locale.ROOT, "%.3f", sum / 9.0f));
	    }

	    private void copyDepthToShared(int srcDepthTex, int dstDepthTex) {
	        // REAL-DEPTH path (2026-08-06, probeFsr4Depth auto-detect): convert the
	        // RenderTarget depth (any format/convention) to NON-REVERSED 0=near,
	        // 1=far in the MANAGED staging R32F via DEPTH_COPY_CS, then
	        // glCopyImageSubData into the shared D3D12-visible R32F (the only write
	        // path proven to persist on this driver — see the constant path below).
	        // Runs EVERY frame (depth changes). The caller must issue a
	        // GL_TEXTURE_UPDATE_BARRIER_BIT before MV-gen reads the shared depth.
	        if (fsr4RealDepthAvailable) {
	            if (!ensureFsr4Staging() || !ensureDepthCopyShader()) return;
	            // Order the world render's depth writes before the compute reads them.
	            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
	            GL43C.glUseProgram(depthCopyProg);
	            GL43C.glUniform2i(dc_uRenderSize, scaledWidth, scaledHeight);
	            GL43C.glUniform1i(dc_uReversedZ, fsr4DepthReversed ? 1 : 0);
	            GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
	            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, srcDepthTex);
	            GL43C.glBindImageTexture(1, fsr4StagingDepth, 0, false, 0,
	                GL43C.GL_WRITE_ONLY, GL43C.GL_R32F);
	            GL43C.glDispatchCompute((scaledWidth + 7) / 8, (scaledHeight + 7) / 8, 1);
	            // imageStore → glCopyImageSubData is ordered by GL command order (a
	            // copy is not a shader read), so no barrier is needed before the copy.
	            GL43C.glCopyImageSubData(
	                fsr4StagingDepth, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
	                dstDepthTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
	                scaledWidth, scaledHeight, 1);
	            return;
	        }

	        // CONSTANT-DEPTH fallback: the RenderTarget depth probe found no real
	        // content, so the FSR4 SDK receives a constant depth. The value must
	        // MATCH the MV-gen's constant reprojection depth — the two together
	        // define the world-depth assumption the SDK's temporal accumulator
	        // operates under.
	        //
	        // 2026-08-13 v2 ("edge smoothing present but weaker than the
	        // validated 10:55 baseline" fix): the motion-tracking experiment set
	        // the SDK depth to FAR (device 0.9995) to match the MV shader's far
	        // reprojection — but that re-triggered the ORIGINAL reason 0.5 was
	        // chosen (see the pre-experiment comment history): a constant FAR
	        // depth makes the FSR4 SDK classify the whole frame as sky/far-plane
	        // (its internal disocclusion/sky logic) and discard history → the
	        // temporal accumulation weakens → edges read softer/jagged than the
	        // validated baseline even with the still-camera MV gate (12:10)
	        // holding MVs at 0. Device 0.5 (== ndcZ 0, z≈1 block) is mid-enough
	        // that the SDK does NOT classify the frame as sky → full in-place
	        // accumulation → the validated-smooth behavior. The MV shader's
	        // constant reprojection STAYS far (ndcZ 0.999): the still-camera MV
	        // gate zeroes MVs whenever it matters (standing still), and during
	        // motion far reprojection keeps rotation MVs exact and translation
	        // parallax ~0 — the SDK's depth input and the MV reprojection only
	        // need to agree when the SDK would USE the depth (disocclusion),
	        // and constant depth disables that regardless of the value.
	        //
	        // Write path: fill the MANAGED staging R32F texture with an FBO clear
	        // (the most basic, guaranteed GL operation), then glCopyImageSubData it
	        // into the D3D12-imported shared texture — the ONLY write mechanism
	        // proven to persist to the memory the D3D12 device reads on this driver
	        // (the color texture works via this exact path; imageStore into the
	        // staging read back 0.0000 — [STAGING_VERIFY] — and glClearTexImage did
	        // not persist to the shared texture either — [SHARE_VERIFY] depthC=0.0).
	        if (!ensureFsr4Staging()) return;

	        if (depthClearFbo == 0) depthClearFbo = GL43C.glGenFramebuffers();
	        // Save/restore the draw-FBO binding — onSceneRendered runs right after the
	        // level render while Minecraft's draw FBO may be non-zero.
	        int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);
	        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, depthClearFbo);
	        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
	            GL43C.GL_TEXTURE_2D, fsr4StagingDepth, 0);
	        // MID-DEPTH constant (2026-08-13 v2 — restored from the validated
	        // baseline): device 0.5 == ndcZ 0 == z≈1 block. A FAR constant
	        // (0.9995, the 11:51 experiment value) made the FSR4 SDK classify the
	        // whole frame as sky/far-plane and discard history → weaker edge
	        // smoothing. The MV shader's reprojection stays far (ndcZ 0.999) but
	        // the still-camera MV gate zeroes MVs standing still, so the SDK's
	        // depth only needs to avoid the sky classification. See the comment
	        // above for the full rationale.
	        float[] clearDepth = {0.5f, 0.0f, 0.0f, 0.0f};
	        GL43C.glClearBufferfv(GL43C.GL_COLOR, 0, clearDepth);
	        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);

	        GL43C.glCopyImageSubData(
	            fsr4StagingDepth, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
	            dstDepthTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
	            scaledWidth, scaledHeight, 1);
	    }

	    private boolean ensureMvGenShader() {
        if (mvGenCompiled) return true;
        if (mvGenProg != 0) { GL43C.glDeleteProgram(mvGenProg); mvGenProg = 0; }

        mvGenProg = ShaderBinaryCache.compileCompute(MV_GEN_CS);
        if (mvGenProg == 0) {
            FSRMod.LOGGER.error("FSR4 MV gen shader compilation failed");
            return false;
        }

        mv_uRenderSize = GL43C.glGetUniformLocation(mvGenProg, "uRenderSize");
        mv_uInvCurrVP = GL43C.glGetUniformLocation(mvGenProg, "uInvCurrVP");
        mv_uPrevVP = GL43C.glGetUniformLocation(mvGenProg, "uPrevVP");
        mv_uJitter = GL43C.glGetUniformLocation(mvGenProg, "uJitter");
        mv_uPrevJitter = GL43C.glGetUniformLocation(mvGenProg, "uPrevJitter");
	        mv_uFarDepth = GL43C.glGetUniformLocation(mvGenProg, "uFarDepth");
	        mv_uDeviceToViewDepth = GL43C.glGetUniformLocation(mvGenProg, "uDeviceToViewDepth");
	        mv_uFarViewZ = GL43C.glGetUniformLocation(mvGenProg, "uFarViewZ");
	        mv_uUseDepth = GL43C.glGetUniformLocation(mvGenProg, "uUseDepth");
	        mv_uShadingThreshold = GL43C.glGetUniformLocation(mvGenProg, "uShadingThreshold");
	        mv_uTCActive = GL43C.glGetUniformLocation(mvGenProg, "uTCActive");
	        mv_uCamMotion = GL43C.glGetUniformLocation(mvGenProg, "uCamMotion");
	        mv_uCamReactive = GL43C.glGetUniformLocation(mvGenProg, "uCamReactive");
	        mv_uConstNdcZ = GL43C.glGetUniformLocation(mvGenProg, "uConstNdcZ");

	        // One-time diagnostic: uniform locations MUST all be valid (>= 0).
	        // A -1 here means the GLSL compiler optimized the uniform out and the
	        // setter silently no-ops — uRenderSize=0 would early-return every
	        // invocation and leave the staging MV/reactive textures at 0, exactly
	        // what [STAGING_VERIFY] has been showing.
	        if (!mvGenDiagLogged) {
	            mvGenDiagLogged = true;
	            FSRMod.LOGGER.info("FSR4 MV_GEN_CS uniforms: prog={} uRenderSize={} uInvCurrVP={} uPrevVP={} uJitter={} uPrevJitter={} uFarDepth={} uDeviceToViewDepth={} uFarViewZ={} uUseDepth={} uShadingThreshold={} uCamMotion={} uCamReactive={}",
	                mvGenProg, mv_uRenderSize, mv_uInvCurrVP, mv_uPrevVP, mv_uJitter,
	                mv_uPrevJitter, mv_uFarDepth, mv_uDeviceToViewDepth, mv_uFarViewZ, mv_uUseDepth, mv_uShadingThreshold, mv_uCamMotion, mv_uCamReactive);
	        }

	        mvGenCompiled = true;
        return true;
    }

	    private void generateFSR4MotionVectors(int depthTex, int mvTex, int reactiveTex, int colorTex) {
        if (!ensureMvGenShader()) return;
        // Write MV + reactive into MANAGED staging textures first, then
        // glCopyImageSubData them into the shared (D3D12-imported) textures.
        // Direct imageStore into the imported textures doesn't persist to the
        // D3D-visible memory (see ensureFsr4Staging), but the copy path does.
        if (!ensureFsr4Staging()) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        tmpQuat.set(camera.rotation()).conjugate();
        tmpView.identity()
            .rotation(tmpQuat)
            .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
        // CRITICAL: use the CLEAN (unjittered) projection for MV generation. The
        // scene renders jittered and the SDK receives jitterOffset separately, so
        // the MVs must carry ONLY true camera motion. Using lastProjJittered here
        // bakes the per-frame jitter delta into the MV — and since the SDK also
        // gets jitterOffset, the jitter is double-counted → history samples are
        // misplaced every frame → smearing during camera motion. (FSR2/3 GLSL
        // pipelines intentionally use jittered MVs and cancel the jitter in the
        // ACCUMULATE shader via uMotionVectorJitterCancellation — FSR4 has no
        // such GL pass, so it must receive clean MVs.)
        currViewProj.set(lastProj);
        currViewProj.mul(tmpView);

        float p22 = lastProj.m22();
	        float p23 = lastProj.m23();
	        float farDepth = (p22 > 0) ? 0.0f : 1.0f;
	        mvDeviceToViewDepth[0] = (1.0f - p22) * 0.5f;
	        mvDeviceToViewDepth[1] = -p23 * 0.5f;
	        mvDeviceToViewDepth[2] = -1.0f / lastProj.m00();
	        mvDeviceToViewDepth[3] = -1.0f / lastProj.m11();
	        mvFarViewZ = mvDeviceToViewDepth[1] / (farDepth - mvDeviceToViewDepth[0] + 1e-10f);

	        GL43C.glUseProgram(mvGenProg);
	        GL43C.glUniform2i(mv_uRenderSize, scaledWidth, scaledHeight);

        currViewProj.invert(mvTmpMat).get(mvMatBuf);
        GL43C.glUniformMatrix4fv(mv_uInvCurrVP, false, mvMatBuf);

        if (hasPrevFrame) {
            prevViewProj.get(mvMatBuf);
        } else {
            mvTmpMat.identity().get(mvMatBuf);
        }
        GL43C.glUniformMatrix4fv(mv_uPrevVP, false, mvMatBuf);

	        GL43C.glUniform2f(mv_uJitter, jitterX, jitterY);
	        GL43C.glUniform2f(mv_uPrevJitter, prevJitterX, prevJitterY);
	        GL43C.glUniform1f(mv_uFarDepth, farDepth);
	        GL43C.glUniform4fv(mv_uDeviceToViewDepth, mvDeviceToViewDepth);
	        GL43C.glUniform1f(mv_uFarViewZ, mvFarViewZ);
	        GL43C.glUniform1i(mv_uUseDepth, fsr4RealDepthAvailable ? 1 : 0);  // 1 = real per-pixel depth (probe-verified)
	        // EXPERIMENTAL near-depth motion MVs (2026-08-30): only read on the
	        // uUseDepth=0 constant-depth branch. When config.fsr4NearDepthMotion
	        // is on, reproject at ndcZ 0.0 (z≈1) to AGREE with the SDK's 0.5
	        // accumulation depth — walking terrain gets real parallax MVs instead
	        // of the ~0 far-depth parallax, so history is tracked and diagonals
	        // stay smooth during motion (sky smears on motion as the accepted
	        // tradeoff). Default 0.999 keeps the validated far baseline exactly.
	        GL43C.glUniform1f(mv_uConstNdcZ, config.fsr4NearDepthMotion ? 0.0f : 0.999f);
	        if (config.fsr4NearDepthMotion && !fsr4NearDepthLogged && !fsr4RealDepthAvailable) {
	            fsr4NearDepthLogged = true;
	            FSRMod.LOGGER.info("[NEAR-DEPTH] near-depth motion MVs ENABLED (ndcZ=0.0 ⇄ SDK depth 0.5) — terrain parallax tracked while walking; sky may smear on motion");
	        }
	        // When real depth is available (probeFsr4Depth found content), the depthTex
	        // bound on unit 0 IS the shared texture, normalized to non-reversed 0=near/1=far
	        // by DEPTH_COPY_CS every frame (see copyDepthToShared) — the shader's uUseDepth
	        // path reads it directly, so no convention math is needed in MV-gen. When the
	        // probe fell back to constant, uUseDepth=0 keeps the old mid-depth model and the
	        // shared depth stays 0.5.

	        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, colorTex);	        GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
	        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4ScPrevTex);
	        GL43C.glActiveTexture(GL43C.GL_TEXTURE6);
	        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fsr4PrevColorTex);
	        GL43C.glBindImageTexture(1, fsr4StagingMv, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG16F);
        GL43C.glBindImageTexture(2, fsr4StagingReactive, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);
        GL43C.glBindImageTexture(5, fsr4ScCurrTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);	        GL43C.glUniform1f(mv_uShadingThreshold, 0.5f);

	        // Real camera-motion gate for the cutout detector (2026-08-06, "static
	        // terrain parts while standing still"): the idle camera bob moves the
	        // EYE POSITION ~0.01-0.02 blocks/frame and rotates slightly, which the
	        // clean MV matrices include — so per-pixel MV magnitude alone (mvLen
	        // > 0.5) can't distinguish standing-still bob (0.5-2.5px MVs, [DEBUG_MV]
	        // verified) from real walking/turning. Measure the PLAYER camera motion
	        // between frames instead: eye-position delta > 0.05 blocks OR rotation
	        // delta > 0.01 rad (~0.6 deg — bob's pitch swing is ~0.002 rad, an
	        // order of magnitude below). Both are derived from the SAME camera
	        // state the view matrix is built from below, so if the MVs show motion
	        // the gate reflects it.
	        tmpQuat.set(camera.rotation());
	        float camRotDelta = Float.NaN;
	        if (Double.isNaN(prevCamQX)) {
	            fsr4CamMoving = true; // first frame — no history, be conservative
	        } else {
	            float dot = (float) Math.min(1.0, Math.max(-1.0,
	                prevCamQX * tmpQuat.x + prevCamQY * tmpQuat.y
	                + prevCamQZ * tmpQuat.z + prevCamQW * tmpQuat.w));
	            camRotDelta = (float) Math.acos(dot) * 2.0f; // quaternion angle between frames
	        }
	        prevCamQX = tmpQuat.x; prevCamQY = tmpQuat.y;
	        prevCamQZ = tmpQuat.z; prevCamQW = tmpQuat.w;
	        float camPosDelta = hasPrevFrame
	            ? (float) Math.sqrt(
	                (camPos.x - prevCamPosX) * (camPos.x - prevCamPosX)
	                + (camPos.y - prevCamPosY) * (camPos.y - prevCamPosY)
	                + (camPos.z - prevCamPosZ) * (camPos.z - prevCamPosZ))
	            : 0.0f;
	        if (!Float.isNaN(camRotDelta)) {
	            fsr4CamMoving = camPosDelta > 0.05f || camRotDelta > 0.01f;
	        }
	        prevCamPosX = camPos.x; prevCamPosY = camPos.y; prevCamPosZ = camPos.z;
	        GL43C.glUniform1f(mv_uCamMotion, fsr4CamMoving ? 1.0f : 0.0f);

	        // Camera-motion reactive floor (2026-08-13, "walking smear" fix —
	        // FSR2/3 uCamReactive parity, 0..0.9): the constant-depth MVs
	        // (z≈500) UNDERESTIMATE near terrain's true screen motion while
	        // walking, so the SDK would blend misaligned history at ~90% weight =
	        // smear + edge wobble. Floor the reactive mask on known motion so the
	        // blend shifts toward the de-jittered (stable) current frame.
	        // Translation term normalized to PER-TICK (FSR2/3's proven units:
	        // walk 0.216/tick → 0.30, sprint 0.28 → 0.42, elytra → 0.9 cap) via
	        // the measured frame time — raw per-frame deltas shrink at high FPS
	        // (walk = 0.072 blocks/frame at 60fps but 0.030 at 144fps, which
	        // would silently derate the floor). Rotation stays per-frame (FSR2/3
	        // parity: fires only on extreme turns >= 0.04 rad/frame; pans are
	        // depth-exact and covered by the MV ramp). Bob ≈ 0.01-0.02 blocks/frame
	        // → dPosTick ~0.03-0.06 < 0.05 threshold → floor 0 standing still =
	        // bit-identical to the pre-floor path (and the still-camera MV gate
	        // keeps accumulation untouched there).
	        if (!Float.isNaN(camRotDelta)) {
	            long motionNow = System.nanoTime();
	            float dtSec = (float) ((motionNow - fsr4PrevMotionNanos) / 1e9);
	            fsr4PrevMotionNanos = motionNow;
	            if (dtSec <= 0f || dtSec > 0.5f) dtSec = 1f / 60f; // first frame / stalls
	            float dPosTick = camPosDelta / (dtSec * 20f);
	            // 2026-08-13 v2 ("when I move fast then no upscaling happens"
	            // fix): the 0.9 cap + 0.04 rad/frame rotation threshold were
	            // too hot. Log-verified: reactive=0.90 with mv=(3.9,0.2) during
	            // fast motion — the MV ramp alone gives ~0.22 for a 3.9px MV, so
	            // the 0.90 was ENTIRELY the floor, and at 0.9 reactive the SDK
	            // keeps only ~10% history → pure spatial upscale → "no
	            // upscaling" (jagged, no temporal reconstruction).
	            //   • Cap 0.9 → 0.7: even elytra keeps 30% history; the SDK's
	            //     internal box clamp still rejects misaligned history, so
	            //     this costs a little smear margin at extreme speed for a
	            //     large edge-smoothing gain. Walk (0.30) and sprint (0.42)
	            //     floors are unchanged.
	            //   • Rotation threshold 0.04 → 0.10 rad/frame (5.7°/frame =
	            //     343°/s at 60fps): rotation MVs are depth-independent/EXACT
	            //     at constant depth, so a normal fast pan/flick was flooring
	            //     the whole screen to 0.9 even though the SDK could have
	            //     tracked it perfectly — only a genuine whip fires now; the
	            //     MV ramp covers ordinary fast pans (matches the FSR2/3
	            //     comment: "pans are depth-exact, MV ramp covers them").
	            // Standing still stays bit-identical: both terms are 0 when
	            // the camera is still (same deltas that drive uCamMotion).
	            fsr4CamReactive = (float) Math.min(0.7, Math.max(
	                (dPosTick - 0.05f) / 0.55f,
	                (camRotDelta - 0.10f) / 0.05f));
	            fsr4CamReactive = Math.max(0f, fsr4CamReactive);
	        } else {
	            fsr4CamReactive = 0f; // first frame — no delta history yet
	        }
	        GL43C.glUniform1f(mv_uCamReactive, fsr4CamReactive);

	        // T&C auto-reactive gate: only compare against prevColorTex when it
	        // holds a REAL, FRESH previous frame (hasPrevFrame AND the snapshot
	        // was updated this session — fsr4PrevColorFresh, cleared on every
	        // skip path). A stale prev-color (skip episode: pre-check spike,
	        // fence timeout, suspension) is from a different camera position;
	        // comparing against it fires reactive EVERYWHERE → the SDK discards
	        // ALL history → spatial output ("edge smoothing stopped"). On the
	        // first frame / after resume it is also stale — skip cleanly; the
	        // snapshot below re-freshens it for next frame.
	        GL43C.glUniform1i(mv_uTCActive, (hasPrevFrame && fsr4PrevColorFresh) ? 1 : 0);

	        GL43C.glDispatchCompute((scaledWidth + 7) / 8, (scaledHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        int mvDispatchErr = GL43C.glGetError();
        if (mvDispatchErr != GL43C.GL_NO_ERROR && timingFrameCount % 60 == 0) {
            FSRMod.LOGGER.warn(String.format("FSR4: MV_GEN_CS dispatch GL error 0x%04X", mvDispatchErr));
        }

        GL43C.glBindImageTexture(1, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG16F);
        GL43C.glBindImageTexture(2, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);
        GL43C.glBindImageTexture(5, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
        // ALSO unbind unit 0 (the shared depth texture was left bound here). If
        // FSR1's fallback dispatch runs next (suspension path), its RCAS image
        // dispatch binds rcasOutTex to image unit 1 while a sampler unit still
        // holds a D3D12-imported texture — AMD flags the sampler/image
        // coexistence at glDispatchCompute with GL_INVALID_OPERATION (0x502,
        // EASU permanently disabled at world-join). Leave zero sampler bindings.
        // NOTE: glActiveTexture(GL_TEXTURE0) MUST come BEFORE the bind — glBindTexture
        // targets the CURRENTLY active unit, so the reverse order would just re-unbind
        // unit 3 and leave the depth texture on unit 0 (a no-op "fix").
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);

        // Ping-pong shading-change history
        int scTmp = fsr4ScPrevTex;
        fsr4ScPrevTex = fsr4ScCurrTex;
        fsr4ScCurrTex = scTmp;

        // Stage MV and reactive into the shared textures via the proven copy path.
        GL43C.glCopyImageSubData(
            fsr4StagingMv, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            mvTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            scaledWidth, scaledHeight, 1);
        GL43C.glCopyImageSubData(
            fsr4StagingReactive, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            reactiveTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            scaledWidth, scaledHeight, 1);

	        // Debug: sample MV and depth at multiple pixels every 60 frames
	        if (shouldDebug()) {
	            // Make the MV compute writes visible before reading them back. The
	            // production memory barrier lives in the caller (onSceneRendered); the
	            // debug readback runs before that, so without its own barrier it reads
	            // stale zeros and falsely reports nonZero=0/9. Only runs every 60 frames.
	            GL43C.glMemoryBarrier(GL43C.GL_ALL_BARRIER_BITS);
	            // Save PBO bindings and unbind — Minecraft 1.21.4 may bind pixel
	            // buffers for texture upload/download, which corrupts our
	            // glTexSubImage2D and glReadPixels calls (they interpret the
	            // data pointer as a PBO offset instead of a direct pointer).
	            int prevPackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_PACK_BUFFER_BINDING);
	            int prevUnpackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_UNPACK_BUFFER_BINDING);
	            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, 0);
	            if (prevUnpackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, 0);
	            try {
	                // Sample 9 points: 3x3 grid
                int stepX = Math.max(1, scaledWidth / 4);
                int stepY = Math.max(1, scaledHeight / 4);
                int[][] samples = {
                    {scaledWidth / 2, scaledHeight / 2},  // center
                    {stepX, stepY}, {stepX * 3, stepY}, {stepX, stepY * 3}, {stepX * 3, stepY * 3},  // inner mid
                    {4, 4}, {scaledWidth - 5, 4}, {4, scaledHeight - 5}, {scaledWidth - 5, scaledHeight - 5}  // corners
                };
                String[] sampleNames = {"CENTER", "M1", "M2", "M3", "M4", "TL", "TR", "BL", "BR"};

                if (debugFbo == -1) debugFbo = GL43C.glGenFramebuffers();

                // ====== RAW DEPTH READBACK (direct glGetTextureSubImage) ======
                StringBuilder sbDepth = new StringBuilder("[DEBUG_DEPTH]");
                for (int si = 0; si < samples.length; si++) {
                    float[] v = readTexPixels(depthTex, samples[si][0], samples[si][1],
                        GL43C.GL_RED, GL43C.GL_FLOAT, 1);
                    if (v == null) break;  // readback unavailable for this texture
                    float d = v[0];
                    float vz = mvDeviceToViewDepth[1] / (d - mvDeviceToViewDepth[0] + 1e-10f);
                    sbDepth.append(String.format(" %s=(%.4f,vz=%.1f)", sampleNames[si], d, vz));
                }

                // ====== MV READBACK (direct glGetTextureSubImage on the shared MV) ======
                StringBuilder sbMV = new StringBuilder("[DEBUG_MV]");
                int nonZeroCount = 0;
                for (int si = 0; si < samples.length; si++) {
                    float[] v = readTexPixels(mvTex, samples[si][0], samples[si][1],
                        GL43C.GL_RG, GL43C.GL_FLOAT, 2);
                    if (v == null) break;  // readback unavailable for this texture
                    float mvx = v[0];
                    float mvy = v[1];
                    sbMV.append(String.format(" %s=(%.1f,%.1f)", sampleNames[si], mvx, mvy));
                    if (Math.abs(mvx) > 0.01f || Math.abs(mvy) > 0.01f) nonZeroCount++;
                }

                sbMV.append(String.format(" nonZero=%d/%d hasPrev=%s farDepth=%.0f farViewZ=%.1f dtod=(%.4f,%.4f)",
                    nonZeroCount, samples.length, hasPrevFrame, farDepth, mvFarViewZ,
                    mvDeviceToViewDepth[0], mvDeviceToViewDepth[1]));
                FSRMod.LOGGER.info(sbMV.toString());
                FSRMod.LOGGER.info(sbDepth.toString());

                // ====== SHARED DEPTH READBACK (direct glGetTextureSubImage) ======
                float sharedDepth = -1.0f;
                if (sharedTexMgr != null && sharedTexMgr.getDepthTexId() != 0) {
                    float[] v = readTexPixels(sharedTexMgr.getDepthTexId(), scaledWidth / 2, scaledHeight / 2,
                        GL43C.GL_RED, GL43C.GL_FLOAT, 1);
                    if (v != null) sharedDepth = v[0];
                }
                FSRMod.LOGGER.info(String.format("[DEBUG_SHARED_DEPTH] center=%.4f (R32F copy)", sharedDepth));

                // [DEBUG_SHARED_VERIFY] Copy the shared MV/depth textures into
                // MANAGED (non-external) GL textures via glCopyImageSubData — the
                // same proven mechanism that copies the scene color into the shared
                // color texture (which D3D12 clearly reads correctly). If the plain
                // copies read back the values the compute shaders wrote (depth 0.5,
                // non-zero MVs) but the direct FBO readback of the shared textures
                // returns 0, then glReadPixels on external-memory textures is the
                // broken link and [DEBUG_MV]'s all-zero conclusion is WRONG.
                if (sharedTexMgr != null) {
                    int verifyMv = GL43C.glGenTextures();
                    int verifyDepth = GL43C.glGenTextures();
                    int verifyColor = GL43C.glGenTextures();
                    try {
                        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, verifyColor);
                        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RGBA8, scaledWidth, scaledHeight);
                        GL43C.glCopyImageSubData(sharedTexMgr.getColorTexId(), GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            verifyColor, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0, scaledWidth, scaledHeight, 1);

                        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, verifyMv);
                        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RG16F, scaledWidth, scaledHeight);
                        GL43C.glCopyImageSubData(sharedTexMgr.getMvTexId(), GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            verifyMv, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0, scaledWidth, scaledHeight, 1);

                        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, verifyDepth);
                        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_R32F, scaledWidth, scaledHeight);
                        GL43C.glCopyImageSubData(sharedTexMgr.getDepthTexId(), GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            verifyDepth, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0, scaledWidth, scaledHeight, 1);

                        // Direct glGetTextureSubImage readback of the managed verify
                        // copies (the FBO glReadPixels path lies for R32F/RG16F on
                        // this driver — see [STAGING_VERIFY] note).
                        {
                            float[] mvC = readTexPixels(verifyMv, scaledWidth / 2, scaledHeight / 2, GL43C.GL_RG, GL43C.GL_FLOAT, 2);
                            if (mvC != null) {
                                FSRMod.LOGGER.info(String.format("[DEBUG_SHARED_VERIFY] centerMv copy=(%.1f,%.1f)", mvC[0], mvC[1]));
                            } else {
                                FSRMod.LOGGER.warn("[DEBUG_SHARED_VERIFY] readTexPixels failed");
                            }
                            float[] dC = readTexPixels(verifyDepth, scaledWidth / 2, scaledHeight / 2, GL43C.GL_RED, GL43C.GL_FLOAT, 1);
                            if (dC != null) {
                                FSRMod.LOGGER.info(String.format("[SHARE_VERIFY] depthC copy=%.4f (expected 0.5)", dC[0]));
                            } else {
                                FSRMod.LOGGER.warn("[SHARE_VERIFY] readTexPixels failed");
                            }
                        }

                        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, debugFbo);
                        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                            GL43C.GL_TEXTURE_2D, verifyColor, 0);
                        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                            GL43C.GL_TEXTURE_2D, 0, 0);
                        GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
                        if (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE) {
                            if (debugPboBuf == null || debugPboBuf.capacity() < 16) {
                                debugPboBuf = java.nio.ByteBuffer.allocateDirect(16)
                                    .order(java.nio.ByteOrder.nativeOrder());
                            }
                            debugPboBuf.clear();
                            debugPboBuf.limit(16);
                            GL43C.glReadPixels(scaledWidth / 2, scaledHeight / 2, 1, 1, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, debugPboBuf);
                            debugPboBuf.rewind();
                            int cr = debugPboBuf.get(0) & 0xFF;
                            int cg = debugPboBuf.get(1) & 0xFF;
                            int cb = debugPboBuf.get(2) & 0xFF;
                            FSRMod.LOGGER.info(String.format("[SHARE_VERIFY] colorC copy=(%d,%d,%d) (scene=nonzero)", cr, cg, cb));
                        } else {
                            FSRMod.LOGGER.info("[SHARE_VERIFY] FBO incomplete for verify textures");
                        }
                        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, 0);
                    } finally {
                        GL43C.glDeleteTextures(verifyMv);
                        GL43C.glDeleteTextures(verifyDepth);
                        GL43C.glDeleteTextures(verifyColor);
                    }
                }

                // ====== STAGING READBACK (managed textures, definitive) ======
                // readTexPixels handles both GL 4.5 (glGetTextureSubImage) and
                // GL 4.4 (FBO glReadPixels via GL_COLOR_ATTACHMENT0, native-order
                // buffer). Historical "0.0000"/"readTexPixels failed" values were
                // either the big-endian decode bug or the GL45-only path returning
                // null in a GL 4.4 context — NOT the texture contents.
                float stgDepth = -1.0f;
                float stgMvX = 0.0f, stgMvY = 0.0f;
                float stgReactive = -1.0f;
                int cxStg = scaledWidth / 2, cyStg = scaledHeight / 2;
                if (fsr4StagingDepth != -1) {
                    float[] v = readTexPixels(fsr4StagingDepth, cxStg, cyStg, GL43C.GL_RED, GL43C.GL_FLOAT, 1);
                    if (v != null) stgDepth = v[0];
                }
                if (fsr4StagingMv != -1) {
                    float[] v = readTexPixels(fsr4StagingMv, cxStg, cyStg, GL43C.GL_RG, GL43C.GL_FLOAT, 2);
                    if (v != null) { stgMvX = v[0]; stgMvY = v[1]; }
                }
                if (fsr4StagingReactive != -1) {
                    float[] v = readTexPixels(fsr4StagingReactive, cxStg, cyStg, GL43C.GL_RED, GL43C.GL_FLOAT, 1);
                    if (v != null) stgReactive = v[0];
                }
                int errStaging = GL43C.glGetError();
                FSRMod.LOGGER.info(String.format(
                    "[STAGING_VERIFY] depth=%.4f mv=(%.1f,%.1f) reactive=%.2f glErr=0x%04X",
                    stgDepth, stgMvX, stgMvY, stgReactive, errStaging));

                // ====== SHADING DETECTOR READBACK ======
                // Ratio (R) + persistence counter (G, stored as persist/3.0 —
                // decode via *3.0). The detector fires when persist >= 2 (2
                // consecutive frames of ratio diff above the 0.10 ramp start,
                // stored as 0.667). If tiles show "FIRE" on a static scene,
                // jitter immunity is still insufficient; if persist stays 0-1
                // everywhere while the shake continues, the cause is NOT the
                // shading detector.
                if (fsr4ScCurrTex != -1) {
                    StringBuilder sbShading = new StringBuilder("[DEBUG_SHADING]");
                    for (int si = 0; si < samples.length; si++) {
                        float[] v = readTexPixels(fsr4ScCurrTex, samples[si][0], samples[si][1],
                            GL43C.GL_RG, GL43C.GL_FLOAT, 2);
                        if (v == null) {
                            sbShading.append(" null");
                            continue;
                        }
                        sbShading.append(String.format(" %s=(r=%.2f,p=%d%s)",
                            sampleNames[si], v[0], (int) Math.round(v[1] * 3.0), v[1] > 0.5f ? " FIRE" : ""));
                    }
                    FSRMod.LOGGER.info(sbShading.toString());
                }

                // Log VP matrix diagnostics
                Matrix4f invCurrVP = currViewProj.invert(new Matrix4f());
                float[] invCurrVPArr = new float[16];
                invCurrVP.get(invCurrVPArr);
                float[] prevVPArr = new float[16];
                prevViewProj.get(prevVPArr);
                Vec3 camPos2 = Minecraft.getInstance().gameRenderer.getMainCamera().position();
                FSRMod.LOGGER.info(String.format(
                    "[DEBUG_VP] hasPrev=%s invCurrVP[0]=(%.4f,%.4f,%.4f,%.4f)"
                    + " prevVP[0]=(%.4f,%.4f,%.4f,%.4f)"
                    + " camPos=(%.1f,%.1f,%.1f) camRot=(%.2f,%.2f,%.2f)",
                    hasPrevFrame,
                    invCurrVPArr[0], invCurrVPArr[1], invCurrVPArr[2], invCurrVPArr[3],
                    prevVPArr[0], prevVPArr[1], prevVPArr[2], prevVPArr[3],
                    camPos2.x, camPos2.y, camPos2.z,
                    Minecraft.getInstance().gameRenderer.getMainCamera().rotation().x,
                    Minecraft.getInstance().gameRenderer.getMainCamera().rotation().y,
                    Minecraft.getInstance().gameRenderer.getMainCamera().rotation().z));

                // Verify GL error state
                int glErr = GL43C.glGetError();
                if (glErr != GL43C.GL_NO_ERROR) {
                    FSRMod.LOGGER.warn(String.format("[DEBUG_GL] Error after readbacks: 0x%X", glErr));
                }

                // Diagnostic: check depth texture format
                GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
                int depthFormat = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
                int depthWidth = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_WIDTH);
                int depthHeight = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_HEIGHT);
                FSRMod.LOGGER.info(String.format("[DEBUG_TEX] depthTex=%d format=0x%X size=%dx%d renderTarget=%dx%d",
                    depthTex, depthFormat, depthWidth, depthHeight, scaledWidth, scaledHeight));

	
            } catch (Exception e) {
                FSRMod.LOGGER.error("[DEBUG_MV] readback failed", e);
            } finally {
                // Restore PBO bindings
                if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, prevPackBuf);
                if (prevUnpackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, prevUnpackBuf);
            }
        }

        prevViewProj.set(currViewProj);
        hasPrevFrame = true;
    }

    private void applyUpscaleAndSharpen(int srcTex, int srcW, int srcH, int dstW, int dstH, float sharpness) {
        if (srcTex == -1 || dstW <= 0 || dstH <= 0) return;
        if (!ensureUpscaleShaders() || rcasUpscaleProg == -1) return;
        if (sharpness < 0.001f) return;

        if (mainRenderTarget == null) return;
        int dstTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();

        // Create temp texture for upscale+sharpen output (if needed or resized)
        if (rcasUpscaleTex == -1 || rcasUpscaleTexW != dstW || rcasUpscaleTexH != dstH) {
            if (rcasUpscaleTex != -1) GL43C.glDeleteTextures(rcasUpscaleTex);
            rcasUpscaleTex = GL43C.glGenTextures();
            rcasUpscaleTexW = dstW;
            rcasUpscaleTexH = dstH;
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, rcasUpscaleTex);
            GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA8, dstW, dstH, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, 0);
            GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
            GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);
        }

        // Use a fullscreen quad + fragment shader
        int oldFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, rcasUpscaleFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, rcasUpscaleTex, 0);

        GL43C.glUseProgram(rcasUpscaleProg);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, srcTex);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);

        int uInputLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uInput");
        int uInputSizeLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uInputSize");
        int uOutputSizeLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uOutputSize");
        int uSharpnessLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uSharpness");

        if (uInputLoc != -1) GL43C.glUniform1i(uInputLoc, 0);
        if (uInputSizeLoc != -1) GL43C.glUniform2i(uInputSizeLoc, srcW, srcH);
        if (uOutputSizeLoc != -1) GL43C.glUniform2i(uOutputSizeLoc, dstW, dstH);
        if (uSharpnessLoc != -1) GL43C.glUniform1f(uSharpnessLoc, sharpness);

        GL43C.glViewport(0, 0, dstW, dstH);
        GL43C.glBindVertexArray(rcasUpscaleVao);
        GL43C.glDrawArrays(GL43C.GL_TRIANGLE_STRIP, 0, 4);

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, oldFbo);

        // Copy temp texture to mainRT
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            rcasUpscaleTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            dstTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            dstW, dstH, 1
        );
    }

    public boolean isInsideFrame() { return insideFrame; }

    // ========================================================================

    /**
     * Result of {@link #getVersionStatus}: whether a given upscale version can
     * be selected, and if not, a human-readable reason why.
     */
    public static final class VersionStatus {
        public final boolean selectable;
        public final String reason;
        /** Shown as a tooltip/notice when the version IS selectable (e.g. the
         *  Linux FSR4 → FSR3-engine fallback explanation). Empty when none. */
        public final String notice;

        private VersionStatus(boolean selectable, String reason, String notice) {
            this.selectable = selectable;
            this.reason = reason;
            this.notice = notice;
        }

        public static VersionStatus ok() {
            return new VersionStatus(true, "", "");
        }

        public static VersionStatus okWithNotice(String notice) {
            return new VersionStatus(true, "", notice);
        }

        public static VersionStatus no(String reason) {
            return new VersionStatus(false, reason, "");
        }
    }

    /**
     * Returns the current GPU renderer string (e.g. "AMD Radeon 860M").
     */
    public String getGpuName() {
        return gpuRenderer();
    }

    /**
     * Checks whether frame generation via DX12 (FSR3_FG_DX12) is available
     * on this system. Requires Fg4Native DLL, fsrvk.dll, and no prior failures.
     */
    public boolean isFrameGenAvailable() {
        return Fg4Native.isAvailable() && !d3d12DeviceLost && !fg4PermanentlyDisabled;
    }

    /**
     * Returns whether the current GPU is known to be incompatible with
     * frame generation (e.g. integrated Intel graphics without D3D12 compute).
     */
    public boolean isFgUnsupportedGpu() {
        // Intel integrated GPUs often lack proper D3D12 compute support for FG.
        // This matches the JAR's logic — check if Intel GPU AND no Vulkan interop.
        // AMD and NVIDIA dedicated GPUs are generally fine.
        String renderer = gpuRenderer().toLowerCase();
        return (renderer.contains("intel") || renderer.contains("mesa"))
            && !checkVulkanAvailable();
    }

    /**
     * Returns the version-status for the given upscale version, indicating
     * whether it is selectable and (if not) why.
     */
    public VersionStatus getVersionStatus(FSRConfig.FSRVersion version) {
        switch (version) {
            case FSR1:
                // Under VulkanMod there is no OpenGL context, so the GL-based FSR1
                // blit/EASU pipeline cannot run — it is a silent pass-through.
                // Surface that in the settings UI instead of letting the user
                // select FSR1 and see zero downscale/upscale.
                if (VulkanModCompat.isActive()) return VersionStatus.no("VulkanMod active — GL upscaling unavailable (FSR4 only)");
                return VersionStatus.ok();
            case FSR2:
                if (isIntelGpu()) return VersionStatus.no("Intel GPUs — compute shader issues");
                if (dhActive) return VersionStatus.no("Distant Horizons — depth not available");
                if (VulkanModCompat.isActive()) return VersionStatus.no("VulkanMod active — GL compute unavailable");
                return VersionStatus.ok();
            case FSR3_UPS:
                if (isIntelGpu()) return VersionStatus.no("Intel GPUs — compute shader issues");
                if (dhActive) return VersionStatus.no("Distant Horizons — depth not available");
                if (VulkanModCompat.isActive()) return VersionStatus.no("VulkanMod active — GL compute unavailable");
                return VersionStatus.ok();
            case FSR3_FG:
                if (isIntelGpu()) return VersionStatus.no("Intel GPUs — compute shader issues");
                if (dhActive) return VersionStatus.no("Distant Horizons — depth not available");
                if (VulkanModCompat.isActive()) return VersionStatus.no("VulkanMod active — GL compute unavailable");
                return VersionStatus.ok();
            case FSR3_FG_DX12:
                // VulkanMod now supports FG (2026-08-15, step 1): the VKMod FSR4
                // pipeline (upscale + pool) feeds FG. Step 1 is single-present —
                // generated frames carry no HUD (pool 6 capture is step 2).
                if (VulkanModCompat.isLoaded()) return VersionStatus.okWithNotice("VulkanMod: FSR4 upscale + FG — step 1 single-present (no HUD on generated frames)");
                if (!Fg4Native.isAvailable()) return VersionStatus.no("FG DX12 DLLs not loaded");
                if (d3d12DeviceLost) return VersionStatus.no("D3D12 device lost — restart required");
                if (fg4PermanentlyDisabled) return VersionStatus.no("Too many runtime failures");
                if (isFgUnsupportedGpu()) return VersionStatus.no("GPU not supported for FG");
                return VersionStatus.ok();
            case FSR4:
                if (!Fsr4Native.isAvailable()) {
                    // Linux compatibility (2026-08-14): the AMD FSR4 SDK is
                    // Windows/DX12-only, but on Linux the FSR4 selection is
                    // still selectable — it runs the mod's own FSR3 temporal
                    // engine (isFsr3() routing). Surface that clearly instead
                    // of the Windows-specific "DLLs not loaded" blocker.
                    if (isLinux() && !VulkanModCompat.isActive()) {
                        return VersionStatus.okWithNotice("AMD FSR4 SDK is Windows-only — on Linux, FSR 4 runs the FSR3 temporal engine");
                    }
                    return VersionStatus.no("FSR4 DLLs not loaded");
                }
                if (fsr4PermanentlyDisabled) return VersionStatus.no("FSR4 permanently disabled after repeated init failures");
                return VersionStatus.ok();
            default:
                return VersionStatus.no("Unknown version");
        }
    }

    /**
     * Records a D3D12 runtime failure and disables FG if too many occur.
     */
    private void noteD3D12RuntimeFailure(String message) {
        fgRuntimeFailCount++;
        FSRMod.LOGGER.error("[FG_DX12] Runtime failure #{} (consecutive): {}", fgRuntimeFailCount, message);
        if (fgRuntimeFailCount >= FG_MAX_RUNTIME_FAILURES) {
            fg4PermanentlyDisabled = true;
            FSRMod.LOGGER.error("[FG_DX12] Too many consecutive failures — disabling FG DX12");
        }
    }

    /**
     * Copies the FG (frame generation) output texture (pool index 5, display-size
     * RGBA8) to the main render target. Used in FSR3_FG_DX12 mode where the FG
     * runs via D3D12 compute — the generated interpolated frame is presented on
     * alternating frames.
     */
    private void copyFgOutputToMainRT() {
        if (mainRenderTarget == null || sharedTexMgr == null) return;
        int fgTex = sharedTexMgr.getFgOutputTexId();
        if (fgTex == 0) return;
        int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
        if (mainTex == 0) return;
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1
        );
    }

    /**
     * Multi-present: after the vanilla loop presents the first generated FG
     * frame, this method presents the remaining generated frames (if
     * fgMultiplier > 1) and the real FSR4 frame. Total images per render
     * loop = fgMultiplier + 1 (N generated + 1 real). Display FPS ≈ (N+1)×
     * render FPS, capped by monitor refresh + vsync.
     *
     * Ordering: FG frame 0 (already shown by vanilla) → FG frame 1..N-1 →
     * real frame. The SDK interpolates from (prevReal, currReal) so the
     * generated frames belong between them.
     */
    public void onAfterSwapBuffers() {
        if (!fgSecondPresentPending || fgInSecondPresent) return;
        if (!fgActive() || !fg4Initialized) {
            fgSecondPresentPending = false;
            fgExtraGenFramesToPresent = 0;
            return;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.level == null || client.screen != null) {
                fgSecondPresentPending = false;
                fgExtraGenFramesToPresent = 0;
                return;
            }
            com.mojang.blaze3d.platform.Window window = client.getWindow();
            if (window == null || window.isMinimized() || mainRenderTarget == null) {
                fgSecondPresentPending = false;
                fgExtraGenFramesToPresent = 0;
                return;
            }

            fgInSecondPresent = true;
            fgSecondPresentPending = false;

            // Present additional generated frames (slots 1..N-1) if multi-frame.
            // Pool slot 0 was already shown by vanilla; slot 1 = pool 7,
            // slot 2 = pool 8, slot 3 = pool 9.
            while (fgExtraGenFramesToPresent > 0) {
                int slot = fgNumGeneratedThisCycle - fgExtraGenFramesToPresent;
                int fgTex = sharedTexMgr.getFgOutputTexId(slot);
                if (fgTex != 0) {
                    int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
                    if (mainTex != 0) {
                        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
                        GL43C.glCopyImageSubData(
                            fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            displayWidth, displayHeight, 1
                        );
                        mainRenderTarget.blitToScreen();
                        RenderSystem.flipFrame(window, null);
                    }
                }
                fgExtraGenFramesToPresent--;
            }

            // Present the real frame WITH the GUI (pool 6 = mainRT captured after
            // the HUD painted). This is the same image FG used as presentColor, so
            // all presented frames carry the UI.
            copyFgPresentToMainRT();
            mainRenderTarget.blitToScreen();
            RenderSystem.flipFrame(window, null);
        } catch (Throwable t) {
            FSRMod.LOGGER.warn("FG_DX12: multi-present failed: {}", t.toString());
            fgSecondPresentPending = false;
            fgExtraGenFramesToPresent = 0;
        } finally {
            fgInSecondPresent = false;
        }
    }

    // ========================================================================
    // Render-skip support (for FG alternating-frame mode)
    // ========================================================================

    /**
     * Consumes the "skip render" flag. Returns true if the caller should skip
     * rendering this frame (an FG interpolated frame will be shown instead).
     */
    public boolean consumeSkipRender() {
        if (fgSkipNextRender) {
            fgSkipNextRender = false;
            return true;
        }
        return false;
    }

    /**
     * Presents the last FG (interpolated) frame instead of rendering a new one.
     * Used when consumeSkipRender returned true.
     */
    public void presentLastFgFrame() {
        // Re-present the previous FG frame from the swapchain / output texture.
        // In GL mode this is a no-op since the previous output remains in the framebuffer.
        // In DX12 mode the output would need to be kept alive and re-blitted.
    }

    /**
     * Returns true if the next frame's 3D render should be skipped (FG frame skip).
     */
    public boolean shouldSkipRender() {
        return fgSkipNextRender;
    }
}




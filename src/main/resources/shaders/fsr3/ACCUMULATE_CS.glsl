#version 430 core
layout(local_size_x = 16, local_size_y = 4) in;
        layout(binding = 0) uniform sampler2D uInputColor;
        layout(binding = 1) uniform sampler2D uInputDepth;
        layout(binding = 2) uniform sampler2D uExposure;
        layout(binding = 3) uniform sampler2D uPreparedInputColor;
        layout(binding = 4) uniform sampler2D uDilatedData;
        layout(binding = 5) uniform sampler2D uDilatedReactiveMasks;
        layout(binding = 6) uniform sampler2D uInternalUpscaled;
        layout(binding = 7) uniform sampler2D uLockStatus;
        layout(binding = 8) uniform sampler2D uShadingChange;
        layout(binding = 9) uniform sampler2D uNewLocks;

        layout(binding = 10, r11f_g11f_b10f) writeonly uniform image2D uInternalUpscaledRW;
        layout(binding = 11, rgba8) writeonly uniform image2D uLockStatusRW;
        layout(binding = 14, rgba8) writeonly uniform image2D uUpscaledOutput;

        uniform ivec2 uRenderSize;
        uniform ivec2 uDisplaySize;
        uniform ivec2 uMaxRenderSize;
        uniform vec4 uDeviceToViewDepth;
        uniform vec2 uJitter;
        uniform vec2 uMotionVectorScale;
        uniform vec2 uDownscaleFactor;
        uniform vec2 uMotionVectorJitterCancellation;
        uniform float uPreExposure;
        uniform float uPreviousFramePreExposure;
        uniform float uViewSpaceToMetersFactor;
        uniform float uExposureVal;
        uniform float uFarDepth;
	        uniform float uFarViewZ;
	        uniform int uFrameIndex;
	        uniform int uDebugMode;
	        uniform int uUseDepth;

	        uniform int uCamStill;
	        uniform float uCamReactive;
	        uniform int uCamStillExact;

	        const float FSR3_EPSILON = 1e-6;
        const float LOCK_LIFETIME = 6.0;
        const float fUpsampleLanczosWeightScale = 1.0 / 6.0;

        vec3 yCoCgToRgb(vec3 c) {
            return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
        }

        vec3 rgbToYCoCg(vec3 c) {
            return vec3(0.25*c.r + 0.5*c.g + 0.25*c.b, 0.5*c.r - 0.5*c.b, -0.25*c.r + 0.5*c.g - 0.25*c.b);
        }

        float getViewSpaceDepth(float d) {
            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0] + 1e-10);
        }

        // Lanczos2 approximation (squared distance form)
        float lanczos2ApproxSq(float x2) {
            x2 = min(x2, 4.0);
            float a = (2.0/5.0)*x2 - 1.0;
            float b = (1.0/4.0)*x2 - 1.0;
            return ((25.0/16.0)*a*a - (25.0/16.0 - 1.0)) * (b*b);
        }

        // Ellipsoid color clamp — clamp history color within ellipsoid
        // defined by neighborhood color distribution in YCoCg space
        vec3 ellipsoidColorClamp(vec3 historyYCoCg, vec3 centerYCoCg,
                                  vec3 boxMin, vec3 boxMax,
                                  float confidence) {
            vec3 mean = centerYCoCg;

            // Scale ellipsoid by confidence: low confidence = tighter clamp
            // to prevent blur accumulation in stable areas
            float ellipsoidScale = mix(2.0, 1.0, confidence);

            vec3 halfExtents = (boxMax - boxMin) * 0.5 * ellipsoidScale;
            halfExtents = max(halfExtents, vec3(0.01));

            vec3 diff = historyYCoCg - mean;
            vec3 normDiff = diff / halfExtents;
            float distSq = dot(normDiff, normDiff);

            if (distSq > 1.0) {
                float dist = sqrt(distSq);
                vec3 clampedDiff = diff / dist;
                historyYCoCg = mean + clampedDiff * halfExtents;
            }

            // Hard bound: history can never exceed the actual neighborhood
            // pixel range, preventing ghosting from loose ellipsoid
            historyYCoCg = clamp(historyYCoCg, boxMin, boxMax);

            return historyYCoCg;
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uDisplaySize.x || p.y >= uDisplaySize.y) return;

            vec2 hrUv = (vec2(p) + 0.5) / vec2(uDisplaySize);
            // CRITICAL: De-jitter the UV before sampling render-scale textures.
            // The scene was rendered with sub-pixel jitter in the projection matrix,
            // which shifts the rendered content. A display pixel at hrUv corresponds
            // to render content at lrUv = hrUv - jitter/renderSize (see AMD FSR2 spec).
            vec2 lrUv = hrUv - uJitter / vec2(uRenderSize);
            vec2 lrUvClamped = clamp(lrUv, vec2(0.5/vec2(uRenderSize)), 1.0 - vec2(0.5/vec2(uRenderSize)));
	            float sd0 = texelFetch(uInputDepth, ivec2(clamp(hrUv, 0.0, 1.0) * vec2(uRenderSize - 1)), 0).r;
	            float sd1 = texelFetch(uInputDepth, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).r;
		            float vz0 = getViewSpaceDepth(clamp(sd0, 0.0, 1.0 - 1e-7));
		            float vz1 = getViewSpaceDepth(clamp(sd1, 0.0, 1.0 - 1e-7));
	                float skyThreshold = abs(uFarViewZ) * 0.95;
	                // Depth-trust gate: with the RT depth unwritten (probe-
	                // rejected), depth ≈ far plane classifies EVERY pixel as sky
	                // → no history accumulation → shaking. Fall back to the
	                // color-based detection (screen-Y guarded) alone.
	                bool isSkyDepth = (uUseDepth > 0) && (abs(vz0) > skyThreshold || abs(vz1) > skyThreshold);

		            // Screen-Y + color sky detection.
		            // Sky is always at the top of the screen (above horizon).
		            // Use screen Y position as the primary classifier — it's 100%
		            // reliable regardless of texture detail or local variance.
		            vec3 skyCol = texelFetch(uInputColor, ivec2(clamp(hrUv * vec2(uRenderSize - 1), vec2(0.0), vec2(uRenderSize - 1))), 0).rgb;
		            float skyLuma = dot(skyCol, vec3(0.2126, 0.7152, 0.0722));
		            float skyMaxC = max(skyCol.r, max(skyCol.g, skyCol.b));
		            float skyMinC = min(skyCol.r, min(skyCol.g, skyCol.b));
		            float skyChroma = skyMaxC - skyMinC;
			            // Gray-cloud classifier relax (2026-08-07, jagged cloud edges): the 0.85 luma
// threshold missed off-white/gray clouds (observed cloud luma 0.70, chroma
// 0.10) → they stayed terrain-like, kept the 0.5 reactive, and never
// accumulated. Screen-Y guard (>0.50) keeps terrain out; while camStill is on
// a misclassified gray pixel gets mv=0 + reactive 0, which is EXACT for a
// still camera anyway.
bool isWhiteSky = skyLuma > 0.58 && skyChroma < 0.12;
			            bool isBlueSky = skyLuma > 0.55 && skyCol.b > max(skyCol.r, skyCol.g) * 1.15;
			            // Screen-Y position guard: 0 = bottom (OpenGL coord), 1 = top
			            float screenYFactor = float(p.y) / float(uDisplaySize.y);
			            // Bottom 50% of screen is NEVER sky — prevents white/bright terrain
			            // blocks from being falsely classified as sky and losing temporal accumulation.
			            bool isSkyColor = (isWhiteSky || isBlueSky) && screenYFactor > 0.50;
		            bool isSky = isSkyDepth || isSkyColor;

		            // Dilate the lrUv=hrUv zone downward by 1 pixel to prevent
		            // boundary flickering at the sky-terrain horizon. The original
		            // lrUv=hrUv fix prevents jitter-induced sky shaking but creates
		            // a discontinuity where adjacent pixels toggle between jittered
		            // (lrUv) and non-jittered (hrUv) UVs. By also using hrUv for
		            // non-sky pixels that have a sky pixel above them, we create a
		            // 1-pixel buffer zone that eliminates the toggle.
		            bool nearSky = false;
		            if (!isSky && screenYFactor > 0.50) {
		                vec2 aboveUv = hrUv + vec2(0.0, 1.0 / float(uDisplaySize.y));
		                ivec2 abovePx = ivec2(clamp(aboveUv * vec2(uRenderSize - 1), vec2(0.0), vec2(uRenderSize - 1)));
		                vec3 ac = texelFetch(uInputColor, abovePx, 0).rgb;
		                float aLuma = dot(ac, vec3(0.2126, 0.7152, 0.0722));
		                float aChroma = max(ac.r, max(ac.g, ac.b)) - min(ac.r, min(ac.g, ac.b));
		                nearSky = (aLuma > 0.92 && aChroma < 0.04) || (aLuma > 0.55 && ac.b > max(ac.r, ac.g) * 1.15);
		            }
		            // 2026-08-06: sky/cloud/nearSky pixels use the SAME de-jittered lrUv
		            // as terrain (no lrUv=hrUv override). The sky is a fresh render every
		            // frame (isNewSample → no accumulation), so the only way to stop it
		            // shaking is to de-jitter the sample: lrUv = hrUv - uJitter/renderSize
		            // tracks the jittered render so the sampled sky direction stays fixed.
		            // The old override showed the RAW jittered sky → ±jitter px oscillation
		            // (the per-frame Halton delta) on sky, clouds and the horizon band —
		            // the residual "kind of shaking" that survived all accumulation fixes.
	            lrUvClamped = clamp(lrUv, vec2(0.5/vec2(uRenderSize)), 1.0 - vec2(0.5/vec2(uRenderSize)));

	            vec2 mv = texelFetch(uDilatedData, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).rg;
      // SKY + STILL-CAMERA: force identity reprojection (2026-08-06 v2). The sky
      // is at infinity, so its TRUE screen position only changes with camera
      // ROTATION (parallax is 0 during translation). The constant-depth MV for a
      // sky pixel is therefore wrong whenever the camera moves - reading history
      // at hrUv+mv would misalign it. With uCamStill=1 the CPU has confirmed the
      // camera isn't rotating, so the sky is genuinely fixed on screen: reproject
      // with mv=0 for an exact history sample. (Terrain and the nearSky horizon
      // band keep their normal MVs.)
      if (uCamStillExact == 1 || (isSky && uCamStill == 1)) mv = vec2(0.0);
		            // MV magnitude in RENDER pixels (mv is render-UV; uRenderSize is
		            // the matching pixel scale — the depth-clip pass measures
		            // nucleusVel the same way). The old `mv * uDisplaySize` measured
		            // display pixels at <1.0 scale (e.g. 33% more at 75%), which
		            // inflated confidence → tighter ellipsoid clamp → less temporal
		            // smoothing → more shimmer. NOTE: UV deltas are scale-invariant
		            // (render UV delta 0.05 == display UV delta 0.05 under uniform
		            // upscale), so reprojUv = hrUv + mv needs NO conversion — the
		            // historical AGENTS.md "mixes display UV + render UV" note was
		            // misleading for this merged display-res accumulate.
		            float mvPx = length(mv * vec2(uRenderSize));

		            // REPROJECTION uses the JITTER-CANCELLED MV only (2026-08-07 fix).
      // RECONSTRUCT already subtracted uMotionVectorJitterCancellation from the
      // MV stored in uDilatedData (rawMv*scale - jitterCancel = TRUE content
      // motion). Adding the cancellation BACK here was a DOUBLE-CANCEL: reprojUv
      // wobbled by (J_prev-J_curr)/renderSize every frame - up to ±3 display px
      // at 4x - even standing still, so the box clamp rejected the misaligned
      // history at edges (jagged/shake) and loosely blended it on smooth
      // surfaces (smear), surviving every weight/lock fix. The history texture
      // is stored de-jittered (input sampled at lrUv = hrUv - uJitter/uRenderSize
      // at write time), so the correct read is exactly hrUv + trueMotion.
      vec2 reprojUv = hrUv + mv;
	            bool isExisting = reprojUv.x >= 0.0 && reprojUv.x <= 1.0 && reprojUv.y >= 0.0 && reprojUv.y <= 1.0;
	            reprojUv = clamp(reprojUv, vec2(0.0), vec2(1.0));

	            // Sample history
	            vec4 hist = vec4(0);
	            vec3 historyRGB = vec3(0);
	            float temporalReactive = 0.0;
	            if (isExisting && uFrameIndex > 0) {
	                hist = textureLod(uInternalUpscaled, reprojUv, 0.0);
	                historyRGB = hist.rgb;
	                temporalReactive = texelFetch(uLockStatus, ivec2(reprojUv * vec2(uDisplaySize - 1)), 0).a;
	            }
	            vec3 historyYCoCg = rgbToYCoCg(historyRGB);

	            // HAND-ZONE IDENTITY REPROJECTION (2026-08-08, "the jagged edges are
	            // also affecting the hand"): the first-person hand is rendered in VIEW
	            // space (camera-attached) — its true screen position is FIXED under ALL
	            // camera motion, but the constant-depth MV field gives it the
	            // world-anchored MV of whatever world point it happens to cover, so
	            // history read at reprojUv never aligns → the hand is a fresh bilinear
	            // upscale every frame = jagged fist/held-item edges (the documented
	            // hand-jitter tradeoff). For bottom-center pixels (the hand zone), ALSO
	            // try the identity read (hrUv): when it matches the current frame
	            // clearly better than the MV read, the content is camera-attached →
	            // reproject at identity so the hand accumulates like terrain and its
	            // edges reconstruct from the jitter spread. Content-gated: a
	            // swinging/swaying hand (pose changed) no longer matches identity →
	            // falls back to the MV path (fresh, no smear); ground/walls caught in
	            // the zone (looking down) keep the MV path because identity mismatches
	            // during motion.
	            bool handIdentity = false;
	            if (screenYFactor < 0.34 && abs(p.x * 2.0 / float(uDisplaySize.x) - 1.0) < 0.96 && uFrameIndex > 0) {
	                vec3 curYCoCg = rgbToYCoCg(textureLod(uPreparedInputColor, lrUvClamped, 0.0).xyz);
	                float mvDiff = abs(curYCoCg.x - historyYCoCg.x);
	                vec3 idHistYCoCg = rgbToYCoCg(textureLod(uInternalUpscaled, hrUv, 0.0).xyz);
	                float idDiff = abs(curYCoCg.x - idHistYCoCg.x);
	                if (idDiff + 0.12 < mvDiff) {
	                    handIdentity = true;
	                    reprojUv = hrUv;
	                    mv = vec2(0.0);
	                    isExisting = true;
	                    historyYCoCg = idHistYCoCg;
	                    historyRGB = yCoCgToRgb(idHistYCoCg);
	                }
	            }

	            // Lock status
	            vec4 lockData = texelFetch(uLockStatus, ivec2(reprojUv * vec2(uDisplaySize - 1)), 0);
	            float reproLockLife = lockData.r * LOCK_LIFETIME;
	            float lockLuma = lockData.g * LOCK_LIFETIME;
	            float accumWeight = (uFrameIndex > 0) ? lockData.b * 15.0 : 0.0;

	            // New locks
	            float newLockIntensity = texelFetch(uNewLocks, ivec2(floor(hrUv * vec2(uDisplaySize))), 0).r;
	            bool newLock = newLockIntensity > (127.0 / 255.0);

	            // Reactive masks
	            vec2 reactiveMasks = texelFetch(uDilatedReactiveMasks, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).rg;
	            float dilatedReactive = reactiveMasks.x;
	            float accumulationMask = reactiveMasks.y;

	            // Shading change mask (G channel = luminance ratio diff between frames)
	            float shadingChange = texelFetch(uShadingChange, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).g;

	            // Prepared input color (YCoCg + depthClip in alpha) + 3x3 for ellipsoid clamp
	            vec4 preparedColor = textureLod(uPreparedInputColor, lrUvClamped, 0.0);
	            float depthClipFactor = preparedColor.a;
	            vec3 upsampledYCoCg = preparedColor.xyz;
	            // BOX-STABILITY FIX (2026-08-08, "still shaking when standing still"): the
// clamp box used to be anchored at lrUvClamped (the de-jittered CURRENT
// position), which moves +/-0.5 render px with the Halton jitter - at an edge
// floor() snapped between two adjacent render texels, so boxMin/boxMax jumped
// between two 3x3 neighborhoods and the box-clamped history toggled with the
// jitter phase (OUTPROBE: center texel oscillating +/-16 B counts while
// camStillExact=0). Anchor at reprojUv (hrUv + jitter-cancelled mv) - the
// position where the history is READ - which is frame-to-frame stable when
// the camera is still and tracks real motion when it moves (FSR2 reference:
// clamp box around the reprojected position). boxMin/boxMax are initialized
// from upsampledYCoCg (the current sample), so the box ALWAYS contains the
// current color even when the 3x3 sits at a distant reprojected position -
// do not change that initialization.
ivec2 boxBase = ivec2(floor(reprojUv * vec2(uRenderSize)));
	            vec3 boxMin = upsampledYCoCg;
	            vec3 boxMax = upsampledYCoCg;
	            for (int dy = -1; dy <= 1; dy++) {
	                for (int dx = -1; dx <= 1; dx++) {
	                    ivec2 sp = clamp(boxBase + ivec2(dx, dy), ivec2(0), uRenderSize - ivec2(1));
	                    vec3 s = texelFetch(uPreparedInputColor, sp, 0).xyz;
	                    boxMin = min(boxMin, s);
	                    boxMax = max(boxMax, s);
	                }
	            }

	            // FIX: detect clouds — bright pixels at far depth. Clouds in Minecraft
	            // are alpha-blended over sky and drift independently of camera motion.
	            // Their MVs are wrong (either 0 if cloud doesn't write depth, or
	            // camera-only if it does). Bypass temporal accumulation for them.
	            float inputLuma = dot(upsampledYCoCg, vec3(1.0));
	            bool isCloud = isSky && inputLuma > 0.55;
	            bool isNewSample = !isExisting || uFrameIndex == 0;
	            // Elytra sky-ghost fix (2026-08-07): constant-depth MVs manufacture 5-11
	            // render px of parallax for sky during fast translation (true parallax = 0
	            // at infinity), landing mid-reactive (~0.45) -> alpha ~0.14 -> ~85%
	            // misaligned sky history blends (smooth gradient passes the box clamp) =
	            // the residual elytra ghost. Sky reprojection is only trustworthy when its
	            // screen motion is small (<=5 render px, box-clamp-absorbable - the walking
	            // case); beyond that render sky fresh. Still-camera sky (mv=0 above) is
	            // unaffected.
	            if (isSky && mvPx * float(uDisplaySize.x) / float(uRenderSize.x) > 11.0) isNewSample = true;
	            float upsampledWeight = fUpsampleLanczosWeightScale;
	            float reactiveFactor = max(dilatedReactive, temporalReactive);
	            // Camera-motion reactive floor (2026-08-07, "fix the shaking" fix): see FSR2.
	            reactiveFactor = max(reactiveFactor, uCamReactive);
      // Sky + still camera: the MV-gen's reactive for sky is based on the same
      // wrong constant-depth MVs (fires during walking/translation) - ignore it
      // so the sky builds full accumulation weight while camStill holds.
      if (uCamStillExact == 1 || (isSky && uCamStill == 1) || handIdentity) reactiveFactor = 0.0;

            vec3 finalColor;
            float adjAccum = isExisting ? accumWeight * (1.0 - reactiveFactor) * (1.0 - depthClipFactor) : 0.0;
            // Content-mismatch persistence (2026-08-08 v2, "jagged edges
            // vibrating" report): set inside the else branch when the box clamp
            // has to clip the history. 1.0 = aligned (full accumulation).
            float rejectDecay = 1.0;
            float clampReject = 0.0;

            if (isNewSample) {
                finalColor = yCoCgToRgb(upsampledYCoCg);
            } else {
                vec3 boxCenter = upsampledYCoCg;
                vec3 boxVec = (boxMax - boxMin) * 0.5;
                float velFactor = clamp(mvPx * float(uDisplaySize.x) / (40.0 * float(uRenderSize.x)), 0.0, 1.0);

                // Untrusted-depth clamp softening (2026-08-06, "still shaking" fix):
                // the constant-depth reprojection is wrong during motion for any
                // content not at the assumed depth — high velocity is parallax error
                // as often as real disocclusion. Damp the velocity confidence so the
                // ellipsoid stays LOOSE: a slightly-misaligned history then blends as
                // motion blur instead of being discarded into raw jittered spatial
                // (which is exactly the shake). Real-depth machines keep the clamp.
                // Combined confidence: low confidence = tighter ellipsoid = more clamping
                float confidence = max(depthClipFactor, max(accumulationMask,
                    velFactor * (uUseDepth > 0 ? 1.0 : 0.35)));
                // FSR3 parity: damp the shading-change confidence the same way
                // (it would otherwise bypass the untrusted-depth softening above).
                confidence = max(confidence, shadingChange * (uUseDepth > 0 ? 1.0 : 0.35));

                // FSR3 ellipsoid color clamp
                vec3 clampedHistory = ellipsoidColorClamp(historyYCoCg, boxCenter, boxMin, boxMax, confidence);

                // Lock logic (same as FSR2). GREEN-SMEAR FIX (2026-08-07): the diff
                // used to compare the CURRENT luma against lockLuma, which is mixed
                // toward the current luma every frame — after ~20 frames it ALWAYS
                // passed, so a locked pixel blended the RAW (unclamped) history at up
                // to 100% and any misaligned content (terrain green ghosted into the
                // sky by a look-up pan, cloud drift, constant-depth reprojection
                // error) was preserved AND diffused by the jitter offset — the green
                // smear through the sky while standing still. Now the diff is a real
                // CONTENT check (history luma vs current luma), and sky/cloud never
                // take the unclamped lock path (they accumulate box-clamped, which
                // rejects the ghost).
                float lumaDiff = abs(upsampledYCoCg.x - historyYCoCg.x) / max(max(upsampledYCoCg.x, historyYCoCg.x), 1e-6);
                // Rejected-pixel persistence (2026-08-08 v2, "jagged edges vibrating"
                // report): drive the fresh share from a PERSISTENT clamp-rejection
                // signal instead of the noisy per-frame diff. When the box clamp must
                // clip the history (content mismatch) the accumulation weight decays,
                // so the blend settles on ~85% fresh over a few frames - the weight is
                // time-integrated, hence stable where the per-frame diff pulsed. Sky /
                // cloud (lumaDiff ~0.1) stay below the knee -> no decay -> their
                // box-clamped accumulation is untouched. Telemetry: the [ACCUM_WEIGHT]
                // B channel now reads low at junk pixels by design (the distrust signal).
                clampReject = clamp((lumaDiff - 0.25) / 0.25, 0.0, 1.0);
                rejectDecay = 1.0 - 0.85 * clampReject;
                // Unstable-smoothed-edges fix (2026-08-08): the hard lumaDiff<0.2 boolean
                // made the lock a BINARY switch - at a reconstructed edge the accumulated
                // history and the sharp current frame differ by the sub-pixel edge
                // straddle, so lumaDiff oscillates around 0.2 with the Halton jitter
                // phase, toggling the history CONTENT between 70% raw (sharp accumulated
                // edge) and 100% box-clamped (soft clipped edge) -> the smoothed edges
                // boil/pop at the jitter frequency. Fade trust in/out continuously over
                // 0.10-0.35 (same center as the old 0.2 cutoff; genuine mismatch >0.35
                // still disengages; the green-ghost diff ~0.26 is ~70% disengaged, and
                // the box clamp still rejects the ghost while it fades).
                float lockActive = (!isSky && reproLockLife > 0.0 && lockLuma > 0.0) ? 1.0 : 0.0;
                float lockTrust = lockActive * clamp(1.0 - smoothstep(0.10, 0.35, lumaDiff), 0.0, 1.0);
                float lockContrib = lockTrust * clamp(reproLockLife / 3.0, 0.0, 1.0);
                float reactiveContrib = 1.0 - pow(reactiveFactor, 1.0 / 2.0);
                // Lock-cap (2026-08-07, smearing fix): standing-still weights now
                // saturate to 15 (fix #1), lock life hits 255, and the unclamped
                // lock blend runs at 100%. But constant-depth MVs have a subtle
                // parallax error during the idle bob (~0.3 render px), so the
                // reprojected history is slightly misaligned frame after frame.
                // With 94% history blend, that error persists and spreads texture
                // detail across the screen (smear). Cap the lock at 0.70 → 30%
                // clamped history provides a safety net that catches the drift.
                float histContribFactor = min(lockContrib, 0.70) * reactiveContrib;
                historyYCoCg = mix(clampedHistory, historyYCoCg, clamp(histContribFactor, 0.0, 1.0));

                // Temporal blend (same as FSR2)
                float newAccum = adjAccum + upsampledWeight;
                // Motion-ghost fix (2026-08-07): same as FSR2 - reactivity must bias
                // the blend directly, not just the slow-saturating weight.
                float alpha = upsampledWeight / max(newAccum, FSR3_EPSILON);
                alpha = max(alpha, smoothstep(0.3, 1.0, reactiveFactor) * 0.9);
                // Standing-still edge-shake fix (2026-08-08): when history doesn't
                // match the current frame (first-person hand, held items, disocclusion
                // edges, animated foliage, cloud drift) lockTrust=0 -> history
                // is 100% box-clamped. The clamp box sits at the de-jittered
                // position, which SNAPS between adjacent render texels as the Halton
                // jitter crosses half-texel boundaries -> the clamped output toggles
                // between two 3x3 neighborhoods = the blocky edge wobble that
                // survives every weight/lock fix while standing still (mv=0 only
                // helps pixels whose history ALIGNS). The v2 weight-decay below now
                // pulls the de-jittered FRESH input in instead - a stable ~85% share
                // at persistently-mismatched pixels.
                // (2026-08-08 v2) The old lumaDiff-driven fresh boost is REMOVED: its
                // input (the history-vs-current diff at a non-accumulating edge)
                // oscillates with the jitter phase, so the fresh share pulsed 0->0.85
                // and the jagged edges vibrated. The rejectDecay weight decay above
                // replaces it with a stable, time-integrated fresh share (~85% at
                // persistently-mismatched pixels, ~0% at aligned/sky pixels).
                alpha = clamp(alpha, 0.0, 0.99);
                finalColor = yCoCgToRgb(mix(historyYCoCg, upsampledYCoCg, vec3(alpha)));
            }

            // Lock lifecycle
            float newLockLife = reproLockLife;
            if (newLock && !isSky) {
                newLockLife = LOCK_LIFETIME;
                lockLuma = upsampledYCoCg.x;
            } else if (!isSky && !isNewSample && isExisting && reactiveFactor < 0.4) {
                // Sky excluded (2026-08-07): the unclamped lock blend is the
                // green-ghost vector — sky accumulates box-clamped only.
                // Scale-independent lock build (2026-08-06): the ridge-based LOCK_CS
                // under-fires at low render scales (thin geometry is sub-pixel at 25%),
                // so lock life decayed to 0 -> blend fell back to the current-frame
                // box -> jitter showed through (wobble) + no temporal reconstruction
                // (jagged). Stable accumulating frames now build trust instead.
                newLockLife = min(reproLockLife + 1.0, LOCK_LIFETIME);
                lockLuma = mix(lockLuma, upsampledYCoCg.x, 0.1);
            } else if (newLockLife > 0.0) {
                newLockLife = max(0.0, newLockLife - 0.04);
            }

	            finalColor /= max(uExposureVal, 1e-7);

            vec4 outputColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);

            // Debug visualization overlay (replaces output when uDebugMode > 0)
            if (uDebugMode > 0) {
                vec3 dbg = vec3(0.0);
                if (uDebugMode == 1) {
                    dbg = isCloud ? vec3(0.0, 0.0, 1.0) : (isSky ? vec3(0.0, 1.0, 0.0) : vec3(0.0));
                } else if (uDebugMode == 2) {
                    dbg = isNewSample ? vec3(1.0) : vec3(0.0);
                } else if (uDebugMode == 3) {
                    vec2 mvPx = mv * vec2(uRenderSize);
                    float mag = min(length(mvPx) / 15.0, 1.0);
                    float ang = atan(mvPx.y, mvPx.x);
                    dbg = (0.5 + 0.5 * vec3(cos(ang), sin(ang), cos(ang + 1.57))) * mag;
                } else if (uDebugMode == 4) {
                    float w = (uFrameIndex > 0 && !isSky) ? lockData.b : 0.0;
                    dbg = vec3(w);
                } else if (uDebugMode == 5) {
                    dbg = vec3(clamp(reproLockLife / LOCK_LIFETIME, 0.0, 1.0));
                } else if (uDebugMode == 6) {
                    float sd0 = texelFetch(uInputDepth, ivec2(clamp(hrUv, 0.0, 1.0) * vec2(uRenderSize - 1)), 0).r;
                    dbg = vec3(clamp(sd0, 0.0, 1.0));
                } else if (uDebugMode == 7) {
                    vec2 delta = (reprojUv - hrUv) * vec2(uDisplaySize);
                    float d = min(length(delta), 5.0) / 5.0;
                    dbg = vec3(d);
		                } else if (uDebugMode == 8) {
		                    // Screen Y position factor: black=bottom, white=top (0.50 cutoff)
		                    // Below 0.50 = bottom half = terrain zone (screenY < 0.50 blacks out)
		                    dbg = vec3(clamp(screenYFactor / 0.50, 0.0, 1.0));
	                } else if (uDebugMode == 9) {
	                    // Chroma: black=0, white>=0.04 (how far from grey)
	                    // Sunlit terrain often has low chroma -> dark; sky also low chroma -> dark
	                    dbg = vec3(clamp(skyChroma / 0.04, 0.0, 1.0));
	                } else if (uDebugMode == 10) {
		                    // Sky decision tree breakdown:
		                    // R = isWhiteSky, G = isBlueSky, B = nearSky dilation active
		                    // White = whiteSky+blueSky+nearSky all true (dilated zone)
		                    // Black = neither white nor blue sky classification triggered
		                    dbg = vec3(isWhiteSky ? 1.0 : 0.0, isBlueSky ? 1.0 : 0.0, nearSky ? 1.0 : 0.0);
                }
                outputColor = vec4(dbg, 1.0);
            }

            imageStore(uUpscaledOutput, p, outputColor);

            // v3 (2026-08-06): sky/cloud accumulate EXACTLY like terrain. The
            // old exclusion existed because the MV-gen used ndcZ=0 (mid-depth): sky MVs
            // were ~0 under camera rotation, history never aligned, and it smeared. The
            // MV-gen now reprojects at ndcZ=0.999 (z~100 blocks) - rotation MVs are
            // depth-INDEPENDENT (the z cancels in the UV division), so sky rotation MVs
            // are correct, and translation parallax at z~100 is <=4px. The reactive
            // mask + box clamp + luma lock protect it exactly like terrain. uCamStill=1
            // (CPU camStill) still refines this: exact identity reprojection (mv=0) +
            // reactive 0 for sky when the camera is genuinely still (see mv-zero above).
            float newTemporalReactive = temporalReactive * 0.5;
            newTemporalReactive = max(newTemporalReactive, max(reactiveFactor * 0.1, dilatedReactive));
            newTemporalReactive = min(newTemporalReactive, 0.99);
            if (isNewSample) newTemporalReactive = 1.0;
            imageStore(uInternalUpscaledRW, p, vec4(finalColor, 0.0));

            float newAccumWeight = min((isExisting ? adjAccum : 0.0) + upsampledWeight, 15.0) * rejectDecay;
            vec2 newLockStatus;
            newLockStatus.r = newLockLife * (1.0 - reactiveFactor * 0.5) / LOCK_LIFETIME;
            newLockStatus.g = newLockLife > 0.0 ? lockLuma / LOCK_LIFETIME : 0.0;
            imageStore(uLockStatusRW, p, vec4(newLockStatus, newAccumWeight / 15.0, newTemporalReactive));
        }

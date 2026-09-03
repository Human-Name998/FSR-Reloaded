  #version 430 core
  layout(local_size_x = 8, local_size_y = 8) in;
  layout(binding = 0) uniform sampler2D uInputColor;
  layout(binding = 1) uniform sampler2D uInputDepth;
  layout(binding = 2) uniform sampler2D uInputMotionVectors;
  layout(binding = 3) uniform usampler2D uReconstructedPrevDepth;
  layout(binding = 4) uniform sampler2D uDilatedData;
  layout(binding = 5) uniform sampler2D uPreviousDilatedMotionVectors;
  layout(binding = 7) uniform sampler2D uExposure;
  layout(binding = 8) uniform sampler2D uReactiveMask;
  layout(binding = 9) uniform sampler2D uTransparencyAndCompositionMask;

  layout(binding = 10, rgba16f) writeonly uniform image2D uPreparedInputColor;
  layout(binding = 11, rg8) writeonly uniform image2D uDilatedReactiveMasks;

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
  uniform int uUseDepth;

  ivec2 clampLoad(ivec2 p, ivec2 off, ivec2 sz) {
      ivec2 r = p + off;
      if (off.x < 0) r.x = max(r.x, 0);
      if (off.x > 0) r.x = min(r.x, sz.x - 1);
      if (off.y < 0) r.y = max(r.y, 0);
      if (off.y > 0) r.y = min(r.y, sz.y - 1);
      return r;
  }

  bool isOnScreen(ivec2 pos, ivec2 size) {
      return all(lessThan(uvec2(pos), uvec2(size)));
  }

  float getViewSpaceDepth(float d) {
      return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0]);
  }

  vec3 yCoCgToRgb(vec3 yCoCg) {
      return vec3(
          yCoCg.x + yCoCg.y - yCoCg.z,
          yCoCg.x + yCoCg.z,
          yCoCg.x - yCoCg.y - yCoCg.z);
  }

  vec3 rgbToYCoCg(vec3 rgb) {
      return vec3(
          0.25*rgb.r + 0.5*rgb.g + 0.25*rgb.b,
          0.5*rgb.r - 0.5*rgb.b,
          -0.25*rgb.r + 0.5*rgb.g - 0.25*rgb.b);
  }

  vec3 getViewSpacePos(ivec2 vp, ivec2 vpSize, float devDepth) {
      float Z = getViewSpaceDepth(devDepth);
      vec2 ndc = vec2(vp) / vec2(vpSize) * vec2(2, -2) + vec2(-1, 1);
      float X = uDeviceToViewDepth[2] * ndc.x * Z;
      float Y = uDeviceToViewDepth[3] * ndc.y * Z;
      return vec3(X, Y, Z);
  }

  float computeDepthClip(ivec2 pos) {
      // Depth-trust gate (2026-08-06, "everything shaking" fix): the depth
      // probe found 26.1.2's GL RenderTarget depth is essentially never
      // written (all ≈ far plane). Without this gate the sky check below
      // fires for EVERY pixel (curViewZ ≈ uFarViewZ) → full disocclusion
      // clip → history rejected → jitter shows through as shaking with
      // zero temporal smoothing. When depth is untrusted we skip
      // depth-based disocclusion entirely — the MV-based reactive mask in
      // main() still protects moving content. Same constant-depth policy
      // FSR4's probe uses.
      if (uUseDepth <= 0) return 0.0;

      vec2 uv = (vec2(pos) + 0.5) / vec2(uRenderSize);
      float curDepth = texelFetch(uInputDepth, pos, 0).r;

      // Sky/infinite depth — force full disocclusion clip to prevent NaN explosion.
      // Use view-space Z ratio instead of device-depth epsilon for the same
      // reason as ACCUMULATE_CS: device-depth fluctuates under jitter at far plane.
float curViewZ = getViewSpaceDepth(curDepth);
      if (abs(curViewZ) > abs(uFarViewZ) * 0.95) return 1.0;

      vec2 mv = texelFetch(uDilatedData, pos, 0).rg;
      float mvLen = length(mv);
      mv *= float(mvLen > 0.01);
      vec2 dilatedUv = uv + mv;
      float dilatedDepth = texelFetch(uDilatedData, pos, 0).b;

      vec2 fpx = dilatedUv * vec2(uRenderSize) - vec2(0.5);
      ivec2 base = ivec2(floor(fpx));
      vec2 frac = fract(fpx);
      vec4 w = vec4((1-frac.x)*(1-frac.y), frac.x*(1-frac.y), (1-frac.x)*frac.y, frac.x*frac.y);
      ivec2 offs[4];
      offs[0] = ivec2(0,0); offs[1] = ivec2(1,0); offs[2] = ivec2(0,1); offs[3] = ivec2(1,1);

      float depthSum = 0.0;
      float weightSum = 0.0;
      for (int i = 0; i < 4; i++) {
          if (w[i] > 0.01) {
              ivec2 sp = base + offs[i];
              if (isOnScreen(sp, uRenderSize)) {
                  float prevD = uintBitsToFloat(texelFetch(uReconstructedPrevDepth, sp, 0).r);
                  float prevViewZ = getViewSpaceDepth(prevD);
                  float diff = curViewZ - prevViewZ;
                  if (diff > 0.0) {
                      float planeD = max(prevD, curDepth);
                      vec2 halfSz = vec2(uRenderSize) * 0.5;
                      vec3 fCenter = getViewSpacePos(ivec2(halfSz), uRenderSize, planeD);
                      vec3 fCorner = getViewSpacePos(ivec2(0), uRenderSize, planeD);
                      float halfVp = length(vec2(uRenderSize));
                      float depthThresh = max(curViewZ, prevViewZ);
                      float ksep = 1.37e-05;
                      float kfov = length(fCorner) / length(fCenter);
                      float requiredSep = ksep * kfov * halfVp * depthThresh;
                      float resFactor = clamp(length(vec2(uRenderSize)) / length(vec2(1920, 1080)), 0.0, 1.0);
                      float power = mix(1.0, 3.0, resFactor);
                      float d = pow(clamp(requiredSep / max(diff, 1e-7), 0.0, 1.0), power) * w[i];
                      depthSum += d;
                      weightSum += w[i];
                  }
              }
          }
      }
      return weightSum > 0.0 ? clamp(1.0 - depthSum / weightSum, 0.0, 1.0) : 0.0;
  }

  void main() {
      ivec2 p = ivec2(gl_GlobalInvocationID.xy);
      if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

      float depthClip = computeDepthClip(p);

      vec3 rgb = max(vec3(0), texelFetch(uInputColor, p, 0).rgb);
      rgb *= uExposureVal;
      vec3 preparedYCoCg = rgbToYCoCg(rgb);
      imageStore(uPreparedInputColor, p, vec4(preparedYCoCg, depthClip));

      // Jitter-cancelled MVs for the reactive mask (2026-08-07, "shaking
      // everywhere" fix): read uDilatedData (RECONSTRUCT's output — the
      // per-frame jitter delta (J_prev−J_curr) is subtracted there) instead
      // of the raw MV-gen texture. The raw field carries the delta up to
      // ±0.75 render px STANDING STILL → divergence/velocity throttled the
      // weight to ~2 everywhere → the residual 40% jittered current showed
      // through as standing-still wobble. uDilatedData.rg is true content
      // motion; ×uRenderSize converts render-UV → render px.
      vec2 nucleusMv = texelFetch(uDilatedData, p, 0).rg * vec2(uRenderSize);
      float nucleusVel = length(nucleusMv);
      float maxVel = nucleusVel;
      float minConv = 1.0;
      int convSamples = 0;
      if (nucleusVel > 1e-2) {
          for (int y = -1; y <= 1; y++) {
              for (int x = -1; x <= 1; x++) {
                  ivec2 sp = clampLoad(p, ivec2(x,y), uRenderSize);
                  vec2 smv = texelFetch(uDilatedData, sp, 0).rg * vec2(uRenderSize);
                  float sv = length(smv);
                  maxVel = max(sv, maxVel);
                  // Zero-MV samples have NO direction — they cannot oppose
                  // the nucleus. The old sv=max(sv,1e-7) → dot(0,dir)=0 made
                  // ANY mixed field (idle-bob rotation zero-line, MV
                  // boundaries) fire divergence at 1.0 → disocclusion 0.5 →
                  // weight throttled → jagged edges while standing still.
                  // 0.05 render px: sub-half-pixel directions are noise.
                  if (sv > 0.05) {
                      minConv = min(minConv, dot(smv/sv, nucleusMv/nucleusVel));
                      convSamples++;
                  }
              }
          }
      }
      // FSR4-PARITY REACTIVE MASK (2026-08-06, "take inspiration from FSR4"):
      // the reference FSR2 divergence mask (motionDiv * velScale) saturates
      // during ANY walking at low render scales — at 25% velScale =
      // nucleusVel * (display/render)/12 = nucleusVel/3, i.e. 1.0 at just
      // 3 render px — so ordinary movement drove reactiveFactor to 1 -> adjAccum
      // to 0 -> the B=0.167 isNewSample floor -> the raw jittered frame showed
      // through -> jagged edges while moving. FSR4 keeps history alive during
      // motion with a gentle MV-magnitude ramp (smoothstep(0.5, 16.0, mvLen):
      // 1-8px walking keeps MOST history; only >16px fully discards) plus a
      // 0.15 motion floor. Port that profile here: nucleusVel is render px,
      // the same convention FSR4 MV_GEN_CS uses. Genuine disocclusion (a moving
      // object revealing background) still gets a bump via motionDiv (directional
      // divergence), capped at 0.5 so ordinary walking can no longer saturate it.
      float mvLen = nucleusVel;
      float mvReactive = smoothstep(0.5, 16.0, mvLen);
      float motionFloor = smoothstep(0.4, 2.5, mvLen) * 0.15;
      float convDiv = convSamples > 0 ? clamp(1.0 - minConv, 0.0, 1.0) : 0.0;
      // Divergence only registers with real neighborhood motion: sub-pixel
      // divergence (idle bob, quantization) is noise, not disocclusion —
      // the old maxVel/0.01 gate was trivially satisfied, so the bob's
      // rotation zero-line fired motionDiv=1 (reactive 0.5) standing still
      // (2026-08-07, jagged edges). Gate on >2 render px of motion.
      float motionDiv = convDiv * clamp(maxVel / 2.0, 0.0, 1.0);
      float disocclusion = motionDiv * 0.5;
      float reactive = max(mvReactive, max(motionFloor, disocclusion));
      vec2 reactiveMask = vec2(reactive, reactive);
      imageStore(uDilatedReactiveMasks, p, vec4(reactiveMask, 0, 0));
  }

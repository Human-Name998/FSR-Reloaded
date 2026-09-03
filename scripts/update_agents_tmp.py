import io

p = 'AGENTS.md'
s = open(p, encoding='utf-8').read()

anchor = '- **FSR2 lock B stored `adjAccum / 15.0` (wrong)**: creates zero-trap'
assert anchor in s, 'anchor not found'

add = anchor + '''
- **Jitter cancellation was SILENTLY DROPPED from ACCUMULATE reprojection (2026-08-06, "lines when moving the camera")**: FSR2/3 use JITTERED view-projection matrices for MV generation (the per-frame Halton jitter cancels inside the MV-gen reprojection — the MVs are TRUE motion), and the ACCUMULATE is supposed to add `uMotionVectorJitterCancellation` to the history read: `reprojUv = hrUv + mv + uMotionVectorJitterCancellation`. At some point in the shake-fix rounds the shader line was rewritten to `reprojUv = hrUv + mv;`, dropping the term — but the Java upload survived (`jitterCancel = (prevJitter - jitter) * motionVecScale` in dispatch, uploaded to `ac_uMotionVectorJitterCancellation`), so the uniform was DEAD CODE: declared, uploaded every frame, never used. Consequence: the history sample is off by (uJitter - uPrevJitter) render px — up to ±0.75 px at full amplitude (~3 display px at 4x upscale) — so at high-contrast edges (clouds, sun, leaves) the box clamp rejects the misaligned history and shows the fresh jittered sample: line/streak artifacts along edges during camera rotation, direction-dependent because the per-frame Halton delta direction changes. It also left a residual sub-pixel edge wobble when standing still. Fix (2026-08-06): restored the term in all 4 ACCUMULATE copies (fsr2/fsr3 disk + embedded). Sanity: with a still camera mv=0 and reprojUv = hrUv + (J_prev-J_curr)/R reads history at exactly the previous frame's sample of the same direction. ANY future edit of the reprojection UV must keep this term — grep for `reprojUv = hrUv + mv` and confirm `uMotionVectorJitterCancellation` has 2 refs (decl + use) in every ACCUMULATE copy.'''
s = s.replace(anchor, add, 1)
open(p, 'w', encoding='utf-8').write(s)
print('AGENTS.md updated')

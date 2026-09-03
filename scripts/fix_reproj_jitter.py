import re
import os

M = os.path.expandvars(r'$APPDATA/PrismLauncher/instances/26.1/minecraft')

files = [
    'src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java',
    'src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java',
    M + '/config/fsr-reloaded/shaders/fsr2/ACCUMULATE_CS.glsl',
    M + '/config/fsr-reloaded/shaders/fsr3/ACCUMULATE_CS.glsl',
]

# Replace any "vec2 reprojUv = hrUv + mv;" with the jitter-cancelled version,
# preserving leading indentation.
pat = re.compile(r'^([ \t]*)vec2 reprojUv = hrUv \+ mv;', re.M)

new_term = r'''\1// Jitter cancellation on reprojection (2026-08-06 "lines when moving"
      // fix): the history texture was written from a render jittered by
      // uPrevJitter, the current input by uJitter. Reading history at hrUv+mv
      // alone is off by (uJitter-uPrevJitter) render px - up to ±0.75 px at
      // full amplitude (~3 display px at 4x) - so at high-contrast edges
      // (clouds, sun, leaves) the box clamp rejects the misaligned history and
      // shows the fresh jittered sample: line/streak artifacts along edges
      // while rotating, direction-dependent because the per-frame Halton delta
      // changes direction. The uniform is computed + uploaded every frame in
      // FSR2/FSR3Pipeline.dispatch (jitterCancel = (prevJitter - jitter) /
      // renderSize) but its use was dropped in an earlier shake-fix round.
      // Restoring it makes the still-camera reprojection EXACT and keeps edges
      // accumulated during rotation.
      vec2 reprojUv = hrUv + mv + uMotionVectorJitterCancellation;'''

changed = 0
for f in files:
    src = open(f, encoding='utf-8').read()
    out, n = pat.subn(new_term, src)
    if n != 1:
        print('WARN', f, 'matched', n, 'occurrences')
    if out != src:
        open(f, 'w', encoding='utf-8').write(out)
        changed += 1
    print(os.path.basename(f), '->', n, 'replacement(s)')

print('files written:', changed)

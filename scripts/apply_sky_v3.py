import re, os, sys

M = os.path.expandvars(r'$APPDATA/PrismLauncher/instances/26.1/minecraft')
files = [
    'src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java',
    'src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java',
    M + '/config/fsr-reloaded/shaders/fsr2/ACCUMULATE_CS.glsl',
    M + '/config/fsr-reloaded/shaders/fsr3/ACCUMULATE_CS.glsl',
]

NL = chr(10)

# --- Edit A: history-read gate ---
a = re.compile(r'([ \t]*)if \(isExisting && uFrameIndex > 0 && \(!isSky \|\| uCamStill == 1\)\) \{')
a_rep = r'\1if (isExisting && uFrameIndex > 0) {'

# --- Edit B: accumWeight gate ---
b = re.compile(r'([ \t]*)float accumWeight = \(uFrameIndex > 0 && \(!isSky \|\| uCamStill == 1\)\) \? (lockData\.b \* 15\.0 : 0\.0);')
b_rep = r'\1float accumWeight = (uFrameIndex > 0) ? \2;'

# --- Edit C: isNewSample ---
c = re.compile(r'([ \t]*)bool isNewSample = !isExisting \|\| uFrameIndex == 0 \|\| \(\(isSky \|\| isCloud\) && uCamStill == 0\);')
c_rep = r'\1bool isNewSample = !isExisting || uFrameIndex == 0;'

# --- Edit D: classifier relaxation ---
d1 = re.compile(r'([ \t]*)bool isWhiteSky = skyLuma > 0\.95 && skyChroma < 0\.02;')
d1_rep = r'\1bool isWhiteSky = skyLuma > 0.85 && skyChroma < 0.06;'
d2 = re.compile(r'([ \t]*)bool isBlueSky = skyLuma > 0\.70 && skyCol\.b > max\(skyCol\.r, skyCol\.g\) \* 1\.30;')
d2_rep = r'\1bool isBlueSky = skyLuma > 0.55 && skyCol.b > max(skyCol.r, skyCol.g) * 1.15;'

# --- Edit E: lock-reset if/else -> plain body ---
e = re.compile(
    r'(?P<ind>[ \t]*)// FIX: sky/cloud handling completely rewritten\.' + NL +
    r'(?:[ \t]*//.*' + NL + r')+?' +
    r'(?P=ind)if \(\(isSky \|\| isCloud\) && uCamStill == 0\) \{' + NL +
    r'(?P=ind)    imageStore\(uInternalUpscaledRW, p, vec4\(finalColor, 1\.0\)\);' + NL +
    r'(?P=ind)    imageStore\(uLockStatusRW, p, vec4\(0\.0, 0\.0, 0\.0, 0\.0\)\);' + NL +
    r'(?P=ind)\} else \{' + NL +
    r'(?P<body>(?:[ \t]*.*' + NL + r')+?)' +
    r'(?P=ind)\}' + NL
)

def e_rep(m):
    ind = m.group('ind')
    lines = [
        '// v3 (2026-08-06): sky/cloud accumulate EXACTLY like terrain. The',
        '// old exclusion existed because the MV-gen used ndcZ=0 (mid-depth): sky MVs',
        '// were ~0 under camera rotation, history never aligned, and it smeared. The',
        '// MV-gen now reprojects at ndcZ=0.999 (z~100 blocks) - rotation MVs are',
        '// depth-INDEPENDENT (the z cancels in the UV division), so sky rotation MVs',
        '// are correct, and translation parallax at z~100 is <=4px. The reactive',
        '// mask + box clamp + luma lock protect it exactly like terrain. uCamStill=1',
        '// (CPU camStill) still refines this: exact identity reprojection (mv=0) +',
        '// reactive 0 for sky when the camera is genuinely still (see mv-zero above).',
    ]
    new_comment = ''.join(ind + l + NL for l in lines)
    body = []
    for line in m.group('body').splitlines(keepends=True):
        if line.startswith('    '):
            body.append(line[4:])
        else:
            body.append(line)
    return new_comment + ''.join(body)

ok = True
for f in files:
    src = open(f, encoding='utf-8').read()
    orig = src
    src, n1 = a.subn(a_rep, src, count=1)
    src, n2 = b.subn(b_rep, src, count=1)
    src, n3 = c.subn(c_rep, src, count=1)
    src, n4 = d1.subn(d1_rep, src, count=1)
    src, n5 = d2.subn(d2_rep, src, count=1)
    src, n6 = e.subn(e_rep, src, count=1)
    changed = src != orig
    ok = ok and changed
    print(os.path.basename(f), 'A=%d B=%d C=%d D1=%d D2=%d E=%d' % (n1, n2, n3, n4, n5, n6), 'CHANGED' if changed else 'NOCHANGE')
    if not changed:
        sys.exit(1)
    open(f, 'w', encoding='utf-8').write(src)
print('done')

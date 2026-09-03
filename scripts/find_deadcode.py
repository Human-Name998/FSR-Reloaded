import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else 'FSRProcessor.java'
src = open(path, encoding='utf-8', errors='replace').read()

# Strip string literals and comments so shader text / log strings don't create
# bogus references.
def strip(s):
    s = re.sub(r'"(?:\\.|[^"\\])*"', '""', s)
    s = re.sub(r"'(?:\\.|[^'\\])*'", "''", s)
    s = re.sub(r'//[^\n]*', '', s)
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return s

stripped = strip(src)

# Count every identifier that is followed by (  (method call OR declaration)
idf = re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\s*\(', stripped)
from collections import Counter
c = Counter(idf)

# Method declarations: public/private/protected/static <return> <name>(
decls = re.findall(
    r'\b(?:public|private|protected|static)[^;(={]*\b([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?:throws[^{]+)?\{',
    stripped)

lines = src.split('\n')
seen = set()
print("=== Methods defined once (possibly uncalled) ===")
for m in sorted(set(decls)):
    if c[m] <= 1 and m not in seen:
        seen.add(m)
        for i, line in enumerate(lines, 1):
            if re.search(r'\b' + re.escape(m) + r'\s*\(', line):
                print(f"  line {i:>5}  count={c[m]}  {m}")
                break

# Fields: find private static/instance field declarations and check reads.
print("\n=== Fields possibly never read ===")
# fields are:  private <type> <name> = ...;  or  private X name;
field_decls = re.findall(
    r'\b(?:private|protected)(?:\s+static)?[^;=]*?\b([A-Za-z_][A-Za-z0-9_]*)\s*[=;]', stripped)

def count_read(name):
    # crude: occurrences outside of: 'this.name =', 'name :=', declaration line
    pat = re.compile(r'\b' + re.escape(name) + r'\b')
    total = len(pat.findall(stripped))
    return total

for name in sorted(set(field_decls)):
    if name in ('true', 'false', 'null', 'class'):
        continue
    total = count_read(name)
    if total <= 2:  # declaration + assignment only => never really read
        for i, line in enumerate(lines, 1):
            if re.search(r'\b(?:private|protected).*\b' + name + r'\b', line):
                print(f"  line {i:>5}  occ={total}  {name}")
                break
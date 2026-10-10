#!/usr/bin/env bash
# Prints failing unit tests (name + first lines of the failure) as GitHub annotations, so they are readable without signing in.
# Usage: scripts/ci-test-failures.sh app/build/test-results
dir="${1:-app/build/test-results}"
[ -d "$dir" ] || exit 0
python3 - "$dir" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
out = []
for f in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    for tc in root.iter("testcase"):
        for kind in ("failure", "error"):
            for el in tc.findall(kind):
                msg = (el.get("message") or "")[:300]
                body = "\n".join((el.text or "").splitlines()[:12])
                out.append("%s.%s: %s\n%s" % (tc.get("classname"), tc.get("name"), msg, body))
if out:
    text = "\n---\n".join(out[:8])
    enc = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::error title=Failing tests::" + enc)
PY

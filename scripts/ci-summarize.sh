#!/usr/bin/env bash
# Prints compiler/Gradle errors from a build log as GitHub annotations so they are readable without auth.
# Usage: scripts/ci-summarize.sh build.log
log="${1:-build.log}"
[ -f "$log" ] || exit 0
errs=$(grep -E '^e: |error:|^> Task .* FAILED|What went wrong|^\* What|AAPT|Execution failed|FAILURE|Could not|Unresolved|ksp' "$log" | sed -E 's|file://||; s|/home/runner/work/[^/]+/[^/]+/||' | awk '!seen[$0]++' | head -60)
[ -n "$errs" ] || exit 0
# Single multi-line annotation (newlines must be %0A in workflow commands).
enc=$(printf '%s' "$errs" | sed ':a;N;$!ba;s/%/%25/g;s/\r/%0D/g;s/\n/%0A/g')
echo "::error title=Build errors::$enc"
# Also one annotation per Kotlin error with file/line when parseable.
printf '%s\n' "$errs" | grep -E '^e: ' | head -20 | while IFS= read -r l; do
  f=$(printf '%s' "$l" | sed -E 's/^e: ([^:]+):([0-9]+):([0-9]+) .*/\1/')
  n=$(printf '%s' "$l" | sed -E 's/^e: ([^:]+):([0-9]+):([0-9]+) .*/\2/')
  m=$(printf '%s' "$l" | sed -E 's/^e: [^ ]+ //')
  case "$n" in ''|*[!0-9]*) echo "::error::$l" ;; *) echo "::error file=$f,line=$n::$m" ;; esac
done
{ echo "### Build errors"; echo '```'; echo "$errs"; echo '```'; } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

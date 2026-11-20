#!/usr/bin/env bash
# Run upstream's CI suites on one exact commit, on this Mac (Xcode + JDK 17).
#   gate.sh <repo> <commit> <out-dir> [suite...]   suites: tools parity hygiene swift app ios android
# Each suite writes <out-dir>/<suite>.log; summary.json maps suite -> exit code.
# The commit is checked out into its own worktree, so baseline and staging can run side by side.
set -uo pipefail
repo=$1 commit=$2 out=$3; shift 3
suites=${*:-tools parity hygiene swift app ios android}
mkdir -p "$out"; out=$(cd "$out" && pwd)
wt="$out/tree"
git -C "$repo" worktree remove --force "$wt" 2>/dev/null
git -C "$repo" worktree add -q --detach "$wt" "$commit" || exit 2
cd "$wt"
echo "{\"commit\":\"$(git rev-parse HEAD)\",\"started\":\"$(date -u +%FT%TZ)\"}" > "$out/meta.json"
export JAVA_HOME=${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
dd="$out/DerivedData"

run_suite() {
  case $1 in
    tools)   (cd Tools && python3 -m unittest discover -p 'test_*.py') ;;
    parity)  (cd Tools && python3 -m unittest -v tests.test_parity_ledger tests.test_parity_governance_acceptance \
                tests.test_parity_disposition_kinds tests.test_parity_harness) ;;
    hygiene) python3 Tools/doc_comment_lint.py && python3 docs/protocol-examples/validate_examples.py \
               && python3 docs/protocol-examples/check_source_references.py ;;
    swift)   rc=0; for p in WhoopProtocol OuraProtocol PolarProtocol WhoopStore StrandAnalytics StrandImport StrandDesign NoopLocalAccess; do
               echo "### $p"; (cd "Packages/$p" && swift test) || { echo "### FAIL $p"; rc=1; }; done; return $rc ;;
    app)     xcodegen generate && xcodebuild -scheme Strand -configuration Debug -destination 'platform=macOS' \
               -derivedDataPath "$dd" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO test ;;
    ios)     xcodegen generate && xcodebuild -scheme NOOPiOS -configuration Debug -destination 'generic/platform=iOS Simulator' \
               -derivedDataPath "$dd" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build ;;
    android) printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
             (cd android && ./gradlew --no-daemon --no-build-cache assembleFullDebug testFullDebugUnitTest) ;;
  esac
}

summary="{"
for s in $suites; do
  t0=$(date +%s); run_suite "$s" > "$out/$s.log" 2>&1; rc=$?
  echo "$s rc=$rc $(( $(date +%s) - t0 ))s"
  summary+="\"$s\":$rc,"
done
echo "${summary%,}}" > "$out/summary.json"
cat "$out/summary.json"

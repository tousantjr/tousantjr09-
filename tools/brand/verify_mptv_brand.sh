#!/usr/bin/env bash
# Fails if any module's mptv_brand.xml overrides are stale relative to its pinned :core version, or
# if the generated MPTV brand drawables differ from what tools/brand/render_mptv_marks.py produces.
#
# mptv_strings.py's own docstring says to re-run it after every core pin bump, but nothing enforced
# that — a core bump landed without it once already (see git history), silently leaving new
# "OwnTV"-named strings unrebranded until someone noticed by hand. This script is that enforcement:
# it regenerates each module's mptv_brand.xml files from a fresh clone of its pinned OwnTV_Core tag
# and fails if the result differs from what's committed.
#
# Usage: tools/brand/verify_mptv_brand.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

# The in-app logo drawables (wordmark, marks, notification icon) that override core's OwnTV ones in
# both apps. Generated from a font the repo ships, so a plain re-run must reproduce them exactly.
python3 tools/brand/render_mptv_marks.py --check

# "<versions.toml path>:<module res dir>" pairs. Add an entry here for any future module that
# vendors its own :core pin and needs MPTV's OwnTV -> MPTV brand overrides.
MODULES=(
  "gradle/libs.versions.toml:app/src/main/res"
)
if [ -d mobile ]; then
  MODULES+=("mobile/gradle/libs.versions.toml:mobile/app/src/main/res")
fi

STALE=0
for entry in "${MODULES[@]}"; do
  TOML="${entry%%:*}"
  RES="${entry##*:}"

  PIN=$(sed -n 's/^owntvCore = "\(.*\)"$/\1/p' "$TOML")
  if [ -z "$PIN" ]; then
    echo "::error::Could not read owntvCore from $TOML."
    exit 1
  fi

  TMP=$(mktemp -d)
  echo "Checking $RES against core-$PIN..."
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "core-$PIN" \
    https://github.com/ahXN00/OwnTV_Core "$TMP"
  python3 tools/brand/mptv_strings.py "$TMP" "$RES"
  rm -rf "$TMP"

  if ! git diff --quiet -- "$RES"; then
    echo "::error::$RES's mptv_brand.xml files are stale for core-$PIN. Run:"
    echo "  python3 tools/brand/mptv_strings.py <OwnTV_Core checkout at core-$PIN> $RES"
    echo "and commit the result. Diff:"
    git --no-pager diff -- "$RES"
    STALE=1
  else
    echo "$RES is up to date with core-$PIN."
  fi
done

exit $STALE

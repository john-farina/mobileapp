#!/bin/sh
# Probe: does Xcode Cloud restore anything into derived data before xcodebuild?
# Post-clone saw nothing; if this also shows nothing, there is no cross-build cache.
set -u
echo "--- derived data before xcodebuild ---"
ls -la "${CI_DERIVED_DATA_PATH:-/nonexistent}" 2>&1 | head -20
du -sh "${CI_DERIVED_DATA_PATH:-/nonexistent}" 2>/dev/null || true

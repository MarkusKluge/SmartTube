#!/usr/bin/env bash
# Build (and optionally install) the local "Claude" variant org.smarttube.fix.
#   ./claude-build.sh            build the arm64 release APK
#   ./claude-build.sh install    build + adb install on the Shield
# Signing key lives outside the repo: ~/.config/smarttube-fix/{fix.jks,keystore.properties}
# (back it up — a new key means uninstalling the app and losing its settings).
set -euo pipefail
cd "$(dirname "$0")"

KEYS="${SMARTTUBE_KEYS:-$HOME/.config/smarttube-fix}"
GRADLE_CACHE="${SMARTTUBE_GRADLE_CACHE:-$HOME/.cache/smarttube-gradle}"
SHIELD="${SHIELD:-$(cat "$KEYS/shield" 2>/dev/null || true)}"   # adb host:port
IMAGE=ghcr.io/cirruslabs/android-sdk:33   # JDK 17; Gradle 7.5 doesn't run on JDK 21

[ -f "$KEYS/keystore.properties" ] || { echo "missing $KEYS/keystore.properties" >&2; exit 1; }
cp "$KEYS/keystore.properties" keystore.properties   # ignored via .git/info/exclude
mkdir -p "$GRADLE_CACHE"
git submodule update --init -q

# Newest upstream (yuliskov) commit contained in this build, shown in the sidebar
UPSTREAM_BASE=$(git merge-base HEAD upstream/master 2>/dev/null || true)
UPSTREAM_COMMIT=$(git log -1 --format=%h "${UPSTREAM_BASE:-HEAD}")
UPSTREAM_TIME=$(git log -1 --format=%ct "${UPSTREAM_BASE:-HEAD}")

# Changelog for the sidebar dialog (generated into the APK assets on every build, never committed):
# <unix time>\t<L=local|U=upstream|V=version bump>\t<ST|MSC>\t<hash>\t<subject|versionName>
NOISE='^(Merge |(upd|tr|bump)( |:|$)|chore: update (translation|MediaServiceCore|SharedModules)|Update .*(strings|localization|translation)|.*strings\.xml)'
changelog() { # <repo dir> <tag> <upstream url>
    local dir=$1 tag=$2 url=$3 base
    git -C "$dir" remote get-url upstream >/dev/null 2>&1 || git -C "$dir" remote add upstream "$url"
    git -C "$dir" fetch -q upstream 2>/dev/null || true
    base=$(git -C "$dir" merge-base HEAD upstream/master 2>/dev/null) || return 0
    git -C "$dir" log --no-merges --format="%at%x09L%x09$tag%x09%h%x09%s" "$base..HEAD"
    git -C "$dir" log --no-merges --since=120.days --format="%at%x09U%x09$tag%x09%h%x09%s" "$base" \
        | NOISE="$NOISE" awk -F'\t' '$5 !~ ENVIRON["NOISE"]'
}
versions() { # when versionName changed (upstream "bump"), so the dialog can group by app version
    local base h
    base=$(git merge-base HEAD upstream/master 2>/dev/null) || return 0
    git log --since=150.days --format='%at %h' -G'versionName "' "$base" -- smarttubetv/build.gradle |
        while read -r t h; do
            printf '%s\tV\tST\t%s\t%s\n' "$t" "$h" "$(git show "$h:smarttubetv/build.gradle" | sed -n 's/.*versionName "\(.*\)".*/\1/p' | head -1)"
        done
}
CHANGELOG_DIR=smarttubetv/build/generated/claude_assets
mkdir -p "$CHANGELOG_DIR"
{ changelog . ST https://github.com/yuliskov/SmartTube.git
  versions
  changelog MediaServiceCore MSC https://github.com/yuliskov/MediaServiceCore.git
} | sort -t$'\t' -k1,1nr > "$CHANGELOG_DIR/claude_changelog.tsv"
echo "changelog: $(wc -l < "$CHANGELOG_DIR/claude_changelog.tsv") entries"

# Server has 8 GB: cap the container (3 GB got OOM-killed on a cold cache), 2 workers, in-process Kotlin
docker rm -f st-build >/dev/null 2>&1 || true
docker run -d --name st-build --memory="${SMARTTUBE_BUILD_MEM:-4g}" \
    -v "$PWD":/src -v "$KEYS":/keys:ro -v "$GRADLE_CACHE":/root/.gradle -w /src \
    -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" "$IMAGE" \
    bash -c 'bash ./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
        -PclaudeUpstreamCommit='"$UPSTREAM_COMMIT"' -PclaudeUpstreamTime='"$UPSTREAM_TIME"' \
        :smarttubetv:assembleStbetaRelease -x lint > /src/build.log 2>&1; rc=$?; \
        chown -R "$HOST_UID:$HOST_GID" /src /root/.gradle; echo "EXIT=$rc" >> /src/build.log; exit $rc' >/dev/null
echo "build running in container st-build, log: $PWD/build.log"
rc=$(docker wait st-build)
docker rm st-build >/dev/null
grep -E "^BUILD|EXIT=" build.log | tail -2
[ "$rc" = 0 ] || exit "$rc"

APK=$(ls -t smarttubetv/build/outputs/apk/stbeta/release/*arm64-v8a.apk | head -1)
echo "$APK"
if [ "${1:-}" = install ]; then
    [ -n "$SHIELD" ] || { echo "set SHIELD or $KEYS/shield" >&2; exit 1; }
    adb connect "$SHIELD" >/dev/null
    adb -s "$SHIELD" install -r "$APK"
fi

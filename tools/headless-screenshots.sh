#!/usr/bin/env bash
# Boots the dev client on a virtual X display, joins a world and takes
# Minecraft screenshots (F2) between keybind presses, e.g. native vs upscaled.
#
# Needs: Xvfb, xdotool, a Vulkan driver (Mesa lavapipe works without a GPU),
# JAVA_HOME pointing at JDK 25, and a world at run/saves/$WORLD.
#
#   tools/headless-screenshots.sh [world] [key sequence...]
#   tools/headless-screenshots.sh testworld F2 F8 F2      # upscaled, toggle off, native
#
# Screenshots land in run/screenshots/, the client log in build/headless-client.log.
set -euo pipefail

cd "$(dirname "$0")/.."
WORLD="${1:-testworld}"
shift || true
KEYS=("$@")
[ ${#KEYS[@]} -eq 0 ] && KEYS=(F2 F8 F2)

DISPLAY_NUM="${DISPLAY_NUM:-:99}"
LOG=build/headless-client.log
mkdir -p build run

if [ ! -d "run/saves/$WORLD" ]; then
	echo "no world at run/saves/$WORLD" >&2
	exit 1
fi

# Force the Vulkan backend and skip first-launch screens.
if [ ! -f run/options.txt ]; then
	cat > run/options.txt <<'OPTS'
preferredGraphicsBackend:"vulkan"
onboardAccessibility:false
narrator:0
pauseOnLostFocus:false
renderDistance:8
soundCategory_master:0.0
tutorialStep:none
OPTS
fi

if ! xdpyinfo -display "$DISPLAY_NUM" >/dev/null 2>&1; then
	Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 >/dev/null 2>&1 &
	sleep 2
fi
export DISPLAY="$DISPLAY_NUM"

./gradlew --no-daemon runClient --args="--quickPlaySingleplayer $WORLD" >"$LOG" 2>&1 &
GRADLE_PID=$!
cleanup() {
	pkill -f 'net.fabricmc.devlaunchinjector.Main' 2>/dev/null || true
	kill "$GRADLE_PID" 2>/dev/null || true
}
trap cleanup EXIT

if ! timeout 600 bash -c "until grep -qE 'joined the game|---- Minecraft Crash Report|BUILD FAILED' '$LOG'; do sleep 3; done"; then
	echo "client did not reach the world in time; see $LOG" >&2
	exit 1
fi
if grep -qE -- '---- Minecraft Crash Report|BUILD FAILED' "$LOG"; then
	echo "client crashed; see $LOG" >&2
	exit 1
fi
grep -E 'Using graphics backend' "$LOG" || true

# Let chunks load before capturing.
sleep 20
WINDOW="$(xdotool search --name 'Minecraft' | head -1)"
for key in "${KEYS[@]}"; do
	xdotool key --window "$WINDOW" "$key"
	sleep 4
done

grep -E 'Saved screenshot' "$LOG" | sed 's/.*Saved screenshot as /screenshot: run\/screenshots\//'

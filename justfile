# Kiln workflow recipes — invoked via `just <recipe>`
# Requires: just (winget Casey.Just) + JDK 21 Temurin + Gradle wrapper
# ADB path is hard-coded since adb is not in PATH (per CLAUDE.md hardware notes)

ADB := "C:/Users/chawo/Desktop/platform-tools/adb.exe"

default: verify

# Canonical session-validation build (per CLAUDE.md workflow)
verify:
    ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest

# Compile-only verify (skip tests; faster for in-session sanity checks)
verify-quick:
    ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build

# Build, install, and launch on attached Pixel
pixel: pixel-install pixel-launch

pixel-install:
    ./gradlew :app-android:assembleDebug
    "{{ADB}}" install -r app-android/build/outputs/apk/debug/app-android-debug.apk

pixel-launch:
    "{{ADB}}" shell am start -n com.clayworks.kiln/.MainActivity

# Launch desktop app (creates %AppData%/kiln/kiln.db on first run — required for dbhub MCP)
desktop:
    ./gradlew :app-desktop:run

# Run all desktop tests (incl. JvmFlacDecoder + library scanner smoke)
test-desktop:
    ./gradlew :audio:playback:desktopTest :data:library:desktopTest

# Show ADB-connected devices
devices:
    "{{ADB}}" devices -l

# Stream filtered logcat for Kiln on Pixel
logcat:
    "{{ADB}}" logcat -v threadtime -s com.clayworks.kiln:V Kiln:V

# Clean Gradle build state (use sparingly — 30-60s rebuild after)
clean:
    ./gradlew clean

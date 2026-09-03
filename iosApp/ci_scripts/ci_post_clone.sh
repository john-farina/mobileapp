#!/bin/sh
# Xcode Cloud: prepare a Kotlin Multiplatform checkout before xcodebuild runs.
#
# Xcode Cloud gives you Xcode and the Apple toolchain. It does not give you a
# JDK, and this project's Xcode build shells out to Gradle -- the CocoaPods
# integration builds the Kotlin framework from an Xcode build phase -- so
# without Java the archive fails inside a run script rather than obviously.
#
# Runs from iosApp/ci_scripts; CI_PRIMARY_REPOSITORY_PATH is the repo root.
set -eu

echo "--- machine ---"
sysctl -n hw.memsize | awk '{printf "memory: %.1f GB\n", $1/1024/1024/1024}'
sysctl -n hw.ncpu | awk '{print "cores: " $1}'

cd "$CI_PRIMARY_REPOSITORY_PATH"

# Print every gradle task that takes more than 5s, so build time is measured
# rather than guessed. Shows up in the xcodebuild log for the archive step too.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/task-timing.gradle" <<'INIT'
// Build-service form: taskGraph.afterTask listeners fail under the configuration cache.
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent

abstract class TaskTiming implements BuildService<BuildServiceParameters.None>, OperationCompletionListener {
  void onFinish(FinishEvent event) {
    if (!(event instanceof TaskFinishEvent)) return
    long ms = event.result.endTime - event.result.startTime
    if (ms > 5000) println String.format("TASK-TIME %6.1fs %s", ms / 1000.0, event.descriptor.taskPath)
  }
}
abstract class TaskTimingPlugin implements Plugin<Gradle> {
  @Inject abstract BuildEventsListenerRegistry getRegistry()
  void apply(Gradle gradle) {
    registry.onTaskCompletion(gradle.sharedServices.registerIfAbsent("taskTiming", TaskTiming) {})
  }
}
apply plugin: TaskTimingPlugin
INIT

echo "--- java 17 ---"
# Temurin 17: the version the project's toolchain expects. Java 8 will not work.
brew install --quiet openjdk@17
JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH
java -version

# The Xcode build phase that runs gradlew (the composeApp podspec's
# "Build composeApp" script) inherits nothing exported here, and brew's JDK is
# keg-only, so /usr/bin/java finds nothing. Registering the JDK where
# /usr/libexec/java_home scans makes it resolvable with no environment at all.
mkdir -p "$HOME/Library/Java/JavaVirtualMachines"
ln -sfn "$(brew --prefix openjdk@17)/libexec/openjdk.jdk" "$HOME/Library/Java/JavaVirtualMachines/openjdk-17.jdk"
/usr/libexec/java_home -v 17

echo "--- android sdk ---"
# The Gradle build configures an Android target even for an iOS-only archive, so
# it needs an SDK location. The command line tools are enough; Android Studio is
# not required.
brew install --quiet --cask android-commandlinetools || true
ANDROID_SDK="$(brew --prefix)/share/android-commandlinetools"
if [ -d "$ANDROID_SDK" ]; then
  echo "sdk.dir=$ANDROID_SDK" > local.properties
  yes | "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
fi

echo "--- version tag ---"
# A build phase runs `git describe --tags --abbrev=0` and parses X.Y.Z or
# X.Y.Z.B, erroring on anything else. Xcode Cloud clones without tags, so the
# archive would fail before compiling anything.
#
# CI_BUILD_NUMBER is Xcode Cloud's own monotonic counter, which is exactly what
# CFBundleVersion wants: TestFlight rejects a build number it has seen before.
#
# TestFlight groups builds by CFBundleShortVersionString, so each branch gets
# its own section by owning a version: stone is 1.0.0, feat/<n>-anything is
# 1.<n>.0, and anything else is 1.999.0 so it is visible but obviously stray.
case "${CI_BRANCH:-}" in
  stone) VERSION="1.0.0" ;;
  feat/[0-9]*) VERSION="1.$(echo "$CI_BRANCH" | sed -E 's#^feat/([0-9]+).*#\1#').0" ;;
  *) VERSION="1.999.0" ;;
esac
if ! git describe --tags --abbrev=0 >/dev/null 2>&1; then
  git -c user.name="Xcode Cloud" -c user.email="ci@localhost" \
    tag -a "${VERSION}.${CI_BUILD_NUMBER:-1}" -m "Xcode Cloud build ${CI_BUILD_NUMBER:-1} of ${CI_BRANCH:-?}"
fi
echo "version tag: $(git describe --tags --abbrev=0)"

echo "--- what to test ---"
# Xcode Cloud reads ci_scripts/TestFlight/WhatToTest.<locale>.txt as the
# build's TestFlight notes. A branch's LEDGER.md is that text.
mkdir -p iosApp/ci_scripts/TestFlight
{
  echo "branch: ${CI_BRANCH:-?}  commit: $(git rev-parse --short HEAD)"
  echo
  [ -f LEDGER.md ] && cat LEDGER.md || git log -1 --format=%B
} > iosApp/ci_scripts/TestFlight/WhatToTest.en-US.txt

echo "--- gradle heaps ---"
# The upstream heaps (native 12g / daemon 6g / gradle 4g) still overflow on the
# Xcode Cloud machine. GRADLE_USER_HOME properties take precedence over the
# project's gradle.properties, so size them for a 64 GB box here and leave the
# upstream file alone. Applies to the xcodebuild gradle phase too: same user.
#
# preferIPv4Stack: four consecutive builds failed resolving dl.google.com with
# java.net "No route to host", which is what Java reports when it picks a host's
# AAAA record on a machine without an IPv6 route.
mkdir -p "$HOME/.gradle"
cat > "$HOME/.gradle/gradle.properties" <<'GRADLE'
kotlin.native.jvmArgs=-Xmx24g -Djava.net.preferIPv4Stack=true
kotlin.daemon.jvmargs=-Xmx12g -Djava.net.preferIPv4Stack=true
org.gradle.jvmargs=-Xmx8g -Djava.net.preferIPv4Stack=true
GRADLE

echo "--- firebase plist ---"
# The Crashlytics build phase reads GoogleService-Info.plist in Release. The
# real one is gitignored, so use the dummy upstream ships.
cp iosApp/iosApp/GoogleService-Info-dummy.plist iosApp/iosApp/GoogleService-Info.plist

echo "--- cocoapods ---"
# Pods is not committed, so the workspace has no dependencies until this runs.
# podInstall also generates the Kotlin framework podspec.
./gradlew podInstall --no-daemon --stacktrace

# The Xcode build phase's gradle run would otherwise execute these two tasks in
# parallel, and two `pod install`s copying FirebaseFirestoreGRPCCPPBinary out of
# the same CocoaPods cache at once fail with Errno::ENOENT. Run them one at a
# time here so the build phase finds them UP-TO-DATE.
./gradlew :experimental:podInstallSyntheticIos --no-daemon
./gradlew :composeApp:podInstallSyntheticIos --no-daemon

echo "--- kotlin framework ---"
# Compile and link the Kotlin framework here, not inside xcodebuild. The archive
# phase's gradle run has failed 5 of 13 builds with 'No route to host' fetching
# dependencies; post-clone's gradle has never failed. This is the task the
# podspec's "Build composeApp" phase (syncFramework) depends on for a Release
# arm64 archive, so that phase finds it UP-TO-DATE and downloads nothing.
# syncFramework itself cannot run here: the compose resources step reads
# Xcode's environment and refuses without it.
./gradlew :composeApp:linkPodReleaseFrameworkIosArm64 --no-daemon

echo "--- ready ---"

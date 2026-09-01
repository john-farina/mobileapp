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
if ! git describe --tags --abbrev=0 >/dev/null 2>&1; then
  git -c user.name="Xcode Cloud" -c user.email="ci@localhost" \
    tag -a "1.0.0.${CI_BUILD_NUMBER:-1}" -m "Xcode Cloud build ${CI_BUILD_NUMBER:-1}"
fi
echo "version tag: $(git describe --tags --abbrev=0)"

echo "--- cocoapods ---"
# Pods is not committed, so the workspace has no dependencies until this runs.
# podInstall also generates the Kotlin framework podspec.
./gradlew podInstall --no-daemon --stacktrace

echo "--- ready ---"

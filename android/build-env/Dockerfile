# Android build toolchain for the Marquee app: JDK 17, Android SDK 35, Gradle 8.11.1.
# The Android SDK license is accepted non-interactively here (approved by John, 2026-10-15).
# Build:  docker build -t android-build android/build-env
# Use:    docker run --rm -v "$PWD/android":/src -v android-gradle-cache:/cache android-build gradle assembleRelease
FROM eclipse-temurin:17-jdk-jammy
ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android GRADLE_USER_HOME=/cache/gradle
RUN apt-get update && apt-get install -y --no-install-recommends unzip wget && rm -rf /var/lib/apt/lists/*
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
 && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q commandlinetools-linux-*.zip && mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm -f commandlinetools-linux-*.zip
ENV PATH=$PATH:/opt/android/cmdline-tools/latest/bin:/opt/android/platform-tools:/opt/gradle/bin
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null
RUN cd /tmp && wget -q https://services.gradle.org/distributions/gradle-8.11.1-bin.zip \
 && unzip -q gradle-8.11.1-bin.zip -d /opt && mv /opt/gradle-8.11.1 /opt/gradle && rm gradle-8.11.1-bin.zip
WORKDIR /src

FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    unzip wget curl && rm -rf /var/lib/apt/lists/*

# Android SDK command-line tools
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools && \
    mv /tmp/cmdtools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm -rf /tmp/cmdtools /tmp/cmdtools.zip

RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager "platforms;android-34" "build-tools;34.0.0" > /dev/null 2>&1

WORKDIR /project
COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew gradlew
RUN chmod +x gradlew

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/build.gradle.kts

# Pre-download gradle + dependencies
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY app/ app/

RUN ./gradlew --no-daemon assembleDebug && \
    cp app/build/outputs/apk/debug/app-debug.apk /project/fang-spoof.apk

ENTRYPOINT ["cat", "/project/fang-spoof.apk"]

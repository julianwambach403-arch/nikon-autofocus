FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        unzip \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Android SDK. AGP 8.7 needs API 35 plus build-tools 35.0.0 and 34.0.0.
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdtools.zip "${CMDLINE_TOOLS_URL}" \
    && unzip -q /tmp/cmdtools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
        "build-tools;34.0.0" \
    && chmod -R a+rwX "${ANDROID_HOME}"

# Match the default Cursor Cloud Agent user so checkout and commands run as "ubuntu".
RUN (id -u ubuntu >/dev/null 2>&1 || useradd -m -s /bin/bash ubuntu)

USER ubuntu

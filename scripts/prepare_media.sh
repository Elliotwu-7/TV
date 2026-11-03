#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEDIA_COMMIT="3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d"
MEDIA_DIR="${Z10_MEDIA_DIR:-${ROOT_DIR}/build/media/${MEDIA_COMMIT}}"
ARCHIVE="${ROOT_DIR}/build/media-${MEDIA_COMMIT}.tar.gz"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"

if [[ ! -d "${MEDIA_DIR}/libraries" ]]; then
  mkdir -p "${ROOT_DIR}/build" "${MEDIA_DIR}"
  curl -fL --retry 3 "https://codeload.github.com/FongMi/media/tar.gz/${MEDIA_COMMIT}" -o "${ARCHIVE}"
  tar -xzf "${ARCHIVE}" --strip-components=1 -C "${MEDIA_DIR}"
fi

# Z10 uses system codecs; build the Java FFmpeg bridge without compiling ARM FFmpeg.
python3 - "${MEDIA_DIR}" <<'PYTHON'
from pathlib import Path
import sys
p = Path(sys.argv[1]) / "libraries/decoder_ffmpeg/build.gradle.kts"
s = p.read_text()
if 'providers.gradleProperty("z10SystemCodecs")' not in s:
    s = s.replace('ndkVersion = nativeDependencies.getProperty("android.ndk.version")',
                  'ndkVersion = "28.2.13676358"')
    s = s.replace('if (project.file("src/main/ffmpeg/include/libavcodec/avcodec.h").exists()) {',
                  'if (!providers.gradleProperty("z10SystemCodecs").isPresent && project.file("src/main/ffmpeg/include/libavcodec/avcodec.h").exists()) {')
    p.write_text(s)
PYTHON

MODULES=(common container database datasource datasource-okhttp datasource-rtmp decoder decoder-ffmpeg effect exoplayer exoplayer-dash exoplayer-hls exoplayer-rtsp exoplayer-smoothstreaming extractor session ui ui-danmaku)
TASKS=()
for module in "${MODULES[@]}"; do TASKS+=(":lib-${module}:assembleRelease"); done
(cd "${MEDIA_DIR}" && bash ./gradlew "${TASKS[@]}" -Pz10SystemCodecs --no-daemon --max-workers="${Z10_MEDIA_WORKERS:-4}" "$@")

mkdir -p "${ROOT_DIR}/app/libs"
for module in "${MODULES[@]}"; do
  directory="${module//-/_}"
  aar="lib-${module}-release.aar"
  cp "${MEDIA_DIR}/libraries/${directory}/buildout/outputs/aar/${aar}" "${ROOT_DIR}/app/libs/${aar}"
done
printf 'Prepared %s Media3 AARs in app/libs\n' "${#MODULES[@]}"

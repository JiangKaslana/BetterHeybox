#!/usr/bin/env bash
set -euo pipefail

chmod +x gradlew

short_commit="${GIT_COMMIT:-local}"
short_commit="${short_commit:0:8}"
version_name="v0.6.0-rootless-${short_commit}"
version_code=600001

./gradlew clean assembleDebug \
  --project-prop VERSION_NAME="${version_name}" \
  --project-prop VERSION_CODE="${version_code}" \
  --stacktrace

apk="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' | head -n 1)"
if [ -z "${apk}" ] || [ ! -f "${apk}" ]; then
  echo "Debug APK not found" >&2
  exit 1
fi

group_id="${GROUP:-com.github.JiangKaslana}"
artifact_id="${ARTIFACT:-BetterHeybox}"
artifact_version="${VERSION:-${short_commit}}"
group_path="${group_id//./\/}"
repo_dir="${HOME}/.m2/repository/${group_path}/${artifact_id}/${artifact_version}"
mkdir -p "${repo_dir}"

cp "${apk}" "${repo_dir}/${artifact_id}-${artifact_version}.apk"
cat > "${repo_dir}/${artifact_id}-${artifact_version}.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${group_id}</groupId>
  <artifactId>${artifact_id}</artifactId>
  <version>${artifact_version}</version>
  <packaging>apk</packaging>
</project>
EOF

printf '%s\n' "APK=${apk}" "M2=${repo_dir}/${artifact_id}-${artifact_version}.apk"

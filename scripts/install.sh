#!/usr/bin/env bash
# 署名付きのリリース版をビルドしてメインユーザー (0) にだけ入れる。
# - 端末にはリリース用の鍵で署名した版が入っているので、デバッグ版では上書きできない
# - adb install は既定で全ユーザーに入れるため、Galaxy の Dual Messenger (DUAL_APP ユーザー) にも入ってしまう
# 署名には keystore.properties (リポジトリ外の鍵を指す) が必要。
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew assembleRelease --console=plain -q
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb"
MSYS_NO_PATHCONV=1 "$ADB" install -r --user 0 app/build/outputs/apk/release/app-release.apk

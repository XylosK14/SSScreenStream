#!/usr/bin/env bash
# ============================================================
# SSScreenStream 一键构建脚本
# 构建推流端 / 拉流端两个 Debug APK。
# 若某 APK 之前已编译过，则其 versionCode 自动 +1（符合版本递增要求）。
# 用法: bash build.sh
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="$ROOT/../toolchain"

# ---------- 定位/准备工具链 ----------
if [ -x "$TC/jdk/bin/java" ] && [ -x "$TC/gradle/gradle-8.11.1/bin/gradle" ] \
   && [ -d "$TC/sdk/platforms/android-36" ]; then
  export JAVA_HOME="$TC/jdk"
  export ANDROID_HOME="$TC/sdk"
  GRADLE="$TC/gradle/gradle-8.11.1/bin/gradle"
else
  # 回退到系统已安装的 gradle / sdk
  export JAVA_HOME="${JAVA_HOME:-/usr}"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  GRADLE="gradle"
fi
export PATH="$JAVA_HOME/bin:$PATH"

bump_version() {
  local proj="$1/app"
  local apk="$proj/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$apk" ]; then
    local cur
    cur="$(grep -oE 'versionCode [0-9]+' "$proj/build.gradle" | awk '{print $2}')"
    local next=$((cur + 1))
    # 使用临时文件替换 versionCode
    sed "s/versionCode ${cur}/versionCode ${next}/" "$proj/build.gradle" > "$proj/build.gradle.tmp"
    mv "$proj/build.gradle.tmp" "$proj/build.gradle"
    echo "  $1: 检测到旧 APK，versionCode $cur -> $next"
  fi
}

echo "==> 构建推流端 (publisher)"
bump_version "$ROOT/publisher"
(cd "$ROOT/publisher" && "$GRADLE" :app:assembleDebug --no-daemon)

echo "==> 构建拉流端 (player)"
bump_version "$ROOT/player"
(cd "$ROOT/player" && "$GRADLE" :app:assembleDebug --no-daemon)

# ---------- 汇总产物 ----------
OUT="$ROOT/build-outputs"
mkdir -p "$OUT"
cp "$ROOT/publisher/app/build/outputs/apk/debug/app-debug.apk" "$OUT/SSPublisher-debug.apk"
cp "$ROOT/player/app/build/outputs/apk/debug/app-debug.apk"   "$OUT/SSPlayer-debug.apk"

echo ""
echo "==> 构建完成，产物位于: $OUT"
ls -lh "$OUT"
echo "    服务器: $ROOT/server/ssserver.py (python3 直接运行)"

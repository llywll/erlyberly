#!/usr/bin/env bash
# 打包 erlyberly 为 macOS DMG（内嵌 JRE + JavaFX，目标机无需安装 Java）
# 产物含 erlyberly.app + Applications 拖拽安装链接
# 用法: bash build-dmg.sh
set -euo pipefail
cd "$(dirname "$0")"

# 1) 定位 JDK（需 25+，带 jpackage/jlink）
JDK="${JAVA_HOME:-}"
if [ -z "$JDK" ]; then
  for d in ~/Library/Java/JavaVirtualMachines/*/Contents/Home /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [ -d "$d" ] && [ -x "$d/bin/jpackage" ]; then JDK="$d"; break; fi
  done
fi
# 未安装 JDK 时，自动下载 Temurin 25 到 ~/.cache
if [ -z "$JDK" ]; then
  CACHE="$HOME/.cache/erlyberly-build"
  mkdir -p "$CACHE"
  JDK=$(ls -d "$CACHE"/jdk-*/Contents/Home 2>/dev/null | head -1 || true)
  if [ -z "$JDK" ] || [ ! -x "$JDK/bin/jpackage" ]; then
    case "$(uname -m)" in
      arm64)  JDK_ARCH=aarch64 ;;
      *)      JDK_ARCH=x64 ;;
    esac
    echo ">> 未安装 JDK，下载 Temurin 25 ($JDK_ARCH) ..."
    curl -fSL --retry 3 -o "$CACHE/jdk.tar.gz" \
      "https://api.adoptium.net/v3/binary/latest/25/ga/mac/${JDK_ARCH}/jdk/hotspot/normal/eclipse"
    tar xzf "$CACHE/jdk.tar.gz" -C "$CACHE"
    JDK=$(ls -d "$CACHE"/jdk-*/Contents/Home | head -1)
  fi
fi
[ -n "$JDK" ] || { echo "未找到 JDK，请设置 JAVA_HOME（需含 jpackage）"; exit 1; }
"$JDK/bin/jpackage" --version >/dev/null 2>&1 || { echo "JDK 缺少 jpackage: $JDK"; exit 1; }
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
echo ">> JDK: $(java -version 2>&1 | head -1)"

VERSION=0.7.0
JAR="erlyberly-${VERSION}-runnable.jar"

# 2) 若本机有 erlc，先编译 erlyberly.erl（与 pom 中 Windows 的 cmd/erlc 步骤等效）
if command -v erlc >/dev/null 2>&1; then
  echo ">> erlc 编译 beam ..."
  erlc +debug_info -o src/main/resources/erlyberly/beam/ src/main/resources/erlyberly/beam/erlyberly.erl
fi

# 3) 构建 fat jar（maven-shade，Main-Class=erlyberly.Launcher）
echo ">> mvn package ..."
./mvnw -q -DskipTests clean package

# 4) 准备干净的输入目录，避免把整个 target 拷进 DMG
rm -rf target/dist target/jpackage
mkdir -p target/dist
cp "target/${JAR}" target/dist/

# 5) jpackage 生成 DMG（erlyberly.app + Applications 拖拽链接 + 精简 runtime）
#    --add-modules: jdeps 探测结果 + JavaFX/Prefs 运行时所需模块
echo ">> jpackage dmg ..."
jpackage \
  --type dmg \
  --name erlyberly \
  --input target/dist \
  --main-jar "${JAR}" \
  --main-class erlyberly.Launcher \
  --dest target/jpackage \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --add-modules java.base,java.datatransfer,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.jfr,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec,jdk.crypto.cryptoki \
  --vendor "erlyberly"

# app-version 0.x 会被 jpackage dmg 校验拒绝（首段不能为 0），产物按项目版本重命名
OUT="target/jpackage/erlyberly-${VERSION}.dmg"
mv target/jpackage/erlyberly-*.dmg "$OUT"

echo ">> 完成 DMG: $OUT"
du -sh "$OUT"

#!/usr/bin/env bash
# 打包 erlyberly 为自包含 exe（内嵌 JRE + JavaFX，目标机无需安装 Java）
# 用法: bash build-exe.sh
set -euo pipefail
cd "$(dirname "$0")"

# 1) 定位 JDK 26（需带 jpackage/jlink）与 erlc
JDK="${JAVA_HOME:-/c/Users/admin/.jdks/openjdk-26.0.1}"
ERL_BIN="/d/Program Files/erl-23.3/bin"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$ERL_BIN:$PATH"
echo ">> JDK: $(java -version 2>&1 | head -1)"

VERSION=0.7.0
JAR="erlyberly-${VERSION}-runnable.jar"

# 2) 构建 fat jar（maven-shade，Main-Class=erlyberly.Launcher）
echo ">> mvn package ..."
./mvnw -q -DskipTests clean package

# 3) 准备干净的输入目录，避免把整个 target 拷进 app-image
rm -rf target/dist target/jpackage
mkdir -p target/dist
cp "target/${JAR}" target/dist/

# 4) jpackage 生成 app-image（自包含 exe + 精简 runtime）
#    --add-modules: jdeps 探测结果 + JavaFX/Prefs 运行时所需模块
echo ">> jpackage ..."
jpackage \
  --type app-image \
  --name erlyberly \
  --app-version "${VERSION}" \
  --input target/dist \
  --main-jar "${JAR}" \
  --main-class erlyberly.Launcher \
  --dest target/jpackage \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --add-modules java.base,java.datatransfer,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.jfr,jdk.unsupported,jdk.unsupported.desktop,jdk.crypto.ec,jdk.crypto.cryptoki \
  --vendor "erlyberly"

echo ">> 完成 app-image: target/jpackage/erlyberly/erlyberly.exe"
du -sh target/jpackage/erlyberly

# 5) 打包为单文件自解压 exe（7z SFX）
#    7za 把整个 app-image 压成 .7z，再用 lib/7z.sfx + config 拼接成单 exe
echo ">> 打包单文件 exe (7z SFX) ..."
SFX="lib/7z.sfx"
SEVENZA="tools/extra/x64/7za.exe"
[ -x "$SEVENZA" ] || SEVENZA="tools/extra/7za.exe"

ARCHIVE="target/jpackage/erlyberly.7z"
rm -f "$ARCHIVE"
# 在 jpackage 目录内压缩，使压缩包内顶层为 erlyberly\ ，SFX 解压后即 erlyberly\erlyberly.exe
( cd target/jpackage && "../../$SEVENZA" a -t7z -mx=9 -mmt=on erlyberly.7z erlyberly >/dev/null )

# SFX 运行配置：解压到临时目录后启动 erlyberly.exe（GUIMode=2 静默解压）
CFG="target/jpackage/sfx-config.txt"
cat > "$CFG" <<'EOF'
;!@Install@!UTF-8!
Title="erlyberly"
GUIMode="2"
RunProgram="erlyberly\\erlyberly.exe"
EOF

OUT="target/jpackage/erlyberly-${VERSION}-setup.exe"
cat "$SFX" "$CFG" "$ARCHIVE" > "$OUT"

echo ">> 完成单文件 exe: $OUT"
du -sh "$OUT"
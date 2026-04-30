#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# Hermes Analyzer - Termux Build Script
# 안드로이드 기기의 Termux에서 직접 APK 빌드
# 사용법: bash termux-build.sh
# ============================================================

set -e

APP_NAME="Hermes Analyzer"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/app/build"

echo "=========================================="
echo "  Hermes Analyzer - Termux Builder"
echo "=========================================="

# --- 1. Termux 환경 확인 ---
echo "[1/8] Termux 환경 확인..."
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo "⚠️  Termux 환경이 아닙니다. Termux에서 실행해주세요."
    exit 1
fi

# --- 2. 필수 패키지 설치 ---
echo "[2/8] 필수 패키지 설치..."
pkg update -y 2>/dev/null || true
pkg install -y \
    openjdk-17 \
    gradle \
    git \
    curl \
    unzip \
    aapt2 \
    apksigner \
    zipalign 2>/dev/null || {
    echo "패키지 설치 중... (일부는 수동 설치 필요)"
}

# Java 확인
if ! command -v java >/dev/null 2>&1; then
    echo "☕ Java 17 설치 중..."
    pkg install -y openjdk-17
fi
export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(command -v javac))))}"
echo "JAVA_HOME=$JAVA_HOME"

# --- 3. Android SDK 설치 (Termux용) ---
echo "[3/8] Android SDK 설정..."
TERMUX_ANDROID_HOME="${HOME}/android-sdk"
if [ ! -d "$TERMUX_ANDROID_HOME" ]; then
    echo "📥 Android SDK 다운로드..."
    mkdir -p "$TERMUX_ANDROID_HOME"
    cd "$TERMUX_ANDROID_HOME"
    
    # commandlinetools
    curl -L -o cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-12266719_latest.zip" 2>/dev/null
    
    if [ -f cmdline-tools.zip ]; then
        unzip -q cmdline-tools.zip
        mkdir -p cmdline-tools/latest
        mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true
        rm -f cmdline-tools.zip
    fi
fi

export ANDROID_HOME="$TERMUX_ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# SDK 컴포넌트 설치
if [ -d "$ANDROID_HOME/cmdline-tools/latest/bin" ]; then
    echo "📦 SDK 컴포넌트 설치..."
    yes | sdkmanager --licenses 2>/dev/null || true
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>/dev/null || true
fi

# --- 4. Gradle Wrapper 확인 ---
echo "[4/8] Gradle 설정..."
cd "$PROJECT_DIR"
if [ ! -f "gradlew" ]; then
    echo "⚙️  Gradle Wrapper 생성..."
    gradle wrapper --gradle-version 8.7 2>/dev/null || {
        echo "gradle wrapper --gradle-version 8.7 --distribution-type bin"
        mkdir -p gradle/wrapper
        curl -L -o gradle/wrapper/gradle-wrapper.jar \
            "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || true
    }
fi
chmod +x gradlew 2>/dev/null || true

# --- 5. 프로젝트 종속성 다운로드 ---
echo "[5/8] 종속성 다운로드..."
./gradlew dependencies --configuration implementation 2>/dev/null || {
    echo "⚠️  Gradle 캐시 초기화 후 재시도..."
    ./gradlew clean --refresh-dependencies 2>/dev/null || true
}

# --- 6. APK 빌드 ---
echo "[6/8] APK 빌드 시작..."
echo "🔨 컴파일링... (시간이 걸릴 수 있습니다)"
./gradlew assembleDebug 2>&1 | tee build.log || {
    echo ""
    echo "=========================================="
    echo "  ⚠️  빌드 중 오류 발생!"
    echo "  로그: $PROJECT_DIR/build.log"
    echo "=========================================="
    echo ""
    echo "일반적인 해결책:"
    echo "1. ./gradlew clean 후 재시도"
    echo "2. pkg install aapt2 apksigner zipalign"
    echo "3. export JAVA_HOME 확인"
    exit 1
}

# --- 7. APK 서명 ---
echo "[7/8] APK 서명..."
APK_DEBUG="$BUILD_DIR/outputs/apk/debug/app-debug.apk"
APK_SIGNED="$BUILD_DIR/outputs/apk/debug/HermesAnalyzer.apk"

if [ -f "$APK_DEBUG" ]; then
    # Debug APK는 이미 서명되어 있음
    cp "$APK_DEBUG" "$APK_SIGNED" 2>/dev/null || true
    echo "✅ Debug APK: $APK_DEBUG"
fi

# --- 8. 완료 ---
echo ""
echo "=========================================="
echo "  ✅ $APP_NAME 빌드 완료!"
echo "=========================================="

APK_PATH="$BUILD_DIR/outputs/apk/debug"
if [ -d "$APK_PATH" ]; then
    echo ""
    echo "📦 생성된 APK 파일:"
    ls -lh "$APK_PATH"/*.apk 2>/dev/null || echo "APK 파일을 찾을 수 없습니다"
    echo ""
    echo "📍 설치 방법:"
    echo "   termux-open $APK_PATH/app-debug.apk"
    echo ""
    echo "📍 adb 설치:"
    echo "   adb install $APK_PATH/app-debug.apk"
fi

echo ""
echo "🚀 Hermes Analyzer 준비 완료!"
echo "   IDA Pro MCP + 8개 AI 병렬 분석 + 채팅"
echo "=========================================="

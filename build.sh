#!/usr/bin/env bash
#
# 메모리 제한 안드로이드 APK 빌드 래퍼
# ------------------------------------------------------------------
# 이 서버는 mgad.service(운영 백엔드)와 RAM을 공유하는 소형 인스턴스(총 ~1.9GB)다.
# 안드로이드 빌드는 메모리를 크게 쓰므로, 아무 제한 없이 돌리면 OOM 킬러가
# mgad를 죽여 운영 장애로 이어질 수 있다. 그래서 이 스크립트는:
#
#   1. 빌드 전에 여유 메모리(+ mgad 사용량)를 확인하고, 부족하면 경고 후 진행 여부를 묻는다.
#   2. 빌드를 cgroup(systemd-run --user --scope)으로 감싸 메모리 하드 상한을 건다.
#      → 빌드가 폭주해도 OOM 대상은 "빌드 자신"이지 mgad가 아니다.
#   3. Gradle을 --no-daemon + 축소 heap + 낮은 CPU/IO 우선순위로 실행한다.
#
# 사용법:
#   ./build.sh                 # assembleDebug 후 releases/ 에 APK 복사
#   ./build.sh assembleRelease # 다른 gradle 태스크 지정
#   FORCE=1 ./build.sh         # 메모리 부족 경고를 무시하고 진행(비대화형/자동화용)
#
# 환경변수로 튜닝 가능(기본값):
#   MIN_AVAIL_MB=400     진행 전 필요한 최소 여유 RAM(MB). 미만이면 경고+질문.
#   MEM_HIGH=700M        빌드 cgroup soft limit. 초과 시 하드 킬 대신 스로틀+회수.
#   MEM_MAX=1024M        빌드 cgroup hard limit. 초과 시 빌드만 OOM(=mgad 보호).
#   MEM_SWAP_MAX=2048M   빌드 cgroup swap 상한.
#   GRADLE_XMX=640m      Gradle JVM 최대 heap.
# ------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

# --- 설정(환경변수로 override 가능) --------------------------------
MIN_AVAIL_MB="${MIN_AVAIL_MB:-400}"
MEM_HIGH="${MEM_HIGH:-700M}"
MEM_MAX="${MEM_MAX:-1024M}"
MEM_SWAP_MAX="${MEM_SWAP_MAX:-2048M}"
GRADLE_XMX="${GRADLE_XMX:-640m}"
FORCE="${FORCE:-0}"

GRADLE_TASK=("${@:-assembleDebug}")

# --- 헬퍼 ----------------------------------------------------------
meminfo_kb() { awk -v k="$1" '$1==k":"{print $2}' /proc/meminfo; }

mgad_mem() {
  # mgad.service 현재 메모리 사용량(사람이 읽는 형식). 실패해도 빌드는 계속.
  systemctl show mgad.service -p MemoryCurrent --value 2>/dev/null \
    | awk '{ if ($1 ~ /^[0-9]+$/) printf "%.0fMB", $1/1024/1024; else print "n/a" }'
}

# --- 1) 빌드 전 메모리 점검 ----------------------------------------
avail_mb=$(( $(meminfo_kb MemAvailable) / 1024 ))
swapfree_mb=$(( $(meminfo_kb SwapFree) / 1024 ))
total_mb=$(( $(meminfo_kb MemTotal) / 1024 ))

echo "──────────────────────────────────────────────"
echo " 빌드 전 메모리 상태"
echo "   총 RAM        : ${total_mb} MB"
echo "   여유 RAM      : ${avail_mb} MB  (기준: ${MIN_AVAIL_MB} MB 이상)"
echo "   여유 Swap     : ${swapfree_mb} MB"
echo "   mgad.service  : $(mgad_mem)"
echo "   빌드 메모리 상한 : high=${MEM_HIGH} / max=${MEM_MAX} / swap=${MEM_SWAP_MAX}"
echo "──────────────────────────────────────────────"

if [ "$avail_mb" -lt "$MIN_AVAIL_MB" ]; then
  echo "⚠️  경고: 여유 RAM(${avail_mb}MB)이 기준(${MIN_AVAIL_MB}MB)보다 낮습니다."
  echo "    빌드는 메모리 상한(cgroup)으로 mgad를 보호하지만, 전체 시스템이"
  echo "    스왑을 심하게 쓰며 느려질 수 있습니다."
  if [ "$FORCE" = "1" ]; then
    echo "    FORCE=1 → 경고 무시하고 진행합니다."
  elif [ -t 0 ]; then
    read -r -p "    그래도 빌드를 진행할까요? [y/N] " ans
    case "$ans" in
      [yY]|[yY][eE][sS]) echo "    진행합니다." ;;
      *) echo "    중단했습니다."; exit 1 ;;
    esac
  else
    echo "    비대화형 실행 + FORCE 미설정 → 안전을 위해 중단합니다. (FORCE=1 로 강제 실행 가능)"
    exit 1
  fi
fi

# --- 2) cgroup 하드 캡 + 낮은 우선순위로 Gradle 실행 -----------------
# --no-daemon: 빌드 JVM이 scope 안에서 끝나고 바로 종료(상주 데몬이 RAM 물고 있지 않게).
# --no-configuration-cache: gradle.properties의 configuration-cache=true는 이 프로젝트의
#   build.gradle.kts가 설정 단계에 git rev-parse(외부 프로세스)를 호출해 캐시 저장 시 실패한다.
#   jvmargs를 override하면 캐시 키가 바뀌어 매번 콜드 저장이 강제되므로 이 위반이 노출된다.
#   콜드 --no-daemon 빌드라 캐시 이득도 없어 아예 끈다.
# kotlin ... in-process: 별도 Kotlin 데몬을 띄우지 않아 메모리/프로세스 관리를 단순화.
# org.gradle.jvmargs override: gradle.properties의 -Xmx2048m를 축소값으로 덮어씀.
GRADLE_JVMARGS="-Xmx${GRADLE_XMX} -Dfile.encoding=UTF-8"

echo "▶ Gradle 실행: ${GRADLE_TASK[*]}  (Xmx=${GRADLE_XMX}, no-daemon)"

# systemd-run --user --scope 로 감싸면 이 셸 세션과 동일 권한(ubuntu)으로 돌아
# 빌드 산출물 소유권 문제가 없다. 메모리 컨트롤러는 user 슬라이스에 위임되어 있어
# MemoryHigh/Max/SwapMax 가 실제로 적용된다(확인 완료).
systemd-run --user --scope \
  --unit="apk-build-$$" \
  -p MemoryHigh="${MEM_HIGH}" \
  -p MemoryMax="${MEM_MAX}" \
  -p MemorySwapMax="${MEM_SWAP_MAX}" \
  -p CPUWeight=20 \
  -p IOWeight=20 \
  -- \
  nice -n 15 ionice -c3 \
  ./gradlew "${GRADLE_TASK[@]}" \
    --no-daemon \
    --no-configuration-cache \
    -Dorg.gradle.jvmargs="${GRADLE_JVMARGS}" \
    -Dorg.gradle.workers.max=1 \
    -Pkotlin.compiler.execution.strategy=in-process

# --- 3) 산출물 정리: 커밋 해시명으로 releases/ 에 복사 ----------------
apk="app/build/outputs/apk/debug/app-debug.apk"
if [[ "${GRADLE_TASK[*]}" == *assembleDebug* ]] && [ -f "$apk" ]; then
  hash=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
  dest="releases/kakaoalertbridge-${hash}-debug.apk"
  mkdir -p releases
  cp -f "$apk" "$dest"
  echo "✅ 빌드 완료 → ${dest} ($(du -h "$dest" | cut -f1))"
else
  echo "✅ 빌드 완료 (assembleDebug가 아니거나 debug APK 경로가 아니어서 releases/ 복사는 생략)"
fi

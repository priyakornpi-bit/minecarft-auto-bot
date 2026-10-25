#!/usr/bin/env bash

# สคริปต์ตรวจสอบและ build Minecraft Auto Bot สำหรับ macOS/Linux
# ผลลัพธ์สรุปจะถูกเขียนลง run/automation-report.md และรายละเอียดลง run/automation-run.log

set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="$PROJECT_DIR/run"
REPORT_FILE="$REPORT_DIR/automation-report.md"
LOG_FILE="$REPORT_DIR/automation-run.log"
GRADLE="$PROJECT_DIR/gradlew"
STARTED_AT="$(date '+%Y-%m-%d %H:%M:%S %z')"

mkdir -p "$REPORT_DIR"
: > "$LOG_FILE"

# เก็บสถานะของแต่ละขั้นตอนเพื่อสร้างรายงานโดยไม่หยุดก่อนสรุปผล
declare -a STEP_NAMES=()
declare -a STEP_RESULTS=()
OVERALL_STATUS=0

record_step() {
    local name="$1"
    local status="$2"
    STEP_NAMES+=("$name")
    if [[ "$status" -eq 0 ]]; then
        STEP_RESULTS+=("ผ่าน")
    else
        STEP_RESULTS+=("ล้มเหลว")
        OVERALL_STATUS=1
    fi
}

run_step() {
    local name="$1"
    shift
    echo ""
    echo "===== $name =====" | tee -a "$LOG_FILE"
    echo "+ $*" | tee -a "$LOG_FILE"
    "$@" 2>&1 | tee -a "$LOG_FILE"
    local status=${PIPESTATUS[0]}
    record_step "$name" "$status"
    return 0
}

# ตรวจสอบเครื่องมือก่อนเรียก Gradle
if [[ ! -x "$GRADLE" ]]; then
    chmod +x "$GRADLE" 2>>"$LOG_FILE" || true
fi
if [[ ! -x "$GRADLE" ]]; then
    echo "ไม่พบหรือไม่สามารถเรียกใช้ Gradle wrapper: $GRADLE" | tee -a "$LOG_FILE"
    record_step "ตรวจสอบ Gradle wrapper" 1
else
    record_step "ตรวจสอบ Gradle wrapper" 0
fi

if command -v java >/dev/null 2>&1; then
    JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
    echo "Java: $JAVA_VERSION" | tee -a "$LOG_FILE"
    if java -version 2>&1 | grep -Eq 'version "(25)(\\.|")'; then
        record_step "ตรวจสอบ Java 25" 0
    else
        echo "คำเตือน: โปรเจกต์ต้องการ Java 25" | tee -a "$LOG_FILE"
        record_step "ตรวจสอบ Java 25" 1
    fi
else
    JAVA_VERSION="ไม่พบคำสั่ง java"
    record_step "ตรวจสอบ Java 25" 1
fi

if [[ -x "$GRADLE" ]]; then
    run_step "Compile main และ client" "$GRADLE" compileJava compileClientJava --no-daemon
    run_step "รัน unit tests" "$GRADLE" test --no-daemon
    run_step "สร้าง Fabric mod artifact" "$GRADLE" build --no-daemon
else
    record_step "Compile main และ client" 1
    record_step "รัน unit tests" 1
    record_step "สร้าง Fabric mod artifact" 1
fi

FINISHED_AT="$(date '+%Y-%m-%d %H:%M:%S %z')"
{
    echo "# Minecraft Auto Bot - Automation Report"
    echo
    echo "- เริ่มทำงาน: $STARTED_AT"
    echo "- สิ้นสุด: $FINISHED_AT"
    echo "- Java: $JAVA_VERSION"
    echo "- สถานะรวม: $([[ "$OVERALL_STATUS" -eq 0 ]] && echo 'ผ่าน' || echo 'ล้มเหลว')"
    echo "- รายละเอียด log: \\`run/automation-run.log\\`"
    echo
    echo "## ผลการตรวจสอบ"
    echo
    echo "| ขั้นตอน | ผลลัพธ์ |"
    echo "|---|---|"
    for index in "${!STEP_NAMES[@]}"; do
        echo "| ${STEP_NAMES[$index]} | ${STEP_RESULTS[$index]} |"
    done
    echo
    echo "## ไฟล์ที่เกี่ยวข้อง"
    echo
    echo "- รายงานจากการเล่นในเกม: \\`run/auto-bot-report.txt\\`"
    echo "- Q-table ของระบบ RL: \\`run/auto-bot-q-table.tsv\\`"
    echo "- Artifact ที่สร้าง: \\`build/libs/\\`"
} > "$REPORT_FILE"

cat "$REPORT_FILE"
exit "$OVERALL_STATUS"

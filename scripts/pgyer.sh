#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

log() {
  printf '%s\n' "$*" >&2
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

load_env_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  set -a
  # shellcheck disable=SC1090
  source "$file"
  set +a
}

load_env_file ".env"
load_env_file ".env.local"

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 not found. Please install it and make sure it is in PATH."
}

java_major_version() {
  local java_bin="$1"
  "$java_bin" -version 2>&1 | awk -F '[".]' '/version/ { print ($2 == "1" ? $3 : $2); exit }'
}

configure_build_jdk() {
  local candidate major
  local -a candidates
  candidates=(
    "${JAVA_HOME:-}"
    "${STUDIO_JDK:-}"
    "${ANDROID_STUDIO_JDK:-}"
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  )
  for candidate in "${candidates[@]}"; do
    [[ -x "$candidate/bin/java" ]] || continue
    major="$(java_major_version "$candidate/bin/java")"
    if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 21 )); then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      log "Using JDK $major: $JAVA_HOME"
      return 0
    fi
  done
  fail "JDK 21+ is required by the LiteRT runtime. Set JAVA_HOME or STUDIO_JDK to a compatible JDK."
}

usage() {
  cat <<'EOF'
Usage:
  ./scripts/pgyer.sh pgyer [type:debug|type:release]
  ./scripts/pgyer.sh apk [type:debug|type:release]
  ./scripts/pgyer.sh upload file:/absolute/path/app.apk

Options:
  type:<type>          Android build type, default: debug
  file:<path>          Existing APK path for the upload command
  desc:<text>          Override Pgyer update description
  git_count:<number>   Number of recent commits in release notes, default: 5

Environment:
  PGYER_API_KEY             Required for upload
  PGYER_INSTALL_TYPE        1: public, 2: password, 3: invitation; default: 1
  PGYER_PASSWORD            Optional installation password
  PGYER_CHANNEL_SHORTCUT    Optional channel shortcut
  PGYER_UPDATE_DESC         Optional update description
  PGYER_GIT_LOG_COUNT       Optional recent commit count
EOF
}

COMMAND="${1:-pgyer}"
if [[ $# -gt 0 ]]; then
  shift
fi

BUILD_TYPE="${BUILD_TYPE:-debug}"
PGYER_FILE="${PGYER_FILE:-}"
UPDATE_DESC="${PGYER_UPDATE_DESC:-}"
GIT_COUNT="${PGYER_GIT_LOG_COUNT:-5}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    type:*|build_type:*)
      BUILD_TYPE="${1#*:}"
      shift
      ;;
    file:*)
      PGYER_FILE="${1#*:}"
      shift
      ;;
    desc:*|update_desc:*)
      UPDATE_DESC="${1#*:}"
      shift
      ;;
    git_count:*)
      GIT_COUNT="${1#*:}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unsupported argument: $1"
      ;;
  esac
done

BUILD_TYPE="$(printf '%s' "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')"
case "$BUILD_TYPE" in
  debug)
    GRADLE_TASK=":app:assembleDebug"
    ;;
  release)
    GRADLE_TASK=":app:assembleRelease"
    ;;
  *)
    fail "Unsupported build type: $BUILD_TYPE. Use debug or release."
    ;;
esac

APP_ID="xyz.liop.tieba.pos"
APP_NAME="Tieba Lite"
LAST_APK_PATH=""

property_value() {
  local name="$1"
  local fallback="${2:-}"
  local value
  value="$(awk -F '=' -v key="$name" '$1 ~ "^[[:space:]]*" key "[[:space:]]*$" { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); print $2; exit }' application.properties 2>/dev/null || true)"
  printf '%s' "${value:-$fallback}"
}

version_name() {
  local name pre_release pre_name pre_version
  name="$(property_value versionName "unknown")"
  pre_release="$(property_value isPreRelease "false")"
  pre_name="$(property_value preReleaseName "")"
  pre_version="$(property_value preReleaseVer "")"
  if [[ "$pre_release" == "true" && -n "$pre_name" && -n "$pre_version" ]]; then
    name="${name}-${pre_name}.${pre_version}"
  fi
  printf '%s' "$name"
}

APP_VERSION_NAME="$(version_name)"
APP_VERSION_CODE="$(property_value versionCode "0")"

find_latest_apk() {
  require_command python3
  python3 - "$ROOT_DIR/app/build/outputs/apk/$BUILD_TYPE" <<'PY'
import os
import sys

root = sys.argv[1]
apks = []
for current_root, _, files in os.walk(root) if os.path.isdir(root) else []:
    for name in files:
        path = os.path.join(current_root, name)
        if name.endswith(".apk") and not name.endswith("unaligned.apk"):
            apks.append(path)
if not apks:
    sys.exit(1)
print(max(apks, key=os.path.getmtime))
PY
}

archive_apk() {
  local apk timestamp destination
  apk="$(find_latest_apk)" || fail "No APK found under app/build/outputs/apk/$BUILD_TYPE"
  timestamp="$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$ROOT_DIR/dist"
  destination="$ROOT_DIR/dist/tiebalite-${BUILD_TYPE}-v${APP_VERSION_NAME}-${APP_VERSION_CODE}-${timestamp}.apk"
  cp -f "$apk" "$destination"
  LAST_APK_PATH="$destination"
  log "APK archived: $destination"
}

build_apk() {
  configure_build_jdk
  log "Building Tieba Lite: type=$BUILD_TYPE task=$GRADLE_TASK"
  ./gradlew --no-daemon "$GRADLE_TASK"
  archive_apk
}

recent_git_commits() {
  local count="$GIT_COUNT"
  [[ "$count" =~ ^[0-9]+$ ]] || count=5
  (( count < 1 )) && count=1
  (( count > 20 )) && count=20
  git log --pretty=format:'%h %s' "-$count" 2>/dev/null || true
}

release_notes() {
  local file_path="$1"
  local branch commits
  branch="$(git branch --show-current 2>/dev/null || true)"
  commits="$(recent_git_commits)"

  {
    printf '%s\n\n' "${UPDATE_DESC:-Tieba Lite Android 包（自动上传）}"
    printf '发布信息:\n'
    printf '构建类型: %s\n' "$BUILD_TYPE"
    printf '包名: %s\n' "$APP_ID"
    printf '应用名: %s\n' "$APP_NAME"
    printf '版本: %s(%s)\n' "$APP_VERSION_NAME" "$APP_VERSION_CODE"
    printf '文件: %s\n' "$(basename "$file_path")"
    if [[ -n "$branch$commits" ]]; then
      printf '\n最近提交:\n'
      [[ -n "$branch" ]] && printf '分支: %s\n' "$branch"
      [[ -n "$commits" ]] && printf '%s\n' "$commits"
    fi
  }
}

json_value() {
  local file="$1"
  local path="$2"
  python3 - "$file" "$path" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    value = json.load(source)
for part in sys.argv[2].split("."):
    value = value[part]
print(json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else value)
PY
}

json_params_lines() {
  local file="$1"
  python3 - "$file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    params = json.load(source)["data"]["params"]
if isinstance(params, str):
    params = json.loads(params)
for key, value in params.items():
    print(f"{key}={value}")
PY
}

wait_for_build() {
  local build_key="$1"
  local deadline info_file code shortcut result_key body
  deadline=$((SECONDS + 420))
  info_file="$(mktemp)"

  while true; do
    curl -sS -f -G "https://www.pgyer.com/apiv2/app/buildInfo" \
      --data-urlencode "_api_key=$PGYER_API_KEY" \
      --data-urlencode "buildKey=$build_key" \
      -o "$info_file"
    code="$(json_value "$info_file" code)"
    if [[ "$code" == "0" ]]; then
      shortcut="$(json_value "$info_file" data.buildShortcutUrl || true)"
      result_key="$(json_value "$info_file" data.buildKey || true)"
      rm -f "$info_file"
      printf 'https://www.pgyer.com/%s\n' "${shortcut:-$result_key}"
      return 0
    fi
    if [[ "$code" == "1247" ]]; then
      (( SECONDS < deadline )) || fail "Timed out waiting for Pgyer build $build_key"
      log "Pgyer is processing build $build_key..."
      sleep 4
      continue
    fi
    body="$(cat "$info_file")"
    rm -f "$info_file"
    fail "Pgyer buildInfo failed: $body"
  done
}

pgyer_upload() {
  local file_path="$1"
  local temp_dir token_file params_file response_file notes code endpoint build_key page_url attempt
  [[ -f "$file_path" ]] || fail "File not found: $file_path"
  [[ -n "${PGYER_API_KEY:-}" ]] || fail "Missing PGYER_API_KEY. Put it in .env or .env.local."
  require_command curl
  require_command python3

  temp_dir="$(mktemp -d)"
  token_file="$temp_dir/token.json"
  params_file="$temp_dir/params.txt"
  response_file="$temp_dir/upload.json"
  notes="$(release_notes "$file_path")"

  for attempt in 1 2 3; do
    log "Preparing Pgyer upload token (attempt $attempt/3)..."
    token_args=(
      -sS -f -X POST "https://www.pgyer.com/apiv2/app/getCOSToken"
      --data-urlencode "_api_key=$PGYER_API_KEY"
      --data-urlencode "buildType=apk"
      --data-urlencode "buildUpdateDescription=$notes"
      --data-urlencode "buildInstallType=${PGYER_INSTALL_TYPE:-1}"
      -o "$token_file"
    )
    [[ -n "${PGYER_PASSWORD:-}" ]] && token_args+=(--data-urlencode "buildPassword=$PGYER_PASSWORD")
    [[ -n "${PGYER_CHANNEL_SHORTCUT:-}" ]] && token_args+=(--data-urlencode "buildChannelShortcut=$PGYER_CHANNEL_SHORTCUT")
    if ! curl "${token_args[@]}"; then
      (( attempt < 3 )) || fail "Pgyer getCOSToken failed."
      sleep $((2 ** (attempt - 1)))
      continue
    fi

    code="$(json_value "$token_file" code)"
    [[ "$code" == "0" ]] || fail "Pgyer getCOSToken failed: $(cat "$token_file")"
    endpoint="$(json_value "$token_file" data.endpoint)"
    build_key="$(json_value "$token_file" data.key)"
    json_params_lines "$token_file" > "$params_file"

    log "Uploading $(basename "$file_path") to Pgyer (attempt $attempt/3)..."
    curl_args=(-sS -f -X POST "$endpoint")
    while IFS= read -r line; do
      [[ -n "$line" ]] && curl_args+=(--form-string "$line")
    done < "$params_file"
    curl_args+=(-F "file=@${file_path};type=application/vnd.android.package-archive")
    if curl "${curl_args[@]}" -o "$response_file"; then
      page_url="$(wait_for_build "$build_key")"
      rm -rf "$temp_dir"
      log "Pgyer upload success: $page_url"
      return 0
    fi
    (( attempt < 3 )) || fail "Pgyer upload failed."
    sleep $((2 ** (attempt - 1)))
  done
}

case "$COMMAND" in
  apk|build)
    build_apk
    ;;
  pgyer|upload-build)
    build_apk
    pgyer_upload "$LAST_APK_PATH"
    ;;
  upload)
    [[ -n "$PGYER_FILE" ]] || fail "Provide file:/absolute/path/app.apk or PGYER_FILE."
    pgyer_upload "$PGYER_FILE"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    fail "Unsupported command: $COMMAND. Use apk, pgyer or upload."
    ;;
esac

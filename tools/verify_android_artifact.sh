#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: verify_android_artifact.sh path/to/app.apk [expected_abi ...]}"
shift
expected_abis=("$@")

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 2
fi

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT" >&2
  exit 2
fi

zipalign_bin="$(find "$sdk_root/build-tools" -type f -name zipalign -perm -111 | sort | tail -1)"
readelf_bin="$(find "$sdk_root/ndk" \( -type f -o -type l \) -name llvm-readelf -perm -111 | sort | tail -1)"
if [[ -z "$zipalign_bin" || -z "$readelf_bin" ]]; then
  echo "Could not locate zipalign or llvm-readelf under $sdk_root" >&2
  exit 2
fi

actual_abis=()
while IFS= read -r abi; do
  actual_abis+=("$abi")
done < <(unzip -Z1 "$apk_path" | sed -nE 's#^lib/([^/]+)/.*#\1#p' | sort -u)
echo "ABIs: ${actual_abis[*]:-(none)}"
if (( ${#expected_abis[@]} > 0 )); then
  if [[ "${actual_abis[*]-}" != "$(printf '%s\n' "${expected_abis[@]}" | sort -u | paste -sd' ' -)" ]]; then
    echo "Unexpected ABI set" >&2
    exit 1
  fi
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
while IFS= read -r entry; do
  [[ "$entry" == lib/*/*.so ]] || continue
  abi="$(cut -d/ -f2 <<<"$entry")"
  library="$(basename "$entry")"
  out="$tmp_dir/$abi-$library"
  unzip -p "$apk_path" "$entry" > "$out"
  echo "--- $entry ---"
  "$readelf_bin" -l "$out" | awk '/LOAD/ { found=1; if ($NF != "0x4000") bad=1; print } END { exit !(found && !bad) }'
done < <(unzip -Z1 "$apk_path")

"$zipalign_bin" -c -P 16 4 "$apk_path" >/dev/null
echo "16 KB ELF and ZIP alignment verification passed: $apk_path"

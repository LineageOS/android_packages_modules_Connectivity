#!/bin/bash
set -x

# Run this script inside a full chromium checkout.

OUT_PATH="out/cronet"

#######################################
# Apply patches in external/cronet.
# Globals:
#   ANDROID_BUILD_TOP
# Arguments:
#   None
#######################################
function apply_patches() {
  local -r patch_root="${ANDROID_BUILD_TOP}/external/cronet/patches"

  local upstream_patches
  upstream_patches=$(ls "${patch_root}/upstream-next")
  local patch
  for patch in ${upstream_patches}; do
    git am --3way "${patch_root}/upstream-next/${patch}"
  done

  local local_patches
  local_patches=$(ls "${patch_root}/local")
  for patch in ${local_patches}; do
    git am --3way "${patch_root}/local/${patch}"
  done
}

#######################################
# Generate desc.json for a specified architecture.
# Globals:
#   OUT_PATH
# Arguments:
#   target_cpu, string
#######################################
function gn_desc() {
  local -a gn_args=(
    "target_os = \"android\""
    "enable_websockets = false"
    "disable_file_support = true"
    "disable_brotli_filter = false"
    "is_component_build = false"
    "use_crash_key_stubs = true"
    "use_partition_alloc = false"
    "include_transport_security_state_preload_list = false"
    "use_platform_icu_alternatives = true"
    "default_min_sdk_version = 19"
    "use_errorprone_java_compiler = true"
    "enable_reporting = true"
    "use_hashed_jni_names = true"
    "treat_warnings_as_errors = false"
    "enable_base_tracing = false"
    "is_cronet_build = true"
    "is_debug = false"
    "is_official_build = true"
    "use_nss_certs = false"
  )
  gn_args+=("target_cpu = \"${1}\"")

  # Only set arm_use_neon on arm architectures to prevent warning from being
  # written to json output.
  if [[ "$1" =~ ^arm ]]; then
    gn_args+=("arm_use_neon = false")
  fi

  # Configure gn args.
  gn gen "${OUT_PATH}" --args="${gn_args[*]}"

  # Generate desc.json.
  local -r out_file="desc_${1}.json"
  gn desc "${OUT_PATH}" --format=json --all-toolchains "//*" > "${out_file}"
}

apply_patches
gn_desc x86
gn_desc x64
gn_desc arm
gn_desc arm64


#!/bin/bash

# This script is expected to run after gen_android_bp is modified.
#
#   ./update_result.sh
#
# TARGETS contains targets which are supported by gen_android_bp and
# this script generates Android.bp.swp from TARGETS.
# This makes it easy to realize unintended impact/degression on
# previously supported targets.

set -eux

TARGETS=(
  "//third_party/zlib:zlib"
  "//third_party/libevent:libevent"
)

BASEDIR=$(dirname "$0")
$BASEDIR/gen_android_bp --desc $BASEDIR/desc.json --out $BASEDIR/Android.bp ${TARGETS[@]}

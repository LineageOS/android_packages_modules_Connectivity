#!/bin/bash

# Copyright 2023 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Script to invoke copybara locally to import Cronet into Android.
# Inputs:
#  Environment:
#   ANDROID_BUILD_TOP: path the root of the current Android directory.
#  Arguments:
#   -l: The last revision that was imported.
#   -n: The new revision to import.

OPTSTRING=l:n:

usage() {
    cat <<EOF
Usage: import_cronet.sh -l last-rev -n new-rev
EOF
    exit 1
}

#######################################
# Runs the copybara import of Chromium
# Globals:
#   ANDROID_BUILD_TOP
# Arguments:
#   last_rev, string
#   new_rev, string
#######################################
do_run_copybara() {
    local _last_rev=$1
    local _new_rev=$2

    /google/bin/releases/copybara/public/copybara/copybara \
        --git-destination-url="file://${ANDROID_BUILD_TOP}/external/cronet" \
        --last-rev "${_last_rev}" \
        --repo-timeout 3h \
        "${ANDROID_BUILD_TOP}/packages/modules/Connectivity/Cronet/tools/import/copy.bara.sky" \
        import_cronet "${_new_rev}"
}

while getopts $OPTSTRING opt; do
    case "${opt}" in
        l) last_rev="${OPTARG}" ;;
        n) new_rev="${OPTARG}" ;;
        ?) usage ;;
        *) echo "'${opt}' '${OPTARG}'"
    esac
done

# TODO: Get last-rev from METADATA file.
# Setting last-rev may only be required for the first commit.
if [ -z "${last_rev}" ]; then
    echo "-l argument required"
    usage
fi

if [ -z "${new_rev}" ]; then
    echo "-n argument required"
    usage
fi

do_run_copybara "${last_rev}" "${new_rev}"


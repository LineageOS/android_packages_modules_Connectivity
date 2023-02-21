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
#   -l rev: The last revision that was imported.
#  Optional Arguments:
#   -n rev: The new revision to import.
#   -f: Force copybara to ignore a failure to find the last imported revision.

OPTSTRING=fl:n:

usage() {
    cat <<EOF
Usage: import_cronet.sh -n new-rev [-l last-rev] [-f]
EOF
    exit 1
}

#######################################
# Create upstream-import branch in external/cronet.
# Globals:
#   ANDROID_BUILD_TOP
# Arguments:
#   none
#######################################
setup_upstream_import_branch() {
    local git_dir="${ANDROID_BUILD_TOP}/external/cronet"
    local initial_empty_repo_sha="d1add53d6e90815f363c91d433735556ce79b0d2"

    # Suppress error message if branch already exists.
    (cd "${git_dir}" && git branch upstream-import "${initial_empty_repo_sha}") 2>/dev/null
}

#######################################
# Runs the copybara import of Chromium
# Globals:
#   ANDROID_BUILD_TOP
# Arguments:
#   new_rev, string
#   last_rev, string or empty
#   force, string or empty
#######################################
do_run_copybara() {
    local _new_rev=$1
    local _last_rev=$2
    local _force=$3

    local -a flags
    flags+=(--git-destination-url="file://${ANDROID_BUILD_TOP}/external/cronet")
    flags+=(--repo-timeout 3h)

    if [ ! -z "${_force}" ]; then
        flags+=(--force)
    fi

    if [ ! -z "${_last_rev}" ]; then
        flags+=(--last-rev "${_last_rev}")
    fi

    /google/bin/releases/copybara/public/copybara/copybara \
        "${flags[@]}" \
        "${ANDROID_BUILD_TOP}/packages/modules/Connectivity/Cronet/tools/import/copy.bara.sky" \
        import_cronet "${_new_rev}"
}

while getopts $OPTSTRING opt; do
    case "${opt}" in
        f) force=true ;;
        l) last_rev="${OPTARG}" ;;
        n) new_rev="${OPTARG}" ;;
        ?) usage ;;
        *) echo "'${opt}' '${OPTARG}'"
    esac
done

if [ -z "${new_rev}" ]; then
    echo "-n argument required"
    usage
fi

setup_upstream_import_branch
do_run_copybara "${new_rev}" "${last_rev}" "${force}"


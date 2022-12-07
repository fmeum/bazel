#!/usr/bin/env bash
#
# Copyright 2023 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -eu

DUPLICATE_SYMBOLS=$(
  "%{nm}" --demangle --print-file-name "$1" |
  LC_ALL=C sort --key=3 |
  uniq -D --skip-field=2)
if [[ -n "$DUPLICATE_SYMBOLS" ]]; then
  echo "Duplicate symbols found in $1:" | tee "$2"
  echo "$DUPLICATE_SYMBOLS" | tee --append "$2"
  exit 1
else
  touch "$2"
fi

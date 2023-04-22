# Copyright 2021 The Bazel Authors. All rights reserved.
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

"""Starlark tests for cc_static_library"""

load("@rules_testing//lib:analysis_test.bzl", "analysis_test", "test_suite")
load("@rules_testing//lib:util.bzl", "util")

def _test_hello(name):
    util.helper_target(
        native.filegroup,
        name = name + "_subject",
        srcs = ["hello_world.txt"],
    )
    analysis_test(
        name = name,
        impl = _test_hello_impl,
        target = name + "_subject",
    )

def _test_hello_impl(env, target):
    env.expect.that_target(target).default_outputs().contains(
        "hello_world.txt",
    )

def my_test_suite(name):
    test_suite(
        name = name,
        tests = [
            _test_hello,
        ],
    )

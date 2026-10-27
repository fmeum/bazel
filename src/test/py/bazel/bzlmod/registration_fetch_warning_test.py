# Copyright 2026 The Bazel Authors. All rights reserved.
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
"""Tests for --experimental_warn_on_expensive_registration_fetches."""

import pathlib

from absl.testing import absltest
from src.test.py.bazel import test_base

_WARNING = (
    'was fetched only to load registered toolchains or execution platforms'
)


class RegistrationFetchWarningTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.ScratchFile(
        '.bazelrc',
        ['common --experimental_warn_on_expensive_registration_fetches'],
    )
    self.ScratchFile(
        'rules.bzl',
        [
            'def _impl(ctx):',
            '  return []',
            'my_rule = rule(',
            '  implementation = _impl,',
            '  toolchains = [',
            '    config_common.toolchain_type(',
            '      "//:used_type",',
            '      mandatory = False,',
            '    ),',
            '  ],',
            ')',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'load(":rules.bzl", "my_rule")',
            'toolchain_type(name = "used_type")',
            'toolchain_type(name = "unused_type")',
            'platform(name = "good_platform")',
            'my_rule(name = "target")',
        ],
    )
    data_path = self.ScratchFile('data.txt', ['some data'])
    data_url = pathlib.Path(data_path).resolve().as_uri()
    self.ScratchFile(
        'repo.bzl',
        [
            'def _impl(rctx):',
            '  if rctx.attr.download:',
            '    rctx.download(url = "%s", output = "data.txt")' % data_url,
            '  rctx.file("BUILD", rctx.attr.build_file_content)',
            'heavy_repo = repository_rule(',
            '  implementation = _impl,',
            '  attrs = {',
            '    "build_file_content": attr.string(),',
            '    "download": attr.bool(default = True),',
            '  },',
            ')',
        ],
    )

  def writeModuleFile(self, toolchain_type, download=True):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'heavy_repo = use_repo_rule("//:repo.bzl", "heavy_repo")',
            'heavy_repo(',
            '  name = "heavy",',
            '  download = %s,' % download,
            '  build_file_content = """',
            'toolchain(',
            '  name = "tc",',
            '  toolchain_type = "@@//:%s",' % toolchain_type,
            '  toolchain = ":impl",',
            ')',
            'filegroup(name = "impl")',
            '""",',
            ')',
            'register_toolchains("@heavy//:all")',
        ],
    )

  def testWarnsAboutRepoOnlyNeededForUnusedToolchain(self):
    self.writeModuleFile(toolchain_type='unused_type')

    _, _, stderr = self.RunBazel(['build', '//:target'])
    stderr = '\n'.join(stderr)
    self.assertIn("Repository '@heavy' " + _WARNING, stderr)
    self.assertIn('expensive repository_ctx methods download.', stderr)
    self.assertIn('  toolchain @heavy//:tc', stderr)
    self.assertIn('  toolchain target pattern @heavy//:all', stderr)

    # The repo isn't fetched again, so there is no warning.
    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testNoWarningIfToolchainIsUsed(self):
    self.writeModuleFile(toolchain_type='used_type')

    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testNoWarningIfRepoRuleIsCheap(self):
    self.writeModuleFile(toolchain_type='unused_type', download=False)

    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testNoWarningWithoutFlag(self):
    self.writeModuleFile(toolchain_type='unused_type')

    _, _, stderr = self.RunBazel([
        'build',
        '--noexperimental_warn_on_expensive_registration_fetches',
        '//:target',
    ])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testWarnsAboutRepoOnlyNeededForUnselectedExecutionPlatform(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'heavy_repo = use_repo_rule("//:repo.bzl", "heavy_repo")',
            'heavy_repo(',
            '  name = "heavy",',
            '  build_file_content = "platform(name = \'platform\')",',
            ')',
            # The first registered platform is selected.
            'register_execution_platforms(',
            '  "//:good_platform",',
            '  "@heavy//:platform",',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '//:target'])
    stderr = '\n'.join(stderr)
    self.assertIn("Repository '@heavy' " + _WARNING, stderr)
    self.assertIn('  execution platform @heavy//:platform', stderr)

  def testNoWarningIfExecutionPlatformIsSelected(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'heavy_repo = use_repo_rule("//:repo.bzl", "heavy_repo")',
            'heavy_repo(',
            '  name = "heavy",',
            '  build_file_content = "platform(name = \'platform\')",',
            ')',
            'register_execution_platforms(',
            '  "@heavy//:platform",',
            '  "//:good_platform",',
            ')',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))


if __name__ == '__main__':
  absltest.main()

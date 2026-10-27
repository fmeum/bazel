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

_WARNING = 'is required by the registered'


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
            'test_repo = repository_rule(',
            '  implementation = _impl,',
            '  attrs = {',
            '    "build_file_content": attr.string(),',
            '    "download": attr.bool(),',
            '  },',
            ')',
        ],
    )

  def writeModuleFile(self, toolchain_repo, toolchain_type, download=True):
    """Declares an expensive repo @heavy and a cheap repo @light.

    Args:
      toolchain_repo: the repo that contains the registered toolchain, whose
        implementation is always @heavy//:impl.
      toolchain_type: the toolchain type in the main repo.
      download: whether @heavy is expensive.
    """
    build_files = {
        'heavy': ['filegroup(name = "impl")'],
        'light': [],
    }
    impl = ':impl' if toolchain_repo == 'heavy' else '@heavy//:impl'
    build_files[toolchain_repo] += [
        'toolchain(',
        '  name = "tc",',
        '  toolchain_type = "@@//:%s",' % toolchain_type,
        '  toolchain = "%s",' % impl,
        ')',
    ]
    lines = ['test_repo = use_repo_rule("//:repo.bzl", "test_repo")']
    for name, repo_download in [('heavy', download), ('light', False)]:
      lines += [
          'test_repo(',
          '  name = "%s",' % name,
          '  download = %s,' % repo_download,
          '  build_file_content = """',
      ]
      lines += build_files[name]
      lines += ['""",', ')']
    lines.append('register_toolchains("@%s//:all")' % toolchain_repo)
    self.ScratchFile('MODULE.bazel', lines)

  def testWarnsAboutRepoWithUnusedToolchain(self):
    self.writeModuleFile(toolchain_repo='heavy', toolchain_type='unused_type')

    _, _, stderr = self.RunBazel(['build', '//:target'])
    stderr = '\n'.join(stderr)
    self.assertIn(
        "Repository '@heavy' is required by the registered toolchain"
        ' @heavy//:tc, but its repository rule called the expensive'
        ' repository_ctx methods download.',
        stderr,
    )

    # The repo isn't fetched again, so there is no warning.
    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testWarnsAboutRepoWithUsedToolchain(self):
    self.writeModuleFile(toolchain_repo='heavy', toolchain_type='used_type')

    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertIn(
        "Repository '@heavy' is required by the registered toolchain"
        ' @heavy//:tc',
        '\n'.join(stderr),
    )

  def testNoWarningIfOnlyToolchainImplementationIsExpensive(self):
    self.writeModuleFile(toolchain_repo='light', toolchain_type='used_type')

    # @heavy is fetched because the toolchain is used, not because the
    # toolchain is registered.
    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testNoWarningIfRepoRuleIsCheap(self):
    self.writeModuleFile(
        toolchain_repo='heavy', toolchain_type='unused_type', download=False
    )

    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testNoWarningWithoutFlag(self):
    self.writeModuleFile(toolchain_repo='heavy', toolchain_type='unused_type')

    _, _, stderr = self.RunBazel([
        'build',
        '--noexperimental_warn_on_expensive_registration_fetches',
        '//:target',
    ])
    self.assertNotIn(_WARNING, '\n'.join(stderr))

  def testWarnsAboutRepoWithExecutionPlatform(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'test_repo = use_repo_rule("//:repo.bzl", "test_repo")',
            'test_repo(',
            '  name = "heavy",',
            '  download = True,',
            '  build_file_content = "platform(name = \'platform\')",',
            ')',
            'register_execution_platforms("@heavy//:platform")',
        ],
    )

    _, _, stderr = self.RunBazel(['build', '//:target'])
    self.assertIn(
        "Repository '@heavy' is required by the registered execution platform"
        ' @heavy//:platform',
        '\n'.join(stderr),
    )


if __name__ == '__main__':
  absltest.main()

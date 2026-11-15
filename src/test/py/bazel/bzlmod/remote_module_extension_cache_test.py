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
"""Tests the remote cache as a replacement for the hidden lockfile."""

import os
from absl.testing import absltest
from src.test.py.bazel.bzlmod import remote_repo_contents_cache_test_base


class RemoteModuleExtensionCacheTest(
    remote_repo_contents_cache_test_base.RemoteRepoContentsCacheTestBase
):

  def BazelrcLines(self):
    return [
        'common --experimental_remote_module_extension_cache',
        'common --remote_cache=grpc://localhost:' + str(self._worker_port),
        'common --auth_enabled=false',
        'common --remote_timeout=3600s',
        'common --verbose_failures',
    ]

  def setUp(self):
    super().setUp()
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("//:ext.bzl", "ext")',
            'use_repo(ext, "my_repo")',
        ],
    )
    self.ScratchFile('BUILD.bazel')
    self.ScratchFile('data.txt', ['first'])
    self.ScratchFile(
        'ext.bzl',
        [
            'def _repo_impl(rctx):',
            '  rctx.file("BUILD", "exports_files([\'data.txt\'])")',
            '  rctx.file("data.txt", rctx.attr.data)',
            'repo = repository_rule(_repo_impl, attrs = {"data": attr.string()})',
            '',
            'def _ext_impl(mctx):',
            '  print("JUST EVALUATED")',
            '  repo(name = "my_repo", data = mctx.read(Label("//:data.txt")))',
            '  return mctx.extension_metadata(',
            '    reproducible = True,',
            '    facts = {"fact": "value"},',
            '  )',
            'ext = module_extension(_ext_impl)',
        ],
    )

  def testCachedAfterCleanExpunge(self):
    # First evaluation: not cached
    _, _, stderr = self.RunBazel(['build', '@my_repo//:data.txt'])
    self.assertIn('JUST EVALUATED', '\n'.join(stderr))

    # After expunging: cached
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '@my_repo//:data.txt'])
    self.assertNotIn('JUST EVALUATED', '\n'.join(stderr))

    # After expunging, without the remote cache: not cached
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel([
        'build',
        '--noexperimental_remote_module_extension_cache',
        '@my_repo//:data.txt',
    ])
    self.assertIn('JUST EVALUATED', '\n'.join(stderr))

  def testHiddenLockfileNotUsed(self):
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    hidden_lockfile = os.path.join(stdout[0].strip(), 'MODULE.bazel.lock')

    self.RunBazel(['build', '@my_repo//:data.txt'])
    self.assertFalse(os.path.exists(hidden_lockfile))
    # The facts are still persisted in the workspace lockfile.
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      self.assertIn('"fact": "value"', f.read())

  def testNotCachedWhenRecordedInputsChange(self):
    _, _, stderr = self.RunBazel(['build', '@my_repo//:data.txt'])
    self.assertIn('JUST EVALUATED', '\n'.join(stderr))

    # A file read by the extension changes: not cached
    self.ScratchFile('data.txt', ['second'])
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '@my_repo//:data.txt'])
    self.assertIn('JUST EVALUATED', '\n'.join(stderr))

    # Both results are cached side by side.
    for content in ['first', 'second', 'first']:
      self.ScratchFile('data.txt', [content])
      self.RunBazel(['clean', '--expunge'])
      _, _, stderr = self.RunBazel(['build', '@my_repo//:data.txt'])
      self.assertNotIn('JUST EVALUATED', '\n'.join(stderr))

  def testStaleFactsInLockfileAreRepairedOnCacheHit(self):
    self.RunBazel(['build', '@my_repo//:data.txt'])
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      lockfile = f.read()
    self.assertIn('"fact": "value"', lockfile)

    # Simulate a bad merge in a fresh checkout: the cached result is not reused
    # as is since the facts it was produced with don't match the lockfile.
    with open(self.Path('MODULE.bazel.lock'), 'w') as f:
      f.write(lockfile.replace('"fact": "value"', '"fact": "stale"'))
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '@my_repo//:data.txt'])
    self.assertIn('JUST EVALUATED', '\n'.join(stderr))
    with open(self.Path('MODULE.bazel.lock'), 'r') as f:
      self.assertIn('"fact": "value"', f.read())


if __name__ == '__main__':
  absltest.main()

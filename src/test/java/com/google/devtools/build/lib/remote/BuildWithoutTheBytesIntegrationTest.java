// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.rewinding.RewindingTestsHelper.rewoundArtifactOwnerLabels;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.readContent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionPhaseCompleteEvent;
import com.google.devtools.build.lib.dynamic.DynamicExecutionModule;
import com.google.devtools.build.lib.remote.options.RemoteStartupOptions;
import com.google.devtools.build.lib.remote.util.IntegrationTestUtils;
import com.google.devtools.build.lib.remote.util.IntegrationTestUtils.WorkerInstance;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlockWaitingModule;
import com.google.devtools.build.lib.runtime.BuildSummaryStatsModule;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.rewinding.RewindingTestsHelper;
import com.google.devtools.build.lib.standalone.StandaloneModule;
import com.google.devtools.build.lib.testutil.ActionEventRecorder;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.common.options.OptionsBase;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integration tests for Build without the Bytes. */
@RunWith(TestParameterInjector.class)
public class BuildWithoutTheBytesIntegrationTest extends BuildWithoutTheBytesIntegrationTestBase {
  @ClassRule @Rule public static final WorkerInstance worker = IntegrationTestUtils.createWorker();

  private final ActionEventRecorder actionEventRecorder = new ActionEventRecorder();
  private final RewindingTestsHelper rewindingTestsHelper =
      new RewindingTestsHelper(this, actionEventRecorder);

  /**
   * Records how many digests are known to be missing from the cache when the execution phase
   * completes, which is before a successful build clears them.
   */
  private final class KnownMissingCasDigestsSampler {
    private volatile int sizeAtExecutionPhaseComplete = -1;

    @Subscribe
    public void onExecutionPhaseComplete(ExecutionPhaseCompleteEvent event) {
      sizeAtExecutionPhaseComplete = knownMissingCasDigestsSize();
    }

    void assertSizeAtExecutionPhaseComplete(int expected) {
      assertThat(sizeAtExecutionPhaseComplete).isEqualTo(expected);
    }
  }

  /** Returns a {@link KnownMissingCasDigestsSampler} registered for the next build. */
  private KnownMissingCasDigestsSampler sampleKnownMissingCasDigests() {
    var sampler = new KnownMissingCasDigestsSampler();
    getRuntimeWrapper().registerSubscriber(sampler);
    return sampler;
  }

  private int knownMissingCasDigestsSize() {
    for (BlazeModule module : getRuntime().getBlazeModules()) {
      if (module instanceof RemoteModule remoteModule) {
        return remoteModule.getKnownMissingCasDigestsSize();
      }
    }
    throw new AssertionError("no RemoteModule in the runtime");
  }

  @TestParameter public boolean useDiskCache;
  private Path diskCacheDir;

  @Override
  protected ImmutableList<Class<? extends OptionsBase>> getStartupOptionClasses() {
    return ImmutableList.<Class<? extends OptionsBase>>builder()
        .addAll(super.getStartupOptionClasses())
        .add(RemoteStartupOptions.class)
        .build();
  }

  @Override
  protected ImmutableList<String> getStartupOptions() {
    // Some tests require the ability to create symlinks on Windows.
    return OS.getCurrent() == OS.WINDOWS
        ? ImmutableList.of("--windows_enable_symlinks")
        : ImmutableList.of();
  }

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();

    addOptions(
        "--remote_executor=grpc://localhost:" + worker.getPort(),
        "--remote_download_minimal",
        "--dynamic_local_strategy=standalone",
        "--dynamic_remote_strategy=remote");

    if (OS.getCurrent() == OS.WINDOWS) {
      // Force MSYS `ln -s` to create a (possibly dangling) native symlink or junction.
      // The default behavior is to require the target path to exist and make a deep copy.
      addOptions("--action_env=MSYS=winsymlinks:native");
    }

    if (useDiskCache) {
      diskCacheDir = getWorkspace().getRelative(UUID.randomUUID().toString());
      addOptions("--disk_cache=" + diskCacheDir.getPathString());
    }
  }

  @Override
  protected void setDownloadToplevel() {
    addOptions("--remote_download_outputs=toplevel");
  }

  @Override
  protected void setDownloadAll() {
    addOptions("--remote_download_outputs=all");
  }

  @Override
  protected void enableActionRewinding() {
    addOptions(
        "--rewind_lost_inputs",
        // Disable build rewinding.
        "--experimental_remote_cache_eviction_retries=0");
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new RemoteModule())
        .addBlazeModule(new BuildSummaryStatsModule())
        .addBlazeModule(new BlockWaitingModule());
  }

  @Override
  protected ImmutableList<BlazeModule> getSpawnModules() {
    return ImmutableList.<BlazeModule>builder()
        .addAll(super.getSpawnModules())
        .add(new StandaloneModule())
        .add(new CredentialModule())
        .add(new DynamicExecutionModule())
        .build();
  }

  @Override
  protected void assertOutputEquals(Path path, String expectedContent) throws Exception {
    assertThat(readContent(path, UTF_8)).isEqualTo(expectedContent);
  }

  @Override
  protected void assertOutputContains(String content, String contains) throws Exception {
    assertThat(content).contains(contains);
  }

  @Override
  protected void evictAllBlobs() throws Exception {
    worker.reset();
    if (useDiskCache && diskCacheDir != null) {
      Path casDir = diskCacheDir.getRelative("cas");
      if (casDir.exists()) {
        casDir.deleteTreesBelow();
      }
      Path acDir = diskCacheDir.getRelative("ac");
      if (acDir.exists()) {
        acDir.deleteTreesBelow();
      }
    }
  }

  @Override
  protected boolean hasAccessToRemoteOutputs() {
    return true;
  }

  @Override
  protected void injectFile(byte[] content) {}

  @Test
  public void executeRemotely_actionFails_outputsAreAvailableLocallyForDebuggingPurpose()
      throws Exception {
    write(
        "a/BUILD",
        """
        genrule(
            name = "fail",
            srcs = [],
            outs = ["fail.txt"],
            cmd = "echo foo > $@ && exit 1",
        )
        """);

    assertThrows(BuildFailedException.class, () -> buildTarget("//a:fail"));

    assertOnlyOutputContent("//a:fail", "fail.txt", "foo\n");
  }

  @Test
  public void intermediateOutputsAreInputForInternalActions_prefetchIntermediateOutputs()
      throws Exception {
    // Test that a remotely stored output that's an input to a internal action
    // (ctx.actions.expand_template) is staged lazily for action execution.
    write(
        "a/substitute_username.bzl",
        """
        def _substitute_username_impl(ctx):
            ctx.actions.expand_template(
                template = ctx.file.template,
                output = ctx.outputs.out,
                substitutions = {
                    "{USERNAME}": ctx.attr.username,
                },
            )

        substitute_username = rule(
            implementation = _substitute_username_impl,
            attrs = {
                "username": attr.string(mandatory = True),
                "template": attr.label(
                    allow_single_file = True,
                    mandatory = True,
                ),
            },
            outputs = {"out": "%{name}.txt"},
        )
        """);
    write(
        "a/BUILD",
        """
        load(":substitute_username.bzl", "substitute_username")

        genrule(
            name = "generate-template",
            srcs = [],
            outs = ["template.txt"],
            cmd = 'echo -n "Hello {USERNAME}!" > $@',
        )

        substitute_username(
            name = "substitute-buchgr",
            template = ":generate-template",
            username = "buchgr",
        )
        """);

    buildTarget("//a:substitute-buchgr");

    // The genrule //a:generate-template should run remotely and //a:substitute-buchgr should be a
    // internal action running locally.
    events.assertContainsInfo("3 processes: 2 internal, 1 remote");
    Artifact intermediateOutput = getOnlyElement(getArtifacts("//a:generate-template"));
    assertThat(intermediateOutput.getPath().exists()).isTrue();
    assertOnlyOutputContent("//a:substitute-buchgr", "substitute-buchgr.txt", "Hello buchgr!");
  }

  @Test
  public void changeOutputMode_notInvalidateActions() throws Exception {
    write(
        "a/BUILD",
        """
        genrule(
            name = "foo",
            srcs = [],
            outs = ["foo.txt"],
            cmd = "echo foo > $@",
        )

        genrule(
            name = "foobar",
            srcs = [":foo"],
            outs = ["foobar.txt"],
            cmd = "cat $(location :foo) > $@ && echo bar > $@",
        )
        """);
    // Download all outputs with regex so in the next build with ALL mode, the actions are not
    // invalidated because of missing outputs.
    addOptions("--remote_download_regex=.*");
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    runtimeWrapper.registerSubscriber(actionEventCollector);
    buildTarget("//a:foobar");
    // Add the new option here because waitDownloads below will internally create a new command
    // which will parse the new option.
    setDownloadAll();
    waitDownloads();
    // 3 = workspace status action + //:foo + //:foobar
    assertThat(actionEventCollector.getNumActionNodesEvaluated()).isEqualTo(3);
    actionEventCollector.clear();

    buildTarget("//a:foobar");

    // Changing output mode should not invalidate SkyFrame's in-memory caching.
    assertThat(actionEventCollector.getNumActionNodesEvaluated()).isEqualTo(0);
    events.assertContainsInfo("0 processes");
  }

  @Test
  public void outputSymlinkHandledGracefully() throws Exception {
    write(
        "a/defs.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_symlink(ctx.label.name)
            ctx.actions.run_shell(
                inputs = [],
                outputs = [out],
                command = "ln -s hello $1",
                arguments = [out.path],
                use_default_shell_env = True,
            )
            return DefaultInfo(files = depset([out]))

        my_rule = rule(
            implementation = _impl,
        )
        """);

    write(
        "a/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "hello")
        """);

    buildTarget("//a:hello");

    Path outputPath = getOutputPath("a/hello");
    assertThat(outputPath.stat(Symlinks.NOFOLLOW).isSymbolicLink()).isTrue();
  }

  @Test
  public void replaceOutputDirectoryWithFile() throws Exception {
    write(
        "a/defs.bzl",
        """
        def _impl(ctx):
            dir = ctx.actions.declare_directory(ctx.label.name + ".dir")
            ctx.actions.run_shell(
                outputs = [dir],
                command = "touch $1/hello",
                arguments = [dir.path],
            )
            return DefaultInfo(files = depset([dir]))

        my_rule = rule(
            implementation = _impl,
        )
        """);
    write(
        "a/BUILD",
        """
        load(":defs.bzl", "my_rule")

        my_rule(name = "hello")
        """);

    setDownloadToplevel();
    buildTarget("//a:hello");

    // Replace the existing output directory of the package with a file.
    // A subsequent build should remove this file and replace it with a
    // directory.
    Path outputPath = getOutputPath("a");
    outputPath.deleteTree();
    FileSystemUtils.writeContent(outputPath, new byte[] {1, 2, 3, 4, 5});

    buildTarget("//a:hello");
  }

  @Test
  public void downloadTopLevel_doesNotDownloadUnrequestedOutputGroups() throws Exception {
    write(
        "a/defs.bzl",
        """
        def _rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".sh")
            ctx.actions.run_shell(
                outputs = [out],
                command = "echo 'echo Hello World' > {output} && chmod +x {output}".format(output = out.path),
            )
            runfiles = ctx.runfiles(
                transitive_files = depset(
                    transitive = [target[DefaultInfo].files for target in ctx.attr.data],
                ),
            ).merge_all([
                target[DefaultInfo].default_runfiles for target in ctx.attr.data
            ])
            return DefaultInfo(
                executable = out,
                runfiles = runfiles,
            )

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "data": attr.label_list(allow_files = True),
            },
            executable = True,
        )

        def _aspect_impl(target, ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".sha256")
            ctx.actions.run_shell(
                inputs = depset(
                    transitive = [
                        target[DefaultInfo].files,
                        target[DefaultInfo].default_runfiles.files,
                    ],
                ),
                outputs = [out],
                command = "echo 'hash' > {output}".format(output = out.path),
            )
            return [
                OutputGroupInfo(
                    my_aspect_out = depset([out]),
                )
            ]

        my_aspect = aspect(implementation = _aspect_impl)
        """);

    write(
        "a/BUILD",
        """
        load(":defs.bzl", "my_rule")

        genrule(
            name = "gen_data",
            srcs = [],
            outs = ["some_data"],
            cmd = "echo 'data content' > $@",
        )

        my_rule(
            name = "hello",
            data = [":gen_data"],
        )
        """);

    setDownloadToplevel();
    addOptions("--aspects=//a:defs.bzl%my_aspect", "--output_groups=my_aspect_out");
    buildTarget("//a:hello");

    assertValidOutputFile("a/hello.sha256", "hash\n");
    assertOutputsDoNotExist("//a:gen_data");
    assertOutputsDoNotExist("//a:hello");
  }

  @Test
  public void leaseExtension() throws Exception {
    // The lease service is only used when action rewinding is disabled.
    disableActionRewinding();
    // Test that Bazel will extend the leases for remote output by sending FindMissingBlobs calls
    // periodically to remote server. The test assumes remote server will set mtime of referenced
    // blobs to `now`.
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo -n foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        // We need the action lasts more than --experimental_remote_cache_ttl so Bazel has the
        // chance to extend the lease
        "  cmd = 'sleep 2 && cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    addOptions("--experimental_remote_cache_ttl=1s", "--experimental_remote_cache_lease_extension");
    var blobPath = getFileSystem().getPath(worker.getCasBlobPath("foo".getBytes(UTF_8)));
    var mtimes = Sets.newConcurrentHashSet();
    // Observe the mtime of the blob in background.
    var thread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  mtimes.add(blobPath.getLastModifiedTime());
                } catch (IOException ignored) {
                  // Intentionally ignored
                }
              }
            });
    thread.start();

    buildTarget("//:foobar");
    waitDownloads();

    thread.interrupt();
    thread.join();
    // We should be able to observe more than 1 mtime if the server extends the lease.
    assertThat(mtimes.size()).isGreaterThan(1);
  }

  @Test
  public void actionRewinding_lostInputWithStaleActionCacheEntry_recovers() throws Exception {
    // The unverified worker serves cached action results even if the blobs they reference are
    // missing from the CAS, emulating a remote cache without integrity checks. Rewinding must not
    // accept such an action result for a rewound action, as it would reinstate the stale metadata
    // of the lost blob instead of regenerating it.
    var unverifiedWorker = IntegrationTestUtils.createWorker("--noaction_cache_integrity_check");
    try (var ignored = unverifiedWorker.start()) {
      addOptions("--remote_executor=grpc://localhost:" + unverifiedWorker.getPort());
      enableActionRewinding();
      write(
          "a/BUILD",
          """
          genrule(
              name = "foo",
              srcs = [],
              outs = ["foo.out"],
              cmd = "echo -n foo > $@",
          )

          genrule(
              name = "bar",
              srcs = [
                  ":foo",
                  "bar.in",
              ],
              outs = ["bar.out"],
              cmd = "cat $(location :foo) $(location bar.in) > $@",
          )
          """);
      write("a/bar.in", "bar");

      buildTarget("//a:bar");

      // Delete the blob backing foo.out from the CAS while keeping all action cache entries.
      unverifiedWorker.evictBlob("foo".getBytes(UTF_8));
      if (useDiskCache) {
        // Prevent the disk cache from restoring the deleted blob.
        addOptions("--disk_cache=" + UUID.randomUUID());
      }

      // Invalidate only //a:bar so that its execution discovers the lost input and rewinds //a:foo.
      write("a/bar.in", "bar2");
      setDownloadToplevel();
      var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
      var sampler = sampleKnownMissingCasDigests();
      buildTarget("//a:bar");

      assertValidOutputFile("a/bar.out", "foobar2\n");
      // Most remote caches verify that the blobs referenced by an action result are present, so
      // //a:foo accepts the stale action result the first time it is rewound. Only once //a:bar has
      // discovered the lost input again is //a:foo rewound a second time and actually executed.
      assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//a:foo", "//a:foo");
      // Action rewinding doesn't track lost digests.
      sampler.assertSizeAtExecutionPhaseComplete(0);
    }
  }

  @Test
  public void actionRewinding_chainedLostInputsWithStaleActionCacheEntries_recovers(
      @TestParameter boolean actionCacheIntegrityCheck) throws Exception {
    // A rewound action that itself observes a lost input must report the lost digest just like an
    // action that hasn't been rewound. Otherwise, if the worker serves cached action results even
    // if the blobs they reference are missing from the CAS, the rewound generating action accepts
    // the stale action result and the two actions keep rewinding each other until the limit on
    // repeated lost inputs fails the build.
    var chainWorker =
        IntegrationTestUtils.createWorker(
            "--action_cache_integrity_check=" + actionCacheIntegrityCheck);
    try (var ignored = chainWorker.start()) {
      addOptions("--remote_executor=grpc://localhost:" + chainWorker.getPort());
      enableActionRewinding();
      write(
          "a/BUILD",
          """
          genrule(
              name = "foo",
              srcs = [],
              outs = ["foo.out"],
              cmd = "echo -n foo > $@",
          )

          genrule(
              name = "bar",
              srcs = [":foo"],
              outs = ["bar.out"],
              cmd = "cat $(location :foo) > $@ && echo -n bar >> $@",
          )

          genrule(
              name = "baz",
              srcs = [
                  ":bar",
                  "baz.in",
              ],
              outs = ["baz.out"],
              cmd = "cat $(location :bar) $(location baz.in) > $@",
          )
          """);
      write("a/baz.in", "baz");

      buildTarget("//a:baz");

      // Delete the blobs backing foo.out and bar.out from the CAS while keeping all action cache
      // entries. Rewinding //a:bar to regenerate bar.out then discovers that foo.out is lost too.
      chainWorker.evictBlob("foo".getBytes(UTF_8));
      chainWorker.evictBlob("foobar".getBytes(UTF_8));
      if (useDiskCache) {
        // Prevent the disk cache from restoring the deleted blobs.
        addOptions("--disk_cache=" + UUID.randomUUID());
      }

      // Invalidate only //a:baz so that its execution discovers the lost input and rewinds //a:bar,
      // which in turn discovers the other lost input and rewinds //a:foo.
      write("a/baz.in", "baz2");
      setDownloadToplevel();
      var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
      var sampler = sampleKnownMissingCasDigests();
      buildTarget("//a:baz");

      assertValidOutputFile("a/baz.out", "foobarbaz2\n");
      if (actionCacheIntegrityCheck) {
        assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
            .containsExactly("//a:bar", "//a:foo")
            .inOrder();
      } else {
        // Each rewound action accepts the stale action result the first time it is rewound and is
        // only executed when it is rewound a second time.
        assertThat(rewoundArtifactOwnerLabels(rewoundKeys))
            .containsExactly("//a:bar", "//a:bar", "//a:foo", "//a:foo")
            .inOrder();
      }
      // Action rewinding doesn't track lost digests.
      sampler.assertSizeAtExecutionPhaseComplete(0);
    }
  }

  @Test
  public void actionRewinding_lostInputWithStaleDiskCacheEntry_recovers() throws Exception {
    // With action rewinding, the disk cache doesn't verify that the blobs referenced by an action
    // result are present. As the outputs of remotely executed actions aren't downloaded, it thus
    // serves a stale action result for //a:foo once foo.out has been lost remotely. The disk cache
    // is consulted before the remote cache, so a rewound action must verify the disk cache entry.
    // Otherwise, //a:foo would accept the stale action result every time it is rewound until the
    // limit on repeated lost inputs fails the build.
    assumeTrue(useDiskCache);
    enableActionRewinding();
    write(
        "a/BUILD",
        """
        genrule(
            name = "foo",
            srcs = [],
            outs = ["foo.out"],
            cmd = "echo -n foo > $@",
        )

        genrule(
            name = "bar",
            srcs = [
                ":foo",
                "bar.in",
            ],
            outs = ["bar.out"],
            cmd = "cat $(location :foo) $(location bar.in) > $@",
        )
        """);
    write("a/bar.in", "bar");

    buildTarget("//a:bar");

    // Delete the blob backing foo.out from the remote CAS and, should it be there, from the disk
    // cache's CAS, but keep the action cache entries referencing it in both.
    worker.evictBlob("foo".getBytes(UTF_8));
    String fooHash = worker.getCasBlobPath("foo".getBytes(UTF_8)).getBaseName();
    Path diskCacheBlob =
        diskCacheDir.getRelative("cas").getRelative(fooHash.substring(0, 2)).getRelative(fooHash);
    var unused = diskCacheBlob.delete();

    // Invalidate only //a:bar so that its execution discovers the lost input and rewinds //a:foo.
    write("a/bar.in", "bar2");
    setDownloadToplevel();
    var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
    buildTarget("//a:bar");

    assertValidOutputFile("a/bar.out", "foobar2\n");
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//a:foo");
  }

  @Test
  public void actionRewinding_lostInputUploadedAgain_acceptsCachedResult() throws Exception {
    // Once a lost blob has been uploaded again, e.g. by a concurrent build, a remote cache that
    // verifies that the blobs referenced by an action result are present serves the action result
    // of the generating action again. The rewound generating action should accept it rather than
    // execute again.
    enableActionRewinding();
    write(
        "a/BUILD",
        """
        genrule(
            name = "foo",
            srcs = [],
            outs = ["foo.out"],
            cmd = "echo -n foo > $@",
        )

        genrule(
            name = "bar",
            srcs = [
                ":foo",
                "bar.in",
            ],
            outs = ["bar.out"],
            cmd = "cat $(location :foo) $(location bar.in) > $@",
        )
        """);
    write("a/bar.in", "bar");

    buildTarget("//a:bar");

    // Delete the blob backing foo.out from the CAS while keeping all action cache entries.
    worker.evictBlob("foo".getBytes(UTF_8));
    if (useDiskCache) {
      // Prevent the disk cache from restoring the deleted blob.
      addOptions("--disk_cache=" + UUID.randomUUID());
    }
    // Emulate another client uploading foo.out again after //a:bar has discovered that it is lost,
    // but before //a:foo is rewound.
    actionEventRecorder.setActionRewoundEventSubscriber(
        _ -> {
          try {
            worker.putCasBlob("foo".getBytes(UTF_8));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    getRuntimeWrapper().registerSubscriber(actionEventRecorder);

    // Invalidate only //a:bar so that its execution discovers the lost input and rewinds //a:foo.
    write("a/bar.in", "bar2");
    setDownloadToplevel();
    var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
    buildTarget("//a:bar");

    assertValidOutputFile("a/bar.out", "foobar2\n");
    assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//a:foo");
    ImmutableList<SpawnResult> fooSpawnResults =
        actionEventRecorder.getActionResultReceivedEvents().stream()
            .filter(
                e ->
                    e.getAction()
                        .getPrimaryOutput()
                        .getRootRelativePathString()
                        .equals("a/foo.out"))
            .flatMap(e -> e.getActionResult().spawnResults().stream())
            .collect(toImmutableList());
    assertThat(fooSpawnResults).isNotEmpty();
    assertThat(fooSpawnResults.stream().allMatch(SpawnResult::isCacheHit)).isTrue();
  }

  @Test
  public void actionRewinding_httpCache_lostInputWithStaleActionCacheEntry_recovers()
      throws Exception {
    // HTTP caches can't verify that the blobs referenced by an action result are present, so a
    // rewound action must not accept cached results from them. Otherwise, //a:foo would accept the
    // stale action result every time it is rewound until the limit on repeated lost inputs fails
    // the build.
    var httpWorker = IntegrationTestUtils.createWorker(/* useHttp= */ true);
    try (var ignored = httpWorker.start()) {
      addOptions("--remote_executor=", "--remote_cache=http://localhost:" + httpWorker.getPort());
      enableActionRewinding();
      write(
          "a/BUILD",
          """
          genrule(
              name = "foo",
              srcs = [],
              outs = ["foo.out"],
              cmd = "echo -n foo > $@",
          )

          genrule(
              name = "bar",
              srcs = [
                  ":foo.out",
                  "bar.in",
              ],
              outs = ["bar.out"],
              cmd = "cat $(location :foo.out) $(location bar.in) > $@",
          )
          """);
      write("a/bar.in", "one");

      buildTarget("//a:bar");

      // Delete foo.out locally.
      clean();
      // Delete foo.out remotely, but keep the action cache entry for //a:foo.
      httpWorker.evictBlob("foo".getBytes(UTF_8));
      if (useDiskCache) {
        // Prevent the disk cache from restoring the deleted blob.
        addOptions("--disk_cache=" + UUID.randomUUID());
      }
      // Invalidate //a:bar so that its execution discovers the lost input and rewinds //a:foo.
      write("a/bar.in", "two");
      setDownloadToplevel();
      var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
      buildTarget("//a:bar");

      assertValidOutputFile("a/bar.out", "footwo\n");
      assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//a:foo");
    }
  }

  @Test
  public void actionRewinding_lostTree_recovers(@TestParameter boolean localExecution)
      throws Exception {
    // A stale action result that references a lost Tree message is only found to be stale when the
    // Tree message is fetched to process its outputs. Both remote execution and local execution
    // with a remote cache must treat this as a cache miss rather than fail the build.
    // Adapted from https://github.com/bazelbuild/bazel/pull/31251.
    assumeFalse(useDiskCache);
    var unverifiedWorker = IntegrationTestUtils.createWorker("--noaction_cache_integrity_check");
    try (var ignored = unverifiedWorker.start()) {
      addOptions("--remote_executor=grpc://localhost:" + unverifiedWorker.getPort());
      setDownloadToplevel();
      writeOutputDirRule();
      write("BUILD");
      write(
          "a/BUILD",
          """
          load("//:output_dir.bzl", "output_dir")

          output_dir(
              name = "foo.out",
              content_map = {"file-inside": "hello world"},
          )

          genrule(
              name = "bar",
              srcs = [
                  "foo.out",
                  "bar.in",
              ],
              outs = ["bar.out"],
              cmd = "( ls $(location :foo.out); cat $(location :bar.in) ) > $@",
          )
          """);
      write("a/bar.in", "bar");

      buildTarget("//a:bar");

      // Delete all blobs from the CAS, including the Tree message describing foo.out, but keep all
      // action cache entries.
      unverifiedWorker.evictAllCasBlobs();

      // Invalidate only //a:bar so that its execution discovers the lost input and rewinds
      // //a:foo.out.
      write("a/bar.in", "updated bar");
      if (localExecution) {
        addOptions("--strategy_regexp=.*=local");
      }
      enableActionRewinding();
      var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
      buildTarget("//a:bar");

      assertValidOutputFile("a/bar.out", "file-inside\nupdated bar\n");
      // The stale action result is rejected as soon as its Tree message turns out to be missing.
      assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//a:foo.out");
    }
  }

  @Test
  public void actionRewinding_localExecution_recovers(
      @TestParameter boolean actionCacheIntegrityCheck, @TestParameter boolean uploadLocalResults)
      throws Exception {
    // Actions that are executed locally with a remote cache recover lost inputs just like remotely
    // executed actions, whether or not their outputs are uploaded again.
    // Adapted from https://github.com/bazelbuild/bazel/pull/31251.
    var cacheWorker =
        IntegrationTestUtils.createWorker(
            "--action_cache_integrity_check=" + actionCacheIntegrityCheck);
    try (var ignored = cacheWorker.start()) {
      addOptions("--remote_executor=", "--remote_cache=grpc://localhost:" + cacheWorker.getPort());
      enableActionRewinding();
      write(
          "a/BUILD",
          """
          genrule(
              name = "foo",
              srcs = [],
              outs = ["foo.out"],
              cmd = "echo -n foo > $@",
          )

          genrule(
              name = "bar",
              srcs = [
                  ":foo.out",
                  "bar.in",
              ],
              outs = ["bar.out"],
              cmd = "cat $(location :foo.out) $(location bar.in) > $@",
          )
          """);
      write("a/bar.in", "one");

      buildTarget("//a:bar");

      // Delete foo.out locally.
      clean();
      // Delete foo.out remotely, but keep the action cache entry for //a:foo.
      cacheWorker.evictBlob("foo".getBytes(UTF_8));
      if (useDiskCache) {
        // Prevent the disk cache from restoring the deleted blob.
        addOptions("--disk_cache=" + UUID.randomUUID());
      }
      addOptions("--remote_upload_local_results=" + uploadLocalResults);
      // Invalidate //a:bar so that its execution discovers the lost input and rewinds //a:foo.
      write("a/bar.in", "two");
      setDownloadToplevel();
      var rewoundKeys = rewindingTestsHelper.collectOrderedRewoundKeys();
      var sampler = sampleKnownMissingCasDigests();
      buildTarget("//a:bar");

      assertValidOutputFile("a/bar.out", "footwo\n");
      if (actionCacheIntegrityCheck) {
        // The remote cache doesn't serve the stale action result, so //a:foo is executed right away
        // rather than rewound.
        assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).isEmpty();
      } else {
        // //a:foo accepts the stale action result, both before and the first time it is rewound.
        assertThat(rewoundArtifactOwnerLabels(rewoundKeys)).containsExactly("//a:foo", "//a:foo");
      }
      assertThat(cacheWorker.hasCasBlob("foo".getBytes(UTF_8))).isEqualTo(uploadLocalResults);
      // Action rewinding doesn't track lost digests.
      sampler.assertSizeAtExecutionPhaseComplete(0);
    }
  }

  @Test
  public void downloadTopLevel_deepSymlinkToFile() throws Exception {
    setDownloadToplevel();
    write(
        "defs.bzl",
        """
        def _impl(ctx):
            file = ctx.actions.declare_file(ctx.label.name + ".file")
            ctx.actions.run_shell(
                outputs = [file],
                command = "echo -n hello > $1",
                arguments = [file.path],
            )

            shallow = ctx.actions.declare_file(ctx.label.name + ".shallow")
            ctx.actions.symlink(output = shallow, target_file = file)

            deep = ctx.actions.declare_file(ctx.label.name + ".deep")
            ctx.actions.symlink(output = deep, target_file = shallow)

            return DefaultInfo(files = depset([deep]))

        symlink = rule(_impl)
        """);
    write("BUILD", "load(':defs.bzl', 'symlink')", "symlink(name = 'foo')");

    buildTarget("//:foo");

    // Materialization skips the intermediate symlink.
    assertSymlink("foo.deep", getOutputPath("foo.file").asFragment());
    assertValidOutputFile("foo.deep", "hello");
  }

  @Test
  public void downloadTopLevel_deepSymlinkToDirectory() throws Exception {
    setDownloadToplevel();
    write(
        "defs.bzl",
        """
        def _impl(ctx):
            dir = ctx.actions.declare_directory(ctx.label.name + ".dir")
            ctx.actions.run_shell(
                outputs = [dir],
                command = "echo -n hello > $1/file.txt",
                arguments = [dir.path],
            )

            shallow = ctx.actions.declare_directory(ctx.label.name + ".shallow")
            ctx.actions.symlink(output = shallow, target_file = dir)

            deep = ctx.actions.declare_directory(ctx.label.name + ".deep")
            ctx.actions.symlink(output = deep, target_file = shallow)

            return DefaultInfo(files = depset([deep]))

        symlink = rule(_impl)
        """);
    write("BUILD", "load(':defs.bzl', 'symlink')", "symlink(name = 'foo')");

    buildTarget("//:foo");

    // Materialization skips the intermediate symlink.
    assertSymlink("foo.deep", getOutputPath("foo.dir").asFragment());
    assertValidOutputFile("foo.deep/file.txt", "hello");
  }

  @Test
  public void downloadTopLevel_genruleSymlinkToInput() throws Exception {
    setDownloadToplevel();
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  outs = ['foo'],",
        "  cmd = 'echo hello > $@',",
        ")",
        "genrule(",
        "  name = 'gen',",
        "  srcs = ['foo'],",
        "  outs = ['foo-link'],",
        "  cmd = 'cd $(RULEDIR) && ln -s foo foo-link',",
        // In Blaze, heuristic label expansion defaults to True and will cause `foo` to be expanded
        // into `blaze-out/.../bin/foo` in the genrule command line.
        "  heuristic_label_expansion = False,",
        ")");

    buildTarget("//:gen");

    assertSymlink("foo-link", getOutputPath("foo").asFragment());
    assertValidOutputFile("foo-link", "hello\n");

    // Delete link, re-plant symlink
    getOutputPath("foo").delete();
    buildTarget("//:gen");

    assertSymlink("foo-link", getOutputPath("foo").asFragment());
    assertValidOutputFile("foo-link", "hello\n");

    // Delete target, re-download it
    getOutputPath("foo").delete();

    buildTarget("//:gen");

    assertSymlink("foo-link", getOutputPath("foo").asFragment());
    assertValidOutputFile("foo-link", "hello\n");
  }

  @Test
  public void downloadTopLevel_genruleSymlinkToOutput() throws Exception {
    setDownloadToplevel();
    write(
        "BUILD",
        """
        genrule(
          name = 'gen',
          outs = ['foo', 'foo-link'],
          cmd = 'cd $(RULEDIR) && echo hello > foo && ln -s foo foo-link',
          # In Blaze, heuristic label expansion defaults to True and will cause `foo` to be expanded
          # into `blaze-out/.../bin/foo` in the genrule command line.
          heuristic_label_expansion = False,
        )
        """);

    buildTarget("//:gen");

    assertSymlink("foo-link", PathFragment.create("foo"));
    assertValidOutputFile("foo-link", "hello\n");

    // Delete link, re-plant symlink
    getOutputPath("foo").delete();
    buildTarget("//:gen");

    assertSymlink("foo-link", PathFragment.create("foo"));
    assertValidOutputFile("foo-link", "hello\n");

    // Delete target, re-download it
    getOutputPath("foo").delete();

    buildTarget("//:gen");

    assertSymlink("foo-link", PathFragment.create("foo"));
    assertValidOutputFile("foo-link", "hello\n");
  }

  @Test
  public void remoteAction_inputTreeWithSymlinks() throws Exception {
    setDownloadToplevel();
    write(
        "tree.bzl",
        "def _impl(ctx):",
        "  d = ctx.actions.declare_directory(ctx.label.name)",
        "  ctx.actions.run_shell(",
        "    outputs = [d],",
        "    command = 'mkdir $1/dir && touch $1/file $1/dir/file && ln -s file $1/filesym && ln"
            + " -s dir $1/dirsym',",
        "    arguments = [d.path],",
        "  )",
        "  return DefaultInfo(files = depset([d]))",
        "tree = rule(_impl)");
    write(
        "BUILD",
        "load(':tree.bzl', 'tree')",
        "tree(name = 'tree')",
        "genrule(name = 'gen', srcs = [':tree'], outs = ['out'], cmd = 'touch $@')");

    // Populate cache
    buildTarget("//:gen");

    // Delete output, replay from cache
    getOutputPath("tree").deleteTree();
    getOutputPath("out").delete();
    buildTarget("//:gen");
  }

  @Test
  public void remoteFilesExpiredBetweenBuilds(@TestParameter boolean actionRewinding)
      throws Exception {
    // Arrange: Prepare workspace and populate remote cache
    write(
        "a/BUILD",
        """
        genrule(
            name = "foo",
            srcs = ["foo.in"],
            outs = ["foo.out"],
            cmd = "cat $(SRCS) > $@",
        )

        genrule(
            name = "bar",
            srcs = [
                "foo.out",
                "bar.in",
            ],
            outs = ["bar.out"],
            cmd = "cat $(SRCS) > $@",
        )
        """);
    write("a/foo.in", "foo");
    write("a/bar.in", "bar");

    // Populate remote cache
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out");
    assertOutputDoesNotExist("a/bar.out");
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    restartServer();

    // Clean build, foo.out isn't downloaded
    setDownloadToplevel();
    addOptions("--experimental_remote_cache_ttl=0s");
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out");
    assertValidOutputFile("a/bar.out", "foo\nbar\n");

    // Evict blobs from remote cache
    evictAllBlobs();

    // Act: Do an incremental build.
    write("a/bar.in", "updated bar");
    addOptions("--strategy_regexp=.*bar=local");
    if (actionRewinding) {
      // The expired input's generating action is rewound within the same build.
      enableActionRewinding();
    } else {
      // The build fails with the exit code that, in a non-integration test setup, would retry
      // the invocation automatically. Simulate the retry.
      disableActionRewinding();
      var e = assertThrows(BuildFailedException.class, () -> buildTarget("//a:bar"));
      assertThat(e.getDetailedExitCode().getFailureDetail().getSpawn().getCode())
          .isEqualTo(FailureDetails.Spawn.Code.REMOTE_CACHE_EVICTED);
    }

    buildTarget("//a:bar");
    waitDownloads();

    // Assert: target was successfully built
    assertValidOutputFile("a/bar.out", "foo\nupdated bar\n");
  }

  @Test
  public void remoteTreeFilesExpiredBetweenBuilds(@TestParameter boolean actionRewinding)
      throws Exception {
    // Arrange: Prepare workspace and populate remote cache
    write("BUILD");
    writeOutputDirRule();
    write(
        "a/BUILD",
        """
        load("//:output_dir.bzl", "output_dir")

        output_dir(
            name = "foo.out",
            content_map = {"file-inside": "hello world"},
        )

        genrule(
            name = "bar",
            srcs = [
                "foo.out",
                "bar.in",
            ],
            outs = ["bar.out"],
            cmd = "( ls $(location :foo.out); cat $(location :bar.in) ) > $@",
        )
        """);
    write("a/bar.in", "bar");

    // Populate remote cache
    buildTarget("//a:bar");
    assertThat(getOutputPath("a/foo.out").getDirectoryEntries()).isEmpty();
    assertOutputDoesNotExist("a/bar.out");
    getOutputBase().getRelative("action_cache").deleteTreesBelow();
    restartServer();

    // Clean build, foo.out isn't downloaded
    setDownloadToplevel();
    addOptions("--experimental_remote_cache_ttl=0s");
    buildTarget("//a:bar");
    assertOutputDoesNotExist("a/foo.out/file-inside");
    assertValidOutputFile("a/bar.out", "file-inside\nbar\n");

    // Evict blobs from remote cache
    evictAllBlobs();

    // Act: Do an incremental build.
    write("a/bar.in", "updated bar");
    addOptions("--strategy_regexp=.*bar=local");
    if (actionRewinding) {
      // The expired input's generating action is rewound within the same build.
      enableActionRewinding();
    } else {
      // The build fails with the exit code that, in a non-integration test setup, would retry
      // the invocation automatically. Simulate the retry.
      disableActionRewinding();
      var e = assertThrows(BuildFailedException.class, () -> buildTarget("//a:bar"));
      assertThat(e.getDetailedExitCode().getFailureDetail().getSpawn().getCode())
          .isEqualTo(FailureDetails.Spawn.Code.REMOTE_CACHE_EVICTED);
    }

    buildTarget("//a:bar");
    waitDownloads();

    // Assert: target was successfully built
    assertValidOutputFile("a/bar.out", "file-inside\nupdated bar\n");
  }
}

// Copyright 2026 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.readContent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.SplitBlobRequest;
import build.bazel.remote.execution.v2.SplitBlobResponse;
import build.bazel.remote.execution.v2.ToolDetails;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.remote.options.RemoteStartupOptions;
import com.google.devtools.build.lib.remote.util.IntegrationTestUtils;
import com.google.devtools.build.lib.remote.util.IntegrationTestUtils.WorkerInstance;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlockWaitingModule;
import com.google.devtools.build.lib.runtime.BuildSummaryStatsModule;
import com.google.devtools.build.lib.standalone.StandaloneModule;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsBase;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Integration tests that remote execution <em>inputs</em> are uploaded with content-defined
 * chunking, i.e. that {@code ensureInputsPresent} goes through the chunked upload path rather than
 * pushing whole blobs.
 *
 * <p>The assertions rely on a property of the test worker's CAS: {@code SplitBlob} only answers for
 * blobs that were previously registered by a {@code SpliceBlob} call (see {@code
 * CasServer#splitBlob}, which returns {@code NOT_FOUND} for any other digest). A successful {@code
 * SplitBlob} response for an input's digest is therefore direct evidence that the client uploaded
 * that input as chunks, not that the server is able to split it after the fact.
 *
 * <p>Unlike {@link ChunkedCacheIntegrationTestBase}, which covers the cache-only path (outputs
 * uploaded through {@code CombinedCache#uploadFile}), this test configures {@code --remote_executor}
 * and asserts on a large <em>source</em> file, which can only reach the CAS through {@code
 * RemoteExecutionCache#uploadFile}.
 */
@RunWith(TestParameterInjector.class)
public class ChunkedRemoteExecutionIntegrationTest extends BuildIntegrationTestCase {
  @ClassRule @Rule public static final WorkerInstance worker = IntegrationTestUtils.createWorker();

  /**
   * Comfortably above the chunking threshold of both chunking functions the worker advertises:
   * 2 MiB for FastCDC 2020 (4 * 512 KiB average) and just under 512 KiB for RepMaxCDC (2 * 256 KiB
   * minimum).
   */
  private static final int LARGE_INPUT_SIZE = 4 * 1024 * 1024;

  private static final int SMALL_INPUT_SIZE = 64 * 1024;

  @TestParameter({"fast_cdc_2020", "rep_max_cdc"})
  public String chunkingFunction;

  @Override
  protected ImmutableList<Class<? extends OptionsBase>> getStartupOptionClasses() {
    return ImmutableList.<Class<? extends OptionsBase>>builder()
        .addAll(super.getStartupOptionClasses())
        .add(RemoteStartupOptions.class)
        .build();
  }

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();
    addOptions(
        "--remote_executor=grpc://localhost:" + worker.getPort(),
        // Pin the strategy so the assertions can't silently pass on a locally executed action that
        // never uploaded its inputs.
        "--strategy=Genrule=remote",
        "--remote_download_outputs=all",
        "--experimental_remote_cache_chunking",
        "--experimental_remote_cache_chunking_function=" + chunkingFunction);
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
        .build();
  }

  @After
  public void waitDownloads() throws Exception {
    runtimeWrapper.newCommand();
  }

  @Test
  public void largeInput_uploadedForRemoteExecution_isChunked() throws Exception {
    byte[] inputContent = writeInput("large_input.bin", LARGE_INPUT_SIZE, /* seed= */ 1);
    writeSizeReportingGenrule("large_input.bin");

    buildTarget("//:size");

    assertThat(readContent(getOutputPath("size.txt"), UTF_8).trim())
        .isEqualTo(Integer.toString(LARGE_INPUT_SIZE));

    List<Digest> chunkDigests = splitBlob(computeDigest(inputContent));
    assertThat(chunkDigests.size()).isGreaterThan(1);
    assertThat(chunkDigests.stream().mapToLong(Digest::getSizeBytes).sum())
        .isEqualTo(inputContent.length);
    // The chunks are individually addressable in the CAS and reassemble to the original input.
    assertThat(downloadAndConcatenate(chunkDigests)).isEqualTo(inputContent);
  }

  @Test
  public void smallInput_uploadedForRemoteExecution_isNotChunked() throws Exception {
    byte[] inputContent = writeInput("small_input.bin", SMALL_INPUT_SIZE, /* seed= */ 2);
    writeSizeReportingGenrule("small_input.bin");

    buildTarget("//:size");

    assertThat(readContent(getOutputPath("size.txt"), UTF_8).trim())
        .isEqualTo(Integer.toString(SMALL_INPUT_SIZE));

    // Below the chunking threshold the blob must be uploaded whole, so the worker has no splice
    // mapping for it.
    StatusRuntimeException e =
        assertThrows(StatusRuntimeException.class, () -> splitBlob(computeDigest(inputContent)));
    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
  }

  @Test
  public void modifiedLargeInput_reusesChunksFromPreviousUpload() throws Exception {
    byte[] originalContent = writeInput("large_input.bin", LARGE_INPUT_SIZE, /* seed= */ 3);
    writeSizeReportingGenrule("large_input.bin");

    buildTarget("//:size");
    List<Digest> originalChunks = splitBlob(computeDigest(originalContent));

    // Append to the input: content-defined chunking keeps the boundaries of the unchanged prefix,
    // so the second upload should only have to send the chunks covering the appended tail.
    byte[] appended = deterministicContent(64 * 1024, /* seed= */ 4);
    byte[] modifiedContent = new byte[originalContent.length + appended.length];
    System.arraycopy(originalContent, 0, modifiedContent, 0, originalContent.length);
    System.arraycopy(appended, 0, modifiedContent, originalContent.length, appended.length);
    FileSystemUtils.writeContent(getWorkspace().getRelative("large_input.bin"), modifiedContent);

    buildTarget("//:size");

    assertThat(readContent(getOutputPath("size.txt"), UTF_8).trim())
        .isEqualTo(Integer.toString(modifiedContent.length));

    List<Digest> modifiedChunks = splitBlob(computeDigest(modifiedContent));
    assertThat(modifiedChunks.stream().mapToLong(Digest::getSizeBytes).sum())
        .isEqualTo(modifiedContent.length);
    // All but the trailing chunk(s) are shared with the first upload.
    long reusedBytes =
        modifiedChunks.stream()
            .filter(originalChunks::contains)
            .mapToLong(Digest::getSizeBytes)
            .sum();
    assertThat(reusedBytes).isAtLeast((long) originalContent.length / 2);
  }

  private byte[] writeInput(String relativePath, int size, int seed) throws IOException {
    byte[] content = deterministicContent(size, seed);
    FileSystemUtils.writeContent(getWorkspace().getRelative(relativePath), content);
    return content;
  }

  private void writeSizeReportingGenrule(String srcName) throws IOException {
    // The output is deliberately tiny: the only blob large enough to be chunked is the input, so a
    // SplitBlob hit can't be attributed to the output upload path.
    write(
        "BUILD",
        """
        genrule(
            name = "size",
            srcs = ["%s"],
            outs = ["size.txt"],
            cmd = "wc -c < $(location %s) > $@",
        )
        """
            .formatted(srcName, srcName));
  }

  /**
   * Returns {@code size} bytes of deterministic, non-repeating content. Content-defined chunking
   * cuts on the data itself, so the input must not be uniform.
   */
  private static byte[] deterministicContent(int size, int seed) {
    byte[] content = new byte[size];
    long state = seed * 0x9E3779B97F4A7C15L + 1;
    for (int i = 0; i < size; i++) {
      state = state * 6364136223846793005L + 1442695040888963407L;
      content[i] = (byte) (state >>> 33);
    }
    return content;
  }

  private Path getOutputPath(String binRelativePath) {
    return getTargetConfiguration().getBinDir().getRoot().getRelative(binRelativePath);
  }

  private static Digest computeDigest(byte[] data) {
    HashCode hash = Hashing.sha256().hashBytes(data);
    return Digest.newBuilder().setHash(hash.toString()).setSizeBytes(data.length).build();
  }

  private static ManagedChannel newChannel() {
    RequestMetadata metadata =
        RequestMetadata.newBuilder()
            .setCorrelatedInvocationsId("test-build-id")
            .setToolInvocationId("test-command-id")
            .setActionId("test-action-id")
            .setToolDetails(ToolDetails.newBuilder().setToolName("bazel").setToolVersion("test"))
            .build();
    ClientInterceptor interceptor = TracingMetadataUtils.attachMetadataInterceptor(metadata);
    return ManagedChannelBuilder.forAddress("localhost", worker.getPort())
        .usePlaintext()
        .intercept(interceptor)
        .build();
  }

  /**
   * Returns the chunks the worker has registered for {@code blobDigest}, or throws {@code
   * NOT_FOUND} if the blob was not uploaded via {@code SpliceBlob}.
   */
  private static List<Digest> splitBlob(Digest blobDigest) {
    ManagedChannel channel = newChannel();
    try {
      SplitBlobResponse response =
          ContentAddressableStorageGrpc.newBlockingStub(channel)
              .splitBlob(SplitBlobRequest.newBuilder().setBlobDigest(blobDigest).build());
      return ImmutableList.copyOf(response.getChunkDigestsList());
    } finally {
      channel.shutdownNow();
    }
  }

  private static byte[] downloadAndConcatenate(List<Digest> chunkDigests) throws IOException {
    ManagedChannel channel = newChannel();
    try {
      ByteStreamGrpc.ByteStreamBlockingStub stub = ByteStreamGrpc.newBlockingStub(channel);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (Digest chunkDigest : chunkDigests) {
        String resourceName = "blobs/" + chunkDigest.getHash() + "/" + chunkDigest.getSizeBytes();
        Iterator<ReadResponse> readIter =
            stub.read(ReadRequest.newBuilder().setResourceName(resourceName).build());
        while (readIter.hasNext()) {
          out.write(readIter.next().getData().toByteArray());
        }
      }
      return out.toByteArray();
    } finally {
      channel.shutdownNow();
    }
  }
}

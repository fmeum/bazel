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

package com.google.devtools.build.lib.bazel.repository.downloader;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.util.OS;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TrustStore}. */
@RunWith(JUnit4.class)
public class TrustStoreTest {

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  /**
   * Certificates to build test fixtures out of. They are taken from whatever trust store this JVM
   * is running with, so the tests never have to mint a certificate of their own, and they exercise
   * the same parsing paths that real bundles go through.
   */
  private List<X509Certificate> jvmCertificates;

  // The tests pin the OS so that they behave the same wherever they run, and always name a
  // certificate source through the environment so that the machine's own /etc/ssl contents can
  // never leak into the result.
  private static final OS OS_UNDER_TEST = OS.LINUX;

  @Before
  public void setUp() throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
    jvmCertificates = new ArrayList<>();
    for (TrustManager trustManager : factory.getTrustManagers()) {
      if (trustManager instanceof X509TrustManager x509TrustManager) {
        for (X509Certificate certificate : x509TrustManager.getAcceptedIssuers()) {
          jvmCertificates.add(certificate);
        }
      }
    }
    assertThat(jvmCertificates.size()).isAtLeast(8);
  }

  @Test
  public void jdkMode_withoutExtraCertificates_leavesTheJvmAlone() throws Exception {
    TrustStore trustStore =
        TrustStore.create(TrustStore.Mode.JDK, ImmutableList.of(), ImmutableMap.of());

    // A null socket factory means the connection is left on the JVM's default configuration, which
    // is what makes this mode exactly the behavior Bazel had before the flag existed.
    assertThat(trustStore.socketFactory()).isNull();
    assertThat(trustStore.sources()).isEmpty();
    assertThat(trustStore.describe()).isEqualTo("the JVM's default trust store");
  }

  @Test
  public void jdkMode_withCaCertificateFile_addsToTheJdkCertificates() throws Exception {
    File bundle = writePem("extra.pem", jvmCertificates.subList(0, 3));

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.JDK,
            ImmutableList.of(bundle.getPath()),
            ImmutableMap.of(),
            OS_UNDER_TEST);

    assertThat(trustStore.socketFactory()).isNotNull();
    assertThat(sourceNames(trustStore)).contains(bundle.getPath());
    assertThat(sourceNames(trustStore)).contains("the JVM's own trust store");
  }

  @Test
  public void mergedMode_deduplicatesCertificatesFoundInMoreThanOneSource() throws Exception {
    // The bundle holds certificates that are already in the JVM's trust store, so merging must not
    // grow the total. Duplicates are the normal case: a distribution bundle and the JDK bundle
    // overlap almost completely.
    File bundle = writePem("dupes.pem", jvmCertificates.subList(0, 3));

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.MERGED,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", bundle.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(jvmCertificates.size());
    assertThat(sourceCount(trustStore, bundle.getPath())).isEqualTo(3);
  }

  @Test
  public void mergedMode_unionsDisjointSources() throws Exception {
    File bundle = writePem("extra.pem", jvmCertificates.subList(0, 2));

    TrustStore jdkOnly =
        TrustStore.create(
            TrustStore.Mode.JDK,
            ImmutableList.of(bundle.getPath()),
            ImmutableMap.of(),
            OS_UNDER_TEST);
    TrustStore merged =
        TrustStore.create(
            TrustStore.Mode.MERGED,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", bundle.getPath()),
            OS_UNDER_TEST);

    assertThat(merged.trustAnchorCount()).isEqualTo(jdkOnly.trustAnchorCount());
  }

  @Test
  public void systemMode_ignoresTheJdkCertificates() throws Exception {
    File bundle = writePem("system.pem", jvmCertificates.subList(0, 3));

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.SYSTEM,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", bundle.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(3);
    assertThat(sourceNames(trustStore)).doesNotContain("the JVM's own trust store");
  }

  @Test
  public void certificateFileEnvVars_areAllHonored() throws Exception {
    for (String envVar : TrustStore.CERTIFICATE_FILE_ENV_VARS) {
      File bundle = writePem(envVar + ".pem", jvmCertificates.subList(0, 2));

      TrustStore trustStore =
          TrustStore.create(
              TrustStore.Mode.SYSTEM,
              ImmutableList.of(),
              ImmutableMap.of(envVar, bundle.getPath()),
              OS_UNDER_TEST);

      assertThat(trustStore.trustAnchorCount()).isEqualTo(2);
    }
  }

  @Test
  public void derEncodedCertificate_isRead() throws Exception {
    File der = tempFolder.newFile("one.der");
    Files.write(der.toPath(), jvmCertificates.get(0).getEncoded());

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.SYSTEM,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", der.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(1);
  }

  @Test
  public void javaKeyStore_isRead() throws Exception {
    // This is the shape of /etc/ssl/certs/java/cacerts, which an administrator may have updated
    // instead of, or as well as, the PEM bundle.
    File keyStoreFile = tempFolder.newFile("cacerts");
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    for (int i = 0; i < 4; i++) {
      keyStore.setCertificateEntry("cert-" + i, jvmCertificates.get(i));
    }
    try (OutputStream out = Files.newOutputStream(keyStoreFile.toPath())) {
      keyStore.store(out, "changeit".toCharArray());
    }

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.SYSTEM,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", keyStoreFile.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(4);
  }

  @Test
  public void certificateDir_readsEveryCertificateAndSkipsEverythingElse() throws Exception {
    File dir = tempFolder.newFolder("certs.d");
    writePem(new File(dir, "a.pem"), jvmCertificates.subList(0, 2));
    writePem(new File(dir, "b.pem"), jvmCertificates.subList(2, 3));
    // A hashed-symlink directory such as /etc/ssl/certs holds plenty of files that are not
    // certificates; they must be skipped rather than failing the whole directory.
    Files.writeString(new File(dir, "README").toPath(), "not a certificate");
    Files.createDirectory(new File(dir, "java").toPath());

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.SYSTEM,
            ImmutableList.of(),
            ImmutableMap.of(TrustStore.CERTIFICATE_DIR_ENV_VAR, dir.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(3);
  }

  @Test
  public void certificateDir_acceptsAListOfDirectories() throws Exception {
    File first = tempFolder.newFolder("first.d");
    File second = tempFolder.newFolder("second.d");
    writePem(new File(first, "a.pem"), jvmCertificates.subList(0, 2));
    writePem(new File(second, "b.pem"), jvmCertificates.subList(2, 4));

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.SYSTEM,
            ImmutableList.of(),
            ImmutableMap.of(
                TrustStore.CERTIFICATE_DIR_ENV_VAR,
                first.getPath() + File.pathSeparator + second.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(4);
  }

  @Test
  public void staleCertificateFileEnvVar_doesNotBreakDownloads() throws Exception {
    // A leftover SSL_CERT_FILE pointing at nothing is common enough in real shells. Since the JDK
    // certificates are still there in merged mode, it must be ignored rather than failing every
    // download the way a hard error would.
    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.MERGED,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", "/does/not/exist.pem"),
            OS_UNDER_TEST);

    assertThat(trustStore.trustAnchorCount()).isEqualTo(jvmCertificates.size());
  }

  @Test
  public void explicitlyNamedCaCertificate_thatCannotBeRead_isAnError() {
    // Unlike the environment variable, this one the user typed on purpose, so a typo has to be
    // reported instead of silently doing nothing.
    IOException e =
        assertThrows(
            IOException.class,
            () ->
                TrustStore.create(
                    TrustStore.Mode.MERGED,
                    ImmutableList.of("/does/not/exist.pem"),
                    ImmutableMap.of(),
                    OS_UNDER_TEST));

    assertThat(e).hasMessageThat().contains("/does/not/exist.pem");
  }

  @Test
  public void explicitlyNamedCaCertificate_thatHoldsNoCertificate_isAnError() throws Exception {
    File notACertificate = tempFolder.newFile("garbage.pem");
    Files.writeString(notACertificate.toPath(), "this is not a certificate");

    IOException e =
        assertThrows(
            IOException.class,
            () ->
                TrustStore.create(
                    TrustStore.Mode.MERGED,
                    ImmutableList.of(notACertificate.getPath()),
                    ImmutableMap.of(),
                    OS_UNDER_TEST));

    assertThat(e).hasMessageThat().contains(notACertificate.getPath());
  }

  @Test
  public void noCertificatesAtAll_failsAndNamesWhatWasSearched() throws Exception {
    File empty = tempFolder.newFolder("empty.d");

    IOException e =
        assertThrows(
            IOException.class,
            () ->
                TrustStore.create(
                    TrustStore.Mode.SYSTEM,
                    ImmutableList.of(),
                    ImmutableMap.of(TrustStore.CERTIFICATE_DIR_ENV_VAR, empty.getPath()),
                    OS_UNDER_TEST));

    assertThat(e).hasMessageThat().contains("Found no CA certificates");
    assertThat(e).hasMessageThat().contains(empty.getPath());
  }

  @Test
  public void describe_namesEverySourceAndItsCount() throws Exception {
    File bundle = writePem("described.pem", jvmCertificates.subList(0, 2));

    TrustStore trustStore =
        TrustStore.create(
            TrustStore.Mode.SYSTEM,
            ImmutableList.of(),
            ImmutableMap.of("SSL_CERT_FILE", bundle.getPath()),
            OS_UNDER_TEST);

    assertThat(trustStore.describe())
        .isEqualTo("2 CA certificate(s) from " + bundle.getPath() + ": 2");
  }

  @Test
  public void relevantEnv_keepsOnlyTheVariablesThatAreRead() {
    ImmutableMap<String, String> relevant =
        TrustStore.relevantEnv(
            ImmutableMap.of(
                "SSL_CERT_FILE", "/a",
                "SSL_CERT_DIR", "/b",
                "CURL_CA_BUNDLE", "/c",
                "PATH", "/usr/bin",
                "HOME", "/home/user"));

    assertThat(relevant)
        .containsExactly("SSL_CERT_FILE", "/a", "SSL_CERT_DIR", "/b", "CURL_CA_BUNDLE", "/c");
  }

  @Test
  public void describeRejectedChain_isEmptyWhenNothingWasRejected() {
    TrustStore.clearRejectedChain();

    assertThat(TrustStore.describeRejectedChain()).isNull();
  }

  private ImmutableList<String> sourceNames(TrustStore trustStore) {
    return trustStore.sources().stream()
        .map(TrustStore.Source::name)
        .collect(ImmutableList.toImmutableList());
  }

  private int sourceCount(TrustStore trustStore, String name) {
    return trustStore.sources().stream()
        .filter(source -> source.name().equals(name))
        .findFirst()
        .orElseThrow()
        .certificateCount();
  }

  private File writePem(String name, List<X509Certificate> certificates) throws Exception {
    return writePem(tempFolder.newFile(name), certificates);
  }

  private File writePem(File file, List<X509Certificate> certificates) throws Exception {
    StringBuilder pem = new StringBuilder();
    Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
    for (X509Certificate certificate : certificates) {
      pem.append("-----BEGIN CERTIFICATE-----\n")
          .append(encoder.encodeToString(certificate.getEncoded()))
          .append("\n-----END CERTIFICATE-----\n");
    }
    Files.write(file.toPath(), pem.toString().getBytes(UTF_8));
    return file;
  }
}

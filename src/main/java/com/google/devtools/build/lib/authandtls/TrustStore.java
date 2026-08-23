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

package com.google.devtools.build.lib.authandtls;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.util.OS;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The set of certificate authorities that the repository downloader trusts when it makes an HTTPS
 * connection.
 *
 * <p>The JVM can only ever be pointed at a <em>single</em> trust store: {@code
 * javax.net.ssl.trustStore} takes one path, and an OS-backed keystore type such as {@code
 * Windows-ROOT} or {@code KeychainStore-ROOT} <em>replaces</em> the certificates bundled with the
 * JDK rather than adding to them. Replacing them is not safe as a default, because neither store is
 * a superset of the other:
 *
 * <ul>
 *   <li>Windows ships a deliberately small root store and pulls the remaining roots on demand
 *       through CryptoAPI while it builds a chain. The JDK only enumerates the store, so it sees a
 *       point-in-time snapshot that is much smaller than the bundled roots on freshly imaged
 *       machines, in containers, and wherever automatic root updates are disabled by policy.
 *   <li>{@code KeychainStore-ROOT} on macOS reads only {@code SystemRootCertificates.keychain}, so
 *       it misses the CAs that MDM and administrators install into the system and login
 *       keychains, which is exactly where a corporate TLS-inspection CA ends up. Those live in
 *       {@code KeychainStore}.
 *   <li>Linux has no OS keystore API at all, only a handful of distribution-specific bundle paths.
 * </ul>
 *
 * <p>So instead of pointing the JVM at one store, this class collects trust anchors from every
 * source that applies and builds one merged, in-memory {@link KeyStore} out of the union. That is
 * lossless: JSSE reduces any trust store to a flat set of certificates ({@code
 * TrustStoreUtil.getTrustedCerts}) and never consults the trust-settings attributes that the Apple
 * provider attaches, so a merged store has exactly the trust semantics of the individual stores it
 * was built from. It is also strictly better for path building, since an intermediate cached in one
 * store can chain to a root that only another store has.
 */
public final class TrustStore {

  /** Which certificate sources the repository downloader draws trust anchors from. */
  public enum Mode {
    /** Only the certificates the JVM itself trusts, i.e. the ones bundled with the JDK. */
    JDK,
    /** The union of the JDK's certificates and the system's. This is the default. */
    MERGED,
    /**
     * Only the system's certificates, i.e. the same set that {@code curl} and other native tools
     * trust. The certificates bundled with the JDK are ignored.
     */
    SYSTEM
  }

  /**
   * Environment variables that name a certificate bundle, honored on every platform because they
   * are how {@code curl} and OpenSSL are pointed at a bundle.
   */
  @VisibleForTesting
  static final ImmutableList<String> CERTIFICATE_FILE_ENV_VARS =
      ImmutableList.of("SSL_CERT_FILE", "CURL_CA_BUNDLE", "NIX_SSL_CERT_FILE");

  /** Environment variable naming one or more directories of PEM files, as OpenSSL uses it. */
  @VisibleForTesting static final String CERTIFICATE_DIR_ENV_VAR = "SSL_CERT_DIR";

  /** Every environment variable {@link #create} reads. */
  private static final ImmutableList<String> ENV_VARS =
      ImmutableList.<String>builder()
          .addAll(CERTIFICATE_FILE_ENV_VARS)
          .add(CERTIFICATE_DIR_ENV_VAR)
          .build();

  /**
   * Certificate bundles shipped by the common Linux distributions and BSDs, searched when no bundle
   * was named by the environment.
   */
  @VisibleForTesting
  static final ImmutableList<String> DEFAULT_CERTIFICATE_FILES =
      ImmutableList.of(
          // Debian, Ubuntu, Arch, Alpine, Gentoo.
          "/etc/ssl/certs/ca-certificates.crt",
          // Fedora, RHEL and CentOS 7 and later, regenerated by update-ca-trust.
          "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
          // Older Fedora and RHEL.
          "/etc/pki/tls/certs/ca-bundle.crt",
          // openSUSE and SLES.
          "/etc/ssl/ca-bundle.pem",
          // Alpine, OpenBSD, FreeBSD.
          "/etc/ssl/cert.pem",
          // FreeBSD ports.
          "/usr/local/share/certs/ca-root-nss.crt",
          // NetBSD.
          "/etc/openssl/certs/ca-certificates.crt",
          // Java-shaped stores maintained by ca-certificates-java and update-ca-trust. An
          // administrator may have updated only these.
          "/etc/ssl/certs/java/cacerts",
          "/etc/pki/java/cacerts");

  /** Directories of individual certificates, searched only if no bundle above was found. */
  @VisibleForTesting
  static final ImmutableList<String> DEFAULT_CERTIFICATE_DIRS =
      ImmutableList.of("/etc/ssl/certs", "/etc/pki/tls/certs");

  /**
   * Certificate files larger than this are assumed not to be certificate files at all and are
   * skipped while scanning a directory.
   */
  private static final long MAX_SCANNED_FILE_SIZE = 8 * 1024 * 1024;

  /** A trust store that leaves the JVM's own TLS configuration untouched. */
  private static final TrustStore JVM_DEFAULT =
      new TrustStore(null, null, ImmutableList.of(), 0);

  /**
   * The certificate chain most recently rejected on this thread, if any.
   *
   * <p>JSSE reports a validation failure as a {@link javax.net.ssl.SSLHandshakeException} that
   * names neither the server's certificate nor its issuer, which is precisely what a user staring
   * at a "PKIX path building failed" message needs to know. The only place that chain is visible is
   * inside the trust manager, so {@link RecordingTrustManager} stashes it here on its way out. The
   * handshake runs on the thread that opened the connection, so a thread local hands it back to the
   * right caller even while other downloads run in parallel.
   */
  private static final ThreadLocal<X509Certificate[]> rejectedChain = new ThreadLocal<>();

  /** One group of certificates that contributed trust anchors, reported in error messages. */
  public record Source(String name, int certificateCount) {}

  @Nullable private final SSLSocketFactory socketFactory;
  @Nullable private final X509ExtendedTrustManager trustManager;
  private final ImmutableList<Source> sources;
  private final int trustAnchorCount;

  private TrustStore(
      @Nullable SSLSocketFactory socketFactory,
      @Nullable X509ExtendedTrustManager trustManager,
      ImmutableList<Source> sources,
      int trustAnchorCount) {
    this.socketFactory = socketFactory;
    this.trustManager = trustManager;
    this.sources = sources;
    this.trustAnchorCount = trustAnchorCount;
  }

  /** A trust store that leaves the JVM's own TLS configuration untouched. */
  public static TrustStore jvmDefault() {
    return JVM_DEFAULT;
  }

  /**
   * The socket factory to use for HTTPS connections, or {@code null} to leave the connection on the
   * JVM's default configuration.
   */
  @Nullable
  public SSLSocketFactory socketFactory() {
    return socketFactory;
  }

  /**
   * The trust manager to use for connections built on Netty, such as the gRPC and HTTP remote
   * cache clients, or {@code null} to leave them on the JVM's default configuration.
   */
  @Nullable
  public X509ExtendedTrustManager trustManager() {
    return trustManager;
  }

  /** The sources that contributed trust anchors, in the order they were consulted. */
  public ImmutableList<Source> sources() {
    return sources;
  }

  /** The number of distinct certificates trusted, or 0 if the JVM's own trust store is in use. */
  public int trustAnchorCount() {
    return trustAnchorCount;
  }

  /** A human-readable summary of what this trust store trusts, for TLS error messages. */
  public String describe() {
    if (sources.isEmpty()) {
      return "the JVM's default trust store";
    }
    return String.format(
        "%d CA certificate(s) from %s",
        trustAnchorCount,
        sources.stream()
            .map(source -> String.format("%s: %d", source.name(), source.certificateCount()))
            .collect(Collectors.joining(", ")));
  }

  /** The inputs that determine a trust store, used to share one build between all TLS clients. */
  private record Key(
      Mode mode,
      @Nullable String pinnedRootCertificate,
      ImmutableList<String> caCertificateFiles,
      ImmutableMap<String, String> env) {}

  // Enumerating an OS certificate store costs a native call per certificate, and every remote
  // cache connection, BES connection and download would otherwise repeat it. There are only ever a
  // couple of distinct configurations in a server, so an unbounded map is bounded in practice; the
  // guard is only there so a pathological caller cannot grow it without limit.
  private static final int MAX_CACHED_TRUST_STORES = 16;

  private static final ConcurrentHashMap<Key, TrustStore> cache = new ConcurrentHashMap<>();

  /**
   * Builds the trust store described by the given options, or returns a previously built one.
   *
   * @param options the TLS options of the command being run
   * @param clientEnv the environment of the client that issued the command
   * @throws IOException if a certificate file named by the user cannot be read, or if the
   *     configuration ends up trusting no certificate at all
   */
  public static TrustStore createFor(AuthAndTLSOptions options, Map<String, String> clientEnv)
      throws IOException {
    return create(
        options.getTlsTrustStore(),
        options.getTlsCertificate(),
        ImmutableList.copyOf(options.getTlsCaCertificates()),
        clientEnv);
  }

  /**
   * Builds the trust store for the given configuration, or returns a previously built one.
   *
   * @param mode which certificate sources to draw from
   * @param pinnedRootCertificate the single root certificate named by {@code --tls_certificate}, if
   *     any; naming one pins trust to it and its meaning is unchanged by {@code mode}
   * @param caCertificateFiles additional certificate files to trust, named explicitly by the user;
   *     it is an error if one of them cannot be read
   * @param clientEnv the environment of the client that issued the command
   * @throws IOException if a certificate file named by the user cannot be read, or if the
   *     configuration ends up trusting no certificate at all
   */
  public static TrustStore create(
      Mode mode,
      @Nullable String pinnedRootCertificate,
      ImmutableList<String> caCertificateFiles,
      Map<String, String> clientEnv)
      throws IOException {
    if (mode == Mode.JDK && pinnedRootCertificate == null && caCertificateFiles.isEmpty()) {
      // Nothing to add to what the JVM already does, so don't pay for building a trust store and
      // don't risk behaving differently from an unconfigured JVM.
      return JVM_DEFAULT;
    }
    Key key =
        new Key(
            mode, pinnedRootCertificate, caCertificateFiles, relevantEnv(clientEnv));
    TrustStore cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    // Not computeIfAbsent: building can fail, and a failure must surface every time rather than be
    // cached, and the build itself must not run while holding a bin lock.
    TrustStore trustStore =
        create(mode, pinnedRootCertificate, caCertificateFiles, clientEnv, OS.getCurrent());
    if (cache.size() < MAX_CACHED_TRUST_STORES) {
      cache.put(key, trustStore);
    }
    return trustStore;
  }

  @VisibleForTesting
  static TrustStore create(
      Mode mode,
      @Nullable String pinnedRootCertificate,
      ImmutableList<String> caCertificateFiles,
      Map<String, String> clientEnv,
      OS os)
      throws IOException {
    Collector collector = new Collector();
    if (pinnedRootCertificate != null) {
      // --tls_certificate names the certificate that is trusted to sign server certificates, so it
      // replaces the trust store rather than adding to it. Widening it to also trust the public
      // roots would quietly undo a deliberate pin.
      collector.addRequiredFile(Path.of(pinnedRootCertificate));
    } else {
      if (mode != Mode.SYSTEM) {
        collector.addJdkCertificates();
      }
      if (mode != Mode.JDK) {
        collector.addSystemCertificates(os, clientEnv);
      }
    }
    for (String file : caCertificateFiles) {
      collector.addRequiredFile(Path.of(file));
    }
    return collector.build();
  }

  @VisibleForTesting
  static void clearCacheForTesting() {
    cache.clear();
  }

  /** Forgets any chain recorded earlier on this thread. Call before starting a request. */
  public static void clearRejectedChain() {
    rejectedChain.remove();
  }

  /**
   * Describes the certificate chain that was last rejected on this thread, or {@code null} if no
   * chain was recorded.
   */
  @Nullable
  public static String describeRejectedChain() {
    X509Certificate[] chain = rejectedChain.get();
    if (chain == null || chain.length == 0) {
      return null;
    }
    // The issuer of the topmost certificate is the CA that has to be trusted for the chain to
    // validate, which is the certificate the user needs to install.
    return String.format(
        "the server presented a certificate for %s, and the chain needs to be anchored in %s",
        chain[0].getSubjectX500Principal().getName(),
        chain[chain.length - 1].getIssuerX500Principal().getName());
  }

  /** Returns the subset of {@code clientEnv} that {@link #create} reads. */
  @VisibleForTesting
  static ImmutableMap<String, String> relevantEnv(Map<String, String> clientEnv) {
    ImmutableMap.Builder<String, String> relevant = ImmutableMap.builder();
    for (String name : ENV_VARS) {
      String value = clientEnv.get(name);
      if (value != null) {
        relevant.put(name, value);
      }
    }
    return relevant.buildOrThrow();
  }

  /** Accumulates certificates from the configured sources, deduplicating as it goes. */
  private static final class Collector {
    // X509Certificate equality is defined by the encoded form, so a LinkedHashSet deduplicates the
    // heavy overlap between the JDK bundle, the OS store and any distribution bundle, while keeping
    // a stable order.
    private final Set<X509Certificate> certificates = new LinkedHashSet<>();
    private final List<Source> sources = new ArrayList<>();
    private final List<String> searchedLocations = new ArrayList<>();
    // SSL_CERT_FILE, CURL_CA_BUNDLE and NIX_SSL_CERT_FILE commonly point at the same bundle, and a
    // distribution may ship one path as a symlink to another. Reading it once keeps the source
    // list, which is what a TLS error reports, from repeating itself.
    private final Set<Path> visitedPaths = new LinkedHashSet<>();

    void addJdkCertificates() throws IOException {
      // Going through the default TrustManagerFactory rather than reading cacerts directly means
      // this also picks up a trust store the user configured with -Djavax.net.ssl.trustStore.
      List<X509Certificate> found = new ArrayList<>();
      try {
        TrustManagerFactory factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager trustManager : factory.getTrustManagers()) {
          if (trustManager instanceof X509TrustManager x509TrustManager) {
            for (X509Certificate certificate : x509TrustManager.getAcceptedIssuers()) {
              found.add(certificate);
            }
          }
        }
      } catch (GeneralSecurityException e) {
        throw new IOException("Failed to read the JVM's default trust store", e);
      }
      record("the JVM's own trust store", found);
    }

    void addSystemCertificates(OS os, Map<String, String> clientEnv) {
      switch (os) {
        case WINDOWS -> {
          // Windows-ROOT is the CurrentUser view, which is a collection that normally already
          // includes the machine-wide store; enumerate the machine store as well so that a CA
          // deployed by policy is picked up even if the collection view does not cover it.
          addOptionalKeyStore("Windows-ROOT");
          addOptionalKeyStore("Windows-ROOT-LOCALMACHINE");
        }
        case DARWIN -> {
          // KeychainStore-ROOT holds Apple's own roots and needs JDK 23 or later. KeychainStore
          // holds the user, admin and MDM-installed certificates, which is where a corporate CA
          // lives, so both are required.
          addOptionalKeyStore("KeychainStore-ROOT");
          addOptionalKeyStore("KeychainStore");
        }
        default -> {}
      }

      // A bundle named by the environment replaces the distribution defaults, the way it does for
      // curl and OpenSSL, but is additive with respect to an OS keystore. It is best-effort rather
      // than required: a stale SSL_CERT_FILE left over in someone's shell must not start failing
      // every download. If it was the only source, build() reports that nothing was found and
      // lists everywhere it looked.
      boolean namedByEnv = false;
      for (String envVar : CERTIFICATE_FILE_ENV_VARS) {
        String value = clientEnv.get(envVar);
        if (!isNullOrEmpty(value)) {
          namedByEnv = true;
          addOptionalFile(Path.of(value));
        }
      }
      String certificateDirs = clientEnv.get(CERTIFICATE_DIR_ENV_VAR);
      if (!isNullOrEmpty(certificateDirs)) {
        namedByEnv = true;
        for (String dir : certificateDirs.split(File.pathSeparator, -1)) {
          if (!dir.isEmpty()) {
            addDirectory(Path.of(dir));
          }
        }
      }
      if (namedByEnv) {
        return;
      }

      boolean foundBundle = false;
      for (String file : DEFAULT_CERTIFICATE_FILES) {
        foundBundle |= addOptionalFile(Path.of(file));
      }
      if (!foundBundle) {
        for (String dir : DEFAULT_CERTIFICATE_DIRS) {
          addDirectory(Path.of(dir));
        }
      }
    }

    /** Adds an OS-backed keystore, ignoring it if this JVM or platform does not provide it. */
    private void addOptionalKeyStore(String type) {
      List<X509Certificate> found;
      try {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(null, null);
        found = trustedCertificatesOf(keyStore);
      } catch (GeneralSecurityException | IOException | RuntimeException e) {
        // An OS keystore that this JVM does not know about (KeychainStore-ROOT before JDK 23) or
        // that the platform refuses to open is not an error: the other sources still apply.
        searchedLocations.add(type + " (unavailable: " + e.getMessage() + ")");
        return;
      }
      record(type, found);
    }

    /** Adds a file the user named explicitly; failing to read it is an error. */
    void addRequiredFile(Path path) throws IOException {
      if (!addFile(path)) {
        throw new IOException(
            String.format(
                "Failed to read CA certificates from %s: expected a PEM or DER encoded certificate"
                    + " bundle, or a Java KeyStore",
                path));
      }
    }

    /** Adds a file if it exists and holds certificates. Returns whether it contributed any. */
    private boolean addOptionalFile(Path path) {
      try {
        return addFile(path);
      } catch (IOException e) {
        searchedLocations.add(path + " (unreadable: " + e.getMessage() + ")");
        return false;
      }
    }

    private boolean addFile(Path path) throws IOException {
      if (!Files.isReadable(path) || Files.isDirectory(path)) {
        searchedLocations.add(path.toString());
        return false;
      }
      if (!markVisited(path)) {
        return true;
      }
      byte[] contents = Files.readAllBytes(path);
      List<X509Certificate> found = parseCertificates(contents);
      if (found.isEmpty()) {
        found = parseKeyStore(contents);
      }
      if (found.isEmpty()) {
        searchedLocations.add(path + " (no certificates found)");
        return false;
      }
      record(path.toString(), found);
      return true;
    }

    private void addDirectory(Path dir) {
      if (!Files.isDirectory(dir)) {
        searchedLocations.add(dir.toString());
        return;
      }
      if (!markVisited(dir)) {
        return;
      }
      List<X509Certificate> found = new ArrayList<>();
      try (Stream<Path> entries = Files.list(dir)) {
        for (Path entry : entries.sorted().collect(Collectors.toList())) {
          try {
            if (!Files.isRegularFile(entry) || Files.size(entry) > MAX_SCANNED_FILE_SIZE) {
              continue;
            }
            // A hashed-symlink directory is full of files that are not certificates; anything that
            // does not parse is simply skipped.
            found.addAll(parseCertificates(Files.readAllBytes(entry)));
          } catch (IOException e) {
            // Skip an individual unreadable entry.
          }
        }
      } catch (IOException e) {
        searchedLocations.add(dir + " (unreadable: " + e.getMessage() + ")");
        return;
      }
      record(dir.toString(), found);
    }

    /** Returns whether this path has not been read yet, remembering it if so. */
    private boolean markVisited(Path path) {
      Path canonical;
      try {
        canonical = path.toRealPath();
      } catch (IOException e) {
        canonical = path.toAbsolutePath().normalize();
      }
      return visitedPaths.add(canonical);
    }

    private void record(String name, List<X509Certificate> found) {
      searchedLocations.add(name);
      if (found.isEmpty()) {
        return;
      }
      certificates.addAll(found);
      sources.add(new Source(name, found.size()));
    }

    TrustStore build() throws IOException {
      if (certificates.isEmpty()) {
        throw new IOException(
            String.format(
                "Found no CA certificates to trust. Locations searched: %s",
                String.join(", ", searchedLocations)));
      }
      try {
        KeyStore merged = KeyStore.getInstance(KeyStore.getDefaultType());
        merged.load(null, null);
        int index = 0;
        for (X509Certificate certificate : certificates) {
          // Synthetic aliases: using the subject DN would silently drop certificates whenever two
          // sources disagree on a name, which cross-signed roots routinely do.
          merged.setCertificateEntry("bazel-ca-" + index++, certificate);
        }
        TrustManagerFactory factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(merged);
        TrustManager[] trustManagers = RecordingTrustManager.wrap(factory.getTrustManagers());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, null);
        X509ExtendedTrustManager x509TrustManager = null;
        for (TrustManager trustManager : trustManagers) {
          if (trustManager instanceof X509ExtendedTrustManager extendedTrustManager) {
            x509TrustManager = extendedTrustManager;
            break;
          }
        }
        return new TrustStore(
            context.getSocketFactory(),
            x509TrustManager,
            ImmutableList.copyOf(sources),
            certificates.size());
      } catch (GeneralSecurityException e) {
        throw new IOException("Failed to build the merged trust store", e);
      }
    }
  }

  /**
   * Delegates to the real trust manager and remembers the chain of any server it rejects, so that
   * the download error can name the certificate authority that would have to be trusted.
   *
   * <p>This extends {@link X509ExtendedTrustManager} and forwards the {@link Socket} and {@link
   * SSLEngine} overloads unchanged. A plain {@link X509TrustManager} would be wrapped by JSSE
   * instead, which changes how endpoint identification is applied; delegating the extended methods
   * keeps hostname verification exactly as the JVM would have done it.
   */
  private static final class RecordingTrustManager extends X509ExtendedTrustManager {
    private final X509ExtendedTrustManager delegate;

    private RecordingTrustManager(X509ExtendedTrustManager delegate) {
      this.delegate = delegate;
    }

    static TrustManager[] wrap(TrustManager[] trustManagers) {
      TrustManager[] wrapped = new TrustManager[trustManagers.length];
      for (int i = 0; i < trustManagers.length; i++) {
        wrapped[i] =
            trustManagers[i] instanceof X509ExtendedTrustManager extendedTrustManager
                ? new RecordingTrustManager(extendedTrustManager)
                : trustManagers[i];
      }
      return wrapped;
    }

    @FunctionalInterface
    private interface Check {
      void run() throws CertificateException;
    }

    private static void recording(X509Certificate[] chain, Check check)
        throws CertificateException {
      try {
        check.run();
      } catch (CertificateException e) {
        rejectedChain.set(chain);
        throw e;
      }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      recording(chain, () -> delegate.checkServerTrusted(chain, authType));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      recording(chain, () -> delegate.checkServerTrusted(chain, authType, socket));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      recording(chain, () -> delegate.checkServerTrusted(chain, authType, engine));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
      delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }

  /** Mirrors what JSSE itself considers a trust anchor in a keystore. */
  private static List<X509Certificate> trustedCertificatesOf(KeyStore keyStore)
      throws GeneralSecurityException {
    List<X509Certificate> certificates = new ArrayList<>();
    for (Enumeration<String> aliases = keyStore.aliases(); aliases.hasMoreElements(); ) {
      String alias = aliases.nextElement();
      // Only trusted certificate entries: the private-key entries of a keychain are the user's own
      // client identities, which have no business acting as trust anchors.
      if (!keyStore.isCertificateEntry(alias)) {
        continue;
      }
      Certificate certificate = keyStore.getCertificate(alias);
      if (certificate instanceof X509Certificate x509Certificate) {
        certificates.add(x509Certificate);
      }
    }
    return certificates;
  }

  /** Parses concatenated PEM or a single DER certificate, returning empty if it is neither. */
  private static List<X509Certificate> parseCertificates(byte[] contents) {
    if (contents.length == 0) {
      return ImmutableList.of();
    }
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      Collection<? extends Certificate> parsed =
          factory.generateCertificates(new ByteArrayInputStream(contents));
      List<X509Certificate> certificates = new ArrayList<>(parsed.size());
      for (Certificate certificate : parsed) {
        if (certificate instanceof X509Certificate x509Certificate) {
          certificates.add(x509Certificate);
        }
      }
      return certificates;
    } catch (GeneralSecurityException e) {
      return ImmutableList.of();
    }
  }

  /** Parses a Java KeyStore, returning empty if the contents are not one. */
  private static List<X509Certificate> parseKeyStore(byte[] contents) {
    for (String type : ImmutableList.of("PKCS12", "JKS")) {
      // A null password skips the integrity check, which is all that reading a JKS needs. A PKCS12
      // additionally encrypts its certificates, so the conventional trust store password is worth
      // trying as well: it is what keytool and ca-certificates-java use for /etc/ssl/certs/java.
      for (char[] password : new char[][] {null, "changeit".toCharArray()}) {
        try {
          KeyStore keyStore = KeyStore.getInstance(type);
          keyStore.load(new ByteArrayInputStream(contents), password);
          List<X509Certificate> certificates = trustedCertificatesOf(keyStore);
          if (!certificates.isEmpty()) {
            return certificates;
          }
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
          // Not this type or not this password; try the next combination.
        }
      }
    }
    return ImmutableList.of();
  }

  private static boolean isNullOrEmpty(@Nullable String value) {
    return value == null || value.isEmpty();
  }
}

package bench;

/** Model of today's PathFragment: sealed abstract identity class with one String field. */
public final class Paths {
  public abstract static sealed class IPathFragment implements Comparable<IPathFragment>
      permits IRel, IAbs {
    private final String normalizedPath;

    IPathFragment(String p) {
      this.normalizedPath = p;
    }

    public static IPathFragment create(String p) {
      return p.startsWith("/") ? new IAbs(p) : new IRel(p);
    }

    public String getPathString() {
      return normalizedPath;
    }

    public abstract int getDriveStrLength();

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      return o instanceof IPathFragment other && normalizedPath.equals(other.normalizedPath);
    }

    @Override
    public int hashCode() {
      return normalizedPath.hashCode();
    }

    @Override
    public int compareTo(IPathFragment o) {
      return normalizedPath.compareTo(o.normalizedPath);
    }
  }

  static final class IRel extends IPathFragment {
    IRel(String p) {
      super(p);
    }

    @Override
    public int getDriveStrLength() {
      return 0;
    }
  }

  static final class IAbs extends IPathFragment {
    IAbs(String p) {
      super(p);
    }

    @Override
    public int getDriveStrLength() {
      return 1;
    }
  }

  /** Stand-in for Artifact.DerivedArtifact (root, execPath, owner, hashCode cache). */
  public static final class IArtifact {
    final Object root;
    final IPathFragment execPath;
    final Object owner;

    public IArtifact(Object root, IPathFragment execPath, Object owner) {
      this.root = root;
      this.execPath = execPath;
      this.owner = owner;
    }

    public IPathFragment getExecPath() {
      return execPath;
    }
  }

  static String path(java.util.Random r, int i) {
    return "bazel-out/k8-fastbuild/bin/src/main/java/com/google/devtools/build/lib/pkg"
        + r.nextInt(500)
        + "/File"
        + i
        + ".java";
  }
}

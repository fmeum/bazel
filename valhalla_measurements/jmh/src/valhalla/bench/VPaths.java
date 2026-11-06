package bench;

import jdk.internal.vm.annotation.NullRestricted;

/** Valhalla variant of PathFragment: a single final value class (no subclasses). */
public final class VPaths {
  public static value class VPathFragment implements Comparable<VPathFragment> {
    private final String normalizedPath;

    VPathFragment(String p) {
      this.normalizedPath = p;
    }

    public static VPathFragment create(String p) {
      return new VPathFragment(p);
    }

    public String getPathString() {
      return normalizedPath;
    }

    public int getDriveStrLength() {
      return normalizedPath.startsWith("/") ? 1 : 0;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof VPathFragment other && normalizedPath.equals(other.normalizedPath);
    }

    @Override
    public int hashCode() {
      return normalizedPath.hashCode();
    }

    @Override
    public int compareTo(VPathFragment o) {
      return normalizedPath.compareTo(o.normalizedPath);
    }
  }

  public static final class VArtifact {
    final Object root;
    @NullRestricted final VPathFragment execPath;
    final Object owner;

    public VArtifact(Object root, VPathFragment execPath, Object owner) {
      this.root = root;
      this.execPath = execPath;
      this.owner = owner;
      super();
    }

    public VPathFragment getExecPath() {
      return execPath;
    }
  }
}

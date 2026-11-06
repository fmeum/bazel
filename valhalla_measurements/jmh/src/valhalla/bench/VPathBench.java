package bench;

import bench.VPaths.VArtifact;
import bench.VPaths.VPathFragment;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(VPathBench.N)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+UseG1GC"})
public class VPathBench {
  public static final int N = 1 << 20;
  public static final int SORT_N = 1 << 16;

  VArtifact[] arts;
  VArtifact[] sortSrc, sortDst;
  HashMap<VPathFragment, Integer> map;

  @Setup
  public void setup() {
    Random r = new Random(42);
    arts = new VArtifact[N];
    map = new HashMap<>();
    Object root = new Object();
    for (int i = 0; i < N; i++) {
      String p = Paths.path(r, i);
      arts[i] = new VArtifact(root, VPathFragment.create(p), Integer.valueOf(i));
      // Separate key instance sharing the String, like a SkyKey built from another source.
      map.put(VPathFragment.create(p), i);
    }
    Random s = new Random(7);
    for (int i = N - 1; i > 0; i--) {
      int j = s.nextInt(i + 1);
      VArtifact t = arts[i];
      arts[i] = arts[j];
      arts[j] = t;
    }
    sortSrc = Arrays.copyOf(arts, SORT_N);
    sortDst = new VArtifact[SORT_N];
  }

  @Benchmark
  public long execPathLength() {
    long s = 0;
    for (VArtifact a : arts) s += a.getExecPath().getPathString().length();
    return s;
  }

  @Benchmark
  public long execPathHash() {
    long s = 0;
    for (VArtifact a : arts) s += a.getExecPath().hashCode();
    return s;
  }

  @Benchmark
  public long mapLookup() {
    long s = 0;
    for (VArtifact a : arts) s += map.get(a.getExecPath());
    return s;
  }

  static final Comparator<VArtifact> BY_EXEC_PATH = Comparator.comparing(VArtifact::getExecPath);

  @Benchmark
  @OperationsPerInvocation(SORT_N)
  public VArtifact[] sortByExecPath() {
    System.arraycopy(sortSrc, 0, sortDst, 0, SORT_N);
    Arrays.sort(sortDst, BY_EXEC_PATH);
    return sortDst;
  }
}

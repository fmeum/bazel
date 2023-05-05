package com.google.devtools.build.lib.bazel.repository.starlark;

import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import net.starlark.java.eval.EvalException;

public interface EnvironmentClosure {

  @FunctionalInterface
  interface WithEnv<T> {

    T apply(Environment env) throws EvalException, InterruptedException;
  }

  <T> T with(WithEnv<T> withEnv) throws EvalException, InterruptedException;

  ExtendedEventHandler getListener();

  static EnvironmentClosure synchronous(Environment env) {
    return new SynchronousEnvironmentClosure(env);
  }
}

class SynchronousEnvironmentClosure implements EnvironmentClosure {

  private final Environment env;

  public SynchronousEnvironmentClosure(Environment env) {
    this.env = env;
  }

  @Override
  public <T> T with(WithEnv<T> withEnv) throws EvalException, InterruptedException {
    T result = withEnv.apply(env);
    if (env.valuesMissing()) {
      throw new NeedsSkyframeRestartException();
    }
    return result;
  }

  @Override
  public ExtendedEventHandler getListener() {
    return env.getListener();
  }
}

class AsynchronousEnvironmentClosure implements EnvironmentClosure {
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition newEnvNeeded = lock.newCondition();
  private final Condition newEnvAvailable = lock.newCondition();

  private volatile Environment env;

  @Override
  public <T> T with(WithEnv<T> withEnv) throws EvalException, InterruptedException {
    while (true) {
      lock.lock();
      try {
        T result = withEnv.apply(env);
        if (!env.valuesMissing()) {
          return result;
        }
        newEnvNeeded.signal();
        newEnvAvailable.await();
      } catch (NeedsSkyframeRestartException ignored) {
      } finally {
        lock.unlock();
      }
    }
  }

  @Override
  public ExtendedEventHandler getListener() {
    return env.getListener();
  }

  public void updateEnv(Environment newEnv) {
    lock.lock();
    try {
      this.env = newEnv;
      newEnvAvailable.signal();
    } finally {
      lock.unlock();
    }
  }
}

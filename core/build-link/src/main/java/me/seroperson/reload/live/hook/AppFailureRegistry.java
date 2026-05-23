package me.seroperson.reload.live.hook;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.seroperson.reload.live.UnrecoverableException;

/** Records failures from the application {@code main} thread for startup hooks to observe. */
public final class AppFailureRegistry {

  private static final ConcurrentMap<Thread, Throwable> failures = new ConcurrentHashMap<>();

  private AppFailureRegistry() {}

  public static void record(Thread appThread, Throwable failure) {
    if (failure != null) {
      failures.put(appThread, failure);
    }
  }

  public static void clear(Thread appThread) {
    failures.remove(appThread);
  }

  /** Throws when {@code main} failed; does not use {@link Thread#isAlive()}. */
  public static void throwIfFailed(Thread appThread) {
    var failure = failures.get(appThread);
    if (failure != null) {
      throw new UnrecoverableException(
          "Application main thread failed before the health check became ready", failure);
    }
  }
}

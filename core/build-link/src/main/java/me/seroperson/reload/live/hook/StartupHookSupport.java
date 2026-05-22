package me.seroperson.reload.live.hook;

import me.seroperson.reload.live.UnrecoverableException;

/** Shared helpers for startup hooks that wait on application readiness. */
public final class StartupHookSupport {

  private StartupHookSupport() {}

  /** Fails fast when the application {@code main()} thread exits before startup hooks finish. */
  public static void ensureAppThreadAlive(Thread appThread) {
    if (!appThread.isAlive()) {
      throw new UnrecoverableException("Application exited before the health check became ready");
    }
  }
}

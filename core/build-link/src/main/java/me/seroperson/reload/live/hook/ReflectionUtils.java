package me.seroperson.reload.live.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.seroperson.reload.live.build.BuildLogger;

/**
 * Utility object for reflection-based operations in hook implementations.
 *
 * <p>This object provides methods for checking class availability and managing application shutdown
 * hooks via reflection, which is useful for live reload scenarios where normal shutdown procedures
 * need to be controlled.
 */
public final class ReflectionUtils {

  private static final Class<?> applicationShutdownHooks;
  private static final Field hooksField;
  private static final Method runHooksMethod;

  static {
    try {
      applicationShutdownHooks = Class.forName("java.lang.ApplicationShutdownHooks");

      hooksField = applicationShutdownHooks.getDeclaredField("hooks");
      hooksField.setAccessible(true);

      runHooksMethod = applicationShutdownHooks.getDeclaredMethod("runHooks");
      runHooksMethod.setAccessible(true);
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Checks if a class with the given name is available on the classpath.
   *
   * @param className the fully qualified class name to check
   * @return true if the class is available, false otherwise
   */
  public static boolean hasClass(String className) {
    try {
      Class.forName(className);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * Runs all registered application shutdown hooks via reflection.
   *
   * <p>This method uses reflection to access the internal ApplicationShutdownHooks class and run
   * all registered shutdown hooks. After running the hooks, it resets the hooks field to prevent
   * the application from thinking it's permanently in shutdown state, which is essential for live
   * reload functionality.
   *
   * <p>This method uses internal JVM APIs and may not work on all JVM implementations.
   */
  public static void runApplicationShutdownHooks(BuildLogger logger) {
    try {
      logger.debug("Running shutdown hooks");
      runHooksMethod.invoke(null);
      logger.debug("java.lang.ApplicationShutdownHooks.runHooks was invoked successfully");
    } catch (Exception e) {
      logger.error("Failed to run shutdown hooks via reflection", e);
    }
  }

  public static void unregisterShutdownHooks(Set<Long> threadIds) {
    var newMap =
        getRegistredShutdownHooks().entrySet().stream()
            .filter(entry -> !threadIds.contains(entry.getKey().getId()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    setShutdownHooks(new IdentityHashMap<>(newMap));
  }

  public static void setShutdownHooks(Map<Thread, Thread> hooks) {
    try {
      hooksField.set(null, hooks);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public static Map<Thread, Thread> getRegistredShutdownHooks() {
    try {
      return (Map<Thread, Thread>) hooksField.get(null);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}

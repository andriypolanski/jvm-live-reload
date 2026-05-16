package me.seroperson.reload.live.hook;

import me.seroperson.reload.live.UnrecoverableException;
import me.seroperson.reload.live.build.BuildLogger;
import me.seroperson.reload.live.settings.DevServerSettings;

/**
 * Shutdown hook that waits for a GRPC health check to fail.
 *
 * <p>This hook combines GRPC health checking with shutdown waiting logic. It polls the GRPC server
 * until it stops responding, indicating the server has shut down successfully.
 */
public class GrpcHealthCheckShutdownHook implements GrpcHealthCheckHook {

  @Override
  public String description() {
    return "Waits for GRPC health-check to return false";
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public void hook(Thread th, ClassLoader cl, DevServerSettings settings, BuildLogger logger) {
    try {
      while (true) {
        logger.debug("Waiting for the GRPC health-check to return failure ...");
        var service = settings.getGrpcHealthService();
        var healthResponse =
            isHealthy(logger, service, settings.getGrpcHost(), settings.getGrpcPort());
        if (healthResponse == 1) {
          // success - server still running
          Thread.sleep(50L);
        } else if (healthResponse == 0) {
          // non-success response, but not an exception
          Thread.sleep(50L);
        } else if (healthResponse == -1) {
          // connection exception, that's what we're looking for
          return;
        } else if (healthResponse == 404) {
          // if health check isn't implemented, don't poll it
          throw new UnrecoverableException(
              "GRPC health-check service " + service + " is not available. Is it implemented?");
        }
      }
    } catch (InterruptedException e) {
      // Don't print anything, just quit
    }
  }
}

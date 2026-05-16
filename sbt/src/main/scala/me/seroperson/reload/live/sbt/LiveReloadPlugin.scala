package me.seroperson.reload.live.sbt

import play.dev.filewatch.FileWatchService
import play.dev.filewatch.LoggerProxy
import sbt.*
import sbt.internal.inc.Analysis
import sbt.plugins.JvmPlugin

/** SBT plugin that provides live reload functionality for JVM applications.
  *
  * This plugin enables automatic recompilation and application restart when
  * source code changes are detected. It works by:
  *
  *   1. Setting up a proxy server that forwards requests to the application
  *   2. Monitoring source files for changes
  *   3. Recompiling and reloading the application when changes are detected
  *   4. Managing application lifecycle through configurable hooks
  *
  * The plugin supports various frameworks through specific hooks (Cats Effect,
  * ZIO, Cask) and provides both blocking and non-blocking interaction modes.
  */
object LiveReloadPlugin extends AutoPlugin {

  val autoImport = LiveKeys

  import autoImport.*
  import sbt.Keys.*

  override def trigger = noTrigger

  override def requires = JvmPlugin

  override lazy val globalSettings = Seq()

  override lazy val projectSettings = Seq(
    liveServerType := HttpServerType,
    libraryDependencies ++= {
      val webserverDep = liveServerType.value match {
        case HttpServerType => "me.seroperson" % "jvm-live-reload-webserver" % BuildInfo.version
        case GrpcServerType => "me.seroperson" % "jvm-live-reload-webserver-grpc" % BuildInfo.version
      }
      Seq(
        webserverDep,
        "me.seroperson" %% "jvm-live-reload-hook-scala" % BuildInfo.version
      )
    },
    liveFileWatchService := FileWatchService.detect(
      pollInterval.value.toMillis.toInt,
      null.asInstanceOf[LoggerProxy]
    ),
    liveDevSettings := Nil,
    liveMonitoredFiles := Commands.liveMonitoredFilesTask.value,
    // all dependencies from outside the project (all dependency jars)
    liveDependencyClasspath := SbtCompat.uncached(
      (Runtime / externalDependencyClasspath).value
    ),
    // all user classes in this project and any other subprojects that it depends on
    liveReloaderClasspath := SbtCompat.uncached(
      SbtCompat.reloaderClasspathTask.value
    ),
    liveReload := SbtCompat.uncached(Commands.liveReloadTask.value),
    liveCompileEverything := SbtCompat.uncached(
      Commands.liveCompileEverythingTask.value
        .asInstanceOf[Seq[Analysis]]
    ),
    liveHookBundle := SbtCompat.uncached(
      liveServerType.value match {
        case GrpcServerType => Some(GrpcAppHookBundle)
        case HttpServerType =>
          (Compile / dependencyClasspath).value.collectFirst {
            case lib if SbtCompat.fileName(lib.data).startsWith("zio-http") =>
              ZioAppHookBundle
            case lib if SbtCompat.fileName(lib.data).startsWith("http4s") =>
              IoAppHookBundle
            case lib if SbtCompat.fileName(lib.data).startsWith("cask") =>
              CaskAppHookBundle
          }
      }
    ),
    liveStartupHooks := SbtCompat.uncached(liveHookBundle.value match {
      case Some(hookBundle) => hookBundle.startupHooks
      case None             => Seq(HookClassnames.RestApiHealthCheckStartup)
    }),
    liveShutdownHooks := SbtCompat.uncached(liveHookBundle.value match {
      case Some(hookBundle) => hookBundle.shutdownHooks
      case None             =>
        Seq(
          HookClassnames.ThreadInterruptShutdown,
          HookClassnames.RestApiHealthCheckShutdown
        )
    }),
    livePropagateEnv := SbtCompat.uncached(Map.empty),
    Compile / bgRun := Commands.liveBgRunTask.evaluated,
    Compile / run := Commands.liveDefaultRunTask.map(_ => ()).evaluated,
    Compile / run / mainClass := SbtCompat.uncached(Some(liveServerType.value match {
      case HttpServerType => "me.seroperson.reload.live.webserver.DevServerStart"
      case GrpcServerType => "me.seroperson.reload.live.webserver.grpc.GrpcDevServerStart"
    }))
  )
}

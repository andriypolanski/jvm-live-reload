package me.seroperson.reload.live.gradle

import me.seroperson.reload.live.build.ReloadableServer
import me.seroperson.reload.live.runner.CompileResult
import me.seroperson.reload.live.runner.DevServerRunner
import me.seroperson.reload.live.runner.StartParams
import me.seroperson.reload.live.settings.DevServerSettings
import org.gradle.deployment.internal.Deployment
import org.gradle.deployment.internal.DeploymentHandle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject

open class LiveReloadRunHandle
    @Inject
    constructor(
        private val params: LiveReloadRunParams,
    ) : DeploymentHandle {
        private var deployment: Deployment? = null
        private var devServer: ReloadableServer? = null
        private val lock: ReadWriteLock = ReentrantReadWriteLock()

        private val isChanged: Boolean
            get() {
                lock.readLock().lock()
                try {
                    return deployment!!.status().hasChanged()
                } finally {
                    lock.readLock().unlock()
                }
            }

        override fun isRunning(): Boolean {
            lock.readLock().lock()
            try {
                return devServer?.isRunning() == true
            } finally {
                lock.readLock().unlock()
            }
        }

        /** Starts the dev server again after [stop] when Gradle re-runs the task. */
        fun restart() {
            lock.writeLock().lock()
            try {
                val deployment =
                    deployment
                        ?: throw IllegalStateException(
                            "Cannot restart live-reload: deployment handle is not available",
                        )
                if (devServer?.isRunning() == true) {
                    return
                }
                startDevServer(deployment)
            } finally {
                lock.writeLock().unlock()
            }
        }

        private fun reloadCompile(): CompileResult {
            lock.readLock().lock()
            try {
                val failure = deployment!!.status().failure
                if (failure != null) {
                    return CompileResult.CompileFailure(
                        RuntimeException("Gradle Build Failure " + failure.message, failure),
                    )
                }
                return CompileResult.CompileSuccess(params.applicationClasspath.toList())
            } catch (e: Exception) {
                logger.error(e.message, e)
                return CompileResult.CompileFailure(RuntimeException("Gradle Build Failure " + e.message, e))
            } finally {
                lock.readLock().unlock()
            }
        }

        override fun start(deployment: Deployment) {
            lock.writeLock().lock()
            this.deployment = deployment
            try {
                startDevServer(deployment)
            } finally {
                lock.writeLock().unlock()
            }
        }

        private fun startDevServer(deployment: Deployment) {
            val serverClass =
                when (params.serverType) {
                    ServerType.HTTP -> "me.seroperson.reload.live.webserver.DevServerStart"
                    ServerType.GRPC -> "me.seroperson.reload.live.webserver.grpc.GrpcDevServerStart"
                }
            val startParams =
                StartParams(
                    // todo: deal with args, properties and java options
                    DevServerSettings(listOf(), listOf(), params.settings),
                    params.dependencyClasspath.toList(),
                    // monitoredFiles
                    listOf(),
                    serverClass,
                    params.mainClass,
                    params.startupHooks,
                    params.shutdownHooks,
                    params.propagateEnv,
                )

            val buildLogger = LiveReloadLogger()
            val devServerRunner = DevServerRunner.getInstance()
            devServer =
                devServerRunner.runBackground(
                    startParams,
                    this::reloadCompile,
                    this::isChanged,
                    // fileWatcherService
                    null,
                    buildLogger,
                )
            // Visible only when running with `--info`
            DevServerRunner.printBanner(devServer, buildLogger)
        }

        override fun stop() {
            lock.writeLock().lock()
            logger.info("Application is stopping ...")
            try {
                devServer?.close()
                logger.info("Application stopped.")
            } catch (e: Exception) {
                logger.error("Application stopped with exception", e)
            } finally {
                devServer = null
                lock.writeLock().unlock()
            }
        }

        companion object {
            private val logger: Logger = LoggerFactory.getLogger(LiveReloadRunHandle::class.java)
        }
    }

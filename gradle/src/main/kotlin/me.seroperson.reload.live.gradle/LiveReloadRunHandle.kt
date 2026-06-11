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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject

open class LiveReloadRunHandle
    @Inject
    constructor(
        params: LiveReloadRunParams,
    ) : DeploymentHandle {
        private var params: LiveReloadRunParams = params
        private var deployment: Deployment? = null
        private var devServer: ReloadableServer? = null
        private val lock: ReadWriteLock = ReentrantReadWriteLock()
        private val pendingReload = AtomicBoolean(false)

        /**
         * Arms a reload for the next request. Called from the task action when its `classes` input
         * changed. Unlike `Deployment.status().hasChanged()`, which is a one-shot flag that a request can
         * consume before the rebuild has written new class files to disk, this flag is set after
         * compilation has finished, so the reload cannot be lost.
         */
        fun markChanged() {
            pendingReload.set(true)
        }

        private val isChanged: Boolean
            get() {
                lock.readLock().lock()
                try {
                    // status() first - it blocks requests while a rebuild is in progress
                    val gradleChanged = deployment!!.status().hasChanged()
                    return pendingReload.getAndSet(false) || gradleChanged
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

        /** Replaces run configuration from the latest [LiveReloadRun] task execution. */
        fun updateParams(params: LiveReloadRunParams) {
            lock.writeLock().lock()
            try {
                this.params = params
            } finally {
                lock.writeLock().unlock()
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
                closeDevServer()
                try {
                    startDevServer(deployment)
                } catch (e: Exception) {
                    logger.error("Failed to restart live-reload dev server", e)
                    throw e
                }
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

        private fun closeDevServer() {
            val server = devServer
            devServer = null
            if (server != null) {
                try {
                    server.close()
                } catch (e: Exception) {
                    logger.error("Error closing live-reload dev server", e)
                }
            }
        }

        private fun startDevServer(deployment: Deployment) {
            closeDevServer()
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
                closeDevServer()
                logger.info("Application stopped.")
            } finally {
                lock.writeLock().unlock()
            }
        }

        companion object {
            private val logger: Logger = LoggerFactory.getLogger(LiveReloadRunHandle::class.java)
        }
    }

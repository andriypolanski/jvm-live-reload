package me.seroperson.reload.live.gradle

import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/** Server type for live reload functionality. */
enum class ServerType {
    /** HTTP/REST server (default) */
    HTTP,

    /** GRPC server */
    GRPC,
}

abstract class LiveReloadExtension(
    project: Project,
) {
    abstract val settings: MapProperty<String, String>
    abstract val startupHooks: ListProperty<String>
    abstract val shutdownHooks: ListProperty<String>
    abstract val propagateEnv: MapProperty<String, String>
    abstract val serverType: Property<ServerType>

    init {
        settings.convention(mapOf())
        serverType.convention(ServerType.HTTP)
        startupHooks.convention(serverType.map { defaultStartupHooksFor(it) })
        shutdownHooks.convention(serverType.map { defaultShutdownHooksFor(it) })
        propagateEnv.convention(mapOf())
    }

    private fun defaultStartupHooksFor(type: ServerType): List<String> =
        when (type) {
            ServerType.HTTP -> listOf("me.seroperson.reload.live.hook.RestApiHealthCheckStartupHook")
            ServerType.GRPC ->
                listOf("me.seroperson.reload.live.webserver.grpc.hook.GrpcHealthCheckStartupHook")
        }

    private fun defaultShutdownHooksFor(type: ServerType): List<String> =
        when (type) {
            ServerType.HTTP ->
                listOf(
                    "me.seroperson.reload.live.hook.ThreadInterruptShutdownHook",
                    "me.seroperson.reload.live.hook.RuntimeShutdownHook",
                    "me.seroperson.reload.live.hook.RestApiHealthCheckShutdownHook",
                )
            ServerType.GRPC ->
                listOf(
                    "me.seroperson.reload.live.hook.ThreadInterruptShutdownHook",
                    "me.seroperson.reload.live.hook.RuntimeShutdownHook",
                    "me.seroperson.reload.live.webserver.grpc.hook.GrpcHealthCheckShutdownHook",
                )
        }
}

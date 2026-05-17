package me.seroperson.reload.live.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LiveReloadGrpcStreamingTest : LiveReloadTestBase() {
    @field:TempDir lateinit var projectDir: File

    private val appCode by lazy {
        val kotlinSources = projectDir.resolve("src/main/kotlin")
        kotlinSources.mkdirs()
        kotlinSources.resolve("App.kt")
    }
    private val buildFile by lazy { projectDir.resolve("build.gradle.kts") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }

    @Test
    fun `reload server-streaming grpc method`() {
        settingsFile.writeText(SETTINGS_CONTENT)
        buildFile.writeText(BUILD_CONTENT)
        appCode.writeText(appCodeWithPrefix("Hi"))

        val runner = initGradleRunner(":liveReloadRun", projectDir)
        val isBuildRunning = AtomicBoolean(true)
        val runThread =
            Thread {
                try {
                    runner.build()
                    isBuildRunning.set(false)
                } catch (_: InterruptedException) {
                    println("Interrupted")
                } catch (ex: Exception) {
                    println("Got exception ${ex.message}")
                }
            }
        runThread.start()

        val initial =
            runServerStreamingUntil(
                isBuildRunning,
                "localhost",
                9000,
                "greeter.Greeter",
                "StreamGreet",
                byteArrayOf(),
                listOf("Hi-1".toByteArray(), "Hi-2".toByteArray(), "Hi-3".toByteArray()),
            )

        appCode.writeText(appCodeWithPrefix("Yo"))

        val reloaded =
            runServerStreamingUntil(
                isBuildRunning,
                "localhost",
                9000,
                "greeter.Greeter",
                "StreamGreet",
                byteArrayOf(),
                listOf("Yo-1".toByteArray(), "Yo-2".toByteArray(), "Yo-3".toByteArray()),
            )

        runThread.interrupt()

        assertTrue(initial && reloaded)
    }

    private fun appCodeWithPrefix(prefix: String): String = APP_CODE_TEMPLATE.replace("__PREFIX__", prefix)

    companion object {
        const val SETTINGS_CONTENT = ""
        const val BUILD_CONTENT =
            """
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    application
    id("me.seroperson.reload.live.gradle")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.72.0")
    implementation("io.grpc:grpc-stub:1.72.0")
    implementation("io.grpc:grpc-protobuf:1.72.0")
    implementation("io.grpc:grpc-services:1.72.0")
}

liveReload {
    serverType = me.seroperson.reload.live.gradle.ServerType.GRPC
    settings = mapOf(
        "live.reload.grpc.port" to "8081",
        "live.reload.proxy.grpc.port" to "9000"
    )
}

application { mainClass = "AppKt" }
"""

        const val APP_CODE_TEMPLATE =
            """
import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.ServerBuilder
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.io.InputStream

fun main() {
    val health = HealthStatusManager()
    val server = ServerBuilder
        .forPort(8081)
        .addService(GreeterService("__PREFIX__"))
        .addService(health.healthService)
        .addService(ProtoReflectionService.newInstance())
        .build()
    try {
        server.start()
        health.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING)
        println("Server started on port 8081")
        Thread.currentThread().join()
    } catch (ex: InterruptedException) {
        health.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING)
        server.shutdown()
    }
}

class GreeterService(private val prefix: String) : BindableService {
    override fun bindService(): ServerServiceDefinition {
        val methodDescriptor = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName("greeter.Greeter/StreamGreet")
            .setRequestMarshaller(ByteArrayMarshaller())
            .setResponseMarshaller(ByteArrayMarshaller())
            .build()

        val handler: ServerCallHandler<ByteArray, ByteArray> = ServerCalls.asyncServerStreamingCall { _, responseObserver ->
            for (i in 1..3) {
                responseObserver.onNext("${'$'}prefix-${'$'}i".toByteArray())
            }
            responseObserver.onCompleted()
        }

        return ServerServiceDefinition.builder("greeter.Greeter")
            .addMethod(methodDescriptor, handler)
            .build()
    }
}

class ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
    override fun parse(stream: InputStream): ByteArray = stream.readBytes()
}
"""
    }
}

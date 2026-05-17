package me.seroperson.reload.live.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LiveReloadGrpcMultiprojectTest : LiveReloadTestBase() {
    @field:TempDir lateinit var projectDir: File

    private val appCode by lazy {
        val kotlinSources = projectDir.resolve("project-a/src/main/kotlin")
        kotlinSources.mkdirs()
        kotlinSources.resolve("App.kt")
    }
    private val textCode by lazy {
        val kotlinSources = projectDir.resolve("project-b/src/main/kotlin")
        kotlinSources.mkdirs()
        kotlinSources.resolve("Greeting.kt")
    }
    private val buildAFile by lazy { projectDir.resolve("project-a/build.gradle.kts") }
    private val buildBFile by lazy { projectDir.resolve("project-b/build.gradle.kts") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }

    @Test
    fun `reload grpc multiproject when sibling module changes`() {
        appCode.writeText(APP_CODE_1)
        textCode.writeText(TEXT_CODE_1)
        settingsFile.writeText(SETTINGS_CONTENT)
        buildAFile.writeText(BUILD_A_CONTENT)
        buildBFile.writeText(BUILD_B_CONTENT)

        val runner = initGradleRunner(":project-a:liveReloadRun", projectDir)
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
            runGrpcUntil(
                isBuildRunning,
                "localhost",
                9000,
                "greeter.Greeter",
                "Greet",
                byteArrayOf(),
                "Multi-Hi".toByteArray(),
            )

        appCode.writeText(APP_CODE_2)
        textCode.writeText(TEXT_CODE_2)

        val reloaded =
            runGrpcUntil(
                isBuildRunning,
                "localhost",
                9000,
                "greeter.Greeter",
                "Greet",
                byteArrayOf(),
                "Multi-Yo!".toByteArray(),
            )

        runThread.interrupt()

        assertTrue(initial && reloaded)
    }

    companion object {
        const val SETTINGS_CONTENT = """include("project-a", "project-b")"""

        const val BUILD_A_CONTENT =
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
    implementation(project(":project-b"))
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

        const val BUILD_B_CONTENT =
            """
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
}

repositories {
    mavenCentral()
}
"""

        val APP_CODE_1 = appCodeWithSuffix("")
        val APP_CODE_2 = appCodeWithSuffix("!")

        private fun appCodeWithSuffix(suffix: String): String =
            """
import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.ServerBuilder
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.io.InputStream

fun main() {
    val health = HealthStatusManager()
    val server = ServerBuilder
        .forPort(8081)
        .addService(MultiGreeter(Greeting.response + "$suffix"))
        .addService(health.healthService)
        .build()
    try {
        server.start()
        health.setStatus("", HealthCheckResponse.ServingStatus.SERVING)
        println("Server started on port 8081")
        Thread.currentThread().join()
    } catch (ex: InterruptedException) {
        health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING)
        server.shutdown()
    }
}

class MultiGreeter(private val response: String) : BindableService {
    override fun bindService(): ServerServiceDefinition {
        val methodDescriptor = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("greeter.Greeter/Greet")
            .setRequestMarshaller(ByteArrayMarshaller())
            .setResponseMarshaller(ByteArrayMarshaller())
            .build()

        val handler: ServerCallHandler<ByteArray, ByteArray> = ServerCalls.asyncUnaryCall { _, responseObserver ->
            responseObserver.onNext(response.toByteArray())
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

        const val TEXT_CODE_1 =
            """
class Greeting {
    companion object {
        const val response = "Multi-Hi"
    }
}
"""

        const val TEXT_CODE_2 =
            """
class Greeting {
    companion object {
        const val response = "Multi-Yo"
    }
}
"""
    }
}

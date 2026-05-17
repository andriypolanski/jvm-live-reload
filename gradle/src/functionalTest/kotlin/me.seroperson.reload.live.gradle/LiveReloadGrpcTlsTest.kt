package me.seroperson.reload.live.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

@Timeout(value = 5, unit = TimeUnit.MINUTES)
class LiveReloadGrpcTlsTest : LiveReloadTestBase() {
    @field:TempDir lateinit var projectDir: File

    private val appCode by lazy {
        val kotlinSources = projectDir.resolve("src/main/kotlin")
        kotlinSources.mkdirs()
        kotlinSources.resolve("App.kt")
    }
    private val buildFile by lazy { projectDir.resolve("build.gradle.kts") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }

    @Test
    fun `reload TLS grpc end to end`() {
        val resourceCert = File("src/functionalTest/resources/grpc-tls/cert.pem").absoluteFile
        val resourceKey = File("src/functionalTest/resources/grpc-tls/key.pem").absoluteFile
        val cert = projectDir.resolve("cert.pem")
        val key = projectDir.resolve("key.pem")
        resourceCert.copyTo(cert, overwrite = true)
        resourceKey.copyTo(key, overwrite = true)

        settingsFile.writeText(SETTINGS_CONTENT)
        buildFile.writeText(buildScript(cert, key))
        appCode.writeText(appCodeWithResponse("Secure-Hi", cert, key))

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
            runGrpcTlsUntil(
                isBuildRunning,
                "localhost",
                9000,
                "greeter.Greeter",
                "Greet",
                byteArrayOf(),
                "Secure-Hi".toByteArray(),
                cert,
            )

        appCode.writeText(appCodeWithResponse("Secure-Yo", cert, key))

        val reloaded =
            runGrpcTlsUntil(
                isBuildRunning,
                "localhost",
                9000,
                "greeter.Greeter",
                "Greet",
                byteArrayOf(),
                "Secure-Yo".toByteArray(),
                cert,
            )

        runThread.interrupt()

        assertTrue(initial && reloaded)
    }

    private fun buildScript(
        cert: File,
        key: File,
    ): String =
        BUILD_TEMPLATE
            .replace("__CERT__", cert.absolutePath.replace("\\", "\\\\"))
            .replace("__KEY__", key.absolutePath.replace("\\", "\\\\"))

    private fun appCodeWithResponse(
        response: String,
        cert: File,
        key: File,
    ): String =
        APP_CODE_TEMPLATE
            .replace("__RESPONSE__", response)
            .replace("__CERT__", cert.absolutePath.replace("\\", "\\\\"))
            .replace("__KEY__", key.absolutePath.replace("\\", "\\\\"))

    companion object {
        const val SETTINGS_CONTENT = ""
        const val BUILD_TEMPLATE =
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
        "live.reload.proxy.grpc.port" to "9000",
        "live.reload.grpc.target.tls" to "true",
        "live.reload.grpc.target.tls.trust" to "__CERT__",
        "live.reload.grpc.proxy.tls.cert" to "__CERT__",
        "live.reload.grpc.proxy.tls.key" to "__KEY__"
    )
}

application { mainClass = "AppKt" }
"""

        const val APP_CODE_TEMPLATE =
            """
import io.grpc.BindableService
import io.grpc.Grpc
import io.grpc.MethodDescriptor
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.TlsServerCredentials
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.ServerCalls
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

fun main() {
    val credentials = TlsServerCredentials.create(File("__CERT__"), File("__KEY__"))
    val health = HealthStatusManager()
    val server = Grpc.newServerBuilderForPort(8081, credentials)
        .addService(GreeterService("__RESPONSE__"))
        .addService(health.healthService)
        .build()
    try {
        server.start()
        health.setStatus("", HealthCheckResponse.ServingStatus.SERVING)
        println("TLS Server started on port 8081")
        Thread.currentThread().join()
    } catch (ex: InterruptedException) {
        health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING)
        server.shutdown()
    }
}

class GreeterService(private val response: String) : BindableService {
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
    }
}

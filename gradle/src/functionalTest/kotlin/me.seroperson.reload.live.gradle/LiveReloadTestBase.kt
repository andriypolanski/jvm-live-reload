package me.seroperson.reload.live.gradle

import io.grpc.CallOptions
import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.TlsChannelCredentials
import io.grpc.stub.ClientCalls
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.testkit.runner.GradleRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class LiveReloadTestBase {
    private val client = OkHttpClient()

    fun initGradleRunner(
        command: String,
        projectDir: File,
        env: Map<String, String> = mapOf(),
    ): GradleRunner {
        val runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withGradleVersion("8.14.3")
        runner.withPluginClasspath()
        runner.withProjectDir(projectDir)
        runner.withEnvironment(
            mapOf("GRADLE_OPTS" to "--add-opens=java.base/java.nio=ALL-UNNAMED") + env,
        )
        runner.withArguments(
            command,
            "--info",
            "--watch-fs",
            "--stacktrace",
            "-Dorg.gradle.vfs.verbose=true",
            "-Dorg.gradle.native=true",
        )
        return runner
    }

    fun runUntil(
        isBuildRunning: AtomicBoolean,
        url: String,
        expectedStatus: Int,
        expectedBody: String,
    ): Boolean {
        if (!isBuildRunning.get()) {
            return false
        }
        val request: Request = Request.Builder().url(url).build()

        try {
            val (code, body) =
                (
                    client.newCall(request).execute().use { response ->
                        response.code to response.body.string()
                    }
                )
            println("Requesting $url, got $code and $body")
            if (expectedStatus == code && expectedBody == body) {
                return true
            } else {
                Thread.sleep(500)
                return runUntil(isBuildRunning, url, expectedStatus, expectedBody)
            }
        } catch (ex: Exception) {
            println("Got exception: ${ex.message}")
            Thread.sleep(500)
            return runUntil(isBuildRunning, url, expectedStatus, expectedBody)
        }
    }

    /**
     * Makes a unary GRPC call using generic byte array marshalling. This allows testing GRPC servers
     * without generated code.
     */
    fun grpcCall(
        host: String,
        port: Int,
        serviceName: String,
        methodName: String,
        request: ByteArray,
    ): ByteArray {
        val channel: ManagedChannel =
            ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
        try {
            val methodDescriptor =
                MethodDescriptor
                    .newBuilder<ByteArray, ByteArray>()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("$serviceName/$methodName")
                    .setRequestMarshaller(ByteArrayMarshaller())
                    .setResponseMarshaller(ByteArrayMarshaller())
                    .build()

            return ClientCalls.blockingUnaryCall(channel, methodDescriptor, CallOptions.DEFAULT, request)
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /** Runs GRPC call until expected response is received. */
    fun runGrpcUntil(
        isBuildRunning: AtomicBoolean,
        host: String,
        port: Int,
        serviceName: String,
        methodName: String,
        request: ByteArray,
        expectedResponse: ByteArray,
    ): Boolean {
        if (!isBuildRunning.get()) {
            return false
        }
        try {
            val response = grpcCall(host, port, serviceName, methodName, request)
            println("GRPC call to $serviceName/$methodName, got ${response.contentToString()}")
            if (response.contentEquals(expectedResponse)) {
                return true
            } else {
                Thread.sleep(500)
                return runGrpcUntil(
                    isBuildRunning,
                    host,
                    port,
                    serviceName,
                    methodName,
                    request,
                    expectedResponse,
                )
            }
        } catch (ex: Exception) {
            println("GRPC exception: ${ex.message}")
            Thread.sleep(500)
            return runGrpcUntil(
                isBuildRunning,
                host,
                port,
                serviceName,
                methodName,
                request,
                expectedResponse,
            )
        }
    }

    /** Makes a server-streaming GRPC call, collecting all response messages until the stream ends. */
    fun serverStreamingCall(
        host: String,
        port: Int,
        serviceName: String,
        methodName: String,
        request: ByteArray,
    ): List<ByteArray> {
        val channel: ManagedChannel =
            ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
        try {
            val methodDescriptor =
                MethodDescriptor
                    .newBuilder<ByteArray, ByteArray>()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName("$serviceName/$methodName")
                    .setRequestMarshaller(ByteArrayMarshaller())
                    .setResponseMarshaller(ByteArrayMarshaller())
                    .build()

            val iterator =
                ClientCalls.blockingServerStreamingCall(channel, methodDescriptor, CallOptions.DEFAULT, request)
            val collected = mutableListOf<ByteArray>()
            iterator.forEachRemaining { collected.add(it) }
            return collected
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /** Runs a server-streaming GRPC call until the collected sequence equals the expected one. */
    fun runServerStreamingUntil(
        isBuildRunning: AtomicBoolean,
        host: String,
        port: Int,
        serviceName: String,
        methodName: String,
        request: ByteArray,
        expected: List<ByteArray>,
    ): Boolean {
        if (!isBuildRunning.get()) {
            return false
        }
        try {
            val collected = serverStreamingCall(host, port, serviceName, methodName, request)
            val match =
                collected.size == expected.size &&
                    collected.zip(expected).all { (a, b) -> a.contentEquals(b) }
            if (match) {
                return true
            }
            println(
                "Streaming response did not match yet, got ${collected.map { String(it) }}, " +
                    "expected ${expected.map { String(it) }}",
            )
        } catch (ex: Exception) {
            println("GRPC streaming exception: ${ex.message}")
        }
        Thread.sleep(500)
        return runServerStreamingUntil(
            isBuildRunning,
            host,
            port,
            serviceName,
            methodName,
            request,
            expected,
        )
    }

    /** Makes a unary gRPC call over TLS, trusting the provided cert file. */
    fun grpcTlsCall(
        host: String,
        port: Int,
        serviceName: String,
        methodName: String,
        request: ByteArray,
        trust: File,
    ): ByteArray {
        val credentials = TlsChannelCredentials.newBuilder().trustManager(trust).build()
        val channel: ManagedChannel = Grpc.newChannelBuilderForAddress(host, port, credentials).build()
        try {
            val methodDescriptor =
                MethodDescriptor
                    .newBuilder<ByteArray, ByteArray>()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("$serviceName/$methodName")
                    .setRequestMarshaller(ByteArrayMarshaller())
                    .setResponseMarshaller(ByteArrayMarshaller())
                    .build()

            return ClientCalls.blockingUnaryCall(channel, methodDescriptor, CallOptions.DEFAULT, request)
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /** Runs a TLS unary GRPC call until expected response is received. */
    fun runGrpcTlsUntil(
        isBuildRunning: AtomicBoolean,
        host: String,
        port: Int,
        serviceName: String,
        methodName: String,
        request: ByteArray,
        expectedResponse: ByteArray,
        trust: File,
    ): Boolean {
        if (!isBuildRunning.get()) {
            return false
        }
        try {
            val response = grpcTlsCall(host, port, serviceName, methodName, request, trust)
            if (response.contentEquals(expectedResponse)) {
                return true
            }
            println("TLS GRPC mismatch, got ${String(response)}")
        } catch (ex: Exception) {
            println("TLS GRPC exception: ${ex.message}")
        }
        Thread.sleep(500)
        return runGrpcTlsUntil(
            isBuildRunning,
            host,
            port,
            serviceName,
            methodName,
            request,
            expectedResponse,
            trust,
        )
    }

    /** Simple marshaller for byte arrays - allows generic GRPC calls without proto. */
    private class ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }
}

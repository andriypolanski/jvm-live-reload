package me.seroperson.reload.live.mill.test

import io.grpc.CallOptions
import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.TlsChannelCredentials
import io.grpc.reflection.v1alpha.ServerReflectionGrpc
import io.grpc.reflection.v1alpha.ServerReflectionRequest
import io.grpc.reflection.v1alpha.ServerReflectionResponse
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait RequestMaker {

  private lazy val client = OkHttpClient()

  def runUntil(
      url: String,
      expectedStatus: Int,
      expectedBody: String
  ): Boolean = {
    val request: Request = Request.Builder().url(url).build()

    try {
      val (code, body) = Using(client.newCall(request).execute()) { response =>
        Using(response.body()) { body =>
          response.code -> body.string()
        }.get
      }.get
      println(s"Requesting $url, got $code and $body")
      if (expectedStatus == code && expectedBody == body) {
        return true
      } else {
        Thread.sleep(500)
        return runUntil(url, expectedStatus, expectedBody)
      }
    } catch {
      case ex: Exception =>
        println(s"Got exception: ${ex.getMessage}")
        Thread.sleep(500)
        return runUntil(url, expectedStatus, expectedBody)
    }
  }

  /** Runs GRPC call until expected response is received. Use
    * www.protobufpal.com to get request/response presentation in bytes.
    */
  def runGrpcUntil(
      host: String,
      port: Int,
      serviceName: String,
      methodName: String,
      request: Array[Byte],
      expectedResponse: Array[Byte]
  ): Boolean = {
    try {
      val response =
        grpcCall(host, port, serviceName, methodName, request)
      println(
        s"GRPC call to $serviceName/$methodName, got ${response.map("%02x".format(_)).mkString}"
      )
      if (response.sameElements(expectedResponse)) {
        return true
      } else {
        Thread.sleep(500)
        return runGrpcUntil(
          host,
          port,
          serviceName,
          methodName,
          request,
          expectedResponse
        )
      }
    } catch {
      case ex: Exception =>
        println(s"GRPC exception: ${ex.getMessage}")
        Thread.sleep(500)
        return runGrpcUntil(
          host,
          port,
          serviceName,
          methodName,
          request,
          expectedResponse
        )
    }
  }

  private def grpcCall(
      host: String,
      port: Int,
      serviceName: String,
      methodName: String,
      request: Array[Byte],
      tlsTrust: Option[File] = None
  ): Array[Byte] = {
    val channel: ManagedChannel = openChannel(host, port, tlsTrust)
    try {
      val methodDescriptor =
        MethodDescriptor
          .newBuilder[Array[Byte], Array[Byte]]()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(s"$serviceName/$methodName")
          .setRequestMarshaller(ByteArrayMarshaller())
          .setResponseMarshaller(ByteArrayMarshaller())
          .build()

      return ClientCalls.blockingUnaryCall(
        channel,
        methodDescriptor,
        CallOptions.DEFAULT,
        request
      )
    } finally {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  private def openChannel(
      host: String,
      port: Int,
      tlsTrust: Option[File]
  ): ManagedChannel =
    tlsTrust match {
      case Some(trust) =>
        val credentials =
          TlsChannelCredentials.newBuilder().trustManager(trust).build()
        Grpc.newChannelBuilderForAddress(host, port, credentials).build()
      case None =>
        ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
    }

  /** Runs a server-streaming GRPC call until the collected messages match the
    * expected ones.
    */
  def runServerStreamingUntil(
      host: String,
      port: Int,
      serviceName: String,
      methodName: String,
      request: Array[Byte],
      expected: Seq[Array[Byte]],
      tlsTrust: Option[File] = None
  ): Boolean = {
    try {
      val collected = serverStreamingCall(
        host,
        port,
        serviceName,
        methodName,
        request,
        tlsTrust
      )
      val match_ = collected.size == expected.size &&
        collected.zip(expected).forall { case (a, b) => a.sameElements(b) }
      if (match_) return true
      println(
        s"Streaming response did not match yet, got ${collected.map(new String(_, "UTF-8"))}"
      )
    } catch {
      case ex: Exception =>
        println(s"GRPC streaming exception: ${ex.getMessage}")
    }
    Thread.sleep(500)
    runServerStreamingUntil(
      host,
      port,
      serviceName,
      methodName,
      request,
      expected,
      tlsTrust
    )
  }

  /** Runs a unary GRPC call over TLS until the expected response is received.
    */
  def runGrpcTlsUntil(
      host: String,
      port: Int,
      serviceName: String,
      methodName: String,
      request: Array[Byte],
      expectedResponse: Array[Byte],
      trust: File
  ): Boolean = {
    try {
      val response =
        grpcCall(host, port, serviceName, methodName, request, Some(trust))
      if (response.sameElements(expectedResponse)) return true
      println(
        s"TLS GRPC mismatch, got ${response.map("%02x".format(_)).mkString}"
      )
    } catch {
      case ex: Exception =>
        println(s"TLS GRPC exception: ${ex.getMessage}")
    }
    Thread.sleep(500)
    runGrpcTlsUntil(
      host,
      port,
      serviceName,
      methodName,
      request,
      expectedResponse,
      trust
    )
  }

  private def serverStreamingCall(
      host: String,
      port: Int,
      serviceName: String,
      methodName: String,
      request: Array[Byte],
      tlsTrust: Option[File]
  ): List[Array[Byte]] = {
    val channel: ManagedChannel = openChannel(host, port, tlsTrust)
    try {
      val methodDescriptor =
        MethodDescriptor
          .newBuilder[Array[Byte], Array[Byte]]()
          .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
          .setFullMethodName(s"$serviceName/$methodName")
          .setRequestMarshaller(ByteArrayMarshaller())
          .setResponseMarshaller(ByteArrayMarshaller())
          .build()

      val iterator =
        ClientCalls.blockingServerStreamingCall(
          channel,
          methodDescriptor,
          CallOptions.DEFAULT,
          request
        )
      iterator.asScala.toList
    } finally {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  /** Lists services via the bidi-streaming reflection method through the proxy.
    */
  def runReflectionListServicesUntil(
      host: String,
      port: Int,
      expectedService: String
  ): Boolean = {
    try {
      val services = reflectionListServices(host, port)
      println(s"Reflection services: $services")
      if (services.contains(expectedService)) return true
    } catch {
      case ex: Exception =>
        println(s"Reflection exception: ${ex.getMessage}")
    }
    Thread.sleep(500)
    runReflectionListServicesUntil(host, port, expectedService)
  }

  private def reflectionListServices(host: String, port: Int): Set[String] = {
    val channel: ManagedChannel =
      ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
    try {
      val promise = Promise[ServerReflectionResponse]()
      val responseObserver = new StreamObserver[ServerReflectionResponse] {
        override def onNext(value: ServerReflectionResponse): Unit =
          promise.trySuccess(value)
        override def onError(t: Throwable): Unit = promise.tryFailure(t)
        override def onCompleted(): Unit = ()
      }
      val stub = ServerReflectionGrpc.newStub(channel)
      val requestObserver = stub.serverReflectionInfo(responseObserver)
      requestObserver.onNext(
        ServerReflectionRequest.newBuilder().setListServices("").build()
      )
      requestObserver.onCompleted()
      Await
        .result(promise.future, 10.seconds)
        .getListServicesResponse
        .getServiceList
        .asScala
        .map(_.getName)
        .toSet
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  private class ByteArrayMarshaller
      extends MethodDescriptor.Marshaller[Array[Byte]] {
    override def stream(value: Array[Byte]): InputStream = ByteArrayInputStream(
      value
    )

    override def parse(stream: InputStream): Array[Byte] = stream.readAllBytes()
  }
}

package me.seroperson.reload.live

import io.grpc.CallOptions
import io.grpc.Grpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.Marshaller
import io.grpc.MethodDescriptor.MethodType
import io.grpc.TlsChannelCredentials
import io.grpc.reflection.v1alpha.ServerReflectionGrpc
import io.grpc.reflection.v1alpha.ServerReflectionRequest
import io.grpc.reflection.v1alpha.ServerReflectionResponse
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.{TimeUnit => JTimeUnit}
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class GrpcLiveReloadSpec extends LiveReloadBase {

  private def hexToBytes(hex: String): Array[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def bytesToHex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  private val byteMarshaller: Marshaller[Array[Byte]] =
    new Marshaller[Array[Byte]] {
      override def stream(value: Array[Byte]): InputStream =
        new ByteArrayInputStream(value)
      override def parse(stream: InputStream): Array[Byte] =
        stream.readAllBytes()
    }

  private def verifyGrpc(
      service: String,
      method: String,
      requestHex: String,
      expectedResponseHex: String,
      port: Int
  ): Unit = {
    val methodDescriptor = MethodDescriptor
      .newBuilder(byteMarshaller, byteMarshaller)
      .setType(MethodType.UNARY)
      .setFullMethodName(s"$service/$method")
      .build()

    @tailrec
    def attempt(remaining: Int): Unit = {
      val result = Try {
        val channel = ManagedChannelBuilder
          .forAddress("localhost", port)
          .usePlaintext()
          .build()
        try {
          val response = ClientCalls.blockingUnaryCall(
            channel.newCall(methodDescriptor, CallOptions.DEFAULT),
            hexToBytes(requestHex)
          )
          val responseHex = bytesToHex(response)
          assert(
            responseHex == expectedResponseHex,
            s"GRPC $service/$method: expected $expectedResponseHex, got $responseHex"
          )
        } finally {
          channel.shutdownNow()
        }
      }
      result match {
        case Success(_)                  => ()
        case Failure(_) if remaining > 0 =>
          Thread.sleep(RetryInterval)
          attempt(remaining - 1)
        case Failure(ex) =>
          throw new AssertionError(
            s"Failed to verify GRPC $service/$method after $MaxRetries attempts: ${ex.getMessage}",
            ex
          )
      }
    }

    attempt(MaxRetries)
  }

  private def openChannel(port: Int, tlsTrust: Option[File]): ManagedChannel =
    tlsTrust match {
      case Some(trust) =>
        val credentials =
          TlsChannelCredentials.newBuilder().trustManager(trust).build()
        Grpc.newChannelBuilderForAddress("localhost", port, credentials).build()
      case None =>
        ManagedChannelBuilder
          .forAddress("localhost", port)
          .usePlaintext()
          .build()
    }

  private def verifyServerStreaming(
      service: String,
      method: String,
      request: Array[Byte],
      expected: Seq[String],
      port: Int,
      tlsTrust: Option[File] = None
  ): Unit = {
    val methodDescriptor = MethodDescriptor
      .newBuilder(byteMarshaller, byteMarshaller)
      .setType(MethodType.SERVER_STREAMING)
      .setFullMethodName(s"$service/$method")
      .build()

    @tailrec
    def attempt(remaining: Int): Unit = {
      val result = Try {
        val channel = openChannel(port, tlsTrust)
        try {
          val iterator = ClientCalls.blockingServerStreamingCall(
            channel.newCall(methodDescriptor, CallOptions.DEFAULT),
            request
          )
          val collected = iterator.asScala.map(new String(_, "UTF-8")).toList
          assert(
            collected == expected.toList,
            s"streaming $service/$method: expected $expected, got $collected"
          )
        } finally {
          channel.shutdownNow()
        }
      }
      result match {
        case Success(_)                  => ()
        case Failure(_) if remaining > 0 =>
          Thread.sleep(RetryInterval)
          attempt(remaining - 1)
        case Failure(ex) =>
          throw new AssertionError(
            s"Failed to verify streaming $service/$method after $MaxRetries attempts: ${ex.getMessage}",
            ex
          )
      }
    }

    attempt(MaxRetries)
  }

  private def verifyGrpcTls(
      service: String,
      method: String,
      requestHex: String,
      expectedResponseHex: String,
      port: Int,
      trust: File
  ): Unit = {
    val methodDescriptor = MethodDescriptor
      .newBuilder(byteMarshaller, byteMarshaller)
      .setType(MethodType.UNARY)
      .setFullMethodName(s"$service/$method")
      .build()

    @tailrec
    def attempt(remaining: Int): Unit = {
      val result = Try {
        val channel = openChannel(port, Some(trust))
        try {
          val response = ClientCalls.blockingUnaryCall(
            channel.newCall(methodDescriptor, CallOptions.DEFAULT),
            hexToBytes(requestHex)
          )
          val responseHex = bytesToHex(response)
          assert(
            responseHex == expectedResponseHex,
            s"GRPC $service/$method: expected $expectedResponseHex, got $responseHex"
          )
        } finally {
          channel.shutdownNow()
        }
      }
      result match {
        case Success(_)                  => ()
        case Failure(_) if remaining > 0 =>
          Thread.sleep(RetryInterval)
          attempt(remaining - 1)
        case Failure(ex) =>
          throw new AssertionError(
            s"Failed to verify TLS GRPC $service/$method after $MaxRetries attempts: ${ex.getMessage}",
            ex
          )
      }
    }

    attempt(MaxRetries)
  }

  // sbt-protoc is only available for sbt 1.x
  test("grpc-scalapb - live reload on source change") {
    withSbt1Runner("grpc-scalapb") { (runner, proxyPort) =>
      runner.run("bgRun")
      // request: HelloRequest(name = "Hello World!")
      // response: HelloReply(message = "World Hello!")
      verifyGrpc(
        "Greeter",
        "SayHello",
        "0a0c48656c6c6f20576f726c6421",
        "0a0c576f726c642048656c6c6f21",
        proxyPort
      )
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      // response: HelloReply(message = "Hello World Reloaded!")
      verifyGrpc(
        "Greeter",
        "SayHello",
        "0a0c48656c6c6f20576f726c6421",
        "0a1548656c6c6f20576f726c642052656c6f6164656421",
        proxyPort
      )
    }
  }

  private def listServicesViaReflection(port: Int): Set[String] = {
    val channel =
      ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()
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
      channel.shutdownNow().awaitTermination(5, JTimeUnit.SECONDS)
    }
  }

  test("grpc-reflection - bidi streaming reflection passthrough") {
    withRunner("grpc-streaming") { (runner, proxyPort) =>
      runner.run("bgRun")
      @tailrec
      def attempt(remaining: Int): Set[String] = {
        Try(listServicesViaReflection(proxyPort)) match {
          case Success(services)           => services
          case Failure(_) if remaining > 0 =>
            Thread.sleep(RetryInterval)
            attempt(remaining - 1)
          case Failure(ex) =>
            throw new AssertionError(
              s"Reflection failed after $MaxRetries attempts: ${ex.getMessage}",
              ex
            )
        }
      }
      val services = attempt(MaxRetries)
      assert(
        services.contains("grpc.health.v1.Health"),
        s"expected grpc.health.v1.Health in reflected services, got $services"
      )
    }
  }

  test("grpc-streaming - live reload of a server-streaming method") {
    withRunner("grpc-streaming") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyServerStreaming(
        "greeter.Greeter",
        "StreamGreet",
        Array.emptyByteArray,
        Seq("Hi-1", "Hi-2", "Hi-3"),
        proxyPort
      )
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyServerStreaming(
        "greeter.Greeter",
        "StreamGreet",
        Array.emptyByteArray,
        Seq("Yo-1", "Yo-2", "Yo-3"),
        proxyPort
      )
    }
  }

  test("grpc-multiproject - reload triggered by sibling-project change") {
    withRunner("grpc-multiproject") { (runner, proxyPort) =>
      runner.run("project-a/bgRun")
      verifyGrpc(
        "greeter.Greeter",
        "Greet",
        "",
        bytesToHex("Multi-Hi".getBytes("UTF-8")),
        proxyPort
      )
      runner.copyFile(
        "changes/App.scala.1",
        "project-a/src/main/scala/App.scala"
      )
      runner.copyFile(
        "changes/Greeting.scala.1",
        "project-b/src/main/scala/Greeting.scala"
      )
      verifyGrpc(
        "greeter.Greeter",
        "Greet",
        "",
        bytesToHex("Multi-Yo!".getBytes("UTF-8")),
        proxyPort
      )
    }
  }

  test("grpc-scala3 - live reload on a Scala 3 grpc app") {
    withRunner("grpc-scala3") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyGrpc(
        "greeter.Greeter",
        "Greet",
        "",
        bytesToHex("Scala3-Hi".getBytes("UTF-8")),
        proxyPort
      )
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyGrpc(
        "greeter.Greeter",
        "Greet",
        "",
        bytesToHex("Scala3-Yo".getBytes("UTF-8")),
        proxyPort
      )
    }
  }

  test("grpc-tls - live reload through full TLS proxy and backend") {
    withRunner("grpc-tls") { (runner, proxyPort) =>
      runner.run("bgRun")
      val trust = new File(runner.baseDirectory, "cert.pem")
      verifyGrpcTls(
        "greeter.Greeter",
        "Greet",
        "",
        bytesToHex("Secure-Hi".getBytes("UTF-8")),
        proxyPort,
        trust
      )
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyGrpcTls(
        "greeter.Greeter",
        "Greet",
        "",
        bytesToHex("Secure-Yo".getBytes("UTF-8")),
        proxyPort,
        trust
      )
    }
  }
}

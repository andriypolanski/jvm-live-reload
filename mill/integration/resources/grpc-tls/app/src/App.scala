import io.grpc.{BindableService, MethodDescriptor, ServerCallHandler, ServerServiceDefinition}
import io.grpc.MethodDescriptor.{Marshaller, MethodType}
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.{ServerCalls, StreamObserver}

import java.io.{ByteArrayInputStream, File, InputStream}

object App {

  private val Response = "Secure-Hi"

  def main(args: Array[String]): Unit = {
    val cert = sys.props("live.reload.grpc.proxy.tls.cert")
    val key = sys.props("live.reload.grpc.proxy.tls.key")
    val port = sys.props("live.reload.grpc.port").toInt

    val health = new HealthStatusManager()
    val server = NettyServerBuilder
      .forPort(port)
      .useTransportSecurity(new File(cert), new File(key))
      .addService(new TlsGreeter(Response))
      .addService(health.getHealthService)
      .build()
      .start()

    health.setStatus("", HealthCheckResponse.ServingStatus.SERVING)
    println(s"TLS Server started on port $port")

    try {
      server.awaitTermination()
    } catch {
      case _: InterruptedException =>
        health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING)
        server.shutdownNow()
    }
  }
}

class ByteMarshaller extends Marshaller[Array[Byte]] {
  override def stream(value: Array[Byte]): InputStream = new ByteArrayInputStream(value)
  override def parse(stream: InputStream): Array[Byte] = stream.readAllBytes()
}

class TlsGreeter(response: String) extends BindableService {
  override def bindService(): ServerServiceDefinition = {
    val method = MethodDescriptor
      .newBuilder(new ByteMarshaller, new ByteMarshaller)
      .setType(MethodType.UNARY)
      .setFullMethodName("greeter.Greeter/Greet")
      .build()

    val handler: ServerCallHandler[Array[Byte], Array[Byte]] =
      ServerCalls.asyncUnaryCall((_, responseObserver: StreamObserver[Array[Byte]]) => {
        responseObserver.onNext(response.getBytes("UTF-8"))
        responseObserver.onCompleted()
      })

    ServerServiceDefinition
      .builder("greeter.Greeter")
      .addMethod(method, handler)
      .build()
  }
}

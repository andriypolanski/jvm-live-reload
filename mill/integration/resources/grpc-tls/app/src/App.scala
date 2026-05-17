import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.Marshaller
import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

object App {

  private val Response = "Secure-Hi"

  def main(args: Array[String]): Unit = {
    val health = new HealthStatusManager()
    val server = NettyServerBuilder
      .forPort(8080)
      .useTransportSecurity(
        new File(sys.env("APP_TLS_CERT")),
        new File(sys.env("APP_TLS_KEY"))
      )
      .addService(new TlsGreeter(Response))
      .addService(health.getHealthService)
      .build()
      .start()

    health.setStatus("", HealthCheckResponse.ServingStatus.SERVING)
    println(s"TLS Server started on port 8080")

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
  override def stream(value: Array[Byte]): InputStream =
    new ByteArrayInputStream(value)
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
      ServerCalls.asyncUnaryCall(
        (_, responseObserver: StreamObserver[Array[Byte]]) => {
          responseObserver.onNext(response.getBytes("UTF-8"))
          responseObserver.onCompleted()
        }
      )

    ServerServiceDefinition
      .builder("greeter.Greeter")
      .addMethod(method, handler)
      .build()
  }
}

import io.grpc.health.v1.HealthCheckResponse
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import scala.concurrent.ExecutionContext
import greeter._

object App {
  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    val health = new HealthStatusManager()
    val server = NettyServerBuilder
      .forPort(me.seroperson.BuildInfo.port)
      .addService(GreeterGrpc.bindService(new GreeterImpl, ec))
      .addService(health.getHealthService)
      .build()
      .start()

    health.setStatus("", HealthCheckResponse.ServingStatus.SERVING)
    println(s"Server started on port ${me.seroperson.BuildInfo.port}")

    try {
      server.awaitTermination()
    } catch {
      case _: InterruptedException =>
        health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING)
        server.shutdownNow()
    }
  }
}

class GreeterImpl extends GreeterGrpc.Greeter {
  override def sayHello(request: HelloRequest): scala.concurrent.Future[HelloReply] = {
    scala.concurrent.Future.successful(
      HelloReply(message = s"World Hello!")
    )
  }
}

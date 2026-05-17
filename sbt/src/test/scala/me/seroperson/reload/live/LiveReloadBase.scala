package me.seroperson.reload.live

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import me.seroperson.reload.live.sbt.BuildInfo
import me.seroperson.sbt.testkit.*
import org.scalatest.funsuite.AnyFunSuite
import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try

trait LiveReloadBase extends AnyFunSuite {

  protected val MaxRetries = 60
  protected val RetryInterval = 1000L // ms

  private val portCounter = new java.util.concurrent.atomic.AtomicInteger(19000)

  protected val httpClient: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  /** Allocates a unique (proxyPort, appPort) pair for each test */
  protected def nextPortPair(): (Int, Int) = {
    val proxy = portCounter.getAndAdd(2)
    (proxy, proxy + 1)
  }

  protected def withRunner(
      resourceDir: String
  )(body: (SbtRunner, Int) => Unit): Unit = {
    val (proxyPort, appPort) = nextPortPair()
    val runner = SbtRunner
      .inTemp()
      .withDirectoryFromResources(resourceDir)
      .withSbtVersion("2.0.0-RC10")
      .withJvmOptions(
        s"-Dproject.version=${BuildInfo.version}",
        s"-Dtestkit.proxyPort=$proxyPort",
        s"-Dtestkit.port=$appPort"
      )
      .withAttachedStdio()
      .withDebugLogging()
      .build()
    try body(runner, proxyPort)
    finally runner.close()
  }

  protected def withSbt1Runner(
      resourceDir: String
  )(body: (SbtRunner, Int) => Unit): Unit = {
    val (proxyPort, appPort) = nextPortPair()
    val runner = SbtRunner
      .inTemp()
      .withDirectoryFromResources(resourceDir)
      .withSbtVersion("1.12.3")
      .withJvmOptions(
        s"-Dproject.version=${BuildInfo.version}",
        s"-Dtestkit.proxyPort=$proxyPort",
        s"-Dtestkit.port=$appPort"
      )
      .withAttachedStdio()
      .withDebugLogging()
      .build()
    try body(runner, proxyPort)
    finally runner.close()
  }

  protected def verifyHttp(
      path: String,
      expectedStatus: Int,
      expectedBody: Option[String] = None,
      port: Int
  ): Unit = {
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"http://localhost:$port/$path"))
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build()

    @tailrec
    def attempt(remaining: Int): Unit = {
      val result = Try {
        val response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assert(
          response.statusCode() == expectedStatus,
          s"Expected status $expectedStatus for /$path, got ${response.statusCode()}"
        )
        expectedBody.foreach { body =>
          val actualBody = response.body()
          assert(
            actualBody == body,
            s"Expected body '$body' for /$path, got '$actualBody'"
          )
        }
      }
      result match {
        case Success(_)                  => ()
        case Failure(_) if remaining > 0 =>
          Thread.sleep(RetryInterval)
          attempt(remaining - 1)
        case Failure(ex) =>
          throw new AssertionError(
            s"Failed to verify /$path (status=$expectedStatus, body=$expectedBody) after $MaxRetries attempts: ${ex.getMessage}",
            ex
          )
      }
    }

    attempt(MaxRetries)
  }
}

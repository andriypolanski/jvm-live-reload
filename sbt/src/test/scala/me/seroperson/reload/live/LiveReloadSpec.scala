package me.seroperson.reload.live

class LiveReloadSpec extends LiveReloadBase {

  test("http4s - live reload on source change") {
    withRunner("http4s") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyHttp("greet_reloaded", 200, Some("World Hello"), proxyPort)
      verifyHttp("greet", 404, Some("Not found"), proxyPort)
    }
  }

  test("zio-http - live reload on source change") {
    withRunner("zio-http") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyHttp("greet_reloaded", 200, Some("World Hello"), proxyPort)
      verifyHttp("greet", 404, port = proxyPort)
    }
  }

  test("cask - live reload on source change") {
    withRunner("cask") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyHttp("greet_reloaded", 200, Some("World Hello"), proxyPort)
      verifyHttp("greet", 404, Some("Error 404: Not Found"), proxyPort)
    }
  }

  test("http4s - add new file triggers reload") {
    withRunner("http4s-add-new-file") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.delete("src/main/scala/App.scala")
      runner.copyFile("changes/NewApp.scala.1", "src/main/scala/NewApp.scala")
      runner.copyFile(
        "changes/NewClass.scala.1",
        "src/main/scala/NewClass.scala"
      )
      verifyHttp("greet_reloaded", 200, Some("World Hello 1"), proxyPort)
      verifyHttp("greet", 404, Some("Not found"), proxyPort)
    }
  }

  test("http4s - dotenv environment variables") {
    withRunner("http4s-dotenv") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyHttp("greet_reloaded", 200, Some("World Hello"), proxyPort)
      verifyHttp("greet", 404, Some("Not found"), proxyPort)
    }
  }

  test("http4s - propagate-env environment variables") {
    withRunner("http4s-propagate-env") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      verifyHttp("greet_reloaded", 200, Some("World Hello"), proxyPort)
      verifyHttp("greet", 404, Some("Not found"), proxyPort)
    }
  }

  test("http4s - reload with resource files") {
    withRunner("http4s-with-resources") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World 1"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      runner.copyFile(
        "changes/application.conf.1",
        "src/main/resources/application.conf"
      )
      verifyHttp("greet_reloaded", 200, Some("World Hello 2"), proxyPort)
      verifyHttp("greet", 404, Some("Not found"), proxyPort)
    }
  }

  test("zio-http - reload with resource files") {
    withRunner("zio-http-with-resources") { (runner, proxyPort) =>
      runner.run("bgRun")
      verifyHttp("greet", 200, Some("Hello World 1"), proxyPort)
      runner.copyFile("changes/App.scala.1", "src/main/scala/App.scala")
      runner.copyFile(
        "changes/application.conf.1",
        "src/main/resources/application.conf"
      )
      verifyHttp("greet_reloaded", 200, Some("World Hello 2"), proxyPort)
      verifyHttp("greet", 404, port = proxyPort)
    }
  }

  test("zio-http - multi-project reload") {
    withRunner("zio-http-multiproject") { (runner, proxyPort) =>
      runner.run("project-a/bgRun")
      verifyHttp("greet", 200, Some("Hello World"), proxyPort)
      runner.copyFile(
        "changes/App.scala.1",
        "project-a/src/main/scala/App.scala"
      )
      runner.copyFile(
        "changes/Text.scala.1",
        "project-b/src/main/scala/Text.scala"
      )
      verifyHttp("greet_reloaded", 200, Some("World Hello!"), proxyPort)
      verifyHttp("greet", 404, port = proxyPort)
    }
  }
}

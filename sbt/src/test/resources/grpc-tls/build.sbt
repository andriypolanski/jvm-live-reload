val GrpcVersion = "1.72.0"

enablePlugins(LiveReloadPlugin)
enablePlugins(BuildInfoPlugin)

scalaVersion := "2.13.16"
resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  "io.grpc" % "grpc-netty-shaded" % GrpcVersion,
  "io.grpc" % "grpc-stub" % GrpcVersion,
  "io.grpc" % "grpc-services" % GrpcVersion
)

val isSbt2 = settingKey[Boolean]("isSbt2")
isSbt2 := (sbtBinaryVersion.value match {
  case "2" => true
  case _   => false
})

val proxyPort = settingKey[Int]("proxyPort")
proxyPort := sys.props.get("testkit.proxyPort").map(_.toInt).getOrElse(if (isSbt2.value) 9001 else 9000)

val port = settingKey[Int]("port")
port := sys.props.get("testkit.port").map(_.toInt).getOrElse(if (isSbt2.value) 8081 else 8080)

val certFile = settingKey[String]("certFile")
certFile := ((ThisBuild / baseDirectory).value / "cert.pem").getAbsolutePath
val keyFile = settingKey[String]("keyFile")
keyFile := ((ThisBuild / baseDirectory).value / "key.pem").getAbsolutePath

liveServerType := me.seroperson.reload.live.sbt.GrpcServerType

liveDevSettings := Seq(
  DevSettingsKeys.LiveReloadProxyGrpcPort -> proxyPort.value.toString,
  DevSettingsKeys.LiveReloadGrpcPort -> port.value.toString,
  DevSettingsKeys.LiveReloadGrpcTargetTls -> "true",
  DevSettingsKeys.LiveReloadGrpcTargetTlsTrust -> certFile.value,
  DevSettingsKeys.LiveReloadGrpcProxyTlsCert -> certFile.value,
  DevSettingsKeys.LiveReloadGrpcProxyTlsKey -> keyFile.value,
  DevSettingsKeys.LiveReloadIsDebug -> "true"
)

buildInfoKeys := Seq[BuildInfoKey](port, certFile, keyFile)
buildInfoPackage := "me.seroperson"

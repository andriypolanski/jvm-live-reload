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

liveServerType := me.seroperson.reload.live.sbt.GrpcServerType

liveDevSettings := Seq(
  DevSettingsKeys.LiveReloadProxyGrpcPort -> proxyPort.value.toString,
  DevSettingsKeys.LiveReloadGrpcPort -> port.value.toString,
  DevSettingsKeys.LiveReloadIsDebug -> "true"
)

buildInfoKeys := Seq[BuildInfoKey](port)
buildInfoPackage := "me.seroperson"

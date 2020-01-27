name := "MapRouletteAPI"

version := "3.4.0"

scalaVersion := "2.12.8"

packageName in Universal := "MapRouletteAPI"

lazy val compileScalastyle = taskKey[Unit]("compileScalastyle")

compileScalastyle := scalastyle.in(Compile).toTask("").value

(scalastyleConfig in Compile) := baseDirectory.value / "conf/scalastyle-config.xml"

//(compile in Compile) := ((compile in Compile) dependsOn compileScalastyle).value

lazy val `MapRouletteV2` = (project in file(".")).enablePlugins(PlayScala, SbtWeb, SwaggerPlugin)

swaggerDomainNameSpaces := Seq("org.maproulette.models", "org.maproulette.exception", "org.maproulette.session", "org.maproulette.actions", "org.maproulette.data")

swaggerOutputTransformers := Seq(envOutputTransformer)

swaggerRoutesFile := "apiv2.routes"

pipelineStages := Seq(gzip)

routesGenerator := InjectedRoutesGenerator

libraryDependencies ++= Seq(
  jdbc,
  ehcache,
  ws,
  evolutions,
  specs2 % Test,
  filters,
  guice,
  "com.typesafe.play" %% "play-json-joda" % "2.7.0",
  "com.typesafe.play" %% "play-json" % "2.7.1",
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test,
  "org.webjars" % "swagger-ui" % "3.20.5",
  "org.playframework.anorm" %% "anorm" % "2.6.2",
  "org.postgresql" % "postgresql" % "42.2.5",
  "net.postgis" % "postgis-jdbc" % "2.3.0",
  "joda-time" % "joda-time" % "2.10.1",
  "com.vividsolutions" % "jts" % "1.13",
  "org.wololo" % "jts2geojson" % "0.10.0",
  "org.apache.commons" % "commons-lang3" % "3.8.1",
  "commons-codec" % "commons-codec" % "1.11",
  "com.typesafe.play" %% "play-mailer" % "6.0.1",
  "com.typesafe.play" %% "play-mailer-guice" % "6.0.1",
  "com.typesafe.akka" %% "akka-cluster-tools" % "2.5.19",
  "net.debasishg" %% "redisclient" % "3.10"
)

resolvers ++= Seq(
  Resolver.bintrayRepo("scalaz", "releases"),
  "Atlassian Releases" at "https://maven.atlassian.com/public/",
  Resolver.sonatypeRepo("snapshots")
)

scalacOptions ++= Seq(
  // Show warning feature details in the console
  "-feature",
  // Enable routes file splitting
  "-language:reflectiveCalls"
)

javaOptions in Test ++= Option(System.getProperty("config.file")).map("-Dconfig.file=" + _).toSeq

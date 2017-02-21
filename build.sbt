
name := "apifetcher"

organization := "com.example"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play" % "2.5.12",
  "com.typesafe.play" %% "play-ws" % "2.5.12",
  "com.typesafe.akka" %% "akka-actor" % "2.4.16",
  "com.typesafe.akka" %% "akka-stream" % "2.4.16",
  "org.slf4j" % "slf4j-api" % "1.7.22",
  "ch.qos.logback" % "logback-classic" % "1.0.13"
)

javaOptions += "-Dlogger.file=src/main/resources/logback.xml"

fork in run := true


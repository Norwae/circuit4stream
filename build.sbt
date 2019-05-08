name := "circuit4stream"
organization := "com.github.norwae"
version := "0.1"
scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.22",
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.22" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)
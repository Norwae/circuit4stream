name := "circuit4stream"
organization := "com.github.norwae"
version := "1.1.0-SNAPSHOT"
scalaVersion := "2.12.8"
publishMavenStyle := true
description := """
    |This module packages a circuit breaker that can be used to avoid overloading or otherwise depending 
    |on a temporarily unavailable (remote) system.
    |
    |The central use of the circuit breaker is to prevent failures from one system to
    |cascade to other systems in an unchecked manner. Thus, our implementation is chiefly 
    |concerned with replacing a failing component with another component that fails in a very
    |predictable manner. These failures are not "dropped" or otherwise made invisible, and still need
    |to be handled, but they will occur in a predictable, and hopefully usable manner.
    |""".stripMargin
scmInfo := Some(ScmInfo(
  url("https://github.com/norwae/circuit4stream"), 
  "scm:git:https://github.com/Norwae/oriana.git", 
  Some("scm:git:ssh://git@github.com:Norwae/circuit4stream.git")
))
pomExtra :=
  Seq(
  <licenses>
    <license>
      <name>MIT</name>
      <url>https://github.com/Norwae/circuit4stream/blob/master/LICENSE</url>
      <distribution>repo</distribution>
    </license>
  </licenses>, 
  <developers>
    <developer>
      <name>Stefan Schulz</name>
      <email>schulz.stefan@gmail.com</email>
    </developer>
  </developers>, 
  <url>https://github.com/norwae/circuit4stream</url>)
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.22",
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.22" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
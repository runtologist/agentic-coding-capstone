ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "workshop.capstone"

lazy val root = (project in file("."))
  .settings(
    name := "snap",
    Compile / mainClass := Some("snap.Main"),
    assembly / mainClass := Some("snap.Main"),
    assembly / assemblyJarName := "snap-assembly-0.1.0.jar",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.26",
      "dev.zio" %% "zio-json" % "0.7.43",
      "dev.zio" %% "zio-http" % "3.3.3",
      "dev.zio" %% "zio-test" % "2.1.26" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    run / fork := true,
    run / connectInput := true,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

ThisBuild / scalaVersion := "3.3.5"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "workshop.capstone"

lazy val root = (project in file("."))
  .settings(
    name := "tabbyshell",
    Compile / mainClass := Some("tabbyshell.Main"),
    assembly / mainClass := Some("tabbyshell.Main"),
    assembly / assemblyJarName := "tabbyshell-assembly-0.1.0.jar",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.26",
      "dev.zio" %% "zio-streams" % "2.1.26",
      "dev.zio" %% "zio-json" % "1.0.0",
      "dev.zio" %% "zio-test" % "2.1.26" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:imports"
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

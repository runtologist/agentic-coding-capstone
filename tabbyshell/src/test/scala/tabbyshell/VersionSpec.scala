package tabbyshell

import zio.test.*

object VersionSpec extends ZIOSpecDefault:
  override def spec = suite("Version")(
    test("prints the expected version line") {
      assertTrue(Version.line == "tabbyshell 0.1.0")
    }
  )

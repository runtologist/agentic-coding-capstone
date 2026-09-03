package tabbyshell

import zio.test.*

import java.nio.file.{AccessDeniedException, FileSystemException, NoSuchFileException}

object TabbyErrorSpec extends ZIOSpecDefault {

  override def spec = suite("TabbyError.ioMessage")(
    test("maps NoSuchFileException to '<file>: No such file or directory'") {
      val message = TabbyError.ioMessage(new NoSuchFileException("/tmp/missing"))
      assertTrue(message == "/tmp/missing: No such file or directory")
    },
    test("maps AccessDeniedException to '<file>: Permission denied'") {
      val message = TabbyError.ioMessage(new AccessDeniedException("/tmp/secret"))
      assertTrue(message == "/tmp/secret: Permission denied")
    },
    test("combines file and reason for FileSystemException") {
      val message = TabbyError.ioMessage(
        new FileSystemException("/tmp/a", null, "Read-only file system")
      )
      assertTrue(message == "/tmp/a: Read-only file system")
    },
    test("uses the throwable message when present") {
      val message = TabbyError.ioMessage(new RuntimeException("boom"))
      assertTrue(message == "boom")
    },
    test("falls back to the simple class name when the message is null or empty") {
      assertTrue(
        TabbyError.ioMessage(new RuntimeException()) == "RuntimeException",
        TabbyError.ioMessage(new RuntimeException("")) == "RuntimeException"
      )
    }
  )
}

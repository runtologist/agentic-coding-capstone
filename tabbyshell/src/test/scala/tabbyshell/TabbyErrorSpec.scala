package tabbyshell

import zio.test.*

import java.nio.file.{
  AccessDeniedException,
  AtomicMoveNotSupportedException,
  DirectoryNotEmptyException,
  FileSystemException,
  NoSuchFileException
}

object TabbyErrorSpec extends ZIOSpecDefault {

  override def spec = suite("TabbyError")(
    suite("error messages")(
      test("every variant formats its message exactly as specified") {
        assertTrue(
          TabbyError.Parse("unexpected token", 3).message ==
            "parse error: unexpected token at column 3",
          TabbyError.TypeMismatch("where", "int", "string").message ==
            "where: expected int, got string",
          TabbyError.MissingColumn("select", "name").message ==
            "select: column not found: name",
          TabbyError.MissingArg("cd", "path").message ==
            "cd: missing required argument: path",
          TabbyError.BadArg("cd", "not a directory: /tmp").message ==
            "cd: not a directory: /tmp",
          TabbyError.IoError("open", "/tmp/x: No such file or directory").message ==
            "open: /tmp/x: No such file or directory",
          TabbyError.ExternalFailed("grep", 2).message ==
            "grep: external command exited with status 2"
        )
      },
      test("errors are exceptions carrying the same message") {
        val error = TabbyError.BadArg("ls", "boom")
        assertTrue(error.getMessage == error.message)
      }
    ),
    suite("ioMessage")(
      test("maps NoSuchFileException to '<file>: No such file or directory'") {
        val message = TabbyError.ioMessage(new NoSuchFileException("/tmp/missing"))
        assertTrue(message == "/tmp/missing: No such file or directory")
      },
      test("maps AccessDeniedException to '<file>: Permission denied'") {
        val message = TabbyError.ioMessage(new AccessDeniedException("/tmp/secret"))
        assertTrue(message == "/tmp/secret: Permission denied")
      },
      test("falls back to the throwable message when the file is missing or empty") {
        assertTrue(
          TabbyError.ioMessage(new NoSuchFileException(null)) ==
            "null: No such file or directory",
          TabbyError.ioMessage(new NoSuchFileException("")) ==
            ": No such file or directory",
          TabbyError.ioMessage(new AccessDeniedException(null)) ==
            "null: Permission denied",
          TabbyError.ioMessage(new AccessDeniedException("")) ==
            ": Permission denied"
        )
      },
      test("combines file and reason for FileSystemException") {
        val message = TabbyError.ioMessage(
          new FileSystemException("/tmp/a", null, "Read-only file system")
        )
        assertTrue(message == "/tmp/a: Read-only file system")
      },
      test("uses only the file when there is no reason") {
        val message = TabbyError.ioMessage(new FileSystemException("/tmp/a", null, null))
        assertTrue(message == "/tmp/a")
      },
      test("uses only the reason when there is no file") {
        val message = TabbyError.ioMessage(
          new FileSystemException(null, null, "Read-only file system")
        )
        assertTrue(message == "Read-only file system")
      },
      test("falls back to class name when FileSystemException has no file or reason") {
        assertTrue(
          TabbyError.ioMessage(new FileSystemException(null, null, null)) ==
            "FileSystemException"
        )
      },
      test("handles FileSystemException subclasses") {
        assertTrue(
          TabbyError.ioMessage(new DirectoryNotEmptyException("/tmp/dir")) == "/tmp/dir",
          TabbyError.ioMessage(
            new AtomicMoveNotSupportedException("/tmp/a", "/tmp/b", "Atomic move unsupported")
          ) == "/tmp/a: Atomic move unsupported"
        )
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
  )
}

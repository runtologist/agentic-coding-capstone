package snap

import zio.*

/** Phase-A scaffold stub. Replaced by the real CLI dispatch in the foundation packet. */
object Main extends ZIOAppDefault {

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for {
      args <- ZIOAppArgs.getArgs
      _ <-
        if (args.headOption.contains("--version")) Console.printLine("snap 1.0.0")
        else
          Console.printLineError("snap: scaffold only") *> ZIO.fail(
            new Exception("not implemented")
          )
    } yield ()
}

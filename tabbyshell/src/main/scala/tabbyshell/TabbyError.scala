package tabbyshell

sealed abstract class TabbyError(val message: String) extends Exception(message)

object TabbyError {
  final case class Parse(detail: String, column: Int)
      extends TabbyError(s"parse error: $detail at column $column")

  final case class TypeMismatch(command: String, expected: String, got: String)
      extends TabbyError(s"$command: expected $expected, got $got")

  final case class MissingColumn(command: String, column: String)
      extends TabbyError(s"$command: column not found: $column")

  final case class MissingArg(command: String, argument: String)
      extends TabbyError(s"$command: missing required argument: $argument")

  final case class BadArg(command: String, detail: String) extends TabbyError(s"$command: $detail")

  final case class IoError(command: String, osMessage: String)
      extends TabbyError(s"$command: $osMessage")

  final case class ExternalFailed(name: String, status: Int)
      extends TabbyError(s"$name: external command exited with status $status")
}

package tabbyshell

enum Value {
  case VNull
  case VBool(value: Boolean)
  case VInt(value: Long)
  case VFloat(value: Double)
  case VStr(value: String)
  case VFilesize(bytes: Long)
  case VDate(seconds: Long)
  case VList(items: List[Value])
  case VRecord private[tabbyshell] (fields: List[(String, Value)])
  case VTable private[tabbyshell] (columns: List[String], rows: List[List[Value]])
}

object Value {
  import Value.*

  sealed abstract class ConstructionError(val message: String)
      extends Product
      with Serializable

  object ConstructionError {
    final case class DuplicateKey(key: String)
        extends ConstructionError(s"duplicate record key: $key")

    final case class DuplicateColumn(column: String)
        extends ConstructionError(s"duplicate column: $column")

    final case class RaggedTable(expectedColumns: Int, rowIndex: Int, actualColumns: Int)
        extends ConstructionError(
          s"row $rowIndex has $actualColumns columns, expected $expectedColumns"
        )
  }

  /** Smart constructor: records cannot carry duplicate keys. */
  def record(fields: List[(String, Value)]): Either[ConstructionError, VRecord] =
    duplicateKey(fields.map(_._1)) match {
      case Some(key) => Left(ConstructionError.DuplicateKey(key))
      case None      => Right(new VRecord(fields))
    }

  /** Smart constructor: tables must have uniform row widths and unique columns. */
  def table(
      columns: List[String],
      rows: List[List[Value]]
  ): Either[ConstructionError, VTable] =
    duplicateKey(columns) match {
      case Some(column) => Left(ConstructionError.DuplicateColumn(column))
      case None =>
        rows.zipWithIndex.find { case (row, _) => row.length != columns.length } match {
          case Some((row, index)) =>
            Left(ConstructionError.RaggedTable(columns.length, index, row.length))
          case None =>
            Right(new VTable(columns, rows))
        }
    }

  /** Internal use only where validity is guaranteed by construction. */
  private[tabbyshell] def recordTrusted(fields: List[(String, Value)]): VRecord =
    record(fields).fold(
      error => throw new IllegalStateException(s"record invariant violated: ${error.message}"),
      identity
    )

  /** Internal use only where validity is guaranteed by construction. */
  private[tabbyshell] def tableTrusted(
      columns: List[String],
      rows: List[List[Value]]
  ): VTable =
    table(columns, rows).fold(
      error => throw new IllegalStateException(s"table invariant violated: ${error.message}"),
      identity
    )

  private def duplicateKey(keys: List[String]): Option[String] =
    keys.groupBy(identity).collectFirst { case (key, occurrences) if occurrences.size > 1 => key }

  def typeName(value: Value): String = value match {
    case VNull        => "null"
    case VBool(_)     => "bool"
    case VInt(_)      => "int"
    case VFloat(_)    => "float"
    case VStr(_)      => "string"
    case VFilesize(_) => "filesize"
    case VDate(_)     => "date"
    case VList(_)     => "list"
    case VRecord(_)   => "record"
    case VTable(_, _) => "table"
  }

  def isScalar(value: Value): Boolean = value match {
    case VList(_) | VRecord(_) | VTable(_, _) => false
    case _                                    => true
  }

  /** If every record has the same keys in the same order, produce a Table. */
  def tableFromUniformRecords(records: List[VRecord]): Option[VTable] = {
    if (records.isEmpty) return None
    val firstKeys = records.head.fields.map(_._1)
    val uniform = records.forall(_.fields.map(_._1) == firstKeys)
    if (!uniform) None
    else table(firstKeys, records.map(_.fields.map(_._2))).toOption
  }
}

final case class ShellState(
    cwd: String,
    prevCwd: Option[String],
    home: String,
    now: Long,
    color: Boolean
)

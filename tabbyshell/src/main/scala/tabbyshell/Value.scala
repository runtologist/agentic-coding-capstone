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
  case VRecord(fields: List[(String, Value)])
  case VTable(columns: List[String], rows: List[List[Value]])
}

object Value {
  import Value.*

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
    else Some(VTable(firstKeys, records.map(_.fields.map(_._2))))
  }
}

final case class ShellState(
    cwd: String,
    prevCwd: Option[String],
    home: String,
    now: Long,
    color: Boolean
)

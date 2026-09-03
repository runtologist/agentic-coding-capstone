package tabbyshell

import scala.collection.mutable.ListBuffer

object Csv {

  def parse(content: String): Either[String, Value] = {
    val normalized = if (content.startsWith("\uFEFF")) content.drop(1) else content
    val rows = parseRows(normalized)
    if (rows.isEmpty) {
      Right(Value.VTable(List.empty, List.empty))
    } else {
      val columns = rows.head
      val dataRows = rows.drop(1).map { row =>
        val paddedOrTruncated = columns.indices.map { i =>
          row.lift(i).getOrElse("")
        }.toList
        paddedOrTruncated.map(Value.VStr.apply)
      }
      Right(Value.VTable(columns, dataRows))
    }
  }

  def toCsv(table: Value.VTable): String = {
    val sb = new StringBuilder
    sb.append(table.columns.map(escape).mkString(",")).append('\n')
    table.rows.foreach { row =>
      sb.append(row.map(cellText).map(escape).mkString(",")).append('\n')
    }
    sb.toString
  }

  private def parseRows(content: String): List[List[String]] = {
    val rows = ListBuffer.empty[List[String]]
    val fields = ListBuffer.empty[String]
    val field = new StringBuilder
    var inQuotes = false
    var i = 0

    def endField(): Unit = {
      fields += field.toString
      field.clear()
    }

    def endRow(): Unit = {
      endField()
      if (fields.nonEmpty || field.nonEmpty) {
        rows += fields.toList
      }
      fields.clear()
    }

    while (i < content.length) {
      val c = content.charAt(i)
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < content.length && content.charAt(i + 1) == '"') {
            field.append('"')
            i += 2
          } else {
            inQuotes = false
            i += 1
          }
        } else {
          field.append(c)
          i += 1
        }
      } else {
        c match {
          case '"' =>
            inQuotes = true
            i += 1
          case ',' =>
            endField()
            i += 1
          case '\n' =>
            endRow()
            i += 1
          case '\r' =>
            endRow()
            if (i + 1 < content.length && content.charAt(i + 1) == '\n') i += 2
            else i += 1
          case _ =>
            field.append(c)
            i += 1
        }
      }
    }

    if (field.nonEmpty || fields.nonEmpty) {
      endRow()
    }

    rows.toList.filter(_.nonEmpty)
  }

  private def cellText(value: Value): String = value match {
    case Value.VNull        => ""
    case Value.VBool(v)     => v.toString
    case Value.VInt(v)      => v.toString
    case Value.VFloat(v)    => Render.formatFloat(v)
    case Value.VStr(v)      => v
    case Value.VFilesize(v) => v.toString
    case Value.VDate(v)     => v.toString
    case other => Render.inlineRender(other, RenderOpts(color = false, maxColWidth = 40, now = 0L))
  }

  private def escape(field: String): String = {
    if (
      field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')
    ) {
      "\"" + field.replace("\"", "\"\"") + "\""
    } else {
      field
    }
  }
}

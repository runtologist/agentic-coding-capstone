package tabbyshell

import java.util.Locale
import scala.collection.mutable.ListBuffer

final case class RenderOpts(color: Boolean, maxColWidth: Int = 40, now: Long = 0L)

object Render {
  import Value.*

  def output(value: Value, opts: RenderOpts): String = {
    val rendered = render(value, opts)
    value match {
      case VStr(s) if s.endsWith("\n") => rendered
      case _                           => rendered + "\n"
    }
  }

  def render(value: Value, opts: RenderOpts): String = value match {
    case VList(items) => renderList(items, opts)
    case VRecord(fields) =>
      renderTable(
        List("key", "value"),
        fields.map { case (k, v) => List(VStr(k), v) },
        opts
      )
    case VTable(columns, rows) =>
      renderTable(
        "#" :: columns,
        rows.zipWithIndex.map { case (row, i) => VInt(i.toLong) :: row },
        opts
      )
    case scalar => scalarString(scalar, opts)
  }

  def inlineRender(value: Value, opts: RenderOpts): String = value match {
    case VStr(s) => Json.quote(s)
    case VRecord(fields) =>
      fields
        .map { case (k, v) => s"$k: ${inlineRender(v, opts)}" }
        .mkString("{", ", ", "}")
    case VList(items) =>
      items.map(inlineRender(_, opts)).mkString("[", ", ", "]")
    case VTable(columns, rows) =>
      s"<table ${rows.size}\u00d7${columns.size}>"
    case scalar => scalarString(scalar, opts.copy(color = false))
  }

  def scalarString(value: Value, opts: RenderOpts): String = value match {
    case VNull        => ""
    case VBool(v)     => v.toString
    case VInt(v)      => v.toString
    case VFloat(v)    => formatFloat(v)
    case VStr(v)      => v
    case VFilesize(v) => formatFilesize(v)
    case VDate(v)     => formatDate(v, opts.now)
    case composite    => inlineRender(composite, opts)
  }

  def formatFloat(d: Double): String = {
    if (d.isNaN || d.isInfinite) return d.toString
    if (d == 0.0d) return "0"
    val formatted = String.format(Locale.US, "%.4f", d)
    val dot = formatted.indexOf('.')
    if (dot < 0) return formatted
    var end = formatted.length
    while (end > dot + 1 && formatted.charAt(end - 1) == '0') end -= 1
    if (end > dot && formatted.charAt(end - 1) == '.') end -= 1
    val result = formatted.substring(0, end)
    if (result == "-0") "0" else result
  }

  def formatFilesize(bytes: Long): String = {
    val absValue = java.math.BigDecimal.valueOf(bytes).abs()
    val oneThousand = java.math.BigDecimal.valueOf(1000L)
    if (absValue.compareTo(oneThousand) < 0) return s"$bytes B"

    val (divisor, unit) =
      if (absValue.compareTo(java.math.BigDecimal.valueOf(1000000L)) < 0) (1000L, "KB")
      else if (absValue.compareTo(java.math.BigDecimal.valueOf(1000000000L)) < 0) (1000000L, "MB")
      else if (absValue.compareTo(java.math.BigDecimal.valueOf(1000000000000L)) < 0)
        (1000000000L, "GB")
      else (1000000000000L, "TB")

    val scaled = absValue.divide(
      java.math.BigDecimal.valueOf(divisor),
      1,
      java.math.RoundingMode.HALF_UP
    )
    val stripped = scaled.stripTrailingZeros()
    val numberText =
      if (stripped.scale() < 0) stripped.setScale(0).toPlainString
      else stripped.toPlainString
    val sign = if (bytes < 0L && scaled.signum() != 0) "-" else ""
    s"$sign$numberText $unit"
  }

  def formatDate(seconds: Long, now: Long): String = {
    val delta = now - seconds
    if (delta < 0L) isoDate(seconds)
    else if (delta < 60L) "just now"
    else if (delta < 3600L) s"${delta / 60L} minutes ago"
    else if (delta < 86400L) s"${delta / 3600L} hours ago"
    else if (delta < 86400L * 2L) "yesterday"
    else if (delta < 86400L * 30L) s"${delta / 86400L} days ago"
    else if (delta < 86400L * 365L) s"${delta / 86400L / 30L} months ago"
    else isoDate(seconds)
  }

  def isoDate(ts: Long): String = {
    var days = Math.floorDiv(ts, 86400L) + 719468L
    val era = (if (days >= 0L) days else days - 146096L) / 146097L
    val doe = days - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    var y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10L) mp + 3L else mp - 9L
    if (m <= 2L) y += 1L
    f"$y%04d-$m%02d-$d%02d"
  }

  private def renderList(items: List[Value], opts: RenderOpts): String = {
    if (items.forall(isScalar)) {
      val rows = items.zipWithIndex.map { case (item, i) =>
        List(VInt(i.toLong), item)
      }
      renderTable(List("#", "value"), rows, opts)
    } else {
      val records = items.collect { case r: VRecord => r }
      if (items.nonEmpty && records.length == items.length) {
        Value.tableFromUniformRecords(records) match {
          case Some(table) => render(table, opts)
          case None        => renderMixedList(items, opts)
        }
      } else {
        renderMixedList(items, opts)
      }
    }
  }

  private def renderMixedList(items: List[Value], opts: RenderOpts): String =
    items.zipWithIndex
      .map { case (item, i) => s"$i: ${inlineRender(item, opts)}" }
      .mkString("\n")

  private def renderTable(
      headers: List[String],
      rows: List[List[Value]],
      opts: RenderOpts
  ): String = {
    val colCount = headers.length
    val cellTexts: List[List[String]] = rows.map { row =>
      val padded = row.padTo(colCount, VNull)
      padded.map(cellText(_, opts))
    }

    val widths: IndexedSeq[Int] = (0 until colCount).map { i =>
      val headerWidth = displayWidth(headers(i))
      val maxCellWidth =
        if (cellTexts.isEmpty) 0
        else cellTexts.map(row => displayWidth(row(i))).max
      math.min(opts.maxColWidth, math.max(headerWidth, maxCellWidth))
    }

    val rightAlign: IndexedSeq[Boolean] = (0 until colCount).map { i =>
      rows.nonEmpty && rows.forall { row =>
        i < row.length && isNumeric(row(i))
      }
    }

    val headerCells = headers.zipWithIndex.map { case (header, i) =>
      val truncated = truncate(header, widths(i))
      val padded = padText(truncated, widths(i), rightAlign = false)
      styleHeader(s" $padded ", opts)
    }

    val dataRows: List[List[String]] = rows.zip(cellTexts).map { case (rowValues, texts) =>
      val values = rowValues.padTo(colCount, VNull)
      texts.zipWithIndex.map { case (text, i) =>
        val truncated = truncate(text, widths(i))
        val padded = padText(truncated, widths(i), rightAlign(i))
        styleCell(s" $padded ", values(i), headers(i), opts)
      }
    }

    val top = borderLine(widths, "\u256d", "\u252c", "\u256e", "\u2500", opts)
    val headerLine = dataLine(headerCells, opts)
    val separator = borderLine(widths, "\u251c", "\u253c", "\u2524", "\u2500", opts)
    val bottom = borderLine(widths, "\u2570", "\u2534", "\u256f", "\u2500", opts)

    val lines = ListBuffer.empty[String]
    lines += top
    lines += headerLine
    if (dataRows.nonEmpty) {
      lines += separator
      lines ++= dataRows.map(dataLine(_, opts))
    }
    lines += bottom
    lines.mkString("\n")
  }

  private def borderLine(
      widths: IndexedSeq[Int],
      left: String,
      mid: String,
      right: String,
      fill: String,
      opts: RenderOpts
  ): String = {
    val segments = widths.map(w => dim(fill * (w + 2), opts))
    dim(left, opts) + segments.mkString(dim(mid, opts)) + dim(right, opts)
  }

  private def dataLine(cells: List[String], opts: RenderOpts): String =
    dim("\u2502", opts) + cells.mkString(dim("\u2502", opts)) + dim("\u2502", opts)

  private def cellText(value: Value, opts: RenderOpts): String =
    if (isScalar(value)) scalarString(value, opts)
    else inlineRender(value, opts)

  private def isScalar(value: Value): Boolean = Value.isScalar(value)

  private def isNumeric(value: Value): Boolean = value match {
    case VInt(_) | VFloat(_) | VFilesize(_) => true
    case _                                  => false
  }

  private def displayWidth(s: String): Int =
    s.codePointCount(0, s.length)

  private def truncate(s: String, maxWidth: Int): String = {
    if (maxWidth <= 0) return ""
    if (displayWidth(s) <= maxWidth) return s
    if (maxWidth == 1) return "\u2026"
    takeCodePoints(s, maxWidth - 1) + "\u2026"
  }

  private def takeCodePoints(s: String, n: Int): String = {
    val sb = new StringBuilder
    var i = 0
    var count = 0
    while (count < n && i < s.length) {
      val cp = s.codePointAt(i)
      sb.appendAll(Character.toChars(cp).toIndexedSeq)
      i += Character.charCount(cp)
      count += 1
    }
    sb.toString
  }

  private def padText(s: String, width: Int, rightAlign: Boolean): String = {
    val pad = math.max(0, width - displayWidth(s))
    val spaces = " " * pad
    if (rightAlign) spaces + s else s + spaces
  }

  private def styleHeader(text: String, opts: RenderOpts): String =
    if (opts.color) ansi("1;36", text) else text

  private def styleCell(text: String, value: Value, header: String, opts: RenderOpts): String = {
    if (!opts.color) text
    else if (header == "#") dim(text, opts)
    else
      value match {
        case VInt(_) | VFilesize(_) => ansi("32", text)
        case VDate(_)               => ansi("33", text)
        case VBool(true)            => ansi("32", text)
        case VBool(false)           => ansi("31", text)
        case _                      => text
      }
  }

  private def dim(text: String, opts: RenderOpts): String =
    if (opts.color) ansi("2", text) else text

  private def ansi(code: String, text: String): String =
    s"\u001b[${code}m$text\u001b[0m"
}

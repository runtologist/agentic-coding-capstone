package tabbyshell

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{BasicFileAttributes, PosixFilePermission}
import java.nio.file.{Files, LinkOption, Path, Paths}
import scala.jdk.CollectionConverters.*

object Executor {
  import Parser.*
  import Value.*

  def runPipeline(
      pipeline: Pipeline,
      state: ShellState
  ): IO[TabbyError, (Value, ShellState)] = {
    pipeline.commands.foldLeft[IO[TabbyError, (Value, ShellState)]](
      ZIO.succeed((VNull, state))
    ) { (acc, cmd) =>
      acc.flatMap { case (input, st) => executeCommand(cmd, input, st) }
    }
  }

  private def executeCommand(
      cmd: Command,
      input: Value,
      state: ShellState
  ): IO[TabbyError, (Value, ShellState)] = {
    cmd.name match {
      case "ls"      => ls(cmd.args, state).map(v => (v, state))
      case "open"    => openFile(cmd.args, state).map(v => (v, state))
      case "cat"     => cat(cmd.args, state).map(v => (v, state))
      case "pwd"     => ZIO.succeed((VStr(state.cwd), state))
      case "cd"      => cd(cmd.args, state).map(ns => (VNull, ns))
      case "where"   => where(cmd.args, input).map(v => (v, state))
      case "select"  => select(cmd.args, input).map(v => (v, state))
      case "sort-by" => sortBy(cmd.args, input).map(v => (v, state))
      case "first" =>
        firstOrLast("first", cmd.args, input, takeFirst = true).map(v => (v, state))
      case "last" =>
        firstOrLast("last", cmd.args, input, takeFirst = false).map(v => (v, state))
      case "length" => length(input).map(v => (v, state))
      case "get"    => get(cmd.args, input).map(v => (v, state))
      case "to"     => to(cmd.args, input).map(v => (v, state))
      case "save"   => save(cmd.args, input, state).map(v => (v, state))
      case other    => External.run(other, externalArgs(cmd.args), state).map(v => (v, state))
    }
  }

  private def ls(args: List[Arg], state: ShellState): IO[TabbyError, Value] = {
    val showAll = hasFlag(args, "a", "all")
    val showLong = hasFlag(args, "l", "long")

    ZIO
      .fromEither(optionalStringArg("ls", "path", args))
      .flatMap { pathOpt =>
        val rawPath = pathOpt.getOrElse(".")
        val resolved = resolvePath(state, rawPath)
        ZIO
          .attemptBlocking {
            val dir = Paths.get(resolved)
            val stream = Files.list(dir)
            try {
              val names = stream
                .iterator()
                .asScala
                .toList
                .map(_.getFileName.toString)
                .filter(name => showAll || !name.startsWith("."))
                .sorted

              val rows = names.map { name =>
                val entry = dir.resolve(name)
                val attrs = Files.readAttributes(
                  entry,
                  classOf[BasicFileAttributes],
                  LinkOption.NOFOLLOW_LINKS
                )
                val fileType =
                  if (attrs.isSymbolicLink) "symlink"
                  else if (attrs.isDirectory) "dir"
                  else "file"
                val size = if (attrs.isDirectory) 0L else attrs.size()
                val modified = attrs.lastModifiedTime().toMillis / 1000L
                val base: List[Value] = List(
                  VStr(name),
                  VStr(fileType),
                  VFilesize(size),
                  VDate(modified)
                )
                if (showLong) {
                  base ++ List(VStr(posixMode(entry)), VInt(posixUid(entry)))
                } else {
                  base
                }
              }

              val columns =
                if (showLong) List("name", "type", "size", "modified", "mode", "uid")
                else List("name", "type", "size", "modified")

              VTable(columns, rows)
            } finally {
              stream.close()
            }
          }
          .mapError(e => TabbyError.IoError("ls", ioMessage(e)))
      }
  }

  private def openFile(args: List[Arg], state: ShellState): IO[TabbyError, Value] = {
    ZIO
      .fromEither(requiredStringArg("open", "path", args))
      .flatMap { rawPath =>
        val resolved = resolvePath(state, rawPath)
        readFile("open", resolved).flatMap { content =>
          val fileName = Paths.get(resolved).getFileName.toString.toLowerCase
          if (fileName.endsWith(".json")) {
            ZIO
              .fromEither(Json.parse(content))
              .mapError(_ => TabbyError.BadArg("open", "invalid JSON"))
          } else if (fileName.endsWith(".csv")) {
            ZIO
              .fromEither(Csv.parse(content))
              .mapError(msg => TabbyError.BadArg("open", msg))
          } else {
            ZIO.succeed(VStr(content))
          }
        }
      }
  }

  private def cat(args: List[Arg], state: ShellState): IO[TabbyError, Value] = {
    ZIO
      .fromEither(requiredStringArg("cat", "path", args))
      .flatMap { rawPath =>
        val resolved = resolvePath(state, rawPath)
        readFile("cat", resolved).map(VStr.apply)
      }
  }

  private def cd(args: List[Arg], state: ShellState): IO[TabbyError, ShellState] = {
    val nonFlags = nonFlagArgs(args)
    nonFlags match {
      case Nil =>
        changeDir(state, state.home)
      case List(Arg.Dash) =>
        state.prevCwd match {
          case Some(prev) =>
            ZIO.succeed(state.copy(cwd = prev, prevCwd = Some(state.cwd)))
          case None =>
            ZIO.fail(TabbyError.BadArg("cd", "no previous directory"))
        }
      case List(arg) =>
        ZIO.fromEither(stringFromArg("cd", "path", arg)).flatMap(changeDir(state, _))
      case _ =>
        ZIO.fail(TabbyError.BadArg("cd", "too many arguments"))
    }
  }

  private def changeDir(state: ShellState, rawPath: String): IO[TabbyError, ShellState] = {
    val resolved = resolvePath(state, rawPath)
    ZIO
      .attemptBlocking {
        val path = Paths.get(resolved)
        if (!Files.exists(path)) {
          Left(TabbyError.IoError("cd", s"$rawPath: No such file or directory"))
        } else if (!Files.isDirectory(path)) {
          Left(TabbyError.BadArg("cd", s"not a directory: $rawPath"))
        } else {
          Right(state.copy(cwd = path.toAbsolutePath.normalize.toString, prevCwd = Some(state.cwd)))
        }
      }
      .mapError(e => TabbyError.IoError("cd", ioMessage(e)))
      .flatMap(ZIO.fromEither)
  }

  private def where(args: List[Arg], input: Value): IO[TabbyError, Value] = {
    val nonFlags = nonFlagArgs(args)
    val result = for {
      _ <- checkExactArgCount("where", nonFlags, 3, "column op literal")
      column <- nonFlags.lift(0) match {
        case Some(Arg.Bare(name)) => Right(name)
        case _ => Left(TabbyError.BadArg("where", "column must be a bare identifier"))
      }
      op <- nonFlags.lift(1) match {
        case Some(Arg.Op(value)) => Right(value)
        case _                   => Left(TabbyError.BadArg("where", "expected comparison operator"))
      }
      literal <- nonFlags.lift(2) match {
        case Some(Arg.Lit(value)) => Right(literalToValue(value))
        case _                    => Left(TabbyError.BadArg("where", "expected literal value"))
      }
      table <- asTable("where", input)
      columnIndex <- table.columns.indexOf(column) match {
        case -1 => Left(TabbyError.MissingColumn("where", column))
        case i  => Right(i)
      }
      filtered <- filterRows(table.rows, columnIndex, op, literal)
    } yield VTable(table.columns, filtered)

    ZIO.fromEither(result)
  }

  private def filterRows(
      rows: List[List[Value]],
      columnIndex: Int,
      op: String,
      literal: Value
  ): Either[TabbyError, List[List[Value]]] = {
    rows.foldLeft[Either[TabbyError, List[List[Value]]]](Right(Nil)) { (accE, row) =>
      accE.flatMap { acc =>
        val cell = row.lift(columnIndex).getOrElse(VNull)
        compareForWhere(cell, literal, op).map { keep =>
          if (keep) acc :+ row else acc
        }
      }
    }
  }

  private def compareForWhere(
      cell: Value,
      literal: Value,
      op: String
  ): Either[TabbyError, Boolean] = {
    val equalityOnly = op == "==" || op == "!="
    val comparison: Either[TabbyError, Int] = (cell, literal) match {
      case (a, b) if numericValue(a).isDefined && numericValue(b).isDefined =>
        Right(compareNumeric(a, b))
      case (VStr(a), VStr(b)) =>
        Right(a.compareTo(b))
      case (VDate(a), VDate(b)) =>
        Right(java.lang.Long.compare(a, b))
      case (VBool(a), VBool(b)) =>
        if (equalityOnly) Right(java.lang.Boolean.compare(a, b))
        else Left(TabbyError.TypeMismatch("where", "bool with == or !=", Value.typeName(cell)))
      case (VNull, VNull) =>
        if (equalityOnly) Right(0)
        else Left(TabbyError.TypeMismatch("where", "null with == or !=", Value.typeName(cell)))
      case _ =>
        Left(TabbyError.TypeMismatch("where", Value.typeName(literal), Value.typeName(cell)))
    }

    comparison.map(cmp => applyComparison(op, cmp))
  }

  private def applyComparison(op: String, comparison: Int): Boolean = op match {
    case "==" => comparison == 0
    case "!=" => comparison != 0
    case "<"  => comparison < 0
    case "<=" => comparison <= 0
    case ">"  => comparison > 0
    case ">=" => comparison >= 0
    case _    => false
  }

  private def select(args: List[Arg], input: Value): IO[TabbyError, Value] = {
    val nonFlags = nonFlagArgs(args)
    if (nonFlags.isEmpty) {
      ZIO.fail(TabbyError.MissingArg("select", "columns"))
    } else {
      val result = for {
        columns <- nonFlags.foldLeft[Either[TabbyError, List[String]]](Right(Nil)) { (accE, arg) =>
          accE.flatMap { acc =>
            stringFromArg("select", "column", arg).map(acc :+ _)
          }
        }
        table <- asTable("select", input)
        indices <- columns.foldLeft[Either[TabbyError, List[Int]]](Right(Nil)) { (accE, column) =>
          accE.flatMap { acc =>
            table.columns.indexOf(column) match {
              case -1 => Left(TabbyError.MissingColumn("select", column))
              case i  => Right(acc :+ i)
            }
          }
        }
      } yield {
        val rows = table.rows.map { row =>
          indices.map(i => row.lift(i).getOrElse(VNull))
        }
        VTable(columns, rows)
      }
      ZIO.fromEither(result)
    }
  }

  private def sortBy(args: List[Arg], input: Value): IO[TabbyError, Value] = {
    val reverse = hasFlag(args, "r", "reverse")
    val nonFlags = nonFlagArgs(args)

    val result = for {
      _ <- checkExactArgCount("sort-by", nonFlags, 1, "column")
      column <- stringFromArg("sort-by", "column", nonFlags.head)
      table <- asTable("sort-by", input)
      columnIndex <- table.columns.indexOf(column) match {
        case -1 => Left(TabbyError.MissingColumn("sort-by", column))
        case i  => Right(i)
      }
      sortedRows <- sortRows(table.rows, columnIndex, reverse)
    } yield table.copy(rows = sortedRows)

    ZIO.fromEither(result)
  }

  private def sortRows(
      rows: List[List[Value]],
      columnIndex: Int,
      reverse: Boolean
  ): Either[TabbyError, List[List[Value]]] = {
    if (rows.isEmpty) return Right(rows)

    val values = rows.map(row => row.lift(columnIndex).getOrElse(VNull))

    def category(value: Value): Int = value match {
      case VInt(_) | VFloat(_) | VFilesize(_) => 0
      case VStr(_)                            => 1
      case VDate(_)                           => 2
      case VBool(_)                           => 3
      case VNull                              => 4
      case _                                  => 5
    }

    val firstCategory = category(values.head)
    if (firstCategory == 5) {
      return Left(
        TabbyError.TypeMismatch("sort-by", "scalar", Value.typeName(values.head))
      )
    }

    values.find(value => category(value) != firstCategory) match {
      case Some(bad) =>
        Left(TabbyError.TypeMismatch("sort-by", Value.typeName(values.head), Value.typeName(bad)))
      case None =>
        val indexed = rows.zipWithIndex
        val sorted = indexed.sortWith { case ((rowA, indexA), (rowB, indexB)) =>
          val a = rowA.lift(columnIndex).getOrElse(VNull)
          val b = rowB.lift(columnIndex).getOrElse(VNull)
          val comparison = compareSortValues(a, b, firstCategory)
          if (comparison != 0) comparison < 0
          else indexA < indexB
        }
        val ordered = if (reverse) sorted.reverse else sorted
        Right(ordered.map(_._1))
    }
  }

  private def compareSortValues(a: Value, b: Value, category: Int): Int = category match {
    case 0 =>
      compareNumeric(a, b)
    case 1 =>
      (a, b) match {
        case (VStr(left), VStr(right)) => left.compareTo(right)
        case _                         => 0
      }
    case 2 =>
      (a, b) match {
        case (VDate(left), VDate(right)) => java.lang.Long.compare(left, right)
        case _                           => 0
      }
    case 3 =>
      (a, b) match {
        case (VBool(left), VBool(right)) => java.lang.Boolean.compare(left, right)
        case _                           => 0
      }
    case _ => 0
  }

  private def firstOrLast(
      command: String,
      args: List[Arg],
      input: Value,
      takeFirst: Boolean
  ): IO[TabbyError, Value] = {
    val nonFlags = nonFlagArgs(args)
    val countEither: Either[TabbyError, Option[Long]] = nonFlags match {
      case Nil =>
        Right(None)
      case List(Arg.Lit(Literal.LInt(n))) =>
        Right(Some(n))
      case List(_) =>
        Left(TabbyError.BadArg(command, "expected integer argument"))
      case _ =>
        Left(TabbyError.BadArg(command, "too many arguments"))
    }

    val result = countEither.flatMap { countOpt =>
      input match {
        case VTable(columns, rows) =>
          countOpt match {
            case None =>
              val chosen = if (takeFirst) rows.headOption else rows.lastOption
              chosen match {
                case Some(row) => Right(VRecord(columns.zip(row)))
                case None      => Left(TabbyError.BadArg(command, "input is empty"))
              }
            case Some(count) =>
              if (count < 0L) Left(TabbyError.BadArg(command, "count must be non-negative"))
              else {
                val n = clampToInt(count)
                val selected = if (takeFirst) rows.take(n) else rows.takeRight(n)
                Right(VTable(columns, selected))
              }
          }
        case VList(items) =>
          countOpt match {
            case None =>
              val chosen = if (takeFirst) items.headOption else items.lastOption
              chosen match {
                case Some(item) => Right(item)
                case None       => Left(TabbyError.BadArg(command, "input is empty"))
              }
            case Some(count) =>
              if (count < 0L) Left(TabbyError.BadArg(command, "count must be non-negative"))
              else {
                val n = clampToInt(count)
                val selected = if (takeFirst) items.take(n) else items.takeRight(n)
                Right(VList(selected))
              }
          }
        case other =>
          Left(TabbyError.TypeMismatch(command, "table or list", Value.typeName(other)))
      }
    }

    ZIO.fromEither(result)
  }

  private def length(input: Value): IO[TabbyError, Value] = input match {
    case VTable(_, rows) => ZIO.succeed(VInt(rows.size.toLong))
    case VList(items)    => ZIO.succeed(VInt(items.size.toLong))
    case VStr(text)      => ZIO.succeed(VInt(text.codePointCount(0, text.length).toLong))
    case VNull           => ZIO.succeed(VInt(0L))
    case other =>
      ZIO.fail(
        TabbyError.TypeMismatch("length", "table, list, string, or null", Value.typeName(other))
      )
  }

  private def get(args: List[Arg], input: Value): IO[TabbyError, Value] = {
    val nonFlags = nonFlagArgs(args)
    val result = for {
      _ <- checkExactArgCount("get", nonFlags, 1, "column")
      column <- stringFromArg("get", "column", nonFlags.head)
      value <- input match {
        case VTable(columns, rows) =>
          columns.indexOf(column) match {
            case -1 => Left(TabbyError.MissingColumn("get", column))
            case i  => Right(VList(rows.map(row => row.lift(i).getOrElse(VNull))))
          }
        case VRecord(fields) =>
          fields.find(_._1 == column) match {
            case Some((_, fieldValue)) => Right(fieldValue)
            case None                  => Left(TabbyError.MissingColumn("get", column))
          }
        case other =>
          Left(TabbyError.TypeMismatch("get", "table or record", Value.typeName(other)))
      }
    } yield value

    ZIO.fromEither(result)
  }

  private def to(args: List[Arg], input: Value): IO[TabbyError, Value] = {
    val nonFlags = nonFlagArgs(args)
    val result = for {
      _ <- checkExactArgCount("to", nonFlags, 1, "format")
      format <- stringFromArg("to", "format", nonFlags.head)
      value <- format.toLowerCase match {
        case "json" =>
          Right(VStr(Json.pretty(input)))
        case "csv" =>
          input match {
            case table: VTable => Right(VStr(Csv.toCsv(table)))
            case other =>
              Left(TabbyError.TypeMismatch("to", "table", Value.typeName(other)))
          }
        case otherFormat =>
          Left(TabbyError.BadArg("to", s"unsupported format: $otherFormat"))
      }
    } yield value

    ZIO.fromEither(result)
  }

  private def save(args: List[Arg], input: Value, state: ShellState): IO[TabbyError, Value] = {
    ZIO
      .fromEither(requiredStringArg("save", "path", args))
      .flatMap { rawPath =>
        val resolved = resolvePath(state, rawPath)
        val content = saveContent(input, resolved, state)
        ZIO
          .attemptBlocking {
            val path = Paths.get(resolved)
            Option(path.getParent).foreach(Files.createDirectories(_))
            Files.write(path, content.getBytes(StandardCharsets.UTF_8))
          }
          .mapError(e => TabbyError.IoError("save", ioMessage(e)))
          .as(VNull)
      }
  }

  private def saveContent(input: Value, resolvedPath: String, state: ShellState): String = {
    val lower = resolvedPath.toLowerCase
    if (lower.endsWith(".json")) {
      Json.pretty(input)
    } else if (lower.endsWith(".csv") && input.isInstanceOf[VTable]) {
      Csv.toCsv(input.asInstanceOf[VTable])
    } else {
      input match {
        case VStr(text) => text
        case other =>
          Render.output(other, RenderOpts(color = false, maxColWidth = 40, now = state.now))
      }
    }
  }

  private def externalArgs(args: List[Arg]): List[String] =
    args.map(externalArgString)

  private def externalArgString(arg: Arg): String = arg match {
    case Arg.Bare(value) => value
    case Arg.Op(value)   => value
    case Arg.Dash        => "-"
    case Arg.Lit(lit)    => literalToRawString(lit)
    case Arg.Flag(name, value) =>
      val prefix = if (name.length == 1) "-" else "--"
      value match {
        case Some(lit) => s"$prefix$name=${literalToRawString(lit)}"
        case None      => s"$prefix$name"
      }
  }

  private def literalToRawString(literal: Literal): String = literal match {
    case Literal.LStr(value)   => value
    case Literal.LInt(value)   => value.toString
    case Literal.LFloat(value) => value.toString
    case Literal.LBool(value)  => value.toString
    case Literal.LNull         => "null"
    case Literal.LFilesize(v)  => s"${v}b"
  }

  private def literalToValue(literal: Literal): Value = literal match {
    case Literal.LStr(value)   => VStr(value)
    case Literal.LInt(value)   => VInt(value)
    case Literal.LFloat(value) => VFloat(value)
    case Literal.LBool(value)  => VBool(value)
    case Literal.LNull         => VNull
    case Literal.LFilesize(v)  => VFilesize(v)
  }

  private def numericValue(value: Value): Option[BigDecimal] = value match {
    case VInt(n)      => Some(BigDecimal(n))
    case VFloat(d)    => Some(BigDecimal(d))
    case VFilesize(b) => Some(BigDecimal(b))
    case _            => None
  }

  private def compareNumeric(a: Value, b: Value): Int = (a, b) match {
    case (VInt(x), VInt(y))           => java.lang.Long.compare(x, y)
    case (VFilesize(x), VFilesize(y)) => java.lang.Long.compare(x, y)
    case (VInt(x), VFilesize(y))      => java.lang.Long.compare(x, y)
    case (VFilesize(x), VInt(y))      => java.lang.Long.compare(x, y)
    case _ =>
      (numericValue(a), numericValue(b)) match {
        case (Some(x), Some(y)) => x.compare(y)
        case _                  => 0
      }
  }

  private def asTable(command: String, input: Value): Either[TabbyError, VTable] = input match {
    case table: VTable => Right(table)
    case other         => Left(TabbyError.TypeMismatch(command, "table", Value.typeName(other)))
  }

  private def nonFlagArgs(args: List[Arg]): List[Arg] =
    args.filterNot(isFlag)

  private def isFlag(arg: Arg): Boolean = arg match {
    case Arg.Flag(_, _) => true
    case _              => false
  }

  private def hasFlag(args: List[Arg], shortName: String, longName: String): Boolean =
    args.exists {
      case Arg.Flag(name, _) => name == shortName || name == longName
      case _                 => false
    }

  private def checkExactArgCount(
      command: String,
      args: List[Arg],
      expected: Int,
      argumentName: String
  ): Either[TabbyError, Unit] = {
    if (args.length < expected) Left(TabbyError.MissingArg(command, argumentName))
    else if (args.length > expected) Left(TabbyError.BadArg(command, "too many arguments"))
    else Right(())
  }

  private def optionalStringArg(
      command: String,
      argumentName: String,
      args: List[Arg]
  ): Either[TabbyError, Option[String]] = {
    val nonFlags = nonFlagArgs(args)
    nonFlags match {
      case Nil => Right(None)
      case List(arg) =>
        stringFromArg(command, argumentName, arg).map(Some(_))
      case _ => Left(TabbyError.BadArg(command, "too many arguments"))
    }
  }

  private def requiredStringArg(
      command: String,
      argumentName: String,
      args: List[Arg]
  ): Either[TabbyError, String] = {
    optionalStringArg(command, argumentName, args).flatMap {
      case Some(value) => Right(value)
      case None        => Left(TabbyError.MissingArg(command, argumentName))
    }
  }

  private def stringFromArg(
      command: String,
      argumentName: String,
      arg: Arg
  ): Either[TabbyError, String] = arg match {
    case Arg.Bare(value)              => Right(value)
    case Arg.Lit(Literal.LStr(value)) => Right(value)
    case Arg.Dash                     => Right("-")
    case _ =>
      Left(TabbyError.BadArg(command, s"$argumentName must be a string"))
  }

  private def resolvePath(state: ShellState, rawPath: String): String = {
    val expanded =
      if (rawPath == "~") state.home
      else if (rawPath.startsWith("~/")) state.home + rawPath.substring(1)
      else rawPath

    val path = Paths.get(expanded)
    val absolute =
      if (path.isAbsolute) path
      else Paths.get(state.cwd).resolve(path)
    absolute.normalize().toString
  }

  private def readFile(command: String, path: String): IO[TabbyError, String] = {
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
      .mapError(e => TabbyError.IoError(command, ioMessage(e)))
  }

  private def posixMode(path: Path): String = {
    try {
      val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).asScala
      def token(permission: PosixFilePermission, letter: Char): Char =
        if (permissions.contains(permission)) letter else '-'

      new String(
        Array(
          token(PosixFilePermission.OWNER_READ, 'r'),
          token(PosixFilePermission.OWNER_WRITE, 'w'),
          token(PosixFilePermission.OWNER_EXECUTE, 'x'),
          token(PosixFilePermission.GROUP_READ, 'r'),
          token(PosixFilePermission.GROUP_WRITE, 'w'),
          token(PosixFilePermission.GROUP_EXECUTE, 'x'),
          token(PosixFilePermission.OTHERS_READ, 'r'),
          token(PosixFilePermission.OTHERS_WRITE, 'w'),
          token(PosixFilePermission.OTHERS_EXECUTE, 'x')
        )
      )
    } catch {
      case _: Throwable => "?????????"
    }
  }

  private def posixUid(path: Path): Long = {
    try {
      Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) match {
        case number: java.lang.Number => number.longValue()
        case _                        => 0L
      }
    } catch {
      case _: Throwable => 0L
    }
  }

  private def clampToInt(value: Long): Int = {
    if (value > Int.MaxValue.toLong) Int.MaxValue
    else if (value < Int.MinValue.toLong) Int.MinValue
    else value.toInt
  }

  private def ioMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

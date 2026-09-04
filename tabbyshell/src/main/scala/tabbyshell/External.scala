package tabbyshell

import zio.{Console => ZConsole, IO, System => ZSystem, Task, UIO, ZIO}

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.jdk.CollectionConverters.*

object External {
  import Value.*

  private val esc = "\u001b"

  def run(name: String, args: List[String], state: ShellState): IO[TabbyError, Value] =
    runWithEnv(name, args, state)(key => ZSystem.env(key).orDie)

  /** [[run]] with the environment reads injected so tests can exercise the success/fallback paths
    * deterministically without touching the real environment.
    */
  private[tabbyshell] def runWithEnv(
      name: String,
      args: List[String],
      state: ShellState
  )(env: String => UIO[Option[String]]): IO[TabbyError, Value] = {
    for {
      result <- ZIO
        .attemptBlocking {
          val builder = new ProcessBuilder((name :: args).asJava)
          builder.directory(new File(state.cwd))
          builder.redirectError(ProcessBuilder.Redirect.DISCARD)
          val process = builder.start()
          process.getOutputStream.close()
          val stdout = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
          val exit = process.waitFor()
          (exit, stdout)
        }
        .mapError(e => TabbyError.IoError(name, TabbyError.ioMessage(e)))
      (exit, stdout) = result
      value <-
        if (exit != 0) ZIO.fail(TabbyError.ExternalFailed(name, exit))
        else formatWithEnv(name, args, stdout, state)(env)
    } yield value
  }

  private[tabbyshell] def formatWithEnv(
      name: String,
      args: List[String],
      stdout: String,
      state: ShellState
  )(env: String => UIO[Option[String]]): IO[TabbyError, Value] = {
    for {
      disabledEnv <- env("TABBY_DISABLE_AI")
      apiKeyEnv <- env("OPENROUTER_API_KEY")
      baseUrlEnv <- env("OPENROUTER_BASE_URL")
      value <- {
        val disabled = disabledEnv.exists(_.nonEmpty)
        val apiKey = apiKeyEnv.filter(_.nonEmpty)
        val baseUrl = baseUrlEnv.filter(_.nonEmpty)

        if (disabled) {
          fallback(stdout, "disabled", state.color)
        } else {
          apiKey match {
            case None =>
              fallback(stdout, "no API key", state.color)
            case Some(key) =>
              callAi(name, args, stdout, key, baseUrl)
                .catchAll(_ => fallback(stdout, "ai request failed", state.color))
          }
        }
      }
    } yield value
  }

  private def callAi(
      name: String,
      args: List[String],
      stdout: String,
      apiKey: String,
      baseUrl: Option[String]
  ): Task[Value] = {
    ZIO.attemptBlocking {
      val url = resolveUrl(baseUrl)
      val body = buildRequestBody(name, args, stdout)
      val client = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer $apiKey")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()
      val response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RuntimeException(s"HTTP ${response.statusCode()}")
      }
      parseAiResponse(response.body()) match {
        case Right(value)  => value
        case Left(message) => throw new RuntimeException(message)
      }
    }
  }

  private[tabbyshell] def fallbackMessage(reason: String, color: Boolean): String = {
    val message = s"(ai formatting unavailable: $reason)"
    if (color) s"$esc[2m$message$esc[0m" else message
  }

  private[tabbyshell] def fallback(stdout: String, reason: String, color: Boolean): UIO[Value] =
    ZConsole.printLineError(fallbackMessage(reason, color)).orDie.as(VStr(stdout.stripTrailing()))

  private[tabbyshell] def resolveUrl(baseUrl: Option[String]): String = {
    baseUrl match {
      case None =>
        "https://openrouter.ai/api/v1/chat/completions"
      case Some(raw) =>
        val cleaned = raw.stripSuffix("/")
        if (cleaned.endsWith("/chat/completions")) cleaned
        else if (cleaned.endsWith("/v1")) cleaned + "/chat/completions"
        else cleaned + "/api/v1/chat/completions"
    }
  }

  private[tabbyshell] def buildRequestBody(
      name: String,
      args: List[String],
      stdout: String
  ): String = {
    val systemPrompt =
      """You convert raw command output into structured data for a typed shell.
        |Reply with ONLY a JSON object, no prose, no markdown fences.
        |
        |Schema:
        |  {"kind":"table","columns":["..."],"rows":[["...", "..."]]}
        |  {"kind":"string","value":"..."}
        |
        |All cells in "rows" are strings. Use "table" only when the output has
        |clear tabular structure with consistent columns. Otherwise use "string"
        |with the cleaned-up text.""".stripMargin

    val commandLine = (name :: args).mkString(" ")
    val userPrompt = s"command: $commandLine\n\noutput:\n$stdout"

    s"""{"model":"google/gemini-2.5-flash-lite","temperature":0,"messages":[{"role":"system","content":${Json
        .quote(systemPrompt)}},{"role":"user","content":${Json.quote(userPrompt)}}]}"""
  }

  private[tabbyshell] def parseAiResponse(body: String): Either[String, Value] = {
    val cleaned = stripFences(body)
    for {
      parsed <- Json.parse(cleaned)
      kind <- getField(parsed, "kind") match {
        case Some(VStr(value)) => Right(value)
        case _                 => Left("missing kind")
      }
      value <- kind match {
        case "string" =>
          getField(parsed, "value") match {
            case Some(VStr(text)) => Right(VStr(text))
            case _                => Left("missing string value")
          }
        case "table" =>
          for {
            columnsValue <- getField(parsed, "columns").toRight("missing columns")
            columns <- columnsValue match {
              case VList(items) => parseStringList(items)
              case _            => Left("columns must be an array")
            }
            rowsValue <- getField(parsed, "rows").toRight("missing rows")
            rows <- rowsValue match {
              case VList(rowItems) => parseRows(rowItems)
              case _               => Left("rows must be an array")
            }
            table <- Value.table(columns, rows).left.map(_.message)
          } yield table
        case _ => Left(s"unknown kind: $kind")
      }
    } yield value
  }

  private def parseStringList(items: List[Value]): Either[String, List[String]] = {
    items.foldRight[Either[String, List[String]]](Right(Nil)) { (item, acc) =>
      (item, acc) match {
        case (VStr(s), Right(list)) => Right(s :: list)
        case (_, Left(error))       => Left(error)
        case _                      => Left("columns must be strings")
      }
    }
  }

  private def parseRows(items: List[Value]): Either[String, List[List[Value]]] = {
    items.foldRight[Either[String, List[List[Value]]]](Right(Nil)) { (item, acc) =>
      (item, acc) match {
        case (VList(cells), Right(rows)) =>
          parseStringList(cells) match {
            case Right(row)  => Right(row.map(s => VStr(s)) :: rows)
            case Left(error) => Left(error)
          }
        case (_, Left(error)) => Left(error)
        case _                => Left("rows must be arrays of strings")
      }
    }
  }

  private[tabbyshell] def getField(value: Value, name: String): Option[Value] = value match {
    case VRecord(fields) => fields.find(_._1 == name).map(_._2)
    case _               => None
  }

  private[tabbyshell] def stripFences(text: String): String = {
    val trimmed = text.trim
    if (trimmed.startsWith("```")) {
      val firstNewline = trimmed.indexOf('\n')
      val withoutOpeningFence =
        if (firstNewline >= 0) trimmed.substring(firstNewline + 1)
        else trimmed.drop(3)
      val trimmedBody = withoutOpeningFence.trim
      if (trimmedBody.endsWith("```")) trimmedBody.stripSuffix("```").trim
      else trimmedBody
    } else {
      trimmed
    }
  }
}

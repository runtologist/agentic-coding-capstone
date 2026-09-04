package snap

import zio.*
import zio.http.*

import java.net.MalformedURLException
import java.nio.charset.StandardCharsets

/** HTTP repository fetch (SPEC §9; CONTRACT §12).
  *
  * When a repository operand starts with `http://` or `https://`, Snap performs exactly one GET of
  * that exact URL, requires status 200, parses the body as a repository value and validates it
  * normally. Redirects are NOT followed: any non-200 status (including 3xx) fails with
  * [[SnapError.HttpStatus]], whose detail contains `HTTP <status>` (test 13 pins `HTTP 302`).
  * Transport failures and timeouts map to [[SnapError.IoFailure]]. HTTP is read-only; no local
  * mutation happens here (that is the command layer's job).
  */
object HttpFetch {

  private val RequestTimeout: Duration = 30.seconds

  private final case class HttpStatusException(status: Int) extends RuntimeException

  private final case class RequestTimedOut(url: String, timeout: Duration) extends RuntimeException

  /** One GET of the exact URL; no redirects, no retries. */
  def fetchRepository(url: String): ZIO[Any, SnapError, Model.Repository] = {
    val fetch: ZIO[Client, Throwable, Model.Repository] =
      for {
        parsedUrl <- ZIO.fromEither(URL.decode(url))
        response <- Client.batched(Request.get(parsedUrl))
        _ <- ZIO
          .when(response.status != Status.Ok)(
            ZIO.fail(HttpStatusException(response.status.code))
          )
        body <- response.body.asString(StandardCharsets.UTF_8)
        repo <- ZIO.fromEither(Json.parseRepository(body))
        _ <- ZIO.fromEither(Codec.validateRepository(repo))
      } yield repo

    fetch
      .timeout(RequestTimeout)
      .flatMap {
        case Some(repo) => ZIO.succeed(repo)
        case None       => ZIO.fail(RequestTimedOut(url, RequestTimeout))
      }
      .provide(Client.default)
      .mapError(toSnapError(url, _))
  }

  /** True when a repository operand is an HTTP URL (SPEC §9). */
  def isHttpUrl(operand: String): Boolean =
    operand.startsWith("http://") || operand.startsWith("https://")

  private def toSnapError(url: String, t: Throwable): SnapError = t match {
    case e: SnapError                => e
    case HttpStatusException(status) => SnapError.HttpStatus(status, url)
    case RequestTimedOut(u, timeout) =>
      SnapError.IoFailure(s"HTTP request timed out after ${timeout.toSeconds} seconds: $u")
    case e: MalformedURLException =>
      SnapError.IoFailure(s"invalid URL: $url")
    case other =>
      SnapError.IoFailure(Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
  }
}

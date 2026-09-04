package snap

import zio.*
import zio.http.*

import java.nio.charset.StandardCharsets

/** Loopback-only HTTP server exposing one immutable repository snapshot (SPEC §7.9, §9; CONTRACT
  * §12).
  *
  * The snapshot body is computed once at startup by the caller (`Json.writeRepository`) and served
  * byte-for-byte; later commits must not change what is served (test 12). Routing is a single
  * catch-all with manual dispatch so the behavior is exact and independent of route-table
  * semantics:
  *
  *   - `GET /repository.json` with no query → 200, `Content-Type: application/json; charset=utf-8`,
  *     body = snapshot;
  *   - `HEAD /repository.json` with no query → 200, same headers as GET including Content-Length,
  *     but zero body bytes (SPEC §9, ruling H);
  *   - any other method on that exact path without query → 405 + `Allow: GET, HEAD`;
  *   - any other path, or any query string on the resource path → 404.
  *
  * Content-Type and Allow are emitted as raw headers so the wire values match the harness
  * byte-for-byte (lowercase `utf-8`, `GET, HEAD`).
  *
  * Signal handling and URL printing belong to the command layer (L7); [[serve]] installs the server
  * into the ambient [[Scope]], yields the actual bound port, and keeps serving until the scope
  * closes (interruption → graceful shutdown, exit 0 when wired by L7).
  */
object HttpServe {

  private val ResourcePath = "/repository.json"

  private val JsonContentType: Headers =
    Headers("Content-Type", "application/json; charset=utf-8")

  private val AllowGetHead: Headers = Headers("Allow", "GET, HEAD")

  /** Routes serving a single pre-serialized repository snapshot. */
  private[snap] def snapshotRoutes(body: String): Routes[Any, Response] = {
    val bodyBytes = body.getBytes(StandardCharsets.UTF_8)
    val responder = handler { (_: Path, req: Request) =>
      val exactResource = req.url.path.encode == ResourcePath && req.url.queryParams.isEmpty
      if (!exactResource) Response.status(Status.NotFound)
      else
        req.method match {
          case Method.GET =>
            Response(
              status = Status.Ok,
              headers = JsonContentType,
              body = Body.fromArray(bodyBytes)
            )
          case Method.HEAD =>
            // SPEC §9: HEAD returns the same status and headers as GET without a body. The
            // explicit Content-Length mirrors the GET body length; zio-http preserves it.
            Response(
              status = Status.Ok,
              headers = JsonContentType ++ Headers("Content-Length", bodyBytes.length.toString),
              body = Body.empty
            )
          case _ =>
            Response(status = Status.MethodNotAllowed, headers = AllowGetHead, body = Body.empty)
        }
    }
    Routes(RoutePattern.any -> responder)
  }

  /** Install the snapshot server bound to `127.0.0.1` at `port` (0 → OS-assigned) in the ambient
    * scope and yield the actual bound port. The server keeps running until the scope closes.
    *
    * Bind failures (e.g. port already in use) surface inside zio-http as defects, so they are
    * sandboxed and re-raised as [[SnapError.IoFailure]] — a typed, exit-1 error (E4-P1).
    */
  def serve(body: String, port: Model.Port): ZIO[Scope, SnapError, Int] = {
    val launch: ZIO[Scope, Throwable, Int] =
      for {
        serverEnv <- Server.defaultWith(_.binding("127.0.0.1", port)).build
        boundPort <- Server.install(snapshotRoutes(body)).provideEnvironment(serverEnv)
      } yield boundPort

    launch
      .mapError(t => SnapError.IoFailure(s"failed to start HTTP server: ${messageOf(t)}"))
      .sandbox
      .catchAll { cause =>
        cause.failureOrCause match {
          case Left(err) => ZIO.fail(err) // already a typed SnapError from mapError
          case Right(defects) =>
            if (defects.isInterruptedOnly) ZIO.refailCause(defects)
            else
              ZIO.fail(
                SnapError.IoFailure(s"failed to start HTTP server: ${messageOf(defects.squash)}")
              )
        }
      }
  }

  /** Convenience for L7: serialize the repository once and serve that immutable snapshot. */
  def serveSnapshot(repo: Model.Repository, port: Model.Port): ZIO[Scope, SnapError, Int] =
    serve(Json.writeRepository(repo), port)

  private def messageOf(t: Throwable): String =
    Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
}

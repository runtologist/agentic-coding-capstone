package snap

import zio.*
import zio.http.*
import zio.test.*

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Unit tests for the HTTP edge (SPEC §7.9/§9, CONTRACT §12), pinned against harness cases 12, 13,
  * and 26. Every server binds 127.0.0.1 on an OS-assigned port.
  */
object HttpSpec extends ZIOSpecDefault {

  // ---------------------------------------------------------------------------
  // Fixtures — the test-12 golden repository
  // ---------------------------------------------------------------------------

  private def contributor(s: String): Model.ContributorId =
    Model.ContributorId.parse(s).fold(e => throw new IllegalStateException(e.detail), identity)

  private val goldenRepo: Model.Repository = {
    val author = contributor("a@x")
    Model.Repository(
      frontier = Model.Version(Vector(author -> 1L)),
      patches = Vector(
        Model.Patch(
          author = author,
          revision = 1L,
          base = Model.Version(Vector.empty),
          message = "one",
          changes = Vector(
            Model.Change.Text("file.txt", Vector(Model.EditOp.Insert(Vector("one\n"))))
          )
        )
      )
    )
  }

  private val goldenBody: String = Json.writeRepository(goldenRepo)

  private val invalidRepoJson: String =
    """{"format":1,"frontier":[],"patches":[],"bad":true}"""

  private val port0: Model.Port =
    Model.Port.parse("0").fold(e => throw new IllegalStateException(e.detail), identity)

  private def urlOf(port: Int, path: String): String = s"http://127.0.0.1:$port$path"

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def installRoutes(routes: Routes[Any, Response]): ZIO[Scope, Throwable, Int] =
    for {
      serverEnv <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
      port <- Server.install(routes).provideEnvironment(serverEnv)
    } yield port

  private def withSnapshotServer(
      body: String = goldenBody
  )(f: Int => ZIO[Any, Throwable, TestResult]): ZIO[Any, Throwable, TestResult] =
    ZIO.scoped {
      for {
        port <- HttpServe.serve(body, port0)
        result <- f(port)
      } yield result
    }

  private def withMockServer(
      routes: Routes[Any, Response]
  )(f: Int => ZIO[Any, Throwable, TestResult]): ZIO[Any, Throwable, TestResult] =
    ZIO.scoped {
      for {
        port <- installRoutes(routes)
        result <- f(port)
      } yield result
    }

  /** Raw-socket HTTP/1.1 request so wire bytes (exact header values, empty HEAD body) can be
    * asserted directly, mirroring the harness's raw-socket checks (ruling H).
    */
  private final case class RawResponse(
      status: Int,
      headers: Vector[(String, String)],
      body: Array[Byte]
  ) {
    def header(name: String): Option[String] =
      headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }
  }

  private def rawRequest(
      port: Int,
      requestLine: String,
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): ZIO[Any, Throwable, RawResponse] =
    ZIO.attemptBlocking {
      val socket = new Socket("127.0.0.1", port)
      try {
        socket.setSoTimeout(10000)
        val out = socket.getOutputStream
        val head =
          (requestLine +: s"Host: 127.0.0.1:$port" +: extraHeaders.map { case (k, v) =>
            s"$k: $v"
          } :+ "Connection: close").mkString("\r\n")
        out.write((head + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII))
        out.flush()
        val in = socket.getInputStream
        val buf = new ByteArrayOutputStream()
        val chunk = new Array[Byte](8192)
        var n = in.read(chunk)
        while (n != -1) {
          buf.write(chunk, 0, n)
          n = in.read(chunk)
        }
        val all = buf.toByteArray
        var sep = -1
        var i = 0
        while (sep < 0 && i + 3 < all.length) {
          if (all(i) == 13 && all(i + 1) == 10 && all(i + 2) == 13 && all(i + 3) == 10) sep = i
          i += 1
        }
        if (sep < 0) RawResponse(-1, Vector.empty, all)
        else {
          val headerText = new String(all, 0, sep, StandardCharsets.ISO_8859_1)
          val lines = headerText.split("\r\n").toVector
          val status =
            lines.headOption.flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption).getOrElse(-1)
          val headers = lines.drop(1).flatMap { line =>
            val idx = line.indexOf(':')
            if (idx <= 0) None
            else Some((line.substring(0, idx).trim, line.substring(idx + 1).trim))
          }
          RawResponse(status, headers, all.drop(sep + 4))
        }
      } finally socket.close()
    }

  /** Mock origin for the client-discipline tests (test 13/26 shapes). */
  private def mockRoutes(counter: AtomicInteger): Routes[Any, Response] = {
    val jsonHeaders = Headers("Content-Type", "application/json; charset=utf-8")
    val responder = handler { (_: Path, req: Request) =>
      counter.incrementAndGet()
      req.url.path.encode match {
        case "/good" =>
          Response(
            status = Status.Ok,
            headers = jsonHeaders,
            body = Body.fromString(goldenBody, StandardCharsets.UTF_8)
          )
        case "/bad" =>
          Response(
            status = Status.Ok,
            headers = Headers("Content-Type", "text/plain"),
            body = Body.fromString("not-json", StandardCharsets.UTF_8)
          )
        case "/invalid" =>
          Response(
            status = Status.Ok,
            headers = jsonHeaders,
            body = Body.fromString(invalidRepoJson, StandardCharsets.UTF_8)
          )
        case "/redirect" =>
          Response(
            status = Status.Found,
            headers = Headers("Location", "/bad"),
            body = Body.empty
          )
        case _ => Response.status(Status.NotFound)
      }
    }
    Routes(RoutePattern.any -> responder)
  }

  // ---------------------------------------------------------------------------
  // Spec
  // ---------------------------------------------------------------------------

  def spec = suite("HttpServe and HttpFetch (SPEC §9, CONTRACT §12)")(
    suite("snapshot server")(
      test("binds an OS-assigned port and reports the actual bound port") {
        withSnapshotServer() { port => ZIO.succeed(assertTrue(port > 0)) }
      },
      test("GET /repository.json returns 200 with exact content type and snapshot bytes") {
        withSnapshotServer() { port =>
          rawRequest(port, "GET /repository.json HTTP/1.1").map { r =>
            assertTrue(
              r.status == 200,
              r.header("content-type").contains("application/json; charset=utf-8"),
              new String(r.body, StandardCharsets.UTF_8) == goldenBody
            )
          }
        }
      },
      test("GET serves the test-12 golden body byte-for-byte") {
        withSnapshotServer() { port =>
          rawRequest(port, "GET /repository.json HTTP/1.1").map { r =>
            val expected = Vector(
              "{",
              "  \"format\": 1,",
              "  \"frontier\": [",
              "    [",
              "      \"a@x\",",
              "      1",
              "    ]",
              "  ],",
              "  \"patches\": [",
              "    {",
              "      \"author\": \"a@x\",",
              "      \"revision\": 1,",
              "      \"base\": [],",
              "      \"message\": \"one\",",
              "      \"changes\": [",
              "        {",
              "          \"type\": \"text\",",
              "          \"path\": \"file.txt\",",
              "          \"edit\": [",
              "            {",
              "              \"insert\": [",
              "                \"one\\n\"",
              "              ]",
              "            }",
              "          ]",
              "        }",
              "      ]",
              "    }",
              "  ]",
              "}"
            ).mkString("\n") + "\n"
            assertTrue(new String(r.body, StandardCharsets.UTF_8) == expected)
          }
        }
      },
      test("HEAD returns 200, same content type, and zero body bytes (ruling H)") {
        withSnapshotServer() { port =>
          rawRequest(port, "HEAD /repository.json HTTP/1.1").map { r =>
            assertTrue(
              r.status == 200,
              r.header("content-type").contains("application/json; charset=utf-8"),
              r.body.isEmpty
            )
          }
        }
      },
      test("POST returns 405 with Allow: GET, HEAD") {
        withSnapshotServer() { port =>
          rawRequest(port, "POST /repository.json HTTP/1.1").map { r =>
            assertTrue(r.status == 405, r.header("allow").contains("GET, HEAD"))
          }
        }
      },
      test("PUT and DELETE return 405 with Allow: GET, HEAD") {
        withSnapshotServer() { port =>
          for {
            put <- rawRequest(port, "PUT /repository.json HTTP/1.1")
            delete <- rawRequest(port, "DELETE /repository.json HTTP/1.1")
          } yield assertTrue(
            put.status == 405,
            put.header("allow").contains("GET, HEAD"),
            delete.status == 405,
            delete.header("allow").contains("GET, HEAD")
          )
        }
      },
      test("unknown paths return 404") {
        withSnapshotServer() { port =>
          for {
            root <- rawRequest(port, "GET / HTTP/1.1")
            other <- rawRequest(port, "GET /nope HTTP/1.1")
            deep <- rawRequest(port, "GET /repository.json/extra HTTP/1.1")
          } yield assertTrue(root.status == 404, other.status == 404, deep.status == 404)
        }
      },
      test("query string on the resource path returns 404 (test 12)") {
        withSnapshotServer() { port =>
          rawRequest(port, "GET /repository.json?query=not-exact HTTP/1.1").map { r =>
            assertTrue(r.status == 404)
          }
        }
      },
      test("repeated GETs serve the identical immutable snapshot") {
        withSnapshotServer() { port =>
          for {
            first <- rawRequest(port, "GET /repository.json HTTP/1.1")
            second <- rawRequest(port, "GET /repository.json HTTP/1.1")
          } yield assertTrue(
            new String(first.body, StandardCharsets.UTF_8) == goldenBody,
            java.util.Arrays.equals(first.body, second.body)
          )
        }
      },
      test("a different startup body is served verbatim (snapshot fixed at startup)") {
        val otherBody = Json.writeRepository(Model.Repository(Model.Version.empty, Vector.empty))
        withSnapshotServer(otherBody) { port =>
          rawRequest(port, "GET /repository.json HTTP/1.1").map { r =>
            assertTrue(new String(r.body, StandardCharsets.UTF_8) == otherBody)
          }
        }
      },
      test("server startup and request handling emit no stdout/stderr log noise") {
        val outBuf = new ByteArrayOutputStream()
        val errBuf = new ByteArrayOutputStream()
        for {
          oldOut <- ZIO.succeed(java.lang.System.out)
          oldErr <- ZIO.succeed(java.lang.System.err)
          response <- ZIO
            .scoped {
              for {
                _ <- ZIO.attemptBlocking {
                  java.lang.System.setOut(new java.io.PrintStream(outBuf, true, "UTF-8"))
                  java.lang.System.setErr(new java.io.PrintStream(errBuf, true, "UTF-8"))
                }
                port <- HttpServe.serve(goldenBody, port0)
                r <- rawRequest(port, "GET /repository.json HTTP/1.1")
              } yield r
            }
            .ensuring(
              ZIO.attemptBlocking {
                java.lang.System.setOut(oldOut)
                java.lang.System.setErr(oldErr)
              }.orDie
            )
          out = outBuf.toString("UTF-8")
          err = errBuf.toString("UTF-8")
        } yield assertTrue(
          response.status == 200,
          out.isEmpty,
          // The harness JVM passes -Dsun.misc.unsafe.memory.access=allow; the sbt test JVM does
          // not, so the one-time JVM Unsafe deprecation WARNING lines are tolerated here.
          err.linesIterator.forall(l => l.startsWith("WARNING:") || l.isBlank)
        )
      }
    ),
    suite("HttpFetch client")(
      test("fetches, parses, and validates a served repository") {
        withSnapshotServer() { port =>
          HttpFetch.fetchRepository(urlOf(port, "/repository.json")).map { repo =>
            assertTrue(
              Json.writeRepository(repo) == goldenBody,
              repo == goldenRepo
            )
          }
        }
      },
      test("a 302 redirect is not followed and surfaces as HttpStatus(302) (test 13)") {
        val counter = new AtomicInteger(0)
        withMockServer(mockRoutes(counter)) { port =>
          val target = urlOf(port, "/redirect")
          for {
            res <- HttpFetch.fetchRepository(target).either
          } yield assertTrue(
            res == Left(SnapError.HttpStatus(302, target)),
            res.left.toOption.exists(_.detail.contains("HTTP 302")),
            counter.get() == 1 // exactly one request; the Location target was never fetched
          )
        }
      },
      test("a non-JSON body surfaces as InvalidJson containing 'invalid JSON' (test 13)") {
        val counter = new AtomicInteger(0)
        withMockServer(mockRoutes(counter)) { port =>
          for {
            res <- HttpFetch.fetchRepository(urlOf(port, "/bad")).either
          } yield assertTrue(
            res.left.toOption.exists(_.detail.contains("invalid JSON")),
            counter.get() == 1
          )
        }
      },
      test("a structurally invalid repository body is rejected by validation (test 26)") {
        val counter = new AtomicInteger(0)
        withMockServer(mockRoutes(counter)) { port =>
          for {
            res <- HttpFetch.fetchRepository(urlOf(port, "/invalid")).either
          } yield assertTrue(
            res == Left(SnapError.UnknownRepoField("bad")),
            counter.get() == 1
          )
        }
      },
      test("a 404 response surfaces as HttpStatus(404)") {
        withMockServer(mockRoutes(new AtomicInteger(0))) { port =>
          val target = urlOf(port, "/missing")
          for {
            res <- HttpFetch.fetchRepository(target).either
          } yield assertTrue(res == Left(SnapError.HttpStatus(404, target)))
        }
      },
      test("exactly one request is issued per fetch attempt (test 13/26 discipline)") {
        val counter = new AtomicInteger(0)
        withMockServer(mockRoutes(counter)) { port =>
          val target = urlOf(port, "/good")
          for {
            first <- HttpFetch.fetchRepository(target).either
            second <- HttpFetch.fetchRepository(target).either
          } yield assertTrue(first.isRight, second.isRight, counter.get() == 2)
        }
      },
      test("connection refused maps to IoFailure") {
        for {
          res <- HttpFetch.fetchRepository("http://127.0.0.1:1/repository.json").either
        } yield assertTrue(res.left.toOption.exists(_.isInstanceOf[SnapError.IoFailure]))
      },
      test("a malformed URL maps to IoFailure without a network call") {
        for {
          res <- HttpFetch.fetchRepository("not a url").either
        } yield assertTrue(res.left.toOption.exists(_.isInstanceOf[SnapError.IoFailure]))
      },
      test("isHttpUrl recognizes http/https operands only") {
        assertTrue(
          HttpFetch.isHttpUrl("http://127.0.0.1:8765/repository.json"),
          HttpFetch.isHttpUrl("https://example.com/repository.json"),
          !HttpFetch.isHttpUrl("../remote"),
          !HttpFetch.isHttpUrl("/abs/path"),
          !HttpFetch.isHttpUrl("file:///x")
        )
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
}

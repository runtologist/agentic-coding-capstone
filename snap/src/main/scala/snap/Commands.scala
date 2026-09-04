package snap

import snap.Cli.Command
import snap.Model.*
import zio.*

import java.nio.file.Path

/** Command orchestration layer (SPEC §7, CONTRACT §1–§14).
  *
  * Every subcommand runs as a ZIO effect producing the exact stdout/stderr text plus a process exit
  * code. [[CmdEnv]] carries a one-time snapshot of the process environment (cwd, HOME, SNAP_COLOR,
  * NO_COLOR presence, TTY) so unit tests can drive commands against temp directories without
  * spawning processes.
  *
  * Check ordering is contractually significant:
  *   - commit: load/validate repo → contributor → message validation → scan → dirty check;
  *   - revert: parse version → known-version → scan + clean check → contributor (CONTRACT §13);
  *   - merge: validate local → load+validate remote → collision → joined replay → scan + dirty
  *     check → write files → write repository.json (CONTRACT §15 ruling 8).
  */
object Commands {

  /** Environment snapshot captured once at startup (CONTRACT §14). `snapDebug` records whether
    * `SNAP_DEBUG` was present at process start so defect traces never re-read the live environment
    * (E3-M2/E2-F4).
    */
  final case class CmdEnv(
      cwd: Path,
      home: Option[Path],
      snapColor: Option[String],
      noColorPresent: Boolean,
      isTty: Boolean,
      snapDebug: Boolean
  )

  /** Output capability: the real process streams in production, in-memory buffers in tests. */
  trait Output {
    def writeOut(text: String): UIO[Unit]
    def writeErr(text: String): UIO[Unit]
    def flushOut: UIO[Unit]
    def flushErr: UIO[Unit]
  }

  object Output {

    /** UTF-8 streams over the real process stdout/stderr (CONTRACT §2). */
    val live: Output = new Output {
      private val out = new java.io.PrintStream(
        new java.io.FileOutputStream(java.io.FileDescriptor.out),
        false,
        java.nio.charset.StandardCharsets.UTF_8
      )
      private val err = new java.io.PrintStream(
        new java.io.FileOutputStream(java.io.FileDescriptor.err),
        false,
        java.nio.charset.StandardCharsets.UTF_8
      )

      def writeOut(text: String): UIO[Unit] = ZIO.succeed(out.print(text))
      def writeErr(text: String): UIO[Unit] = ZIO.succeed(err.print(text))
      def flushOut: UIO[Unit] = ZIO.succeed(out.flush())
      def flushErr: UIO[Unit] = ZIO.succeed(err.flush())
    }

    /** In-memory capture used by unit tests. */
    final class Captured private[Output] (outRef: Ref[String], errRef: Ref[String]) {
      val output: Output = new Output {
        def writeOut(text: String): UIO[Unit] = outRef.update(_ + text)
        def writeErr(text: String): UIO[Unit] = errRef.update(_ + text)
        def flushOut: UIO[Unit] = ZIO.unit
        def flushErr: UIO[Unit] = ZIO.unit
      }
      def stdout: UIO[String] = outRef.get
      def stderr: UIO[String] = errRef.get
    }

    def captured: UIO[Captured] =
      for {
        out <- Ref.make("")
        err <- Ref.make("")
      } yield new Captured(out, err)
  }

  /** Run the CLI: resolve presentation first (CONTRACT §5), then parse (CONTRACT §1), then
    * dispatch. Never fails; yields the process exit code.
    */
  def run(args: Seq[String], env: CmdEnv, out: Output): UIO[Int] =
    Render.resolvePresentation(env.snapColor, env.noColorPresent, env.isTty, env.isTty) match {
      case Left(err) =>
        // The invalid-SNAP_COLOR error is rendered plain before any command execution.
        (out.writeErr(Render.errorLine(err, Render.Presentation.Plain)) *> out.flushErr).as(1)
      case Right((pOut, pErr)) =>
        Cli.parse(args) match {
          case Left(err) => (out.writeErr(Render.errorLine(err, pErr)) *> out.flushErr).as(1)
          case Right(Command.Serve(port)) => serve(port, env, out, pErr)
          case Right(command) =>
            finish(execute(command, env, pOut, pErr), env, out, pErr)
        }
    }

  private def finish(
      effect: IO[SnapError, (String, String)],
      env: CmdEnv,
      out: Output,
      pErr: Render.Presentation
  ): UIO[Int] =
    effect.exit.flatMap {
      case Exit.Success((stdout, stderr)) =>
        // stderr may carry merge warnings: flush both streams (E3-M1).
        out.writeOut(stdout) *> out.writeErr(stderr) *> out.flushOut *> out.flushErr.as(0)
      case Exit.Failure(cause) =>
        cause.failureOrCause match {
          case Left(err) => (out.writeErr(Render.errorLine(err, pErr)) *> out.flushErr).as(1)
          case Right(defect) =>
            if (defect.dieOption.contains(Jnu.ReexecRequired)) ZIO.succeed(Jnu.ReexecCode)
            else {
              val dbg =
                if (env.snapDebug)
                  ZIO.succeed(java.lang.System.err.println(defect.prettyPrint))
                else ZIO.unit
              (dbg *> out.writeErr(Render.errorLine(SnapError.InternalError, pErr)) *>
                out.flushErr).as(2)
            }
        }
    }

  private def execute(
      command: Cli.Command,
      env: CmdEnv,
      pOut: Render.Presentation,
      pErr: Render.Presentation
  ): IO[SnapError, (String, String)] =
    command match {
      case Command.Init(path)         => init(path, env, pOut)
      case Command.Config(global, id) => config(global, id, env)
      case Command.Status             => status(env, pOut)
      case Command.Log                => log(env, pOut)
      case Command.Commit(message)    => commit(message, env, pOut)
      case Command.Diff(o, n, repo)   => diff(o, n, repo, env, pOut)
      case Command.Revert(versionRaw) => revert(versionRaw, env, pOut)
      case Command.Merge(remote)      => merge(remote, env, pOut, pErr)
      case Command.Serve(_)           => ZIO.dieMessage("serve is handled by the caller")
      case Command.ShowVersion        => ZIO.succeed((Render.versionLine(pOut), ""))
    }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private def discover(cwd: Path): IO[SnapError, Path] =
    RepoIo.discoverRepo(cwd).flatMap {
      case Some(root) => ZIO.succeed(root)
      case None       => ZIO.fail(SnapError.NotASnapRepository)
    }

  private def currentTree(repo: Repository): IO[SnapError, Model.Tree] =
    ZIO.fromEither(Replay.materialize(repo.patches, repo.frontier).map(_._1))

  private def requireContributor(root: Path, env: CmdEnv): IO[SnapError, ContributorId] =
    snap.Config.resolveContributor(Some(root), env.home).flatMap {
      case Some(id) => ZIO.succeed(id)
      case None     => ZIO.fail(SnapError.ContributorIdRequired)
    }

  /** Insert `patch` keeping patches sorted by (author unsigned-UTF-8, revision). */
  private def patchLess(a: Patch, b: Patch): Boolean = {
    val c = Model.utf8Compare(a.author.value, b.author.value)
    if (c != 0) c < 0 else a.revision < b.revision
  }

  /** Build one patch's changes from a current→working tree diff (CONTRACT §13). Changes are sorted
    * by unsigned-UTF-8 path; text changes use the canonical diff, everything else is a put.
    */
  private def buildChanges(
      current: Model.Tree,
      updated: Model.Tree
  ): Either[SnapError, Vector[Change]] = {
    val paths =
      (current.keySet ++ updated.keySet).toVector.sortWith((a, b) => Model.utf8Compare(a, b) < 0)
    // H2: foldLeft over the sorted paths, short-circuiting on the first path validation error.
    paths.foldLeft[Either[SnapError, Vector[Change]]](Right(Vector.empty)) {
      case (acc @ Left(_), _) => acc
      case (Right(changes), path) =>
        Model.validatePath(path) match {
          case Left(err) => Left(err)
          case Right(()) =>
            (current.get(path), updated.get(path)) match {
              case (Some(old), Some(bytes)) =>
                if (!Model.bytesEqual(old, bytes))
                  Right(changes :+ textOrPut(path, Some(old), bytes))
                else Right(changes)
              case (None, Some(bytes)) => Right(changes :+ textOrPut(path, None, bytes))
              case (Some(_), None)     => Right(changes :+ Change.Del(path))
              case (None, None)        => Right(changes)
            }
        }
    }
  }

  /** Text change when the new content is text and the old is absent or text; otherwise put. An
    * empty new text file becomes a text change with an empty edit script (CONTRACT §15 ruling F).
    */
  private def textOrPut(path: String, old: Option[Array[Byte]], bytes: Array[Byte]): Change =
    if (Model.isText(bytes) && old.forall(Model.isText))
      Change.Text(path, Diff.canonicalDiff(decodeTokens(old), decodeTokens(Some(bytes))))
    else Change.Put(path, bytes)

  private def decodeTokens(bytes: Option[Array[Byte]]): Vector[String] =
    bytes.flatMap(b => Model.decodeUtf8(b).map(Model.tokenize)).getOrElse(Vector.empty)

  /** Diff entries for two trees: text-vs-text becomes a whole-file unified block, anything else is
    * binary; absent sides render as /dev/null (CONTRACT §10).
    */
  private def diffEntries(oldTree: Model.Tree, newTree: Model.Tree): Vector[Render.DiffEntry] = {
    val paths =
      (oldTree.keySet ++ newTree.keySet).toVector.sortWith((a, b) => Model.utf8Compare(a, b) < 0)
    paths.flatMap { path =>
      (oldTree.get(path), newTree.get(path)) match {
        case (Some(o), Some(n)) if Model.bytesEqual(o, n) => None
        case (o, n)                                       =>
          // Text block whenever both sides are text-or-absent (empty files are text,
          // ruling F); any binary side collapses to the binary marker.
          if (o.forall(Model.isText) && n.forall(Model.isText)) {
            val oldTokens = decodeTokens(o)
            val newTokens = decodeTokens(n)
            Some(
              Render.DiffEntry.Text(
                path,
                o.isDefined,
                n.isDefined,
                oldTokens,
                Diff.canonicalDiff(oldTokens, newTokens)
              )
            )
          } else Some(Render.DiffEntry.Binary(path, o.isDefined, n.isDefined))
      }
    }
  }

  private def loadRemote(operand: String, cwd: Path): IO[SnapError, Repository] =
    if (HttpFetch.isHttpUrl(operand)) HttpFetch.fetchRepository(operand)
    else RepoIo.loadRepository(cwd.resolve(operand))

  // ---------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------

  private def init(
      path: Option[String],
      env: CmdEnv,
      p: Render.Presentation
  ): IO[SnapError, (String, String)] =
    RepoIo
      .init(env.cwd.resolve(path.getOrElse(".")))
      .as((Render.successLine(Render.SuccessLabel.InitializedRepository, "()", p), ""))

  private def config(
      global: Boolean,
      rawId: String,
      env: CmdEnv
  ): IO[SnapError, (String, String)] =
    for {
      id <- ZIO.fromEither(ContributorId.parse(rawId))
      _ <-
        if (global)
          env.home match {
            case Some(home) => snap.Config.writeGlobal(home, id)
            case None =>
              ZIO.fail(SnapError.IoFailure("cannot write global config: HOME is not set"))
          }
        else
          for {
            root <- discover(env.cwd)
            _ <- snap.Config.writeLocal(root, id)
          } yield ()
    } yield ("", "")

  private def status(env: CmdEnv, p: Render.Presentation): IO[SnapError, (String, String)] =
    for {
      root <- discover(env.cwd)
      repo <- RepoIo.loadRepository(root)
      working <- WorkingTree.scan(root)
      current <- currentTree(repo)
      rows = WorkingTree.compare(current, working)
    } yield (Render.status(repo.frontier, rows, p), "")

  private def log(env: CmdEnv, p: Render.Presentation): IO[SnapError, (String, String)] =
    for {
      root <- discover(env.cwd)
      repo <- RepoIo.loadRepository(root)
      ordered <- ZIO.fromEither(Replay.integrationOrder(repo.patches))
      entries = ordered.reverse.map(pt => Render.LogEntry(pt.result, pt.author.value, pt.message))
    } yield (Render.log(entries, p), "")

  /** Commit core over an already-loaded repository (E1-S1: the revision increment goes through
    * [[Model.nextRevision]], failing typed before anything is written). Package-visible so tests
    * can drive overflow scenarios that cannot be represented as a valid repository file.
    */
  private[snap] def commitWithRepo(
      root: Path,
      repo: Repository,
      contributor: ContributorId,
      message: String,
      p: Render.Presentation
  ): IO[SnapError, (String, String)] =
    for {
      _ <- ZIO.fromEither(Model.validateCommitMessage(message))
      working <- WorkingTree.scan(root)
      current <- currentTree(repo)
      _ <-
        if (WorkingTree.isClean(current, working)) ZIO.fail(SnapError.WorkingTreeClean)
        else ZIO.unit
      changes <- ZIO.fromEither(buildChanges(current, working))
      revision <- ZIO.fromEither(Model.nextRevision(repo.frontier.get(contributor)))
      patch = Patch(contributor, revision, repo.frontier, message, changes)
      _ <- ZIO.fromEither(Codec.validateChangesAgainstBase(patch, current))
      newRepo = Repository(
        Version.withComponent(repo.frontier, contributor, revision),
        (repo.patches :+ patch).sortWith(patchLess)
      )
      _ <- RepoIo.writeRepositoryAtomic(root, newRepo)
    } yield (Render.successLine(Render.SuccessLabel.Committed, patch.result.render, p), "")

  private def commit(
      message: String,
      env: CmdEnv,
      p: Render.Presentation
  ): IO[SnapError, (String, String)] =
    for {
      root <- discover(env.cwd)
      repo <- RepoIo.loadRepository(root)
      contributor <- requireContributor(root, env)
      result <- commitWithRepo(root, repo, contributor, message, p)
    } yield result

  private def diff(
      oldRaw: Option[String],
      newRaw: Option[String],
      repoArg: Option[String],
      env: CmdEnv,
      p: Render.Presentation
  ): IO[SnapError, (String, String)] =
    (oldRaw, newRaw, repoArg) match {
      case (None, None, _) =>
        for {
          root <- discover(env.cwd)
          repo <- RepoIo.loadRepository(root)
          current <- currentTree(repo)
          working <- WorkingTree.scan(root)
        } yield (Render.diff(diffEntries(current, working), p), "")
      case (Some(o), Some(n), remoteOpt) =>
        for {
          oldV <- ZIO.fromEither(Version.parse(o))
          newV <- ZIO.fromEither(Version.parse(n))
          root <- discover(env.cwd)
          local <- RepoIo.loadRepository(root)
          remote <- remoteOpt match {
            case Some(operand) => loadRemote(operand, env.cwd)
            case None          => ZIO.succeed(local)
          }
          // Collision check happens before any output (test 16).
          _ <- remoteOpt match {
            case Some(_) => ZIO.fromEither(Codec.checkCollision(local.patches, remote.patches))
            case None    => ZIO.unit
          }
          _ <- ZIO.fromEither(Codec.knownVersion(local, oldV))
          _ <- ZIO.fromEither(Codec.knownVersion(remote, newV))
          oldTree <- ZIO.fromEither(Replay.materialize(local.patches, oldV).map(_._1))
          newTree <- ZIO.fromEither(Replay.materialize(remote.patches, newV).map(_._1))
        } yield (Render.diff(diffEntries(oldTree, newTree), p), "")
      case _ =>
        // Unreachable: Cli.parse enforces zero or two version operands.
        ZIO.fail(SnapError.InvalidCommandOrArguments)
    }

  /** Revert core after version/known-version/clean/contributor checks (E1-S1: revision increment
    * via [[Model.nextRevision]]). Package-visible for overflow tests; check ordering that lives in
    * [[revert]] stays unchanged (CONTRACT §13).
    */
  private[snap] def revertWithRepo(
      root: Path,
      repo: Repository,
      target: Version,
      contributor: ContributorId,
      p: Render.Presentation
  ): IO[SnapError, (String, String)] =
    for {
      targetTree <- ZIO.fromEither(Replay.materialize(repo.patches, target).map(_._1))
      current <- currentTree(repo)
      _ <-
        if (Model.treeEqual(targetTree, current)) ZIO.fail(SnapError.TargetTreeAlreadyCurrent)
        else ZIO.unit
      changes <- ZIO.fromEither(buildChanges(current, targetTree))
      revision <- ZIO.fromEither(Model.nextRevision(repo.frontier.get(contributor)))
      patch = Patch(contributor, revision, repo.frontier, s"revert to ${target.render}", changes)
      _ <- ZIO.fromEither(Codec.validateChangesAgainstBase(patch, current))
      // Working files first, then repository metadata (SPEC §10).
      _ <- WorkingTree.materialize(root, targetTree)
      newRepo = Repository(
        Version.withComponent(repo.frontier, contributor, revision),
        (repo.patches :+ patch).sortWith(patchLess)
      )
      _ <- RepoIo.writeRepositoryAtomic(root, newRepo)
    } yield (Render.successLine(Render.SuccessLabel.Reverted, patch.result.render, p), "")

  private def revert(
      versionRaw: String,
      env: CmdEnv,
      p: Render.Presentation
  ): IO[SnapError, (String, String)] =
    for {
      root <- discover(env.cwd)
      repo <- RepoIo.loadRepository(root)
      // Version parse and known-version checks precede the contributor check (CONTRACT §13).
      target <- ZIO.fromEither(Version.parse(versionRaw))
      _ <- ZIO.fromEither(Codec.knownVersion(repo, target))
      working <- WorkingTree.scan(root)
      current <- currentTree(repo)
      _ <-
        if (WorkingTree.isClean(current, working)) ZIO.unit
        else ZIO.fail(SnapError.WorkingTreeDirty)
      contributor <- requireContributor(root, env)
      result <- revertWithRepo(root, repo, target, contributor, p)
    } yield result

  private def merge(
      remoteRef: String,
      env: CmdEnv,
      pOut: Render.Presentation,
      pErr: Render.Presentation
  ): IO[SnapError, (String, String)] =
    for {
      root <- discover(env.cwd)
      local <- RepoIo.loadRepository(root)
      remote <- loadRemote(remoteRef, env.cwd)
      _ <- ZIO.fromEither(Codec.checkCollision(local.patches, remote.patches))
      union <- ZIO.fromEither(Replay.dedupePatches(local.patches ++ remote.patches))
      joined = Codec.joinedFrontier(local.frontier, remote.patches)
      joinedReplay <- ZIO.fromEither(Replay.materialize(union, joined))
      (joinedTree, joinedWarnings) = joinedReplay
      localReplay <- ZIO.fromEither(Replay.materialize(local.patches, local.frontier))
      (localTree, localWarnings) = localReplay
      working <- WorkingTree.scan(root)
      _ <-
        if (WorkingTree.isClean(localTree, working)) ZIO.unit
        else ZIO.fail(SnapError.WorkingTreeDirty)
      noOp = union.length == local.patches.length && joined == local.frontier
      _ <-
        if (noOp) ZIO.unit
        else
          WorkingTree.materialize(root, joinedTree) *>
            RepoIo.writeRepositoryAtomic(root, Repository(joined, union.sortWith(patchLess)))
      localPairs = localWarnings.map(w => (w.path, w.reason)).toSet
      newWarnings =
        if (noOp) Vector.empty
        else joinedWarnings.filterNot(w => localPairs.contains((w.path, w.reason)))
      stderr = newWarnings.map(Render.warningLine(_, pErr)).mkString
      stdout = Render.successLine(Render.SuccessLabel.Merged, joined.render, pOut)
    } yield (stdout, stderr)

  // ---------------------------------------------------------------------------
  // Serve
  // ---------------------------------------------------------------------------

  private def serve(
      port: Port,
      env: CmdEnv,
      out: Output,
      pErr: Render.Presentation
  ): UIO[Int] = {
    val app: IO[SnapError, Unit] =
      for {
        root <- discover(env.cwd)
        // Validate before printing anything (test 12 final step).
        repo <- RepoIo.loadRepository(root)
        _ <- ZIO.scoped {
          for {
            boundPort <- HttpServe.serveSnapshot(repo, port)
            done <- Promise.make[Nothing, Unit]
            runtime <- ZIO.runtime[Any]
            _ <- installSignalHandlers(done, runtime)
            // URL line is always plain and flushed immediately (CONTRACT §15 ruling I).
            _ <- out.writeOut(Render.serveUrlLine(boundPort))
            _ <- out.flushOut
            _ <- done.await
          } yield ()
        }
      } yield ()
    app
      .foldCauseZIO(
        cause =>
          cause.failureOrCause match {
            case Left(err) =>
              (out.writeErr(Render.errorLine(err, pErr)) *> out.flushErr).as(1)
            case Right(defect) =>
              if (defect.dieOption.contains(Jnu.ReexecRequired)) ZIO.succeed(Jnu.ReexecCode)
              else {
                val dbg =
                  if (env.snapDebug)
                    ZIO.succeed(java.lang.System.err.println(defect.prettyPrint))
                  else ZIO.unit
                (dbg *> out.writeErr(Render.errorLine(SnapError.InternalError, pErr)) *>
                  out.flushErr).as(2)
              }
          },
        _ => ZIO.succeed(0)
      )
  }

  /** Install TERM/INT handlers that complete `done`, restoring the previous handlers when the
    * surrounding scope closes. Documented raw-JVM exception (SPEC §14, CONTRACT §12).
    */
  private def installSignalHandlers(
      done: Promise[Nothing, Unit],
      runtime: Runtime[Any]
  ): ZIO[Scope, SnapError, Unit] = {
    val install = ZIO
      .attemptBlocking {
        val handler: sun.misc.SignalHandler = (_: sun.misc.Signal) =>
          Unsafe.unsafe { implicit unsafe =>
            runtime.unsafe.fork(done.succeed(()))
            ()
          }
        val prevTerm = sun.misc.Signal.handle(new sun.misc.Signal("TERM"), handler)
        val prevInt = sun.misc.Signal.handle(new sun.misc.Signal("INT"), handler)
        (prevTerm, prevInt)
      }
      .mapError(t => SnapError.IoFailure(s"cannot install signal handlers: ${t.getMessage}"))
    ZIO
      .acquireReleaseExit(install) { case ((prevTerm, prevInt), _) =>
        ZIO.attemptBlocking {
          sun.misc.Signal.handle(new sun.misc.Signal("TERM"), prevTerm)
          sun.misc.Signal.handle(new sun.misc.Signal("INT"), prevInt)
          ()
        }.orDie
      }
      .unit
  }
}

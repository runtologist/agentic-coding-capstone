package snap

/** JNU re-exec helpers for UTF-8 filename handling (F-utf8b).
  *
  * Provides lazy detection of when a JVM re-exec is needed to handle non-ASCII filenames correctly
  * under lossy sun.jnu.encoding settings.
  */
object Jnu {

  /** Sentinel exit code indicating a re-exec is required (F-utf8b). */
  private[snap] val ReexecCode = 254

  /** Control exception thrown when a re-exec is required. */
  case object ReexecRequired extends Exception("JNU re-exec required")

  /** True iff the JVM is in lossy-jnu mode: sun.jnu.encoding is not UTF-8, SNAP_JNU_REEXEC guard is
    * unset, and we are running from a .jar.
    */
  lazy val lossyRisk: Boolean = {
    val jnuEncoding = Option(java.lang.System.getProperty("sun.jnu.encoding"))
    val guardEnv = Option(java.lang.System.getenv("SNAP_JNU_REEXEC"))
    val jarPath =
      try
        Option(getClass.getProtectionDomain.getCodeSource)
          .flatMap(cs => Option(cs.getLocation))
          .map(l => java.nio.file.Paths.get(l.toURI).toString)
      catch { case _: Throwable => None }

    val encodingIsUtf8 = jnuEncoding match {
      case None      => true
      case Some(raw) => raw.toUpperCase.replace("-", "").replace("_", "").contains("UTF8")
    }
    val guardSet = guardEnv.isDefined
    val runningFromJar = jarPath.exists(_.endsWith(".jar"))
    !encodingIsUtf8 && !guardSet && runningFromJar
  }

  /** True if the decoded name contains U+FFFD (replacement character), indicating lossy decoding
    * occurred.
    */
  def decodedNameNeedsReexec(lossy: Boolean, name: String): Boolean =
    lossy && name.contains('\uFFFD')

  /** True if any path contains a character > 0x7F, meaning non-ASCII filenames would be mangled
    * under lossy encoding.
    */
  def writeNeedsReexec(lossy: Boolean, paths: Iterable[String]): Boolean =
    lossy && paths.exists(_.exists(_ > '\u007F'))
}

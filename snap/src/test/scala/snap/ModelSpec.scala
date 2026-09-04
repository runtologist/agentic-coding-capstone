package snap

import zio.test.*

import snap.Model.*

object ModelSpec extends ZIOSpecDefault {

  private def leftDetail[A](e: Either[SnapError, A]): Option[String] =
    e.left.toOption.map(_.detail)

  def spec = suite("Model")(
    suite("ContributorId.parse")(
      test("accepts a minimal email-shaped id") {
        assertTrue(ContributorId.parse("a@x") == Right(ContributorId("a@x")))
      },
      test("accepts ids with dots, plus, hyphen and underscore") {
        assertTrue(
          ContributorId.parse("first.last+tag-1_2@example-domain.com").isRight
        )
      },
      test("rejects every harness-tested invalid id") {
        val bad =
          Vector("bad-id", "two@@x", "space @x", "a,b@x", "a(b)@x", "a->b@x", "not-an-id", "")
        assertTrue(bad.forall(id => ContributorId.parse(id).isLeft))
      },
      test("rejects control characters, DEL and non-ASCII") {
        assertTrue(
          ContributorId.parse("a\uu0001b@x").isLeft,
          ContributorId.parse("a\u007fb@x").isLeft,
          ContributorId.parse("é@x").isLeft
        )
      },
      test("rejects ids longer than 254 bytes") {
        val long = ("a" * 251) + "@x.c"
        assertTrue(ContributorId.parse(long).isLeft)
      },
      test("accepts an id of exactly 254 bytes") {
        val id = ("a" * 250) + "@x.c"
        assertTrue(utf8Bytes(id).length == 254 && ContributorId.parse(id).isRight)
      }
    ),
    suite("Version.parse")(
      test("parses the empty version") {
        assertTrue(Version.parse("()") == Right(Version.empty))
      },
      test("parses a single component") {
        assertTrue(
          Version.parse("(a@x->1)") == Right(
            Version(Vector((ContributorId("a@x"), 1L)))
          )
        )
      },
      test("parses sorted multi-component versions") {
        assertTrue(
          Version.parse("(alice@x->1,bob@x->2,seed@x->1)") == Right(
            Version(
              Vector(
                (ContributorId("alice@x"), 1L),
                (ContributorId("bob@x"), 2L),
                (ContributorId("seed@x"), 1L)
              )
            )
          )
        )
      },
      test("renders round-trip") {
        val s = "(jdegoes@example.com->2323,vigoo@example.com->239)"
        assertTrue(Version.parse(s).map(_.render) == Right(s))
      },
      test("rejects whitespace anywhere") {
        assertTrue(
          Version.parse("(a@x->1, b@x->1)").isLeft,
          Version.parse("( a@x->1)").isLeft,
          Version.parse("(a@x->1 )").isLeft
        )
      },
      test("rejects leading zeroes and explicit zero") {
        assertTrue(
          Version.parse("(a@x->01)").isLeft,
          Version.parse("(a@x->0)").isLeft
        )
      },
      test("rejects negative and non-numeric revisions") {
        assertTrue(
          Version.parse("(good@x->-1)").isLeft,
          Version.parse("(a@x->1.5)").isLeft,
          Version.parse("(a@x->x)").isLeft,
          Version.parse("(a@x->)").isLeft
        )
      },
      test("rejects overflow beyond max safe integer") {
        assertTrue(
          Version.parse("(good@x->9007199254740992)").isLeft,
          Version.parse("(good@x->9007199254740991)").isRight
        )
      },
      test("rejects duplicate ids") {
        assertTrue(Version.parse("(a@x->1,a@x->2)").isLeft)
      },
      test("rejects noncanonical ordering") {
        assertTrue(Version.parse("(b@x->1,a@x->1)").isLeft)
      },
      test("rejects missing arrows and empty components") {
        assertTrue(
          Version.parse("(a@x1)").isLeft,
          Version.parse("(a@x->1,)").isLeft,
          Version.parse("(,a@x->1)").isLeft,
          Version.parse("").isLeft,
          Version.parse("a@x->1").isLeft
        )
      },
      test("rejects invalid ids inside versions") {
        assertTrue(Version.parse("(bad id->1)").isLeft)
      },
      test("errors are InvalidVersion variants") {
        assertTrue(
          leftDetail(Version.parse("(a@x->01)")).exists(_.startsWith("invalid version:"))
        )
      }
    ),
    suite("Version causal comparison")(
      test("equal versions compare Equal") {
        val a = Version.parse("(a@x->1,b@x->2)").toOption.get
        val b = Version.parse("(a@x->1,b@x->2)").toOption.get
        assertTrue(Version.causalCompare(a, b) == CausalOrder.Equal)
      },
      test("subset version is Before its superset") {
        val a = Version.parse("(a@x->1)").toOption.get
        val b = Version.parse("(a@x->1,b@x->1)").toOption.get
        assertTrue(
          Version.causalCompare(a, b) == CausalOrder.Before,
          Version.causalCompare(b, a) == CausalOrder.After
        )
      },
      test("lower revision is Before higher revision") {
        val a = Version.parse("(a@x->1)").toOption.get
        val b = Version.parse("(a@x->2)").toOption.get
        assertTrue(Version.causalCompare(a, b) == CausalOrder.Before)
      },
      test("disjoint contributors are Concurrent") {
        val a = Version.parse("(a@x->1)").toOption.get
        val b = Version.parse("(b@x->1)").toOption.get
        assertTrue(Version.causalCompare(a, b) == CausalOrder.Concurrent)
      },
      test("mixed advance/retreat is Concurrent not Before/After") {
        val a = Version.parse("(a@x->2,b@x->1)").toOption.get
        val b = Version.parse("(a@x->1,b@x->2)").toOption.get
        assertTrue(Version.causalCompare(a, b) == CausalOrder.Concurrent)
      },
      test("empty version is Before any nonempty version") {
        val b = Version.parse("(a@x->1)").toOption.get
        assertTrue(
          Version.causalCompare(Version.empty, b) == CausalOrder.Before,
          Version.causalCompare(b, Version.empty) == CausalOrder.After,
          Version.causalCompare(Version.empty, Version.empty) == CausalOrder.Equal
        )
      }
    ),
    suite("Version.join")(
      test("join is componentwise max") {
        val a = Version.parse("(a@x->2,b@x->1)").toOption.get
        val b = Version.parse("(a@x->1,b@x->2,seed@x->1)").toOption.get
        assertTrue(
          Version.join(a, b) == Version.parse("(a@x->2,b@x->2,seed@x->1)").toOption.get
        )
      },
      test("join with empty returns the other side") {
        val a = Version.parse("(alice@x->1,bob@x->1)").toOption.get
        assertTrue(Version.join(a, Version.empty) == a, Version.join(Version.empty, a) == a)
      },
      test("join is commutative and idempotent") {
        val a = Version.parse("(a@x->1,c@x->3)").toOption.get
        val b = Version.parse("(b@x->2,c@x->1)").toOption.get
        assertTrue(Version.join(a, b) == Version.join(b, a), Version.join(a, a) == a)
      }
    ),
    suite("Version.snapOrder")(
      test("extends causal order") {
        val a = Version.parse("(a@x->1)").toOption.get
        val b = Version.parse("(a@x->2)").toOption.get
        assertTrue(
          Version.snapOrder(a, b) < 0,
          Version.snapOrder(b, a) > 0,
          Version.snapOrder(a, a) == 0
        )
      },
      test("concurrent versions order by sorted-id counters (test 09/17 scenario)") {
        val alice = Version.parse("(alice@x->1,seed@x->1)").toOption.get
        val bob = Version.parse("(bob@x->1,seed@x->1)").toOption.get
        // Sorted union is [alice@x, bob@x, seed@x]; at alice@x bob has 0 < 1, so bob is first.
        assertTrue(Version.snapOrder(bob, alice) < 0, Version.snapOrder(alice, bob) > 0)
      },
      test("tie at first ids falls through to later ids") {
        val v1 = Version.parse("(a@x->1,b@x->1)").toOption.get
        val v2 = Version.parse("(a@x->1,b@x->2)").toOption.get
        assertTrue(Version.snapOrder(v1, v2) < 0)
      }
    ),
    suite("Version.knownIn")(
      test("empty version is known in any frontier") {
        val f = Version.parse("(a@x->2,b@x->1)").toOption.get
        assertTrue(Version.knownIn(Version.empty, f))
      },
      test("componentwise <= frontier is known") {
        val f = Version.parse("(a@x->2,b@x->1)").toOption.get
        assertTrue(
          Version.knownIn(Version.parse("(a@x->1)").toOption.get, f),
          Version.knownIn(Version.parse("(a@x->2,b@x->1)").toOption.get, f)
        )
      },
      test("exceeding or unknown contributors are not known") {
        val f = Version.parse("(a@x->2)").toOption.get
        assertTrue(
          !Version.knownIn(Version.parse("(a@x->3)").toOption.get, f),
          !Version.knownIn(Version.parse("(b@x->1)").toOption.get, f)
        )
      }
    ),
    suite("text tokenization")(
      test("splits after every LF retaining LF") {
        assertTrue(tokenize("a\r\nb") == Vector("a\r\n", "b"))
      },
      test("empty file has no tokens") {
        assertTrue(tokenize("") == Vector.empty)
      },
      test("trailing content without LF is a final token") {
        assertTrue(tokenize("a\nb") == Vector("a\n", "b"))
      },
      test("isText rejects NUL bytes and invalid UTF-8") {
        assertTrue(
          !isText(Array[Byte](0x61, 0x00, 0x62)),
          !isText(Array[Byte](0xff.toByte, 0xfe.toByte)),
          isText("hé\n".getBytes("UTF-8")),
          isText(Array.emptyByteArray)
        )
      },
      test("detokenize inverts tokenize") {
        val text = "start\nA\nend\n"
        assertTrue(detokenize(tokenize(text)) == text)
      },
      test("canonical token sequence validation") {
        assertTrue(
          isCanonicalTokenSeq(Vector("a\n", "b\n")),
          isCanonicalTokenSeq(Vector("a\n", "b")),
          isCanonicalTokenSeq(Vector.empty),
          !isCanonicalTokenSeq(Vector("a", "b\n")),
          !isCanonicalTokenSeq(Vector("a\nb\n")),
          !isCanonicalTokenSeq(Vector(""))
        )
      }
    ),
    suite("applyEdit")(
      test("insert-only script creates content from empty base") {
        assertTrue(
          applyEdit(Vector.empty, Vector(EditOp.Insert(Vector("one\n")))) == Right(Vector("one\n"))
        )
      },
      test("empty script on empty base creates empty file") {
        assertTrue(applyEdit(Vector.empty, Vector.empty) == Right(Vector.empty))
      },
      test("retain, delete and insert compose (test 05 golden edit)") {
        val base = Vector("a\n", "b\n", "a\n")
        val edit = Vector(EditOp.Delete(1), EditOp.Retain(2), EditOp.Insert(Vector("a")))
        assertTrue(applyEdit(base, edit) == Right(Vector("b\n", "a\n", "a")))
      },
      test("under-consumption is rejected") {
        val res = applyEdit(Vector("one\n", "two\n"), Vector(EditOp.Retain(1)))
        assertTrue(
          leftDetail(res).exists(_.contains("does not consume old content"))
        )
      },
      test("over-consumption is rejected") {
        val res = applyEdit(Vector("one\n"), Vector(EditOp.Delete(2)))
        assertTrue(
          leftDetail(res).exists(_.contains("consumes beyond old content"))
        )
      },
      test("non-canonical result is rejected") {
        val res = applyEdit(Vector.empty, Vector(EditOp.Insert(Vector("a", "b"))))
        assertTrue(
          leftDetail(res).exists(_.contains("not a canonical token sequence"))
        )
      }
    ),
    suite("hasAdjacentSameKind")(
      test("flags adjacent inserts") {
        val edit = Vector(EditOp.Insert(Vector("a\n")), EditOp.Insert(Vector("b\n")))
        assertTrue(hasAdjacentSameKind(edit).exists(_.contains("adjacent insert")))
      },
      test("flags adjacent retains and deletes") {
        assertTrue(
          hasAdjacentSameKind(Vector(EditOp.Retain(1), EditOp.Retain(1)))
            .exists(_.contains("adjacent retain")),
          hasAdjacentSameKind(Vector(EditOp.Delete(1), EditOp.Delete(1)))
            .exists(_.contains("adjacent delete"))
        )
      },
      test("allows alternating ops") {
        assertTrue(
          hasAdjacentSameKind(
            Vector(EditOp.Retain(1), EditOp.Insert(Vector("a\n")), EditOp.Delete(1))
          ).isEmpty
        )
      }
    ),
    suite("validatePath")(
      test("accepts simple and nested paths") {
        assertTrue(
          validatePath("a").isRight,
          validatePath("nested/file").isRight,
          validatePath("é").isRight
        )
      },
      test("rejects empty, dot, dotdot and empty segments") {
        assertTrue(
          validatePath("").isLeft,
          validatePath(".").isLeft,
          validatePath("..").isLeft,
          validatePath("a/./b").isLeft,
          validatePath("a/../b").isLeft,
          validatePath("a//b").isLeft,
          validatePath("/a").isLeft,
          validatePath("a/").isLeft
        )
      },
      test("rejects backslash and control characters") {
        assertTrue(
          validatePath("a\\b").isLeft,
          validatePath("a\uu0001b").isLeft,
          validatePath("a\tb").isLeft
        )
      },
      test("rejects .snap as first segment only") {
        assertTrue(
          validatePath(".snap/secret").isLeft,
          validatePath(".snap").isLeft,
          validatePath("nested/.snap/file").isRight
        )
      }
    ),
    suite("messages")(
      test("stored message allows tab and LF, rejects other controls") {
        assertTrue(
          validateStoredMessage("first\tline\nsecond").isRight,
          validateStoredMessage("").isLeft,
          validateStoredMessage("bad\uu0001msg").isLeft,
          validateStoredMessage("bad\u007fmsg").isLeft
        )
      },
      test("commit message enforces 4096-byte limit") {
        val ok = "x" * 4096
        val tooLong = "x" * 4097
        assertTrue(
          validateCommitMessage(ok).isRight,
          validateCommitMessage(tooLong).isLeft,
          validateCommitMessage("").isLeft
        )
      },
      test("multibyte characters count by UTF-8 bytes") {
        val em = "é" * 2049 // 4098 bytes
        assertTrue(validateCommitMessage(em).isLeft)
      },
      test("log escaping order: backslash, then tab, then LF (test 04)") {
        val raw = "first\tline\nsecond\\tail"
        assertTrue(escapeLogMessage(raw) == "first\\tline\\nsecond\\\\tail")
      }
    ),
    suite("base64")(
      test("decodes canonical padded base64") {
        val res = decodeCanonicalBase64("AAEC")
        assertTrue(res.map(_.toSeq) == Right(Seq[Byte](0, 1, 2)))
      },
      test("accepts empty base64 as empty bytes") {
        assertTrue(decodeCanonicalBase64("").map(_.toSeq) == Right(Seq.empty[Byte]))
      },
      test("rejects unpadded input") {
        assertTrue(leftDetail(decodeCanonicalBase64("abc")).exists(_.contains("canonical base64")))
      },
      test("rejects non-canonical trailing bits") {
        assertTrue(decodeCanonicalBase64("Ab==").isLeft)
      },
      test("rejects invalid alphabet characters") {
        assertTrue(decodeCanonicalBase64("AB!C").isLeft, decodeCanonicalBase64("AB C").isLeft)
      },
      test("round-trips arbitrary bytes") {
        val bytes = Array[Byte](0, 1, 2, -1, 127, -128)
        assertTrue(decodeCanonicalBase64(encodeBase64(bytes)).map(_.toSeq) == Right(bytes.toSeq))
      }
    ),
    suite("Patch structural equality")(
      test("equal parsed values compare equal regardless of construction order") {
        val p1 = Patch(
          ContributorId("a@x"),
          1L,
          Version.empty,
          "same",
          Vector(Change.Text("f", Vector(EditOp.Insert(Vector("same\n")))))
        )
        val p2 = Patch(
          ContributorId("a@x"),
          1L,
          Version.empty,
          "same",
          Vector(Change.Text("f", Vector(EditOp.Insert(Vector("same\n")))))
        )
        assertTrue(Patch.sameValue(p1, p2))
      },
      test("different messages collide") {
        val base = Patch(ContributorId("a@x"), 1L, Version.empty, "local", Vector(Change.Del("f")))
        assertTrue(!Patch.sameValue(base, base.copy(message = "different")))
      },
      test("different put bytes collide") {
        val base = Patch(
          ContributorId("a@x"),
          1L,
          Version.empty,
          "m",
          Vector(Change.Put("f", Array[Byte](1)))
        )
        val other = Patch(
          ContributorId("a@x"),
          1L,
          Version.empty,
          "m",
          Vector(Change.Put("f", Array[Byte](2)))
        )
        assertTrue(!Patch.sameValue(base, other))
      },
      test("different change types collide") {
        val a = Patch(ContributorId("a@x"), 1L, Version.empty, "m", Vector(Change.Del("f")))
        val b = Patch(
          ContributorId("a@x"),
          1L,
          Version.empty,
          "m",
          Vector(Change.Text("f", Vector.empty))
        )
        assertTrue(!Patch.sameValue(a, b))
      },
      test("result increments only the author component") {
        val base = Version.parse("(b@x->2)").toOption.get
        val p = Patch(ContributorId("a@x"), 3L, base, "m", Vector(Change.Del("f")))
        assertTrue(p.result == Version.parse("(a@x->3,b@x->2)").toOption.get)
      }
    ),
    suite("tree helpers")(
      test("sortedPaths uses unsigned UTF-8 byte order (test 25 golden)") {
        val tree: Tree = Map(
          "😀" -> Array.emptyByteArray,
          "z" -> Array.emptyByteArray,
          "nested/file" -> Array.emptyByteArray,
          "é" -> Array.emptyByteArray
        )
        assertTrue(sortedPaths(tree) == Vector("nested/file", "z", "é", "😀"))
      },
      test("treeEqual compares bytes not references") {
        val a: Tree = Map("f" -> Array[Byte](1, 2, 3))
        val b: Tree = Map("f" -> Array[Byte](1, 2, 3))
        val c: Tree = Map("f" -> Array[Byte](1, 2, 4))
        assertTrue(treeEqual(a, b), !treeEqual(a, c))
      },
      test("isProperAncestor uses segment boundaries") {
        assertTrue(
          isProperAncestor("a", "a/b"),
          !isProperAncestor("a", "a"),
          !isProperAncestor("a", "ab"),
          isProperAncestor("a/b", "a/b/c")
        )
      }
    ),
    suite("Port.parse")(
      test("accepts 0, the default, and the max") {
        assertTrue(
          Port.parse("0").map(p => p: Int) == Right(0),
          Port.parse("8765").map(p => p: Int) == Right(8765),
          Port.parse("65535").map(p => p: Int) == Right(65535),
          (Port.default: Int) == 8765
        )
      },
      test("rejects out-of-range, negative, non-numeric, fractional, and leading-zero input") {
        assertTrue(
          Port.parse("65536").isLeft,
          Port.parse("-1").isLeft,
          Port.parse("abc").isLeft,
          Port.parse("1.5").isLeft,
          Port.parse("07").isLeft,
          Port.parse("").isLeft,
          Port.parse(" 80").isLeft,
          Port.parse("123456").isLeft
        )
      },
      test("invalid port errors echo the raw input (test 14 pins 65536)") {
        assertTrue(
          leftDetail(Port.parse("65536")).contains("invalid port: 65536"),
          leftDetail(Port.parse("abc")).contains("invalid port: abc")
        )
      }
    ),
    suite("ReplayWarning")(
      test("renders auto-resolved <path>: <reason> for all five winners") {
        assertTrue(
          (ReplayWarning.DeleteWins("f"): ReplayWarning).detail == "auto-resolved f: delete-wins",
          (ReplayWarning.LaterCreateWins("p"): ReplayWarning).detail ==
            "auto-resolved p: later-create-wins",
          (ReplayWarning.LaterPutWins("p"): ReplayWarning).detail ==
            "auto-resolved p: later-put-wins",
          (ReplayWarning.NamespaceWins("a/b"): ReplayWarning).detail ==
            "auto-resolved a/b: namespace-wins",
          (ReplayWarning.PutWins("x"): ReplayWarning).detail == "auto-resolved x: put-wins"
        )
      },
      test("byPathThenReason sorts by path (UTF-8) then reason (test 10 order)") {
        val warnings = Vector(
          ReplayWarning.PutWins("b"),
          ReplayWarning.NamespaceWins("a"),
          ReplayWarning.DeleteWins("a"),
          ReplayWarning.DeleteWins("z")
        )
        val sorted = warnings.sorted(using ReplayWarning.byPathThenReason)
        assertTrue(
          sorted.map(w => (w.path, w.reason)) == Vector(
            ("a", "delete-wins"),
            ("a", "namespace-wins"),
            ("b", "put-wins"),
            ("z", "delete-wins")
          )
        )
      }
    ),
    suite("positiveSafeInteger")(
      test("accepts 1 and the JS max safe integer") {
        assertTrue(
          positiveSafeInteger(new java.math.BigDecimal("1"), "revision") == Right(1L),
          positiveSafeInteger(new java.math.BigDecimal("9007199254740991"), "revision") ==
            Right(9007199254740991L)
        )
      },
      test("accepts integer-valued forms 1.0 and 1e2 (JS Number.isInteger semantics)") {
        assertTrue(
          positiveSafeInteger(new java.math.BigDecimal("1.0"), "revision") == Right(1L),
          positiveSafeInteger(new java.math.BigDecimal("1e2"), "revision") == Right(100L)
        )
      },
      test("rejects fractional, zero, negative, and over-max values (tests 23/25)") {
        assertTrue(
          positiveSafeInteger(new java.math.BigDecimal("1.5"), "revision").isLeft,
          positiveSafeInteger(new java.math.BigDecimal("0"), "retain count").isLeft,
          positiveSafeInteger(new java.math.BigDecimal("-1"), "revision").isLeft,
          positiveSafeInteger(new java.math.BigDecimal("9007199254740992"), "revision").isLeft
        )
      },
      test("rejection message carries context and ends with the pinned substring") {
        val r = positiveSafeInteger(new java.math.BigDecimal("1.5"), "revision")
        assertTrue(
          leftDetail(r).contains("revision is not a positive safe integer"),
          leftDetail(r).exists(_.endsWith("positive safe integer"))
        )
      }
    )
  )
}

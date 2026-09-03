package tabbyshell

import zio.test.*

object ValueSpec extends ZIOSpecDefault {
  import Value.*

  override def spec = suite("Value")(
    suite("record smart constructor")(
      test("accepts unique keys and preserves field order") {
        val fields = List("b" -> VInt(1L), "a" -> VStr("x"))
        Value.record(fields) match {
          case Right(VRecord(actual)) => assertTrue(actual == fields)
          case _                      => assertTrue(false)
        }
      },
      test("accepts an empty record") {
        Value.record(Nil) match {
          case Right(VRecord(fields)) => assertTrue(fields.isEmpty)
          case _                      => assertTrue(false)
        }
      },
      test("rejects duplicate keys with DuplicateKey") {
        Value.record(List("a" -> VNull, "b" -> VNull, "a" -> VInt(1L))) match {
          case Left(ConstructionError.DuplicateKey(key)) => assertTrue(key == "a")
          case _                                         => assertTrue(false)
        }
      }
    ),
    suite("table smart constructor")(
      test("accepts unique columns and uniform rows") {
        val columns = List("a", "b")
        val rows = List(List(VInt(1L), VInt(2L)), List(VInt(3L), VInt(4L)))
        Value.table(columns, rows) match {
          case Right(VTable(actualColumns, actualRows)) =>
            assertTrue(actualColumns == columns, actualRows == rows)
          case _ => assertTrue(false)
        }
      },
      test("accepts an empty table") {
        Value.table(List.empty, List.empty) match {
          case Right(VTable(columns, rows)) => assertTrue(columns.isEmpty, rows.isEmpty)
          case _                            => assertTrue(false)
        }
      },
      test("accepts headers with zero data rows") {
        Value.table(List("a", "b"), List.empty) match {
          case Right(VTable(columns, rows)) =>
            assertTrue(columns == List("a", "b"), rows.isEmpty)
          case _ => assertTrue(false)
        }
      },
      test("rejects duplicate columns with DuplicateColumn") {
        Value.table(List("a", "b", "a"), List.empty) match {
          case Left(ConstructionError.DuplicateColumn(column)) => assertTrue(column == "a")
          case _                                               => assertTrue(false)
        }
      },
      test("rejects ragged rows with RaggedTable") {
        Value.table(List("a", "b"), List(List(VInt(1L), VInt(2L)), List(VInt(3L)))) match {
          case Left(ConstructionError.RaggedTable(expected, rowIndex, actual)) =>
            assertTrue(expected == 2, rowIndex == 1, actual == 1)
          case _ => assertTrue(false)
        }
      }
    ),
    suite("trusted constructors")(
      test("recordTrusted returns the record for valid input") {
        val record = Value.recordTrusted(List("a" -> VInt(1L)))
        assertTrue(record.fields == List("a" -> VInt(1L)))
      },
      test("tableTrusted returns the table for valid input") {
        val table = Value.tableTrusted(List("a"), List(List(VInt(1L))))
        assertTrue(table.columns == List("a"), table.rows == List(List(VInt(1L))))
      },
      test("recordTrusted throws on duplicate keys") {
        val attempt = scala.util.Try(Value.recordTrusted(List("a" -> VNull, "a" -> VNull)))
        assertTrue(attempt.failed.toOption.exists(_.isInstanceOf[IllegalStateException]))
      },
      test("tableTrusted throws on ragged rows") {
        val attempt = scala.util.Try(Value.tableTrusted(List("a", "b"), List(List(VInt(1L)))))
        assertTrue(attempt.failed.toOption.exists(_.isInstanceOf[IllegalStateException]))
      }
    ),
    suite("tableFromUniformRecords")(
      test("uniform records become a table with matching columns and rows") {
        val records = List(
          Value.recordTrusted(List("a" -> VInt(1L), "b" -> VStr("x"))),
          Value.recordTrusted(List("a" -> VInt(2L), "b" -> VStr("y")))
        )
        Value.tableFromUniformRecords(records) match {
          case Some(VTable(columns, rows)) =>
            assertTrue(
              columns == List("a", "b"),
              rows == List(List(VInt(1L), VStr("x")), List(VInt(2L), VStr("y")))
            )
          case _ => assertTrue(false)
        }
      },
      test("records with different keys return None") {
        val records = List(
          Value.recordTrusted(List("a" -> VInt(1L))),
          Value.recordTrusted(List("b" -> VInt(2L)))
        )
        assertTrue(Value.tableFromUniformRecords(records).isEmpty)
      },
      test("records with the same keys in a different order return None") {
        val records = List(
          Value.recordTrusted(List("a" -> VInt(1L), "b" -> VInt(2L))),
          Value.recordTrusted(List("b" -> VInt(3L), "a" -> VInt(4L)))
        )
        assertTrue(Value.tableFromUniformRecords(records).isEmpty)
      },
      test("an empty record list returns None") {
        assertTrue(Value.tableFromUniformRecords(Nil).isEmpty)
      }
    ),
    suite("typeName and isScalar")(
      test("typeName covers every variant") {
        assertTrue(
          Value.typeName(VNull) == "null",
          Value.typeName(VBool(true)) == "bool",
          Value.typeName(VInt(1L)) == "int",
          Value.typeName(VFloat(1.0)) == "float",
          Value.typeName(VStr("s")) == "string",
          Value.typeName(VFilesize(1L)) == "filesize",
          Value.typeName(VDate(0L)) == "date",
          Value.typeName(VList(Nil)) == "list",
          Value.typeName(Value.recordTrusted(Nil)) == "record",
          Value.typeName(Value.tableTrusted(Nil, Nil)) == "table"
        )
      },
      test("isScalar is false only for containers") {
        assertTrue(
          Value.isScalar(VNull),
          Value.isScalar(VInt(1L)),
          Value.isScalar(VStr("s")),
          !Value.isScalar(VList(Nil)),
          !Value.isScalar(Value.recordTrusted(Nil)),
          !Value.isScalar(Value.tableTrusted(Nil, Nil))
        )
      }
    )
  )
}

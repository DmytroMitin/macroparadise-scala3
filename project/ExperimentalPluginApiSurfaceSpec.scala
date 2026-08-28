object ExperimentalPluginApiSurfaceSpec {
  val CaseCount = 23

  def run(): Unit = {
    var completed = 0
    def check(name: String)(body: => Unit): Unit = {
      try {
        body
        completed += 1
      } catch {
        case error: Throwable =>
          throw new AssertionError(s"surface spec failed: $name", error)
      }
    }
    def fails(fragment: String)(body: => Unit): Unit = {
      val error = try {
        body
        throw new AssertionError("expected failure")
      } catch {
        case expected: IllegalArgumentException => expected
      }
      assert(error.getMessage.contains(fragment), error.getMessage)
    }

    val header = Vector(
      "format-version=1",
      "scala-compiler=exact",
      "artifact-role=experimental"
    )

    check("reviewed 3.8.4 surface remains byte-for-byte unchanged") {
      assert(
        ExperimentalPluginApiSurface.expectedSurfaceForExactCompilerLine(
          header,
          "3.8.4"
        ) == header
      )
    }
    check("3.3.8 normalizes only its known enum superclass encoding") {
      val enumRecord =
        "CLASS|paradise3/api/ExpansionOutcome.class|HANDLER_CONTRACT|public abstract class paradise3.api.ExpansionOutcome implements scala.reflect.Enum"
      val ordinaryRecord =
        "CLASS|paradise3/api/ExpansionInput.class|HANDLER_CONTRACT|public final class paradise3.api.ExpansionInput implements scala.Product"
      assert(
        ExperimentalPluginApiSurface.expectedSurfaceForExactCompilerLine(
          Vector("scala-compiler=3.8.4", enumRecord, ordinaryRecord),
          "3.3.8"
        ) == Vector(
          "scala-compiler=3.3.8",
          enumRecord.replace(
            " implements scala.reflect.Enum",
            " implements scala.Product,scala.reflect.Enum"
          ),
          ordinaryRecord
        )
      )
    }
    check("3.3.8 normalizes both companion conflict policy enum encodings") {
      val enumRecords = Vector(
        "CompanionMethodConflictPolicy",
        "CompanionTypeConflictPolicy"
      ).map { name =>
        s"CLASS|paradise3/api/helpers/$name.class|HANDLER_CONTRACT|public abstract class paradise3.api.helpers.$name implements scala.reflect.Enum"
      }
      assert(
        ExperimentalPluginApiSurface.expectedSurfaceForExactCompilerLine(
          "scala-compiler=3.8.4" +: enumRecords,
          "3.3.8"
        ) == "scala-compiler=3.3.8" +: enumRecords.map(
          _.replace(
            " implements scala.reflect.Enum",
            " implements scala.Product,scala.reflect.Enum"
          )
        )
      )
    }
    check("3.3.8 normalizes the bounded body-view enum encodings") {
      val enumRecords = Vector(
        "AnnotatedClassBodyView$DirectMemberKind",
        "AnnotatedClassBodyView$DirectMethodStatus",
        "AnnotatedClassBodyView$DirectTypeShape",
        "AnnotatedClassBodyView$DirectVisibility"
      ).map { name =>
        s"CLASS|paradise3/api/$name.class|HANDLER_CONTRACT|public abstract class paradise3.api.$name implements scala.reflect.Enum"
      }
      val actual = ExperimentalPluginApiSurface.expectedSurfaceForExactCompilerLine(
        "scala-compiler=3.8.4" +: enumRecords,
        "3.3.8"
      )
      assert(actual.head == "scala-compiler=3.3.8")
      assert(
        actual.tail == enumRecords.map(
          _.replace(
            " implements scala.reflect.Enum",
            " implements scala.Product,scala.reflect.Enum"
          )
        )
      )
    }

    check("deterministic ordering") {
      assert(
        ExperimentalPluginApiSurface.canonicalizeRecords(Vector("z", "a")) ==
          Vector("a", "z")
      )
    }
    check("duplicate record rejection") {
      fails("duplicate surface records") {
        ExperimentalPluginApiSurface.canonicalizeRecords(Vector("a", "a"))
      }
    }
    check("missing header") {
      fails("missing the format-version") {
        ExperimentalPluginApiSurface.parseManifest(
          ExperimentalPluginApiSurface.withIntegrity(Vector("scala=x"))
        )
      }
    }
    check("unsupported format") {
      fails("unsupported surface manifest") {
        ExperimentalPluginApiSurface.parseManifest(
          ExperimentalPluginApiSurface.withIntegrity(Vector("format-version=2"))
        )
      }
    }
    check("integrity mismatch") {
      fails("integrity mismatch") {
        ExperimentalPluginApiSurface.parseManifest(
          header :+ "normalized-sha256=wrong"
        )
      }
    }
    check("added class") {
      val diff = ExperimentalPluginApiSurface.compare(
        header,
        header :+ "CLASS|paradise3/api/New.class|HANDLER_CONTRACT|public class New"
      )
      assert(diff.added.size == 1 && diff.removed.isEmpty && diff.changed.isEmpty)
    }
    check("removed class") {
      val record = "CLASS|paradise3/api/Old.class|HANDLER_CONTRACT|public class Old"
      val diff = ExperimentalPluginApiSurface.compare(header :+ record, header)
      assert(diff.removed == Vector(record))
    }
    check("changed descriptor") {
      val oldRecord =
        "MEMBER|paradise3/api/A.class|METHOD|run|0|public|-|()V"
      val newRecord =
        "MEMBER|paradise3/api/A.class|METHOD|run|0|public|-|(I)V"
      val diff = ExperimentalPluginApiSurface.compare(header :+ oldRecord, header :+ newRecord)
      assert(diff.changed.size == 1 && diff.changed.head._1.contains("MEMBER"))
    }
    check("visibility change") {
      val publicRecord =
        "MEMBER|paradise3/api/A.class|METHOD|run|0|public|-|()V"
      val protectedRecord =
        "MEMBER|paradise3/api/A.class|METHOD|run|0|protected|-|()V"
      val diff = ExperimentalPluginApiSurface.compare(
        header :+ publicRecord,
        header :+ protectedRecord
      )
      assert(diff.changed.size == 1)
    }
    check("missing metadata carrier") {
      assert(
        ExperimentalPluginApiSurface
          .validateClassification(Vector.empty)
          .exists(_.contains("missing metadata carrier"))
      )
    }
    check("marker category mismatch") {
      val marker = ExperimentalPluginApiSurface.FixtureMarkerEntries.head
      val records = Vector(
        s"CLASS|${ExperimentalPluginApiSurface.MetadataCarrierEntry}|METADATA_CARRIER|carrier",
        s"CLASS|$marker|HANDLER_CONTRACT|marker"
      )
      assert(
        ExperimentalPluginApiSurface
          .validateClassification(records)
          .exists(_.contains("category mismatch"))
      )
    }
    check("forbidden package entry") {
      assert(
        ExperimentalPluginApiSurface
          .entryPolicyErrors(Vector("macroparadise/Plugin.class"))
          .exists(_.contains("forbidden packaged ownership"))
      )
    }
    check("standard manifest metadata acceptance") {
      assert(
        ExperimentalPluginApiSurface
          .entryPolicyErrors(Vector("META-INF/MANIFEST.MF"))
          .isEmpty
      )
    }
    check("standard license metadata acceptance") {
      assert(
        ExperimentalPluginApiSurface
          .entryPolicyErrors(Vector("META-INF/LICENSE"))
          .isEmpty
      )
    }
    check("split duplicate entry rejection") {
      assert(
        ExperimentalPluginApiSurface
          .splitOwnershipErrors(
            Vector("paradise3/api/A.class"),
            Vector("paradise3/api/A.class")
          )
          .exists(_.contains("duplicate entries across split artifacts"))
      )
    }
    check("split ownership reversal rejection") {
      assert(
        ExperimentalPluginApiSurface
          .splitOwnershipErrors(
            Vector("paradise3/externalDebug.class"),
            Vector("paradise3/api/expander.class")
          )
          .exists(_.contains("contract artifact owns non-contract"))
      )
    }
    check("split package-realistic fixture marker acceptance") {
      assert(
        ExperimentalPluginApiSurface
          .splitOwnershipErrors(
            Vector("paradise3/api/Handler.class"),
            Vector(
              "qualifiedone/audit.class",
              "qualifiedone/audit.tasty"
            )
          )
          .exists(_.contains("is missing fixtures"))
      )
      assert(
        !ExperimentalPluginApiSurface
          .splitOwnershipErrors(
            Vector("paradise3/api/Handler.class"),
            Vector(
              "qualifiedone/audit.class",
              "qualifiedone/audit.tasty"
            )
          )
          .exists(_.contains("non-fixture entry `qualifiedone"))
      )
    }
    check("presentation normalization preserves descriptors") {
      val path = ExperimentalPluginApiSurface.normalizePresentation(
        "/tmp/work/artifact.jar descriptor=()Lparadise3/api/A;",
        Seq("/tmp/work")
      )
      assert(path.contains("<ABSOLUTE_PATH>/artifact.jar"))
      assert(path.contains("descriptor=()Lparadise3/api/A;"))
      assert(
        ExperimentalPluginApiSurface.normalizePresentation(
          "javap 25.0.3",
          Nil
        ) == "javap <TOOL_BANNER>"
      )
    }
    check("focused diff rendering") {
      val diff = ExperimentalPluginApiSurface.SurfaceDiff(
        Vector("CLASS|new"),
        Vector("CLASS|old"),
        Vector(("MEMBER|A|METHOD|run|0", "old", "new"))
      )
      val rendered = diff.render
      assert(rendered.contains("CHANGED MEMBER|A|METHOD|run|0"))
      assert(rendered.contains("REMOVED CLASS|old"))
      assert(rendered.contains("ADDED CLASS|new"))
    }

    assert(completed == CaseCount, s"expected $CaseCount cases, completed $completed")
  }
}

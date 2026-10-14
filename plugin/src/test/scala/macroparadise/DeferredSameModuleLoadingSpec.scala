package macroparadise

import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.reporting.StoreReporter
import paradise3.api.{ExpansionChanges, ExpansionHandler, ExpansionInput, ExpansionOutcome}

import java.net.{URL, URLClassLoader}

final class DeferredMismatchTestHandler extends ExpansionHandler:
  def annotationName: String = "demo.otherMarker"

  def expand(input: ExpansionInput)(using Context): ExpansionOutcome =
    ExpansionOutcome.Structured(ExpansionChanges())

final class DeferredSameModuleLoadingSpec extends munit.FunSuite:
  import DeferredSameModuleHandlerSupport.{SourceDigest, SourceIdentity}
  import ExternalHandlerLoading.{DeferredLoadResult, DeferredSameModuleHandler}

  test("deferred pair loading rejects a descriptor mismatch with the configured identities") {
    val reporter = StoreReporter()
    given Context = ContextBase().initialCtx.fresh.setReporter(reporter)
    val deferred = configuration(
      "demo.markerA",
      classOf[DeferredMismatchTestHandler].getName
    )

    assertEquals(
      ExternalHandlerLoading.loadDeferred(deferred, getClass.getClassLoader),
      DeferredLoadResult.Invalid
    )
    val messages = reporter.removeBufferedMessages.map(_.message())
    assert(
      messages.exists(
        _ ==
          s"experimental same-module handler `${deferred.handlerClassName}` claims `demo.otherMarker` instead of configured annotation `demo.markerA`"
      ),
      messages.mkString("\n")
    )
  }

  test("deferred pair loading reports unavailable and closes its fresh loader") {
    given Context = ContextBase().initialCtx
    val loader = TrackingLoader()
    val result =
      try
        ExternalHandlerLoading.loadDeferred(
          configuration("demo.markerA", "missing.PairHandler"),
          loader
        )
      finally ExternalHandlerLoading.closeDeferredLoader(loader)

    assertEquals(result, DeferredLoadResult.Unavailable)
    assert(loader.closed)
  }

  private def configuration(
      annotationName: String,
      handlerClassName: String
  ): DeferredSameModuleHandler =
    DeferredSameModuleHandler(
      annotationName,
      handlerClassName,
      SourceIdentity.parse("demo/MarkerA.scala").toOption.get,
      SourceIdentity.parse("demo/HandlerA.scala").toOption.get,
      SourceDigest.parse("sha256:" + "0" * 64).toOption.get
    )

  private final class TrackingLoader
      extends URLClassLoader(Array.empty[URL], getClass.getClassLoader):
    var closed = false

    override def close(): Unit =
      closed = true
      super.close()

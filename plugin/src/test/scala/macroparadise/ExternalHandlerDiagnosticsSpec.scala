package macroparadise

import paradise3.api.ParadiseAnnotationExpander

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.net.{URL, URLClassLoader}

class ExternalHandlerDiagnosticsSpec extends munit.FunSuite:
  test("missing explicit handler classpath gives stable first-use wiring guidance") {
    val diagnostic =
      ExternalHandlerDiagnostics.handlerLoadFailure(
        "com.example.macro.handlers.IdentityHandler",
        new ClassNotFoundException("com.example.macro.handlers.IdentityHandler"),
        Nil
      )

    assert(diagnostic.contains("stage=loading"), diagnostic)
    assert(diagnostic.contains("category=HANDLER_LOAD_FAILURE"), diagnostic)
    assert(diagnostic.contains("handlerClasspathConfigured=false"), diagnostic)
    assert(diagnostic.contains("handlerClasspathEntries=0"), diagnostic)
    assert(diagnostic.contains("-P:macroparadise:handlerClasspath=<handler-jar-or-path-list>"), diagnostic)
    assert(diagnostic.contains("ordinary source compilation classpath"), diagnostic)
    assert(diagnostic.contains("compiler-plugin loader classpath"), diagnostic)
    assert(diagnostic.contains("marker metadata"), diagnostic)
    assert(!diagnostic.contains("requestedLoader="), diagnostic)
  }

  test("ineffective explicit handler classpath identifies the selected handler and entry count") {
    val diagnostic =
      ExternalHandlerDiagnostics.handlerLoadFailure(
        "com.example.macro.handlers.IdentityHandler",
        new ClassNotFoundException("com.example.macro.handlers.IdentityHandler"),
        List("/task/handler.jar", "/task/handler-dependency.jar")
      )

    assert(diagnostic.contains("handlerClasspathConfigured=true"), diagnostic)
    assert(diagnostic.contains("handlerClasspathEntries=2"), diagnostic)
    assert(diagnostic.contains("explicit external handler classpath cannot load selected handler"), diagnostic)
    assert(diagnostic.contains("com.example.macro.handlers.IdentityHandler"), diagnostic)
    assert(!diagnostic.contains("/task/handler.jar"), diagnostic)
  }

  test("ordinary non-handler class is classified as a loading type mismatch") {
    val diagnostic =
      ExternalHandlerDiagnostics.typeMismatch(
        "java.lang.String",
        classOf[String],
        classOf[String].getClassLoader
      )

    assert(diagnostic.contains("stage=loading"), diagnostic)
    assert(diagnostic.contains("category=HANDLER_TYPE_MISMATCH"), diagnostic)
    assert(diagnostic.contains("loaderPolicy=parent-first"), diagnostic)
    assert(diagnostic.contains("does not implement paradise3.api.ParadiseAnnotationExpander"), diagnostic)
  }

  test("same binary API name from a child loader is classified as identity mismatch") {
    val apiLocation =
      classOf[ParadiseAnnotationExpander]
        .getProtectionDomain
        .getCodeSource
        .getLocation
    val duplicateLoader =
      ChildFirstApiLoader(
        Array(apiLocation),
        classOf[ParadiseAnnotationExpander].getClassLoader
      )

    try
      val duplicateApi =
        duplicateLoader.loadClass("paradise3.api.ParadiseAnnotationExpander")
      assert(duplicateApi ne classOf[ParadiseAnnotationExpander])
      val proxy =
        Proxy.newProxyInstance(
          duplicateLoader,
          Array(duplicateApi),
          NoOpInvocationHandler
        )

      val diagnostic =
        ExternalHandlerDiagnostics.typeMismatch(
          "synthetic.DuplicateApiHandler",
          proxy.getClass,
          duplicateLoader
        )

      assert(diagnostic.contains("stage=loading"), diagnostic)
      assert(diagnostic.contains("category=API_IDENTITY_MISMATCH"), diagnostic)
      assert(diagnostic.contains("loaderPolicy=parent-first"), diagnostic)
      assert(diagnostic.contains("expectedApiLoader="), diagnostic)
      assert(diagnostic.contains("handlerApiLoader="), diagnostic)
      assert(diagnostic.contains("different classloader identity"), diagnostic)
    finally duplicateLoader.close()
  }

  private object NoOpInvocationHandler extends InvocationHandler:
    def invoke(proxy: Object, method: Method, args: Array[Object]): Object = null

  private final class ChildFirstApiLoader(urls: Array[URL], parent: ClassLoader)
      extends URLClassLoader(urls, parent):
    override protected def loadClass(name: String, resolve: Boolean): Class[?] = synchronized:
      if name.startsWith("paradise3.api.") then
        val loaded = findLoadedClass(name)
        val value =
          if loaded != null then loaded
          else
            try findClass(name)
            catch case _: ClassNotFoundException => super.loadClass(name, false)
        if resolve then resolveClass(value)
        value
      else super.loadClass(name, resolve)

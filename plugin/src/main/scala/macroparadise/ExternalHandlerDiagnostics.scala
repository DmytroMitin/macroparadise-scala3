package macroparadise

import paradise3.api.ParadiseAnnotationExpander

private[macroparadise] object ExternalHandlerDiagnostics:
  enum Stage(val label: String):
    case Discovery extends Stage("discovery")
    case Loading extends Stage("loading")
    case Invocation extends Stage("invocation")
    case OutputValidation extends Stage("output-validation")

  def render(
      stage: Stage,
      category: String,
      fields: (String, String)*
  ): String =
    val renderedFields =
      fields.iterator
        .map((name, value) => s"$name=${normalize(value)}")
        .mkString(" ")
    s"external handler failure: stage=${stage.label} category=$category $renderedFields"

  def typeMismatch(
      requestedClassName: String,
      actualClass: Class[?],
      requestedLoader: ClassLoader
  ): String =
    val expectedApi = classOf[ParadiseAnnotationExpander]
    sameNamedContract(actualClass, expectedApi.getName) match
      case Some(actualApi) if actualApi ne expectedApi =>
        render(
          Stage.Loading,
          "API_IDENTITY_MISMATCH",
          "handler" -> requestedClassName,
          "actualClass" -> actualClass.getName,
          "loaderPolicy" -> "parent-first",
          "requestedLoader" -> loaderIdentity(requestedLoader),
          "handlerLoader" -> loaderIdentity(actualClass.getClassLoader),
          "expectedApi" -> expectedApi.getName,
          "expectedApiLoader" -> loaderIdentity(expectedApi.getClassLoader),
          "handlerApiLoader" -> loaderIdentity(actualApi.getClassLoader),
          "detail" -> s"external annotation handler `$requestedClassName` implements `${expectedApi.getName}` from a different classloader identity"
        )
      case _ =>
        render(
          Stage.Loading,
          "HANDLER_TYPE_MISMATCH",
          "handler" -> requestedClassName,
          "actualClass" -> actualClass.getName,
          "loaderPolicy" -> "parent-first",
          "requestedLoader" -> loaderIdentity(requestedLoader),
          "handlerLoader" -> loaderIdentity(actualClass.getClassLoader),
          "expectedApi" -> expectedApi.getName,
          "expectedApiLoader" -> loaderIdentity(expectedApi.getClassLoader),
          "detail" -> s"external annotation handler `$requestedClassName` does not implement ${expectedApi.getName}; got `${actualClass.getName}`"
        )

  def loaderIdentity(loader: ClassLoader): String =
    if loader == null then "bootstrap"
    else s"${loader.getClass.getName}@${System.identityHashCode(loader).toHexString}"

  def normalize(value: String): String =
    Option(value)
      .map(_.replaceAll("\\s+", " ").trim)
      .filter(_.nonEmpty)
      .getOrElse("<no-message>")

  private def sameNamedContract(
      candidate: Class[?],
      expectedName: String
  ): Option[Class[?]] =
    val visited = scala.collection.mutable.Set.empty[Class[?]]

    def loop(current: Class[?]): Option[Class[?]] =
      if current == null || visited.contains(current) then None
      else
        visited += current
        current.getInterfaces.iterator
          .map: interfaceClass =>
            if interfaceClass.getName == expectedName then Some(interfaceClass)
            else loop(interfaceClass)
          .collectFirst { case Some(found) => found }
          .orElse(loop(current.getSuperclass))

    loop(candidate)

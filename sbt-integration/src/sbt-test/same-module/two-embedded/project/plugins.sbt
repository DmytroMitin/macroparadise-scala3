resolvers ++= sys.props.get("p229.ivy").toSeq.map { root =>
  Resolver.file("p236-task-ivy", file(root) / "local")(Resolver.ivyStylePatterns)
}

addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % sys.props.getOrElse("plugin.version", "0.2.0-SNAPSHOT"))

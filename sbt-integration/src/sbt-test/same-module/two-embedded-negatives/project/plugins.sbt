resolvers ++= sys.props.get("p229.ivy").toSeq.map { root =>
  Resolver.file("p236-task-ivy", file(root) / "local")(Resolver.ivyStylePatterns)
}

addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % sys.props("plugin.version"))

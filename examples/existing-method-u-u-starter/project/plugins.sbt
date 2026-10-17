resolvers ++= sys.props.get("macroparadise.example.localIvy").toSeq.map { root =>
  Resolver.file("isolated-local-ivy", file(root))(Resolver.ivyStylePatterns)
}

addSbtPlugin("com.github.dmytromitin" % "sbt-macroparadise" % "0.2.0-SNAPSHOT")

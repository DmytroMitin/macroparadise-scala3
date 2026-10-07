scalaVersion := sys.props.getOrElse("test.scala.version", "3.8.4")

val productVersion = "0.2.0-SNAPSHOT"
val organizationId = "com.github.dmytromitin"

libraryDependencies += compilerPlugin(
  (organizationId % "macroparadise-scala3-plugin" % productVersion)
    .cross(CrossVersion.full)
)
libraryDependencies +=
  (organizationId % "macroparadise-scala3-plugin-api" % productVersion)
    .cross(CrossVersion.full)

Compile / scalacOptions ++= Seq(
  "-Xplugin-require:macroparadise",
  "-P:macroparadise:handlerClasspath=" + (Compile / classDirectory).value.getCanonicalPath,
  "-P:macroparadise:sameModuleEmbedded=demo.marker:demo/EmbeddedAnnotations.scala",
  "-P:macroparadise:sameModuleSourceIdentity=sha256:" + ("a" * 64)
)

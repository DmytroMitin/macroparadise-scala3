package macroparadise.sbt

import java.io.File
import java.util.Properties
import java.util.jar.JarFile

import sbt._
import sbt.Keys._

object MacroParadisePrecompiledPlugin extends AutoPlugin {
  private val IntegrationVersion = "0.1.1-SNAPSHOT"
  private val Organization = "com.github.dmytromitin"
  private val PluginModule = "macroparadise-scala3-plugin"
  private val PluginApiModule = "macroparadise-scala3-plugin-api"
  private val SupportedScalaVersions = Set("3.3.8", "3.8.4", "3.9.0")

  object autoImport {
    val macroParadiseCompilerProductVersion =
      settingKey[String]("Macro-Paradise compiler product version selected independently of the sbt plugin version")
    val macroParadiseCompilerPluginModule =
      settingKey[ModuleID]("Exact-full-cross Macro-Paradise compiler-plugin module")
    val macroParadisePluginApiModule =
      settingKey[ModuleID]("Exact-full-cross Macro-Paradise plugin-API module")
    val macroParadiseMarkerModules =
      settingKey[Seq[ModuleID]]("Published marker modules added as ordinary consumer dependencies")
    val macroParadiseHandlerModules =
      settingKey[Seq[ModuleID]]("Published handler modules resolved in the hidden handler configuration")
    val macroParadiseMarkerArtifacts =
      taskKey[Seq[LabelledArtifact]]("Labelled explicit marker-role artifacts")
    val macroParadiseHandlerClasspath =
      taskKey[Seq[LabelledArtifact]]("Complete ordered effective handler expansion classpath")
    val macroParadiseAdditionalHandlerClasspath =
      taskKey[Seq[LabelledArtifact]]("Advanced additions to the effective handler expansion classpath")
    val macroParadisePrecheckEnabled =
      settingKey[Boolean]("Whether supported precompiled-mode build validation runs before compilation")
    val macroParadiseValidate =
      taskKey[Unit]("Validate the supported exact line, modules, roles, and artifact inputs")
    val macroParadiseExternalArtifactIdentity =
      taskKey[String]("Derived SHA-256 build identity for marker artifacts and complete handler classpath")
    val macroParadiseCompilerOptions =
      taskKey[Seq[String]]("Inspectable Macro-Paradise compiler options derived from current artifacts")

    def macroParadiseLabelled(label: String, file: File): LabelledArtifact =
      LabelledArtifact(label, file)
  }

  import autoImport._

  private val MacroParadiseHandler = config("macroParadiseHandler").hide
  private val macroParadiseDerivedIdentityInternal =
    taskKey[DerivedArtifactIdentity]("Internal validated Macro-Paradise artifact identity")

  override def trigger: PluginTrigger = noTrigger
  override def projectConfigurations: Seq[Configuration] = Seq(MacroParadiseHandler)

  override def projectSettings: Seq[Def.Setting[_]] =
    inConfig(MacroParadiseHandler)(Defaults.configSettings) ++ Seq(
    macroParadiseCompilerProductVersion := IntegrationVersion,
    macroParadiseCompilerPluginModule :=
      (Organization % PluginModule % macroParadiseCompilerProductVersion.value)
        .cross(CrossVersion.full),
    macroParadisePluginApiModule :=
      (Organization % PluginApiModule % macroParadiseCompilerProductVersion.value)
        .cross(CrossVersion.full),
    macroParadiseMarkerModules := Seq.empty,
    macroParadiseHandlerModules := Seq.empty,
    macroParadiseAdditionalHandlerClasspath := Seq.empty,
    macroParadisePrecheckEnabled := true,
    libraryDependencies += compilerPlugin(macroParadiseCompilerPluginModule.value),
    libraryDependencies ++= macroParadiseMarkerModules.value,
    libraryDependencies ++= macroParadiseHandlerModules.value.map(_ % MacroParadiseHandler.name),
    macroParadiseMarkerArtifacts := resolveConfigured(
      macroParadiseMarkerModules.value,
      (Compile / dependencyClasspath).value,
      "marker"
    ),
    macroParadiseHandlerClasspath := {
      val resolved = resolveHandlerClasspath(
        macroParadiseHandlerModules.value,
        (MacroParadiseHandler / dependencyClasspath).value
      )
      resolved ++ macroParadiseAdditionalHandlerClasspath.value
    },
    macroParadiseDerivedIdentityInternal :=
      validatedIdentity(
        scalaVersion.value,
        macroParadiseCompilerProductVersion.value,
        macroParadiseCompilerPluginModule.value,
        macroParadisePluginApiModule.value,
        libraryDependencies.value,
        macroParadiseMarkerArtifacts.value,
        macroParadiseHandlerClasspath.value
      ),
    macroParadiseValidate := {
      val derived = macroParadiseDerivedIdentityInternal.value
      check(
        macroParadiseExternalArtifactIdentity.value == derived.identity,
        "macroParadiseExternalArtifactIdentity is derived output and cannot be replaced in supported AutoPlugin mode"
      )
      ()
    },
    macroParadiseExternalArtifactIdentity := macroParadiseDerivedIdentityInternal.value.identity,
    macroParadiseCompilerOptions := {
      val derived = macroParadiseDerivedIdentityInternal.value
      check(
        macroParadiseExternalArtifactIdentity.value == derived.identity,
        "macroParadiseExternalArtifactIdentity is derived output and cannot be replaced in supported AutoPlugin mode"
      )
      Seq(
        "-Xplugin-require:macroparadise",
        "-P:macroparadise:handlerClasspath=" +
          derived.handlerClasspath.map(_.file.getAbsolutePath).mkString(File.pathSeparator),
        "-P:macroparadise:externalArtifactIdentity=sha256:" + derived.identity
      )
    },
    Compile / scalacOptions ++= Def.taskDyn {
      if (macroParadisePrecheckEnabled.value)
        Def.task {
          macroParadiseValidate.value
          macroParadiseCompilerOptions.value
        }
      else Def.task(macroParadiseCompilerOptions.value)
    }.value
    )

  private def validatedIdentity(
      targetScalaVersion: String,
      productVersion: String,
      pluginModule: ModuleID,
      apiModule: ModuleID,
      configuredDependencies: Seq[ModuleID],
      markers: Seq[LabelledArtifact],
      handlers: Seq[LabelledArtifact]
  ): DerivedArtifactIdentity = {
    check(
      SupportedScalaVersions(targetScalaVersion),
      s"unsupported target Scala version $targetScalaVersion; expected 3.3.8, 3.8.4, or 3.9.0"
    )
    validateProductModule(pluginModule, PluginModule, productVersion, "compiler plugin")
    validateProductModule(apiModule, PluginApiModule, productVersion, "plugin API")
    val configuredCompilerPlugins = configuredDependencies.filter { module =>
      module.organization == Organization &&
      (module.name == PluginModule || module.name.startsWith(PluginModule + "_"))
    }
    check(
      configuredCompilerPlugins.size == 1,
      s"expected exactly one configured Macro-Paradise compiler-plugin artifact, found ${configuredCompilerPlugins.size}"
    )
    validateProductModule(configuredCompilerPlugins.head, PluginModule, productVersion, "configured compiler plugin")
    val derived = ArtifactIdentity.derive(markers, handlers)
    derived.handlerClasspath.foreach { artifact =>
      check(
        !containsMacroParadiseCompilerPlugin(artifact.file),
        "Macro-Paradise compiler-plugin implementation is forbidden on the handler child path"
      )
    }
    derived
  }

  private def containsMacroParadiseCompilerPlugin(file: File): Boolean = {
    try {
      val jar = new JarFile(file)
      try {
        Option(jar.getJarEntry("plugin.properties")).exists { entry =>
          val properties = new Properties
          val input = jar.getInputStream(entry)
          try properties.load(input)
          finally input.close()
          properties.getProperty("pluginClass") == "macroparadise.MacroParadisePlugin"
        }
      } finally jar.close()
    } catch {
      case _: java.util.zip.ZipException => false
    }
  }

  private def validateProductModule(
      module: ModuleID,
      expectedName: String,
      expectedVersion: String,
      role: String
  ): Unit = {
    check(module.organization == Organization, s"$role organization mismatch")
    check(module.name == expectedName, s"$role module mismatch")
    check(module.revision == expectedVersion, s"$role/product version mismatch")
    check(module.crossVersion == CrossVersion.full, s"$role must use CrossVersion.full")
  }

  private def resolveConfigured(
      modules: Seq[ModuleID],
      classpath: Classpath,
      role: String
  ): Seq[LabelledArtifact] =
    modules.flatMap { requested =>
      val matches = classpath.filter { attributed =>
        attributed.get(moduleID.key).exists { actual =>
          actual.organization == requested.organization &&
          (actual.name == requested.name || actual.name.startsWith(requested.name + "_")) &&
          actual.revision == requested.revision
        }
      }
      check(matches.nonEmpty, s"configured $role module did not resolve: ${requested.organization}:${requested.name}:${requested.revision}")
      matches.zipWithIndex.map { case (attributed, index) =>
        LabelledArtifact(
          s"${requested.organization}:${requested.name}:${requested.revision}:$index",
          attributed.data
        )
      }
    }

  private def resolveHandlerClasspath(
      modules: Seq[ModuleID],
      classpath: Classpath
  ): Seq[LabelledArtifact] = {
    val direct = resolveConfigured(modules, classpath, "handler")
    val directPaths = direct.map(_.file.getCanonicalFile).toSet
    val transitive = classpath.iterator.filterNot { attributed =>
      directPaths(attributed.data.getCanonicalFile)
    }.zipWithIndex.map { case (attributed, index) =>
      val coordinate = attributed.get(moduleID.key).map { module =>
        s"${module.organization}:${module.name}:${module.revision}"
      }.getOrElse(attributed.data.getName)
      LabelledArtifact(f"transitive-$index%04d:$coordinate", attributed.data)
    }.toVector
    direct ++ transitive
  }

  private def check(condition: Boolean, message: String): Unit =
    if (!condition) throw new MessageOnlyException(message)
}

object MacroParadiseIntegration {
  import MacroParadisePrecompiledPlugin.autoImport._

  def precompiledProjects(
      marker: ProjectReference,
      handler: ProjectReference,
      markerLabel: String = "local-marker",
      handlerLabel: String = "local-handler"
  ): Seq[Def.Setting[_]] = Seq(
    macroParadiseMarkerArtifacts := Seq(
      LabelledArtifact(markerLabel, (marker / Compile / packageBin).value)
    ),
    macroParadiseHandlerClasspath := {
      val primary = (handler / Compile / packageBin).value
      val ownClasses = (handler / Compile / classDirectory).value.getCanonicalFile
      val runtime = (handler / Runtime / dependencyClasspath).value
      val dependencies = runtime.filterNot(_.data.getCanonicalFile == ownClasses).zipWithIndex.map { case (attributed, index) =>
        val coordinate = attributed.get(moduleID.key).map { module =>
          s"${module.organization}:${module.name}:${module.revision}"
        }.getOrElse(attributed.data.getName)
        LabelledArtifact(f"runtime-$index%04d:$coordinate", attributed.data)
      }
      LabelledArtifact(handlerLabel, primary) +: dependencies
    }
  )
}

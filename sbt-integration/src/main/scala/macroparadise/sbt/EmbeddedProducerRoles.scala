package macroparadise.sbt

import java.io.{ByteArrayInputStream, DataInputStream, File, FileInputStream, FileOutputStream}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
 * Pure JDK role splitter shared by the producer AutoPlugin and the copyable
 * manual producer path. It deliberately has no sbt or Scala 3 dependency.
 */
object EmbeddedProducerRoles {
  private val ExpanderDescriptor = "Lparadise3/api/expander;"
  private val GeneratedHandlerSuffix = "__MacroParadiseEmbeddedExpansionHandler"
  private val GeneratedTransformSuffix = "__MacroParadiseEmbeddedTransform"
  private val Epoch = FileTime.fromMillis(0L)

  final case class RoleInventory(
      embeddedMarkers: Vector[String],
      generatedHandlers: Vector[String],
      markerEntries: Vector[String],
      handlerEntries: Vector[String],
      markerSha256: String,
      handlerSha256: String
  )

  def packageRoles(
      classDirectory: File,
      markerArtifact: File,
      handlerArtifact: File,
      strict: Boolean
  ): RoleInventory = {
    if (strict)
      require(
        classDirectory.isDirectory,
        s"no valid embedded declaration was found because the producer output is empty: ${classDirectory.getAbsolutePath}"
      )
    val root = normalizedDirectory(classDirectory)
    val markerOutput = markerArtifact.getCanonicalFile
    val handlerOutput = handlerArtifact.getCanonicalFile
    require(markerOutput != handlerOutput, "marker and handler roles require distinct output JARs")
    require(!markerOutput.toPath.startsWith(root), "marker role output must be outside the producer class directory")
    require(!handlerOutput.toPath.startsWith(root), "handler role output must be outside the producer class directory")

    val files = regularFiles(root)
    val classes = files.collect {
      case (entry, path) if entry.endsWith(".class") =>
        val parsed = parseClass(path)
        parsed.internalName -> parsed
    }.toMap

    val generated = classes.values.toVector.flatMap { parsed =>
      parsed.expanderHandler.flatMap { handlerName =>
        val handlerInternal = handlerName.replace('.', '/')
        if (!handlerInternal.endsWith(GeneratedHandlerSuffix)) None
        else {
          require(
            classes.contains(handlerInternal),
            s"embedded marker ${parsed.internalName.replace('/', '.')} names missing generated handler $handlerName"
          )
          Some(parsed.internalName -> handlerInternal)
        }
      }
    }.sortBy(_._1)

    if (strict)
      require(generated.nonEmpty, "no valid embedded declaration was found in the producer output")

    val generatedImplementation = classes.keySet.filter { name =>
      name.endsWith(GeneratedHandlerSuffix) || name.contains(GeneratedTransformSuffix)
    }
    val markerClasses = markerClosure(
      generated.map(_._1).toSet,
      classes,
      generatedImplementation
    )
    val markerEntries = markerFiles(markerClasses, files.keySet).sorted
    val markerSet = markerEntries.toSet
    val excluded = files.keySet.filter(isBundledImplementation)
    val handlerEntries = (files.keySet -- markerSet -- excluded).toVector.sorted

    require(markerEntries.nonEmpty || !strict, "derived marker role is empty")
    require(handlerEntries.nonEmpty || !strict, "derived handler role is empty")
    require(
      markerSet.intersect(handlerEntries.toSet).isEmpty,
      "derived marker and handler roles contain duplicate canonical files"
    )
    require(
      generated.forall { case (_, handler) => handlerEntries.contains(handler + ".class") },
      "derived handler role is missing a generated adapter"
    )
    require(
      generated.forall { case (marker, _) =>
        markerEntries.contains(marker + ".class") && !handlerEntries.contains(marker + ".class")
      },
      "derived marker class leaked into the handler role"
    )

    writeJar(root, markerEntries, markerOutput)
    writeJar(root, handlerEntries, handlerOutput)
    verifyJar(markerOutput, markerEntries, "marker")
    verifyJar(handlerOutput, handlerEntries, "handler")

    RoleInventory(
      generated.map(_._1.replace('/', '.')),
      generated.map(_._2.replace('/', '.')),
      markerEntries,
      handlerEntries,
      sha256(markerOutput.toPath),
      sha256(handlerOutput.toPath)
    )
  }

  def completeHandlerClasspath(
      handlerArtifact: File,
      producerClassDirectory: File,
      runtimeClasspath: Seq[File],
      excludedArtifacts: Seq[File],
      materializedDirectory: File
  ): Vector[File] = {
    val primary = normalizedJar(handlerArtifact)
    val classes = producerClassDirectory.getCanonicalFile
    val excluded = (excludedArtifacts.map(_.getCanonicalFile) :+ classes :+ primary).toSet
    val seenInputs = mutable.LinkedHashSet.empty[File]
    val materialized = runtimeClasspath.iterator.map(_.getCanonicalFile)
      .filterNot(excluded)
      .filter(seenInputs.add)
      .zipWithIndex
      .map { case (entry, index) =>
        require(entry.exists(), s"handler runtime dependency does not exist: ${entry.getAbsolutePath}")
        if (entry.isDirectory) {
          val output = new File(materializedDirectory, f"runtime-$index%04d.jar")
          val root = entry.toPath.toRealPath()
          writeJar(root, regularFiles(root).keys.toVector.sorted, output)
          output.getCanonicalFile
        } else normalizedJar(entry)
      }
      .toVector
    val seenOutputs = mutable.LinkedHashSet.empty[File]
    (primary +: materialized).filter(seenOutputs.add)
  }

  private final case class ClassInfo(
      internalName: String,
      dependencies: Set[String],
      expanderHandler: Option[String]
  )

  private final case class ClassConstant(nameIndex: Int)

  private def markerClosure(
      seeds: Set[String],
      classes: Map[String, ClassInfo],
      generatedImplementation: Set[String]
  ): Set[String] = {
    val result = mutable.LinkedHashSet.empty[String]
    val pending = mutable.Queue(seeds.toVector.sorted: _*)
    while (pending.nonEmpty) {
      val current = pending.dequeue()
      if (result.add(current)) {
        classes.get(current).foreach { parsed =>
          parsed.dependencies.toVector.sorted.foreach { dependency =>
            if (
              classes.contains(dependency) &&
              !generatedImplementation(dependency) &&
              !isBundledImplementation(dependency + ".class") &&
              !result(dependency)
            ) pending.enqueue(dependency)
          }
        }
      }
    }
    result.toSet
  }

  private def markerFiles(markerClasses: Set[String], available: Set[String]): Vector[String] =
    markerClasses.toVector.flatMap { internalName =>
      val topLevel = internalName.takeWhile(_ != '$')
      Vector(internalName + ".class", internalName + ".tasty", topLevel + ".tasty")
        .filter(available)
    }.distinct

  private def isBundledImplementation(entry: String): Boolean =
    entry.startsWith("paradise3/api/") ||
      entry == "plugin.properties" ||
      entry.startsWith("META-INF/services/dotty.tools.dotc.plugins.Plugin")

  private def normalizedDirectory(directory: File): Path = {
    require(directory.isDirectory, s"producer class directory does not exist: ${directory.getAbsolutePath}")
    directory.toPath.toRealPath()
  }

  private def normalizedJar(file: File): File = {
    val normalized = file.getCanonicalFile
    require(normalized.isFile, s"derived artifact is not a regular JAR: ${normalized.getAbsolutePath}")
    require(normalized.getName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"), s"derived artifact is not a regular JAR: ${normalized.getAbsolutePath}")
    normalized
  }

  private def regularFiles(root: Path): Map[String, Path] = {
    val stream = Files.walk(root)
    try stream.iterator().asScala
      .filter(Files.isRegularFile(_))
      .map(path => root.relativize(path).toString.replace(File.separatorChar, '/') -> path)
      .toMap
    finally stream.close()
  }

  private def writeJar(root: Path, entries: Vector[String], output: File): Unit = {
    Option(output.getParentFile).foreach(_.mkdirs())
    Files.deleteIfExists(output.toPath)
    val stream = new JarOutputStream(new FileOutputStream(output))
    try {
      entries.sorted.foreach { name =>
        val entry = new JarEntry(name)
        entry.setTime(0L)
        entry.setCreationTime(Epoch)
        entry.setLastAccessTime(Epoch)
        entry.setLastModifiedTime(Epoch)
        stream.putNextEntry(entry)
        Files.copy(root.resolve(name), stream)
        stream.closeEntry()
      }
    } finally stream.close()
  }

  private def verifyJar(file: File, expected: Vector[String], role: String): Unit = {
    normalizedJar(file)
    val jar = new JarFile(file)
    try {
      val actual = jar.entries().asScala.map(_.getName).filterNot(_.endsWith("/")).toVector
      require(actual == expected.sorted, s"$role role JAR inventory differs from the canonical sorted inventory")
    } finally jar.close()
  }

  private def parseClass(path: Path): ClassInfo = {
    val input = new DataInputStream(new FileInputStream(path.toFile))
    try {
      require(input.readInt() == 0xcafebabe, s"invalid class file: $path")
      input.readUnsignedShort()
      input.readUnsignedShort()
      val count = input.readUnsignedShort()
      val pool = new Array[AnyRef](count)
      var index = 1
      while (index < count) {
        input.readUnsignedByte() match {
          case 1 => pool(index) = input.readUTF()
          case 3 | 4 => input.skipBytes(4)
          case 5 | 6 => input.skipBytes(8); index += 1
          case 7 => pool(index) = ClassConstant(input.readUnsignedShort())
          case 8 | 16 | 19 | 20 => input.skipBytes(2)
          case 9 | 10 | 11 | 12 | 17 | 18 => input.skipBytes(4)
          case 15 => input.skipBytes(3)
          case tag => throw new IllegalArgumentException(s"unsupported class constant tag $tag in $path")
        }
        index += 1
      }
      input.readUnsignedShort()
      val thisClass = input.readUnsignedShort()
      input.readUnsignedShort()
      val internalName = utf8(pool, pool(thisClass).asInstanceOf[ClassConstant].nameIndex)
      skipInterfaces(input)
      skipMembers(input)
      skipMembers(input)
      val expander = readClassAttributes(input, pool)
      val direct = pool.iterator.collect {
        case ClassConstant(nameIndex) => utf8(pool, nameIndex)
      }.toSet
      val descriptors = pool.iterator.collect { case value: String => value }
        .flatMap(descriptorInternalNames).toSet
      ClassInfo(internalName, (direct ++ descriptors) - internalName, expander)
    } finally input.close()
  }

  private def skipInterfaces(input: DataInputStream): Unit =
    (0 until input.readUnsignedShort()).foreach(_ => input.readUnsignedShort())

  private def skipMembers(input: DataInputStream): Unit =
    (0 until input.readUnsignedShort()).foreach { _ =>
      input.skipBytes(6)
      skipAttributes(input)
    }

  private def skipAttributes(input: DataInputStream): Unit =
    (0 until input.readUnsignedShort()).foreach { _ =>
      input.readUnsignedShort()
      val length = input.readInt()
      require(length >= 0, "class attribute is too large")
      input.skipBytes(length)
    }

  private def readClassAttributes(input: DataInputStream, pool: Array[AnyRef]): Option[String] = {
    var result = Option.empty[String]
    (0 until input.readUnsignedShort()).foreach { _ =>
      val name = utf8(pool, input.readUnsignedShort())
      val length = input.readInt()
      require(length >= 0, "class attribute is too large")
      val bytes = new Array[Byte](length)
      input.readFully(bytes)
      if (name == "RuntimeVisibleAnnotations") {
        val annotations = new DataInputStream(new ByteArrayInputStream(bytes))
        try {
          (0 until annotations.readUnsignedShort()).foreach { _ =>
            val descriptor = utf8(pool, annotations.readUnsignedShort())
            val pairs = annotations.readUnsignedShort()
            var value = Option.empty[String]
            (0 until pairs).foreach { _ =>
              val elementName = utf8(pool, annotations.readUnsignedShort())
              val elementValue = readElementValue(annotations, pool)
              if (elementName == "value") value = elementValue
            }
            if (descriptor == ExpanderDescriptor) result = value
          }
        } finally annotations.close()
      }
    }
    result
  }


  private def readElementValue(input: DataInputStream, pool: Array[AnyRef]): Option[String] =
    input.readUnsignedByte().toChar match {
      case 's' => Some(utf8(pool, input.readUnsignedShort()))
      case 'B' | 'C' | 'D' | 'F' | 'I' | 'J' | 'S' | 'Z' | 'c' => input.readUnsignedShort(); None
      case 'e' => input.readUnsignedShort(); input.readUnsignedShort(); None
      case '@' => skipAnnotation(input, pool); None
      case '[' => (0 until input.readUnsignedShort()).foreach(_ => readElementValue(input, pool)); None
      case tag => throw new IllegalArgumentException(s"unsupported annotation element tag $tag")
    }

  private def skipAnnotation(input: DataInputStream, pool: Array[AnyRef]): Unit = {
    input.readUnsignedShort()
    (0 until input.readUnsignedShort()).foreach { _ =>
      input.readUnsignedShort()
      readElementValue(input, pool)
    }
  }

  private def descriptorInternalNames(value: String): Vector[String] = {
    val result = Vector.newBuilder[String]
    var index = 0
    while (index < value.length) {
      if (value.charAt(index) == 'L') {
        var end = index + 1
        while (end < value.length && value.charAt(end) != ';' && value.charAt(end) != '<') end += 1
        val candidate = value.substring(index + 1, end)
        if (candidate.contains('/')) result += candidate
      }
      index += 1
    }
    result.result()
  }

  private def utf8(pool: Array[AnyRef], index: Int): String =
    pool(index).asInstanceOf[String]

  private def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try {
      val buffer = new Array[Byte](8192)
      var read = input.read(buffer)
      while (read >= 0) {
        if (read > 0) digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    } finally input.close()
    digest.digest().map(value => f"${value & 0xff}%02x").mkString
  }
}

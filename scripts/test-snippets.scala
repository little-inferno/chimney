//> using scala 3.3.3
//> using dep org.virtuslab::scala-yaml:0.0.8

import java.io.File
import java.nio.file.{Files, Paths}
import scala.Console.{GREEN, MAGENTA, RED, RESET, YELLOW}
import scala.collection.immutable.ListMap
import scala.util.chaining.*
import scala.util.matching.Regex
import scala.util.{Try, Using}
import scala.sys.process.*

// models

case class Markdown(name: String, content: List[String]) {

  def extractAll: List[Snippet] = Snippet.extractAll(this)
}
object Markdown {

  def readAllInDir(dir: File): List[Markdown] =
    for {
      files <- Option(dir.listFiles()).toList
      markdownFile <- files.sortBy(_.getName()) if markdownFile.getAbsolutePath().endsWith(".md")
    } yield Using(io.Source.fromFile(markdownFile)) { src =>
      val name = markdownFile.getName()
      Markdown(name.substring(0, name.length() - ".md".length()), src.getLines().toList)
    }.get
}

case class Snippet(name: String, hint: String, content: String) {

  def save(tmpDir: File): File = {
    val snippetFile: File = File(s"${tmpDir.getPath()}/$name/snippet.sc")
    snippetFile.getParentFile().mkdirs()
    Files.writeString(snippetFile.toPath(), content)
    snippetFile
  }

  def run(tmpDir: File): Unit = {
    val snippetDir = File(s"${tmpDir.getPath()}/$name/snippet.sc").getParent()
    s"scala-cli run '$snippetDir'".!!
  }
}
object Snippet {

  def extractAll(markdown: Markdown): List[Snippet] = {
    val name = markdown.name

    case class Example(section: String, ordinal: Int = 0) {

      def next: Example = copy(ordinal = ordinal + 1)

      def toName: String = s"${name}_${section}_$ordinal".replaceAll(" +", "-").replaceAll("[^A-Za-z0-9_-]+", "")
    }

    enum Mode:
      case Reading(lineNo: Int, indent: Int, contentReverse: List[String])
      case Awaiting

    import Mode.*

    val start = "```scala"
    val end = "```"
    val sectionName = "#+(.+)".r

    def adjustLine(line: String, indent: Int): String =
      if line.length() > indent then line.substring(indent) else line

    def mkSnippet(example: Example, lineNo: Int, contentReverse: List[String]): Snippet =
      Snippet(example.toName, s"$name.md:$lineNo", contentReverse.reverse.mkString("\n"))

    def loop(content: List[(String, Int)], example: Example, mode: Mode, reverseResult: List[Snippet]): List[Snippet] =
      content match {
        case (line, lineNo) :: lines =>
          mode match {
            case Reading(lineNo, indent, contentReverse) =>
              if line.trim() == end then
                loop(lines, example, Awaiting, mkSnippet(example, lineNo, contentReverse) :: reverseResult)
              else
                loop(lines, example, Reading(lineNo, indent, adjustLine(line, indent) :: contentReverse), reverseResult)
            case Awaiting =>
              line.trim() match {
                case `start` => loop(lines, example.next, Reading(lineNo + 1, line.indexOf(start), Nil), reverseResult)
                case sectionName(section) => loop(lines, Example(section.trim()), Awaiting, reverseResult)
                case _                    => loop(lines, example, Awaiting, reverseResult)
              }
          }
        case Nil => reverseResult.reverse
      }

    loop(markdown.content.zipWithIndex, Example(""), Awaiting, Nil)
  }
}

enum SnippetStrategy:
  case ExpectSuccess
  case ExpectErrors(errors: List[String]) // TODO
  case Ignore(cause: String)

trait SnippetExtension:

  extension (snippet: Snippet)
    def adjusted: Snippet
    def howToRun: SnippetStrategy
    def isIgnored: Boolean = howToRun match
      case SnippetStrategy.Ignore(_) => true
      case _                         => false

// program

extension (s: StringContext)
  def hl(args: Any*): String = s"$MAGENTA${s.s(args*)}$RESET"
  def red(args: Any*): String = s"$RED${s.s(args*)}$RESET"
  def green(args: Any*): String = s"$GREEN${s.s(args*)}$RESET"
  def yellow(args: Any*): String = s"$YELLOW${s.s(args*)}$RESET"

def testSnippets(
    docsDir: File,
    tmpDir: File,
    snippetsDrop: Int,
    snippetsTake: Int
)(using SnippetExtension): Unit = {
  println(hl"Testing with docs in $docsDir, snippets extracted to: tmp=$tmpDir")
  println(hl"Started reading from ${docsDir.getAbsolutePath()}")
  println()
  val markdowns = Markdown.readAllInDir(docsDir)
  println(hl"Read files: ${markdowns.map(_.name)}")
  println()
  val snippets = markdowns.flatMap(_.extractAll).drop(snippetsDrop).take(snippetsTake).map(_.adjusted)
  println(
    hl"Found snippets" + ":\n" + snippets.map(s => hl"\n${s.hint} (${s.name})" + ":\n" + s.content).mkString("\n")
  )
  println()
  val (ignoredSnippets, testedSnippets) = snippets.partition(_.isIgnored)
  println(hl"Ignoring snippets" + ":\n" + ignoredSnippets.map(s => hl"${s.hint} (${s.name})").mkString("\n"))
  println()
  /*
  val ignoredNotFound = ignored.filterNot(i => snippets.exists(_.name == i)).toList.sorted
  if ignoredNotFound.nonEmpty && providedSnippetsDrop == -1 && providedSnippetsTake == -1 then {
    println(
      hl"Some ignored snippets have been moved, their indices changed and cannot be matched" + ":\n" + ignoredNotFound
        .mkString("\n")
    )
    sys.exit(1)
  }
   */
  val failed = snippets.flatMap { snippet =>
    println()
    import snippet.{hint, name}
    // TODO: move to SnippetStrategy
    if snippet.isIgnored then {
      println(yellow"Snippet $hint (stable name: $name) was ignored")
      List.empty[String]
    } else {
      val snippetDir = snippet.save(tmpDir)
      println(hl"Snippet: $hint (stable name: $name) saved in $snippetDir, testing" + ":\n" + snippet.content)
      try {
        snippet.run(tmpDir)
        println(green"Snippet: $hint (stable name: $name) succeeded")
        List.empty[String]
      } catch {
        case _: Throwable =>
          println(red"Snippet: $hint (stable name: $name) failed")
          List(s"$hint (stable name: $name)")
      }
    }
  }

  println()
  if failed.nonEmpty then {
    println(
      hl"Failed snippets (${failed.length}/${testedSnippets.length}, ignored: ${ignoredSnippets.length})" + s":\n${failed
          .mkString("\n")}"
    )
    println(hl"Fix them or add to ignored list (name in parenthesis is less subject to change)")
    sys.exit(1)
  } else {
    println(hl"All snippets (${testedSnippets.length}, ignored: ${ignoredSnippets.length}) run succesfully!")
  }
}

// Chimney-specific configuration

case class MkDocsConfig(extra: Map[String, String])
object MkDocsConfig {

  def parse(cfgFile: File): Either[String, MkDocsConfig] = {
    import org.virtuslab.yaml.*
    def decode(any: Any): Map[String, String] = any match {
      case map: Map[?, ?] =>
        map.flatMap {
          case (k, v: Map[?, ?]) => decode(v).map { case (k2, v2) => s"$k.$k2" -> v2 }
          case (k, v: List[?])   => decode(v).map { case (k2, v2) => s"$k.$k2" -> v2 }
          case (k, v)            => Map(k.toString -> v.toString)
        }.toMap
      case list: List[?] =>
        list.zipWithIndex.flatMap {
          case (i: Map[?, ?], idx) => decode(i).map { case (k, v) => s"[$idx].$k" -> v }
          case (i: List[?], idx)   => decode(i).map { case (k, v) => s"[$idx].$k" -> v }
          case (i, idx)            => Map(s"[$idx]" -> i.toString)
        }.toMap
      case _ => throw new IllegalArgumentException(s"$any is not an expected YAML")
    }
    for {
      cfgStr <- Using(io.Source.fromFile(cfgFile))(_.getLines().toList.mkString("\n")).toEither.left
        .map(_.getMessage())
      cfgRaw <- cfgStr.as[Any].left.map(_.toString)
      extra <- Try(decode(cfgRaw.asInstanceOf[Map[Any, Any]].apply("extra"))).toEither.left.map(_.getMessage)
    } yield MkDocsConfig(extra)
  }
}

enum SpecialHandling:
  case NotExample(reason: String)
  case NeedManual(reason: String)
  case TestErrors

val specialHandling: ListMap[String, SpecialHandling] = ListMap(
  "cookbook_Reusing-flags-for-several-transformationspatchings_3" -> SpecialHandling.NotExample("pseudocode"),
  "cookbook_Automatic-vs-semiautomatic_1" -> SpecialHandling.NotExample("pseudocode"),
  "cookbook_Automatic-vs-semiautomatic_2" -> SpecialHandling.NotExample("pseudocode"),
  "cookbook_Automatic-vs-semiautomatic_3" -> SpecialHandling.NotExample("pseudocode"),
  "cookbook_Automatic-vs-semiautomatic_4" -> SpecialHandling.NotExample("pseudocode"),
  "cookbook_Automatic-vs-semiautomatic_5" -> SpecialHandling.NotExample("pseudocode"),
  "cookbook_Performance-concerns_2" -> SpecialHandling.NotExample("example of code generated by macro"),
  "cookbook_Performance-concerns_3" -> SpecialHandling.NotExample("example of code generated by macro"),
  "cookbook_UnknownFieldSet_1" -> SpecialHandling.TestErrors,
  "cookbook_UnknownFieldSet_2" -> SpecialHandling.NeedManual("continuation from cookbook_UnknownFieldSet_1"),
  "cookbook_UnknownFieldSet_3" -> SpecialHandling.NeedManual("continuation from cookbook_UnknownFieldSet_1"),
  "cookbook_oneof-fields_1" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_oneof-fields_2" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_oneof-fields_3" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_oneof-fields_4" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_oneof-fields_5" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_sealed_value-oneof-fields_1" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_sealed_value-oneof-fields_2" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_sealed_value-oneof-fields_3" -> SpecialHandling.NeedManual("depends on code generated by codegen"),
  "cookbook_sealed_value_optional-oneof-fields_1" -> SpecialHandling.NeedManual(
    "depends on code generated by codegen"
  ),
  "cookbook_sealed_value_optional-oneof-fields_2" -> SpecialHandling.NeedManual(
    "depends on code generated by codegen"
  ),
  "cookbook_Libraries-with-smart-constructors_5" -> SpecialHandling.NotExample("pseudocode"),
  "index__2" -> SpecialHandling.NeedManual("landing page"),
  "index__3" -> SpecialHandling.NeedManual("landing page"),
  "index__4" -> SpecialHandling.NeedManual("landing page"),
  "index__5" -> SpecialHandling.NeedManual("landing page"),
  "index__6" -> SpecialHandling.NeedManual("landing page"),
  "quickstart_Quick-Start_1" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Quick-Start_2" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Java-collections-integration_1" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Java-collections-integration_2" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Cats-integration_1" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Cats-integration_2" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Protocol-Buffers-integration_1" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Protocol-Buffers-integration_2" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Protocol-Buffers-integration_1" -> SpecialHandling.NotExample("sbt example"),
  "quickstart_Protocol-Buffers-integration_2" -> SpecialHandling.NotExample("sbt example"),
  "supported-patching_Ignoring-fields-in-patches_1" -> SpecialHandling.TestErrors,
  "supported-patching_Ignoring-fields-in-patches_3" -> SpecialHandling.TestErrors,
  "supported-transformations_Reading-from-methods_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Reading-from-inherited-valuesmethods_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Reading-from-Bean-getters_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Writing-to-Bean-setters_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Ignoring-unmatched-Bean-setters_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Allowing-fallback-to-the-constructors-default-values_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Allowing-fallback-to-None-as-the-constructors-argument_3" -> SpecialHandling.TestErrors,
  "supported-transformations_Customizing-field-name-matching_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Frominto-an-AnyVal_2" -> SpecialHandling.TestErrors,
  "supported-transformations_Between-sealedenums_2" -> SpecialHandling.NeedManual(
    "snippet fails!!! investigate later"
  ), // FIXME
  "supported-transformations_Between-sealedenums_3" -> SpecialHandling.NeedManual(
    "snippet throws exception!!! investigate later"
  ), // FIXME
  "supported-transformations_Between-sealedenums_4" -> SpecialHandling.NeedManual(
    "snippet throws exception!!! investigate later"
  ), // FIXME
  "supported-transformations_Javas-enums_1" -> SpecialHandling.NeedManual("requires previous snipper with Java code"),
  "supported-transformations_Javas-enums_2" -> SpecialHandling.NeedManual("requires previous snipper with Java code"),
  "supported-transformations_Handling-a-specific-sealed-subtype-with-a-computed-value_3" -> SpecialHandling
    .NeedManual(
      "snippet throws exception!!! investigate later"
    ), // FIXME
  "supported-transformations_Handling-a-specific-sealed-subtype-with-a-computed-value_4" -> SpecialHandling
    .NeedManual(
      "requires previous snipper with Java code"
    ),
  "supported-transformations_Handling-a-specific-sealed-subtype-with-a-computed-value_5" -> SpecialHandling
    .NeedManual(
      "requires previous snipper with Java code"
    ),
  "supported-transformations_Handling-a-specific-sealed-subtype-with-a-computed-value_6" -> SpecialHandling
    .NeedManual(
      "requires previous snipper with Java code"
    ),
  "supported-transformations_Customizing-subtype-name-matching_3" -> SpecialHandling.TestErrors,
  "supported-transformations_Controlling-automatic-Option-unwrapping_1" -> SpecialHandling.TestErrors,
  "supported-transformations_Types-with-manually-provided-constructors_3" -> SpecialHandling.NeedManual(
    "example split into multiple files"
  ),
  "supported-transformations_Types-with-manually-provided-constructors_4" -> SpecialHandling.NeedManual(
    "contunuation from the previous snippet"
  ),
  "supported-transformations_Types-with-manually-provided-constructors_5" -> SpecialHandling.NeedManual(
    "example split into multiple files"
  ),
  "supported-transformations_Types-with-manually-provided-constructors_6" -> SpecialHandling.NeedManual(
    "contunuation from the previous snippet"
  ),
  "supported-transformations_Resolving-priority-of-implicit-Total-vs-Partial-Transformers_1" -> SpecialHandling.TestErrors,
  "supported-transformations_Defining-custom-name-matching-predicate_1" -> SpecialHandling.NeedManual(
    "example split into multiple files"
  ),
  "supported-transformations_Defining-custom-name-matching-predicate_2" -> SpecialHandling.NeedManual(
    "contunuation from the previous snippet"
  ),
  "troubleshooting_Replacing-Lifted-Transformers-TransformerF-with-PartialTransformers_1" -> SpecialHandling
    .NotExample(
      "pseudocode"
    ),
  "troubleshooting_Explicit-enabling-of-default-values_1" -> SpecialHandling.NotExample("pseudocode"),
  "troubleshooting_Ducktape_2" -> SpecialHandling.NeedManual(
    "snippet throws exception!!! investigate later"
  ), // FIXME
  "troubleshooting_Ducktape_4" -> SpecialHandling.NeedManual(
    "snippet throws exception!!! investigate later"
  ), // FIXME
  "troubleshooting_Ducktape_8" -> SpecialHandling.NeedManual(
    "snippet throws exception!!! investigate later"
  ), // FIXME
  "troubleshooting_Ducktape_10" -> SpecialHandling.NeedManual(
    "snippet throws exception!!! investigate later"
  ), // FIXME
  "troubleshooting_Recursive-types-fail-to-compile_1" -> SpecialHandling.NotExample("pseudocode"),
  "troubleshooting_Recursive-types-fail-to-compile_2" -> SpecialHandling.NotExample("pseudocode"),
  "troubleshooting_Recursive-calls-on-implicits_1" -> SpecialHandling.NotExample("pseudocode"),
  "troubleshooting_Recursive-calls-on-implicits_2" -> SpecialHandling.NotExample("pseudocode"),
  "troubleshooting_Recursive-calls-on-implicits_3" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-summons-Transformer-instance_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-summons-Transformer-instance_2" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-summons-Transformer-instance_3" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-summons-Transformer-instance_4" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-summons-Transformer-instance_5" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-summons-Transformer-instance_6" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_How-DSL-manages-customizations_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Carrying-around-the-runtime-configuration_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Carrying-around-the-runtime-configuration_2" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Carrying-around-the-runtime-configuration_3" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Carrying-around-the-runtime-configuration_4" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Carrying-around-the-type-level-configuration_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Total-vs-Partial_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Total-vs-Partial_2" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Total-vs-Partial_3" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Summoning-implicits_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Sealed-hierarchies_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Sealed-hierarchies_2" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_1" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_3" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_4" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_5" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_6" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_7" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_8" -> SpecialHandling.NotExample("pseudocode"),
  "under-the-hood_Scala-2-vs-Scala-3-in-derivation_9" -> SpecialHandling.NotExample("pseudocode")
)

class ChimneySpecific(chimneyVersion: String, cfg: MkDocsConfig) extends SnippetExtension {

  private val defaultScalaVersion = "2.13.13"

  private val replacePatterns = (cfg.extra + (raw"chimney_version\(\)" -> chimneyVersion)).map { case (k, v) =>
    (raw"\{\{\s*" + k + raw"\s*\}\}") -> v
  }

  extension (snippet: Snippet)
    def adjusted: Snippet =
      snippet.copy(content =
        replacePatterns.foldLeft(
          if snippet.content.contains("//> using scala") then snippet.content
          else s"//> using scala $defaultScalaVersion\n${snippet.content}"
        ) { case (s, (k, v)) => s.replaceAll(k, v) }
      )

    def howToRun: SnippetStrategy = specialHandling.get(snippet.name) match
      case None                                     => SnippetStrategy.ExpectSuccess
      case Some(SpecialHandling.NotExample(reason)) => SnippetStrategy.Ignore(reason)
      case Some(SpecialHandling.NeedManual(reason)) => SnippetStrategy.Ignore(reason)
      case Some(SpecialHandling.TestErrors)         => SnippetStrategy.Ignore("TODO error checking")
}

/** Usage:
  *
  * From the project root (if called from other directory, adapt path after PWD accordingly):
  *
  * on CI:
  * {{{
  * # run all tests, use artifacts published locally from current tag
  * scala-cli run scripts/test-snippets.scala -- "$PWD/docs" "$(sbt -batch -error 'print chimney/version')" "" -1 -1
  * }}}
  *
  * during development:
  * {{{
  * # fix: version to use, tmp directory, drop and take from snippets list (the ordering is deterministic)
  * scala-cli run scripts/test-snippets.scala -- "$PWD/docs" "1.0.0-RC1" /var/folders/m_/sm90t09d5591cgz5h242bkm80000gn/T/docs-snippets13141962741435068727 0 44
  * }}}
  */
@main def testChimneySnippets(
    path: String,
    providedVersion: String,
    providedTmpDir: String,
    providedSnippetsDrop: Int,
    providedSnippetsTake: Int
): Unit = {
  val chimneyVersion = providedVersion.trim
    .pipe("\u001b\\[([0-9]+)m".r.replaceAllIn(_, "")) // remove possible console coloring from sbt
    .pipe(raw"(?U)\s".r.replaceAllIn(_, "")) // remove possible ESC characters
    .replaceAll("\u001B\\[0J", "") // replace this one offending thing

  val cfgFile = File(s"$path/mkdocs.yml")
  val cfg = MkDocsConfig.parse(cfgFile).right.get

  given SnippetExtension = new ChimneySpecific(chimneyVersion, cfg)

  testSnippets(
    docsDir = File(s"$path/docs"),
    tmpDir =
      if providedTmpDir.isEmpty() then Files.createTempDirectory(s"docs-snippets").toFile() else File(providedTmpDir),
    snippetsDrop = Option(providedSnippetsDrop).filter(_ >= 0).getOrElse(0),
    snippetsTake = Option(providedSnippetsTake).filter(_ > 0).getOrElse(Int.MaxValue)
  )
}

package org.duhemm.parsermacro

import scala.tools.nsc.Global

import scala.reflect.macros.whitebox.Context
import scala.reflect.runtime.ReflectionUtils
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.annotation.compileTimeOnly

import scala.meta.dialects.Scala211
import scala.meta.Input.String.{ apply => string2input }
import scala.meta.{Token, Tokens}

import java.lang.reflect.Method

class ExpandParserMacro(val c: Context) extends UniverseUtils
                                        with Signatures
                                        with ParserMacroSyntheticObject {

  val universe: c.universe.type = c.universe
  import universe._

  def impl(annottees: c.Tree*): c.Tree = {

    assert(annottees.length == 1)
    val annottee = annottees.head

    val q"new $_($originalTree).macroTransform($_)" = c.macroApplication
    val TemporaryObject(rawArguments) = annottee

    // get classpath and sourcepath from env vars
    val sys = new scala.sys.SystemProperties()
    val cp: String =
      sys.getOrElse("CLASSPATH", sys.getOrElse("sbt.class.directory", c.compilerSettings.last))
    val sp: String = "src/"


    // Warning, normally we put
    // val mirror = scala.meta.Mirror(global)
    // here
    import scala.meta.internal.scalahost.v1.offline.Mirror;

    val mirror = new Mirror(cp, sp, Mirror.autodetectScalahostNscPluginPath)
    val arguments: List[Tokens] = rawArguments.map { string2input }.map { in =>
      mirror.dialect(in).tokenize.get
    }

    val typechecked = c.typecheck(originalTree, mode = c.TYPEmode)
    val methodSymbol = typechecked.symbol.asMethod

    getMacroImplementation(methodSymbol) match {
      case Some((instance, method)) =>
        val expanded =
          try loader.invoke(instance, method, arguments)
          catch {
            case e: InvalidMacroInvocationException =>
              c.error(c.macroApplication.pos, e.msg)
          }

        // There are many trees toGTree cannot convert at the moment
        // (e.g. `def foo = bar`, `class foo extends bar`, ...)
        c.parse(expanded.toString)

      case _ =>
        c.abort(c.macroApplication.pos, "Could not find macro implementation")
    }
  }

  /**
   * Extracts the reference to the macro implementation from a macro method, and returns
   * an instance of the macro provider along with the method to call on it.
   */
  private def getMacroImplementation(sym: MethodSymbol): Option[(Object, Method)] = {

    sym.annotations collect {
      case ann if ann.tree.tpe.toString == macroImplType => ann.tree.children.tail
    } match {
      case _ :: List(signature) :: Nil =>
        val ParserMacroSignature(ddef) = signature.asInstanceOf[universe.Tree]
        val className = erasedName(ddef.symbol.owner)
        val methName = ddef.name.toString + "$impl"
        loader.findMethod(className, methName)

      case _ =>
        c.abort(c.macroApplication.pos, "Unrecognized macro implementation binding")
    }
  }

  private lazy val loader = new GlobalClassLoaderProvider(universe.asInstanceOf[Global])

  /**
   * The pattern to extract the reference to the macro implementation from the string representation
   * of a macro impl binding.
   * This type is private to the package `scala`, thus we cannot use the associated extractor.
   */
  private val legacyImplExtract =
    """`macro`\("macroEngine" = "v7\.0 \(implemented in Scala 2\.11\.0-M8\)", "isBundle" = false, "isBlackbox" = true, "className" = "(.+?)", "methodName" = "(.+?)", "signature" = (?:.+?)""".r

  /**
   * This type is private to scalac implementation. We compare the name of the types...
   */
  private val macroImplType = "scala.reflect.macros.internal.macroImpl"

}

@compileTimeOnly("enable macro paradise to expand macro annotations")
class ParserMacroExpansion(args: Any) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro ExpandParserMacro.impl
}

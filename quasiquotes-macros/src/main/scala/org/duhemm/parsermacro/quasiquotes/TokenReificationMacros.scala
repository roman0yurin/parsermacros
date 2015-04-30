package scala.meta
package parsermacro

import scala.util.matching.Regex

import scala.meta.{ Tree => MetaTree }
import scala.meta.internal.ast.Lit
import scala.meta.internal.quasiquotes.ReificationMacros
import scala.reflect.macros.whitebox.Context

import java.lang.reflect.Method

import org.duhemm.parsermacro.quasiquotes.TokenQuasiquoteLiftables

/**
 * Object used to extract the underlying code for each token.
 * Two tokens are considered equal (in pattern matching) if they both come the same code.
 */
object TokenExtractor {
  def unapply(t: Token) = Some(t.code)
}

class TokenReificationMacros(override val c: Context) extends ReificationMacros(c) with TokenQuasiquoteLiftables {

  import c.universe.{ Tree => ReflectTree, _ }

  override def apply(args: ReflectTree*)(dialect: ReflectTree): c.Tree = expand(dialect)
  override def unapply(scrutinee: ReflectTree)(dialect: ReflectTree): c.Tree = expand(dialect)

  // Extract the interesting parts of toks"..."
  private lazy val q"$_($_.apply(..${parts: List[String]})).$_.$method[..$_](..$args)($_)" = c.macroApplication

  /** Removes the heading BOF and trailing EOF from a sequence of tokens */
  private def trim(toks: Vector[Token]): Vector[Token] = toks match {
    case (_: Token.BOF) +: tokens :+ (_: Token.EOF) => tokens
    case _                                => toks
  }

  private def arg(i: Int): c.Tree = method match {
    case TermName("apply") =>
      args(i)

    case TermName("unapply") =>
      val name = TermName(s"x$i")
      pq"$name @ _"
  }

  /**
   * Gets the tokens for each part of the input, and glue them together in a single Vector
   * by inserting special `Token.Unquote` tokens between them.
   * The tree of the Unquote token is a scala.meta tree that represents the index of the
   * argument.
   */
  private def input(implicit dialect: Dialect): Vector[Token] =
    parts.toVector.init.zipWithIndex.flatMap {
      case (part, i) =>
        trim(part.tokens) :+ Token.Unquote(Input.None, dialect, 0, 0, 0, arg(i), Token.Prototype.None)
    } ++ trim(parts.last.tokens)


  override def expand(dialectTree: c.Tree): c.Tree = {
    implicit val dialect: Dialect = dialects.Quasiquote(instantiateDialect(dialectTree))

    method match {
      case TermName("apply") =>
        input match {
          case Seq(single) => q"$single"
          case tokens      => q"$tokens"
        }

      case TermName("unapply") =>
        def countEllipsisUnquote(toks: Vector[Token]): Int = toks match {
          case (_: Token.Ellipsis) +: (_: Token.Unquote) +: rest =>
            1 + countEllipsisUnquote(rest)
          case other +: rest =>
            countEllipsisUnquote(rest)
          case _ =>
            0
        }

        if (countEllipsisUnquote(input) > 1) {
          c.abort(c.macroApplication.pos, "Cannot use ellipsis-unquote more than once.")
        }

        def patternForToken(t: Token) = t match {
          case t: Token.Unquote => pq"${t.tree.asInstanceOf[c.Tree]}"
          case t                => pq"_root_.scala.meta.parsermacro.TokenExtractor(${t.code})"
        }

        val splitted = {
          def split(toks: Vector[Token]): (Vector[Token], Option[Token], Vector[Token]) = toks match {
            case (_: Token.Ellipsis) +: (u: Token.Unquote) +: rest =>
              (Vector(), Some(u), rest)

            case t +: rest =>
              val (before, middle, after) = split(rest)
              (t +: before, middle, after)

            case Vector() =>
              (Vector(), None, Vector())
          }

          split(input)
        }

        val pattern =
          splitted match {
            case (Vector(), Some(middle), Vector()) =>
              patternForToken(middle)

            case (before, None, _) =>
              val subPatterns = before map patternForToken
              q"Vector(..$subPatterns)"

            case (before, Some(middle), Vector()) =>
              val beforePatterns = before map patternForToken
              (beforePatterns foldRight patternForToken(middle)) {
                case (pat, acc) => pq"$pat +: $acc"
              }

            case (before, Some(middle), after) =>
              val beforePatterns = before map patternForToken
              val afterPatterns  = after map patternForToken

              val withBeforePatterns = (beforePatterns foldRight patternForToken(middle)) {
                case (pat, acc) => pq"$pat +: $acc"
              }

              (afterPatterns foldLeft withBeforePatterns) {
                case (acc, pat) => pq"$acc :+ $pat"
              }

          }

        val (thenp, elsep) =
          if (parts.size == 1) (q"true", q"false")
          else {
            val bindings = parts.init.zipWithIndex map { case (_, i) => val name = TermName(s"x$i") ; q"$name" }
            (q"_root_.scala.Some(..$bindings)", q"_root_.scala.None")
          }

        q"""
          new {
            def unapply(in: Vector[_root_.scala.meta.Token]) = in match {
              case $pattern => $thenp
              case _        => $elsep
            }
          }.unapply(..$args)
        """
    }
  }

  // Required to make use of private members (`instantiateDialect`) defined in `ReificationMacros`
  private val parentClass = classOf[ReificationMacros]
  private def getAndSetAccessible(meth: String): Method = {
    val m = parentClass.getDeclaredMethods.filter(_.getName == meth).headOption.getOrElse(parentClass.getDeclaredMethods.filter(_.getName endsWith meth).head)
    m.setAccessible(true)
    m
  }

  private val instantiateDialect: ReflectTree => Dialect = {
    val parentInstantiateDialect = getAndSetAccessible("instantiateDialect")
    (dialect: ReflectTree) => parentInstantiateDialect.invoke(this, dialect).asInstanceOf[Dialect]
  }
}

package dotty.tools
package dotc
package typer

import ast._
import core._
import Trees._
import Types._, Contexts._, Decorators._, Denotations._, Symbols._
import Applications._
import util.Positions._
import printing.Showable
import reporting.Reporter.SuppressedMessage

object ErrorReporting {

  import tpd._

  def errorTree(tree: untpd.Tree, msg: => String)(implicit ctx: Context): tpd.Tree =
    tree withType errorType(msg, tree.pos)

  def errorType(msg: => String, pos: Position)(implicit ctx: Context): ErrorType = {
    ctx.error(msg, pos)
    ErrorType
  }

  class Errors(implicit ctx: Context) {

    def expectedTypeStr(tp: Type): String = tp match {
      case tp: FunProtoType =>
        val result = tp.resultType match {
          case tp: WildcardType => ""
          case tp => i"and expected result type $tp"
        }
        i"arguments (${tp.typedArgs.tpes}%, %)$result"
      case _ =>
        i"expected type $tp"
    }

    def anonymousTypeMemberStr(tpe: Type) = {
      val kind = tpe match {
          case _: TypeBounds => "type with bounds"
          case _: PolyType | _: MethodType => "method"
          case _ => "value of type"
        }
        i"$kind $tpe"
    }

    def overloadedAltsStr(alts: List[SingleDenotation]) =
      i"overloaded alternatives of ${denotStr(alts.head)} with types\n" +
      i" ${alts map (_.info)}%\n %"

    def denotStr(denot: Denotation): String =
      if (denot.isOverloaded) overloadedAltsStr(denot.alternatives)
      else if (denot.symbol.exists) denot.symbol.showLocated
      else anonymousTypeMemberStr(denot.info)

    def refStr(tp: Type): String = tp match {
      case tp: NamedType => denotStr(tp.denot)
      case _ => anonymousTypeMemberStr(tp)
    }

    def exprStr(tree: Tree): String = refStr(tree.tpe)

    def patternConstrStr(tree: Tree): String = ???

    def typeMismatch(tree: Tree, pt: Type): Tree = {
      val result = errorTree(tree,
        i"""type mismatch:
           | found   : ${tree.tpe}
           | required: $pt""".stripMargin)
      if (ctx.settings.explaintypes.value)
        new ExplainingTypeComparer().isSubType(tree.tpe, pt)
      result
    }

  }

  def err(implicit ctx: Context): Errors = new Errors

  def isSensical(arg: Any)(implicit ctx: Context): Boolean = arg match {
    case tpe: Type if tpe.isErroneous => false
    case NoSymbol => false
    case _ => true
  }

  def treatArg(arg: Any, suffix: String)(implicit ctx: Context): (Any, String) = arg match {
    case arg: Showable =>
      (arg.show, suffix)
    case arg: List[_] if suffix.head == '%' =>
      val (sep, rest) = suffix.tail.span(_ != '%')
      if (rest.nonEmpty) (arg mkString sep, rest.tail)
      else (arg, suffix)
    case _ =>
      (arg, suffix)
  }

  /** Implementation of i"..." string interpolator */
  implicit class InfoString(val sc: StringContext) extends AnyVal {

    def i(args: Any*)(implicit ctx: Context): String = {
      if (ctx.reporter.hasErrors &&
          ctx.suppressNonSensicalErrors &&
          !ctx.settings.YshowSuppressedErrors.value &&
          !args.forall(isSensical(_)))
        throw new SuppressedMessage
      val prefix :: suffixes = sc.parts.toList
      val (args1, suffixes1) = (args, suffixes).zipped.map(treatArg(_, _)).unzip
      new StringContext(prefix :: suffixes1.toList: _*).s(args1: _*)
    }
  }
}
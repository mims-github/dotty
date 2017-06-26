package dotty.tools
package repl

import dotc.reporting.diagnostic.MessageContainer
import dotc.core.Contexts.Context
import dotc.parsing.Parsers.Parser
import dotc.util.SourceFile
import dotc.ast.untpd
import dotc.reporting._

import results._

sealed trait ParseResult
case class Parsed(sourceCode: String, trees: List[untpd.Tree]) extends ParseResult
case class SyntaxErrors(errors: List[MessageContainer]) extends ParseResult
case object Newline extends ParseResult

sealed trait Command extends ParseResult
case class UnknownCommand(cmd: String) extends Command
case class Load(path: String) extends Command
case class Type(expr: String) extends Command
case object Reset extends Command
case object Quit extends Command
case object Help extends Command {
  val text =
    """The REPL has several commands available:
      |
      |:help                    print this summary or command-specific help
      |:load <path>             interpret lines in a file
      |:quit                    exit the interpreter
      |:type <expression>       evaluate the type of the given expression
      |:reset                   reset the repl to its initial state, forgetting all session entries
    """.stripMargin
}

object ParseResult {

  private[this] val CommandExtract = """(:[\S]+)\s*(.*)""".r

  private[this] def storeReporter =
    new StoreReporter(null)
    with UniqueMessagePositions with HideNonSensicalMessages

  def apply(sourceCode: String)(implicit ctx: Context): ParseResult =
    sourceCode match {
      case "" => Newline
      case CommandExtract(cmd, arg) => cmd match {
        case ":quit"  => Quit
        case ":help"  => Help
        case ":reset" => Reset
        case ":load"  => Load(arg)
        case ":type"  => Type(arg)
        case _        => UnknownCommand(cmd)
      }
      case _ => {
        def parse(sourceCode: String): Result[List[untpd.Tree]] = {
          val reporter = storeReporter
          val source = new SourceFile("<console>", sourceCode.toCharArray)
          val parser = new Parser(source)(ctx.fresh.setReporter(reporter))

          val (_, stats) = parser.templateStatSeq

          if (reporter.hasErrors) reporter.removeBufferedMessages.errors
          else stats.result
        }

        parse(sourceCode).fold(SyntaxErrors(_), Parsed(sourceCode, _))
      }
    }

  def isIncomplete(sourceCode: String)(implicit ctx: Context): Boolean =
    sourceCode match {
      case CommandExtract(_) | "" => false
      case _ => {
        val reporter = storeReporter
        var needsMore = false
        reporter.withIncompleteHandler(_ => _ => needsMore = true) {
          val source = new SourceFile("<console>", sourceCode.toCharArray)
          val parser = new Parser(source)(ctx.fresh.setReporter(reporter))
          parser.templateStatSeq
          !reporter.hasErrors && needsMore
        }
      }
    }
}

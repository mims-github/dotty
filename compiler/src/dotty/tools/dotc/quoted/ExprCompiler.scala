package dotty.tools.dotc
package quoted

import dotty.tools.backend.jvm.GenBCode
import dotty.tools.dotc.ast.tpd

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.{EmptyFlags, Method}
import dotty.tools.dotc.core.{Mode, Phases}
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.Scopes.{EmptyScope, newScope}
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.defn
import dotty.tools.dotc.core.Types.ExprType
import dotty.tools.dotc.core.quoted.PickledQuotes
import dotty.tools.dotc.transform.Pickler
import dotty.tools.dotc.typer.FrontEnd
import dotty.tools.dotc.util.Positions.Position
import dotty.tools.dotc.util.SourceFile
import dotty.tools.io.{Path, PlainFile, VirtualDirectory}

import scala.quoted.Expr

/** Compiler that takes the contents of a quoted expression `expr` and produces
 *  a class file with `class ' { def apply: Object = expr }`.
 */
class ExprCompiler(directory: VirtualDirectory) extends Compiler {
  import tpd._

  /** A GenBCode phase that outputs to a virtual directory */
  private class ExprGenBCode extends GenBCode {
    override def phaseName = "genBCode"
    override def outputDir(implicit ctx: Context) = directory
  }

  override def phases: List[List[Phase]] = {
    val backendPhases = super.phases.dropWhile {
      case List(_: Pickler) => false
      case _ => true
    }.tail

    List(new ExprFrontend(putInClass = true)) ::
    Phases.replace(classOf[GenBCode], _ => new ExprGenBCode :: Nil, backendPhases)
  }

  override def newRun(implicit ctx: Context): ExprRun = {
    reset()
    new ExprRun(this, ctx.addMode(Mode.ReadPositions))
  }

  /** Frontend that receives scala.quoted.Expr as input */
  class ExprFrontend(putInClass: Boolean) extends FrontEnd {
    import tpd._

    override def isTyper = false

    override def runOn(units: List[CompilationUnit])(implicit ctx: Context): List[CompilationUnit] = {
      units.map {
        case exprUnit: ExprCompilationUnit =>
          val tree =
            if (putInClass) inClass(exprUnit.expr)
            else PickledQuotes.quotedToTree(exprUnit.expr)
          val source = new SourceFile("", Seq())
          CompilationUnit.mkCompilationUnit(source, tree, forceTrees = true)
      }
    }

    /** Places the contents of expr in a compilable tree for a class
      *  with the following format.
      *  `package __root__ { class ' { def apply: Any = <expr> } }`
      */
    private def inClass(expr: Expr[_])(implicit ctx: Context): Tree = {
      val pos = Position(0)
      val assocFile = new PlainFile(Path("<quote>"))

      val cls = ctx.newCompleteClassSymbol(defn.RootClass, nme.QUOTE.toTypeName, EmptyFlags,
        defn.ObjectType :: Nil, newScope, coord = pos, assocFile = assocFile).entered.asClass
      cls.enter(ctx.newDefaultConstructor(cls), EmptyScope)
      val meth = ctx.newSymbol(cls, nme.apply, Method, ExprType(defn.AnyType), coord = pos).entered

      val quoted = PickledQuotes.quotedToTree(expr)(ctx.withOwner(meth))

      val run = DefDef(meth, quoted)
      val classTree = ClassDef(cls, DefDef(cls.primaryConstructor.asTerm), run :: Nil)
      PackageDef(ref(defn.RootPackage).asInstanceOf[Ident], classTree :: Nil).withPos(pos)
    }
  }

  class ExprRun(comp: Compiler, ictx: Context) extends Run(comp, ictx) {
    def compileExpr(expr: Expr[_]): Unit = {
      val units = new ExprCompilationUnit(expr) :: Nil
      compileUnits(units)
    }
  }

}

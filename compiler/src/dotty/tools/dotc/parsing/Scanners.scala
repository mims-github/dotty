package dotty.tools
package dotc
package parsing

import scala.language.unsafeNulls

import core.Names._, core.Contexts._, core.Decorators._, util.Spans._
import core.StdNames._, core.Comments._
import util.SourceFile
import util.Chars._
import util.{SourcePosition, CharBuffer}
import util.Spans.Span
import config.Config
import Tokens._
import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.collection.immutable.SortedMap
import rewrites.Rewrites.patch
import config.Feature
import config.Feature.migrateTo3
import config.SourceVersion.`3.0`

object Scanners {

  /** Offset into source character array */
  type Offset = Int

  /** An undefined offset */
  val NoOffset: Offset = -1

  type Token = Int

  private val identity: IndentWidth => IndentWidth = Predef.identity

  trait TokenData {

    /** the next token */
    var token: Token = EMPTY

    /** the offset of the first character of the current token */
    var offset: Offset = 0

    /** the offset of the character following the token preceding this one */
    var lastOffset: Offset = 0

    /** the offset of the newline immediately preceding the token, or -1 if
     *  token is not preceded by a newline.
     */
    var lineOffset: Offset = -1

    /** the name of an identifier */
    var name: SimpleName = null

    /** the string value of a literal */
    var strVal: String = null

    /** the base of a number */
    var base: Int = 0

    def copyFrom(td: TokenData): Unit = {
      this.token = td.token
      this.offset = td.offset
      this.lastOffset = td.lastOffset
      this.lineOffset = td.lineOffset
      this.name = td.name
      this.strVal = td.strVal
      this.base = td.base
    }

    def isNewLine = token == NEWLINE || token == NEWLINES
    def isStatSep = isNewLine || token == SEMI
    def isIdent = token == IDENTIFIER || token == BACKQUOTED_IDENT
    def isIdent(name: Name) = token == IDENTIFIER && this.name == name

    def isNestedStart = token == LBRACE || token == INDENT
    def isNestedEnd = token == RBRACE || token == OUTDENT

    /** Is token a COLON, after having converted COLONEOL to COLON?
     *  The conversion means that indentation is not significant after `:`
     *  anymore. So, warning: this is a side-effecting operation.
     */
    def isColon() =
      if token == COLONEOL then token = COLON
      token == COLON

    /** Is current token first one after a newline? */
    def isAfterLineEnd: Boolean = lineOffset >= 0

    def isOperator =
      token == BACKQUOTED_IDENT
      || token == IDENTIFIER && isOperatorPart(name(name.length - 1))

    def isArrow =
      token == ARROW || token == CTXARROW
  }

  abstract class ScannerCommon(source: SourceFile)(using Context) extends CharArrayReader with TokenData {
    val buf: Array[Char] = source.content
    def nextToken(): Unit

    // Errors -----------------------------------------------------------------

    /** the last error offset
      */
    var errOffset: Offset = NoOffset

    /** Generate an error at the given offset */
    def error(msg: String, off: Offset = offset): Unit = {
      errorButContinue(msg, off)
      token = ERROR
      errOffset = off
    }

    def errorButContinue(msg: String, off: Offset = offset): Unit =
      report.error(msg, sourcePos(off))

    /** signal an error where the input ended in the middle of a token */
    def incompleteInputError(msg: String): Unit = {
      report.incompleteInputError(msg, sourcePos())
      token = EOF
      errOffset = offset
    }

    def sourcePos(off: Offset = offset): SourcePosition =
      source.atSpan(Span(off))

    // Setting token data ----------------------------------------------------

    /** A character buffer for literals
      */
    protected val litBuf = CharBuffer()

    /** append Unicode character to "litBuf" buffer
      */
    protected def putChar(c: Char): Unit = litBuf.append(c)

    /** Finish an IDENTIFIER with `this.name`. */
    inline def finishNamed(): Unit = finishNamedToken(IDENTIFIER, this)

    /** Clear buffer and set name and token.
     *  If `target` is different from `this`, don't treat identifiers as end tokens.
     */
    def finishNamedToken(idtoken: Token, target: TokenData): Unit =
      target.name = termName(litBuf.chars, 0, litBuf.length)
      litBuf.clear()
      target.token = idtoken
      if idtoken == IDENTIFIER then
        val converted = toToken(target.name)
        if converted != END || (target eq this) then target.token = converted

    /** The token for given `name`. Either IDENTIFIER or a keyword. */
    def toToken(name: SimpleName): Token

    /** Clear buffer and set string */
    def setStrVal(): Unit =
      strVal = litBuf.toString
      litBuf.clear()

    @inline def isNumberSeparator(c: Char): Boolean = c == '_'

    @inline def removeNumberSeparators(s: String): String = if (s.indexOf('_') == -1) s else s.replace("_", "")

    // disallow trailing numeric separator char, but continue lexing
    def checkNoTrailingSeparator(): Unit =
      if (!litBuf.isEmpty && isNumberSeparator(litBuf.last))
        errorButContinue("trailing separator is not allowed", offset + litBuf.length - 1)
  }

  class Scanner(source: SourceFile, override val startFrom: Offset = 0)(using Context) extends ScannerCommon(source) {
    val keepComments = !ctx.settings.YdropComments.value

    /** A switch whether operators at the start of lines can be infix operators */
    private[Scanners] var allowLeadingInfixOperators = true

    var debugTokenStream = false
    val showLookAheadOnDebug = false

    val rewrite = ctx.settings.rewrite.value.isDefined
    val oldSyntax = ctx.settings.oldSyntax.value
    val newSyntax = ctx.settings.newSyntax.value

    val rewriteToIndent = ctx.settings.indent.value && rewrite
    val rewriteNoIndent = ctx.settings.noindent.value && rewrite

    val noindentSyntax =
      ctx.settings.noindent.value
      || ctx.settings.oldSyntax.value
      || (migrateTo3 && !ctx.settings.indent.value)
    val indentSyntax =
      ((if (Config.defaultIndent) !noindentSyntax else ctx.settings.indent.value)
       || rewriteNoIndent)
      && !isInstanceOf[LookaheadScanner]

    if (rewrite) {
      val s = ctx.settings
      val rewriteTargets = List(s.newSyntax, s.oldSyntax, s.indent, s.noindent)
      val enabled = rewriteTargets.filter(_.value)
      if (enabled.length > 1)
        error(s"illegal combination of -rewrite targets: ${enabled(0).name} and ${enabled(1).name}")
    }

    private var myLanguageImportContext: Context = ctx
    def languageImportContext = myLanguageImportContext
    final def languageImportContext_=(c: Context) = myLanguageImportContext = c

    def featureEnabled(name: TermName) = Feature.enabled(name)(using languageImportContext)
    def erasedEnabled = featureEnabled(Feature.erasedDefinitions)

    private var fewerBracesEnabledCache = false
    private var fewerBracesEnabledCtx: Context = NoContext

    def fewerBracesEnabled =
      if fewerBracesEnabledCtx ne myLanguageImportContext then
        fewerBracesEnabledCache = featureEnabled(Feature.fewerBraces)
        fewerBracesEnabledCtx = myLanguageImportContext
      fewerBracesEnabledCache

    private var postfixOpsEnabledCache = false
    private var postfixOpsEnabledCtx: Context = NoContext

    def postfixOpsEnabled =
      if postfixOpsEnabledCtx ne myLanguageImportContext then
        postfixOpsEnabledCache = featureEnabled(nme.postfixOps)
        postfixOpsEnabledCtx = myLanguageImportContext
      postfixOpsEnabledCache

    /** All doc comments kept by their end position in a `Map`.
      *
      * Note: the map is necessary since the comments are looked up after an
      * entire definition is parsed, and a definition can contain nested
      * definitions with their own docstrings.
      */
    private var docstringMap: SortedMap[Int, Comment] = SortedMap.empty

    /* A Buffer for comment positions */
    private val commentPosBuf = new mutable.ListBuffer[Span]

    /** Return a list of all the comment positions */
    def commentSpans: List[Span] = commentPosBuf.toList

    private def addComment(comment: Comment): Unit = {
      val lookahead = lookaheadReader()
      def nextPos: Int = (lookahead.getc(): @switch) match {
        case ' ' | '\t' | CR | LF | FF => nextPos
        case _ => lookahead.charOffset - 1
      }
      docstringMap = docstringMap + (nextPos -> comment)
    }

    /** Returns the closest docstring preceding the position supplied */
    def getDocComment(pos: Int): Option[Comment] = docstringMap.get(pos)

    /** A buffer for comments */
    private val commentBuf = CharBuffer()

    def toToken(identifier: SimpleName): Token =
      def handleMigration(keyword: Token): Token =
        if scala3keywords.contains(keyword) && migrateTo3 then
          val what = tokenString(keyword)
          report.errorOrMigrationWarning(
            i"$what is now a keyword, write `$what` instead of $what to keep it as an identifier",
            sourcePos(),
            from = `3.0`)
          patch(source, Span(offset), "`")
          patch(source, Span(offset + identifier.length), "`")
          IDENTIFIER
        else keyword
      val idx = identifier.start
      if (idx >= 0 && idx <= lastKeywordStart) handleMigration(kwArray(idx))
      else IDENTIFIER

    def newTokenData: TokenData = new TokenData {}

    /** We need one token lookahead and one token history
     */
    val next = newTokenData
    private val prev = newTokenData

    /** The current region. This is initially an Indented region with zero indentation width. */
    var currentRegion: Region = Indented(IndentWidth.Zero, EMPTY, null)

// Error recovery ------------------------------------------------------------

    private def lastKnownIndentWidth: IndentWidth =
      def recur(r: Region): IndentWidth =
        if r.knownWidth == null then recur(r.enclosing) else r.knownWidth
      recur(currentRegion)

    private var skipping = false

    /** Skip on error to next safe point.
     */
    def skip(): Unit =
      val lastRegion = currentRegion
      skipping = true
      def atStop =
        token == EOF
        || (currentRegion eq lastRegion)
            && (isStatSep
                || closingParens.contains(token) && lastRegion.toList.exists(_.closedBy == token)
                || token == COMMA && lastRegion.toList.exists(_.commasExpected)
                || token == OUTDENT && indentWidth(offset) < lastKnownIndentWidth)
          // stop at OUTDENT if the new indentwidth is smaller than the indent width of
          // currentRegion. This corrects for the problem that sometimes we don't see an INDENT
          // when skipping and therefore might erroneously end up syncing on a nested OUTDENT.
      if debugTokenStream then
        println(s"\nSTART SKIP AT ${sourcePos().line + 1}, $this in $currentRegion")
      while !atStop do
        nextToken()
      if debugTokenStream then
        println(s"\nSTOP SKIP AT ${sourcePos().line + 1}, $this in $currentRegion")
      if token == OUTDENT then dropUntil(_.isInstanceOf[Indented])
      skipping = false

// Get next token ------------------------------------------------------------

    /** Are we directly in a multiline string interpolation expression?
     *  @pre inStringInterpolation
     */
    private def inMultiLineInterpolation = currentRegion match {
      case InString(multiLine, _) => multiLine
      case _ => false
    }

    /** Are we in a `${ }` block? such that RBRACE exits back into multiline string. */
    private def inMultiLineInterpolatedExpression =
      currentRegion match {
        case InBraces(InString(true, _)) => true
        case _ => false
      }

    /** read next token and return last offset
     */
    def skipToken(): Offset = {
      val off = offset
      nextToken()
      off
    }

    def skipToken[T](result: T): T =
      nextToken()
      result

    private inline def dropUntil(inline matches: Region => Boolean): Unit =
      while !matches(currentRegion) && !currentRegion.isOutermost do
        currentRegion = currentRegion.enclosing

    def adjustSepRegions(lastToken: Token): Unit = (lastToken: @switch) match {
      case LPAREN | LBRACKET =>
        currentRegion = InParens(lastToken, currentRegion)
      case LBRACE =>
        currentRegion = InBraces(currentRegion)
      case RBRACE =>
        dropUntil(_.isInstanceOf[InBraces])
        if !currentRegion.isOutermost then currentRegion = currentRegion.enclosing
      case RPAREN | RBRACKET =>
        currentRegion match {
          case InParens(prefix, outer) if prefix + 1 == lastToken => currentRegion = outer
          case _ =>
        }
      case OUTDENT =>
        currentRegion match
          case r: Indented => currentRegion = r.enclosing
          case _ =>
      case STRINGLIT =>
        currentRegion match {
          case InString(_, outer) => currentRegion = outer
          case _ =>
        }
      case _ =>
    }

    /** Read a token or copy it from `next` tokenData */
    private def getNextToken(lastToken: Token): Unit =
      if next.token == EMPTY then
        lastOffset = lastCharOffset
        currentRegion match
          case InString(multiLine, _) if lastToken != STRINGPART => fetchStringPart(multiLine)
          case _ => fetchToken()
        if token == ERROR then adjustSepRegions(STRINGLIT) // make sure we exit enclosing string literal
      else
        this.copyFrom(next)
        next.token = EMPTY

    /** Produce next token, filling TokenData fields of Scanner.
     */
    def nextToken(): Unit =
      val lastToken = token
      adjustSepRegions(lastToken)
      getNextToken(lastToken)
      if isAfterLineEnd then handleNewLine(lastToken)
      postProcessToken()
      printState()

    final def printState() =
      if debugTokenStream && (showLookAheadOnDebug || !isInstanceOf[LookaheadScanner]) then
        print(s"[$show${if isInstanceOf[LookaheadScanner] then "(LA)" else ""}]")

    /** Insert `token` at assumed `offset` in front of current one. */
    def insert(token: Token, offset: Int) = {
      assert(next.token == EMPTY, next)
      next.copyFrom(this)
      this.offset = offset
      this.token = token
    }

    /** A leading symbolic or backquoted identifier is treated as an infix operator if
      *   - it does not follow a blank line, and
      *   - it is followed by at least one whitespace character and a
      *     token that can start an expression.
      *   - if the operator appears on its own line, the next line must have at least
      *     the same indentation width as the operator. See pos/i12395 for a test where this matters.
      *  If a leading infix operator is found and the source version is `3.0-migration`, emit a change warning.
      */
    def isLeadingInfixOperator(nextWidth: IndentWidth = indentWidth(offset), inConditional: Boolean = true) =
      allowLeadingInfixOperators
      && isOperator
      && (isWhitespace(ch) || ch == LF)
      && !pastBlankLine
      && {
        // Is current lexeme  assumed to start an expression?
        // This is the case if the lexime is one of the tokens that
        // starts an expression or it is a COLONEOL. Furthermore, if
        // the previous token is in backticks, the lexeme may not be a binary operator.
        // I.e. in
        //
        //   a
        //   `x` += 1
        //
        // `+=` is not assumed to start an expression since it follows an identifier
        // in backticks and is a binary operator. Hence, `x` is not classified as a
        // leading infix operator.
        def assumeStartsExpr(lexeme: TokenData) =
          (canStartExprTokens.contains(lexeme.token) || lexeme.token == COLONEOL)
          && (!lexeme.isOperator || nme.raw.isUnary(lexeme.name))
        val lookahead = LookaheadScanner()
        lookahead.allowLeadingInfixOperators = false
          // force a NEWLINE a after current token if it is on its own line
        lookahead.nextToken()
        assumeStartsExpr(lookahead)
        || lookahead.token == NEWLINE
           && assumeStartsExpr(lookahead.next)
           && indentWidth(offset) <= indentWidth(lookahead.next.offset)
      }
      && {
        currentRegion match
          case r: Indented =>
            r.width <= nextWidth
            || {
              r.outer match
                case null => true
                case ro @ Indented(outerWidth, _, _) =>
                  outerWidth < nextWidth && !ro.otherIndentWidths.contains(nextWidth)
                case outer =>
                  outer.indentWidth < nextWidth
            }
          case _ => true
      }
      && {
        if migrateTo3 then
          val (what, previous) =
            if inConditional then ("Rest of line", "previous expression in parentheses")
            else ("Line", "expression on the previous line")
          report.errorOrMigrationWarning(
            em"""$what starts with an operator;
                |it is now treated as a continuation of the $previous,
                |not as a separate statement.""",
            sourcePos(), from = `3.0`)
        true
      }

    def isContinuing(lastToken: Token) =
      (openParensTokens.contains(token) || lastToken == RETURN)
      && !pastBlankLine
      && !migrateTo3
      && !noindentSyntax

    /** The indentation width of the given offset. */
    def indentWidth(offset: Offset): IndentWidth =
      import IndentWidth.{Run, Conc}
      def recur(idx: Int, ch: Char, n: Int, k: IndentWidth => IndentWidth): IndentWidth =
        if (idx < 0) k(Run(ch, n))
        else {
          val nextChar = buf(idx)
          if (nextChar == LF) k(Run(ch, n))
          else if (nextChar == ' ' || nextChar == '\t')
            if (nextChar == ch)
              recur(idx - 1, ch, n + 1, k)
            else {
              val k1: IndentWidth => IndentWidth = if (n == 0) k else Conc(_, Run(ch, n))
              recur(idx - 1, nextChar, 1, k1)
            }
          else recur(idx - 1, ' ', 0, identity)
        }
      recur(offset - 1, ' ', 0, identity)
    end indentWidth

    /** Handle newlines, possibly inserting an INDENT, OUTDENT, NEWLINE, or NEWLINES token
     *  in front of the current token. This depends on whether indentation is significant or not.
     *
     *  Indentation is _significant_ if indentSyntax is set, and we are not inside a
     *  {...}, [...], (...), case ... => pair, nor in a if/while condition
     *  (i.e. currentRegion is empty).
     *
     *  There are three rules:
     *
     *   1. Insert NEWLINE or NEWLINES if
     *
     *      - the closest enclosing sepRegion is { ... } or for ... do/yield,
     *         or we are on the toplevel, i.e. currentRegion is empty, and
     *      - the previous token can end a statement, and
     *      - the current token can start a statement, and
     *      - the current token is not a leading infix operator, and
     *      - if indentation is significant then the current token starts at the current
     *        indentation width or to the right of it.
     *
     *      The inserted token is NEWLINES if the current token is preceded by a
     *      whitespace line, or NEWLINE otherwise.
     *
     *   2. Insert INDENT if
     *
     *      - indentation is significant, and
     *      - the last token can start an indentation region.
     *      - the indentation of the current token is strictly greater than the previous
     *        indentation width, or the two widths are the same and the current token is
     *        one of `:` or `match`.
     *
     *      The following tokens can start an indentation region:
     *
     *         :  =  =>  <-  if  then  else  while  do  try  catch  finally  for  yield  match
     *
     *      Inserting an INDENT starts a new indentation region with the indentation of the current
     *      token as indentation width.
     *
     *   3. Insert OUTDENT if
     *
     *      - indentation is significant, and
     *      - the indentation of the current token is strictly less than the
     *        previous indentation width,
     *      - the current token is not a leading infix operator.
     *
     *      Inserting an OUTDENT closes an indentation region. In this case, issue an error if
     *      the indentation of the current token does not match the indentation of some previous
     *      line in an enclosing indentation region.
     *
     *      If a token is inserted and consumed, the original source token is still considered to
     *      start a new line, so the process that inserts an OUTDENT might repeat several times.
     *
     *  Indentation widths are strings consisting of spaces and tabs, ordered by the prefix relation.
     *  I.e. `a <= b` iff `b.startsWith(a)`. If indentation is significant it is considered an error
     *  if the current indentation width and the indentation of the current token are incomparable.
     */
    def handleNewLine(lastToken: Token) =
      var indentIsSignificant = false
      var newlineIsSeparating = false
      var lastWidth = IndentWidth.Zero
      var indentPrefix = EMPTY
      val nextWidth = indentWidth(offset)

      // If nextWidth is an indentation level not yet seen by enclosing indentation
      // region, invoke `handler`.
      def handleNewIndentWidth(r: Region, handler: Indented => Unit): Unit = r match
        case r @ Indented(curWidth, prefix, outer)
        if curWidth < nextWidth && !r.otherIndentWidths.contains(nextWidth) && nextWidth != lastWidth =>
          handler(r)
        case _ =>

      currentRegion match
        case r: Indented =>
          indentIsSignificant = indentSyntax
          lastWidth = r.width
          newlineIsSeparating = lastWidth <= nextWidth || r.isOutermost
          indentPrefix = r.prefix
        case _: InString => ()
        case r =>
          indentIsSignificant = indentSyntax
          r.proposeKnownWidth(nextWidth, lastToken)
          lastWidth = r.knownWidth
          newlineIsSeparating = r.isInstanceOf[InBraces]

      if newlineIsSeparating
         && canEndStatTokens.contains(lastToken)
         && canStartStatTokens.contains(token)
         && !isLeadingInfixOperator(nextWidth)
         && !(lastWidth < nextWidth && isContinuing(lastToken))
      then
        insert(if (pastBlankLine) NEWLINES else NEWLINE, lineOffset)
      else if indentIsSignificant then
        if nextWidth < lastWidth
           || nextWidth == lastWidth && (indentPrefix == MATCH || indentPrefix == CATCH) && token != CASE then
          if currentRegion.isOutermost then
            if nextWidth < lastWidth then currentRegion = topLevelRegion(nextWidth)
          else if !isLeadingInfixOperator(nextWidth) && !statCtdTokens.contains(lastToken) && lastToken != INDENT then
            currentRegion match
              case r: Indented =>
                insert(OUTDENT, offset)
                if next.token != COLON then
                  handleNewIndentWidth(r.enclosing, ir =>
                    errorButContinue(
                      i"""The start of this line does not match any of the previous indentation widths.
                          |Indentation width of current line : $nextWidth
                          |This falls between previous widths: ${ir.width} and $lastWidth"""))
              case r =>
                if skipping then
                  if r.enclosing.isClosedByUndentAt(nextWidth) then
                    insert(OUTDENT, offset)
                else if r.isInstanceOf[InBraces] && !closingRegionTokens.contains(token) then
                  report.warning("Line is indented too far to the left, or a `}` is missing", sourcePos())

        else if lastWidth < nextWidth
             || lastWidth == nextWidth && (lastToken == MATCH || lastToken == CATCH) && token == CASE then
          if canStartIndentTokens.contains(lastToken) then
            currentRegion = Indented(nextWidth, lastToken, currentRegion)
            insert(INDENT, offset)
          else if lastToken == SELFARROW then
            currentRegion.knownWidth = nextWidth
        else if (lastWidth != nextWidth)
          errorButContinue(spaceTabMismatchMsg(lastWidth, nextWidth))
      if token != OUTDENT || next.token == COLON then
        handleNewIndentWidth(currentRegion, _.otherIndentWidths += nextWidth)
    end handleNewLine

    def spaceTabMismatchMsg(lastWidth: IndentWidth, nextWidth: IndentWidth) =
      i"""Incompatible combinations of tabs and spaces in indentation prefixes.
         |Previous indent : $lastWidth
         |Latest indent   : $nextWidth"""

    def observeColonEOL(): Unit =
      if token == COLON then
        lookAhead()
        val atEOL = isAfterLineEnd || token == EOF
        reset()
        if atEOL then token = COLONEOL

    def observeIndented(): Unit =
      if indentSyntax && isNewLine then
        val nextWidth = indentWidth(next.offset)
        val lastWidth = currentRegion.indentWidth
        if lastWidth < nextWidth then
          currentRegion = Indented(nextWidth, COLONEOL, currentRegion)
          offset = next.offset
          token = INDENT
    end observeIndented

    /** Insert an <outdent> token if next token closes an indentation region.
     *  Exception: continue if indentation region belongs to a `match` and next token is `case`.
     */
    def observeOutdented(): Unit = currentRegion match
      case r: Indented
      if !r.isOutermost
         && closingRegionTokens.contains(token)
         && !(token == CASE && r.prefix == MATCH)
         && next.token == EMPTY  // can be violated for ill-formed programs, e.g. neg/i12605.sala
      =>
        insert(OUTDENT, offset)
      case _ =>

    def lookAhead() =
      prev.copyFrom(this)
      getNextToken(token)
      if token == END && !isEndMarker then token = IDENTIFIER

    def reset() = {
      next.copyFrom(this)
      this.copyFrom(prev)
    }

    def closeIndented() = currentRegion match
      case r: Indented if !r.isOutermost => insert(OUTDENT, offset)
      case _ =>

    /** - Join CASE + CLASS => CASECLASS, CASE + OBJECT => CASEOBJECT
     *         SEMI + ELSE => ELSE, COLON + <EOL> => COLONEOL
     *  - Insert missing OUTDENTs at EOF
     */
    def postProcessToken(): Unit = {
      def fuse(tok: Int) = {
        token = tok
        offset = prev.offset
        lastOffset = prev.lastOffset
        lineOffset = prev.lineOffset
      }
      (token: @switch) match {
        case CASE =>
          lookAhead()
          if (token == CLASS) fuse(CASECLASS)
          else if (token == OBJECT) fuse(CASEOBJECT)
          else reset()
        case SEMI =>
          lookAhead()
          if (token != ELSE) reset()
        case COMMA =>
          def isEnclosedInParens(r: Region): Boolean = r match
            case r: Indented => isEnclosedInParens(r.outer)
            case _: InParens => true
            case _ => false
          currentRegion match
            case r: Indented if isEnclosedInParens(r.outer) =>
              insert(OUTDENT, offset)
            case _ =>
              lookAhead()
              if isAfterLineEnd
                 && currentRegion.commasExpected
                 && (token == RPAREN || token == RBRACKET || token == RBRACE || token == OUTDENT)
              then
                () /* skip the trailing comma */
              else
                reset()
        case END =>
          if !isEndMarker then token = IDENTIFIER
        case COLON =>
          if fewerBracesEnabled then observeColonEOL()
        case RBRACE | RPAREN | RBRACKET =>
          closeIndented()
        case EOF =>
          if !source.maybeIncomplete then closeIndented()
        case _ =>
      }
    }

    protected def isEndMarker: Boolean =
      if indentSyntax && isAfterLineEnd then
        val endLine = source.offsetToLine(offset)
        val lookahead = new LookaheadScanner():
          override def isEndMarker = false
        lookahead.nextToken()
        if endMarkerTokens.contains(lookahead.token)
          && source.offsetToLine(lookahead.offset) == endLine
        then
          lookahead.nextToken()
          if lookahead.token == EOF
          || source.offsetToLine(lookahead.offset) > endLine
          then return true
      false

    /** Is there a blank line between the current token and the last one?
     *  A blank line consists only of characters <= ' '.
     *  @pre  afterLineEnd().
     */
    private def pastBlankLine: Boolean = {
      val end = offset
      def recur(idx: Offset, isBlank: Boolean): Boolean =
        idx < end && {
          val ch = buf(idx)
          if (ch == LF || ch == FF) isBlank || recur(idx + 1, true)
          else recur(idx + 1, isBlank && ch <= ' ')
        }
      recur(lastOffset, false)
    }

    import Character.{isHighSurrogate, isLowSurrogate, isUnicodeIdentifierPart, isUnicodeIdentifierStart, isValidCodePoint, toCodePoint}

    // f"\\u$c%04x" or f"${"\\"}u$c%04x"
    private def toUnicode(c: Char): String = { val s = c.toInt.toHexString; "\\u" + "0" * (4 - s.length) + s }

    // given char (ch) is high surrogate followed by low, codepoint passes predicate.
    // true means supplementary chars were put to buffer.
    // strict to require low surrogate (if not in string literal).
    private def isSupplementary(high: Char, test: Int => Boolean, strict: Boolean = true): Boolean =
      isHighSurrogate(high) && {
        var res = false
        nextChar()
        val low = ch
        if isLowSurrogate(low) then
          nextChar()
          val codepoint = toCodePoint(high, low)
          if isValidCodePoint(codepoint) && test(codepoint) then
            putChar(high)
            putChar(low)
            res = true
          else
            error(s"illegal character '${toUnicode(high)}${toUnicode(low)}'")
        else if !strict then
          putChar(high)
          res = true
        else
          error(s"illegal character '${toUnicode(high)}' missing low surrogate")
        res
      }
    private def atSupplementary(ch: Char, f: Int => Boolean): Boolean =
      isHighSurrogate(ch) && {
        val hi = ch
        val lo = lookaheadChar()
        isLowSurrogate(lo) && {
          val codepoint = toCodePoint(hi, lo)
          isValidCodePoint(codepoint) && f(codepoint)
        }
      }

    /** read next token, filling TokenData fields of Scanner.
     */
    protected final def fetchToken(): Unit = {
      offset = charOffset - 1
      lineOffset = if (lastOffset < lineStartOffset) lineStartOffset else -1
      name = null
      (ch: @switch) match {
        case ' ' | '\t' | CR | LF | FF =>
          nextChar()
          fetchToken()
        case 'A' | 'B' | 'C' | 'D' | 'E' |
             'F' | 'G' | 'H' | 'I' | 'J' |
             'K' | 'L' | 'M' | 'N' | 'O' |
             'P' | 'Q' | 'R' | 'S' | 'T' |
             'U' | 'V' | 'W' | 'X' | 'Y' |
             'Z' | '$' | '_' |
             'a' | 'b' | 'c' | 'd' | 'e' |
             'f' | 'g' | 'h' | 'i' | 'j' |
             'k' | 'l' | 'm' | 'n' | 'o' |
             'p' | 'q' | 'r' | 's' | 't' |
             'u' | 'v' | 'w' | 'x' | 'y' |
             'z' =>
          putChar(ch)
          nextChar()
          getIdentRest()
          if (ch == '"' && token == IDENTIFIER)
            token = INTERPOLATIONID
        case '<' => // is XMLSTART?
          def fetchLT() = {
            val last = if (charOffset >= 2) buf(charOffset - 2) else ' '
            nextChar()
            last match {
              case ' ' | '\t' | '\n' | '{' | '(' | '>' if xml.Utility.isNameStart(ch) || ch == '!' || ch == '?' =>
                token = XMLSTART
              case _ =>
                // Console.println("found '<', but last is '" + in.last +"'"); // DEBUG
                putChar('<')
                getOperatorRest()
            }
          }
          fetchLT()
        case '~' | '!' | '@' | '#' | '%' |
             '^' | '*' | '+' | '-' | /*'<' | */
             '>' | '?' | ':' | '=' | '&' |
             '|' | '\\' =>
          putChar(ch)
          nextChar()
          getOperatorRest()
        case '/' =>
          if (skipComment())
            fetchToken()
          else {
            putChar('/')
            getOperatorRest()
          }
        case '0' =>
          def fetchLeadingZero(): Unit = {
            nextChar()
            ch match {
              case 'x' | 'X' => base = 16 ; nextChar()
              //case 'b' | 'B' => base = 2  ; nextChar()
              case _         => base = 10 ; putChar('0')
            }
            if (base != 10 && !isNumberSeparator(ch) && digit2int(ch, base) < 0)
              error("invalid literal number")
          }
          fetchLeadingZero()
          getNumber()
        case '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
          base = 10
          getNumber()
        case '`' =>
          getBackquotedIdent()
        case '\"' =>
          def stringPart(multiLine: Boolean) = {
            getStringPart(multiLine)
            currentRegion = InString(multiLine, currentRegion)
          }
          def fetchDoubleQuote() =
            if (token == INTERPOLATIONID) {
              nextRawChar()
              if (ch == '\"') {
                if (lookaheadChar() == '\"') {
                  nextRawChar()
                  //offset += 3   // first part is positioned at the quote
                  nextRawChar()
                  stringPart(multiLine = true)
                }
                else {
                  nextChar()
                  token = STRINGLIT
                  strVal = ""
                }
              }
              else {
                //offset += 1   // first part is positioned at the quote
                stringPart(multiLine = false)
              }
            }
            else {
              nextChar()
              if (ch == '\"') {
                nextChar()
                if (ch == '\"') {
                  nextRawChar()
                  getRawStringLit()
                }
                else {
                  token = STRINGLIT
                  strVal = ""
                }
              }
              else
                getStringLit()
            }
          fetchDoubleQuote()
        case '\'' =>
          def fetchSingleQuote(): Unit = {
            nextChar()
            if isIdentifierStart(ch) then
              charLitOr { getIdentRest(); QUOTEID }
            else if isOperatorPart(ch) && ch != '\\' then
              charLitOr { getOperatorRest(); QUOTEID }
            else ch match {
              case '{' | '[' | ' ' | '\t' if lookaheadChar() != '\'' =>
                token = QUOTE
              case _ if !isAtEnd && ch != SU && ch != CR && ch != LF =>
                val isEmptyCharLit = (ch == '\'')
                getLitChar()
                if ch == '\'' then
                  if isEmptyCharLit then error("empty character literal (use '\\'' for single quote)")
                  else if litBuf.length != 1 then error("illegal codepoint in Char constant: " + litBuf.toString.map(toUnicode).mkString("'", "", "'"))
                  else finishCharLit()
                else if isEmptyCharLit then error("empty character literal")
                else error("unclosed character literal")
              case _ =>
                error("unclosed character literal")
            }
          }
          fetchSingleQuote()
        case '.' =>
          nextChar()
          if ('0' <= ch && ch <= '9') {
            putChar('.'); getFraction(); setStrVal()
          }
          else
            token = DOT
        case ';' =>
          nextChar(); token = SEMI
        case ',' =>
          nextChar(); token = COMMA
        case '(' =>
          nextChar(); token = LPAREN
        case '{' =>
          nextChar(); token = LBRACE
        case ')' =>
          nextChar(); token = RPAREN
        case '}' =>
          if (inMultiLineInterpolatedExpression) nextRawChar() else nextChar()
          token = RBRACE
        case '[' =>
          nextChar(); token = LBRACKET
        case ']' =>
          nextChar(); token = RBRACKET
        case SU =>
          if (isAtEnd) token = EOF
          else {
            error("illegal character")
            nextChar()
          }
        case _ =>
          def fetchOther() =
            if (ch == '\u21D2') {
              nextChar(); token = ARROW
              report.deprecationWarning("The unicode arrow `⇒` is deprecated, use `=>` instead. If you still wish to display it as one character, consider using a font with programming ligatures such as Fira Code.", sourcePos(offset))
            }
            else if (ch == '\u2190') {
              nextChar(); token = LARROW
              report.deprecationWarning("The unicode arrow `←` is deprecated, use `<-` instead. If you still wish to display it as one character, consider using a font with programming ligatures such as Fira Code.", sourcePos(offset))
            }
            else if (Character.isUnicodeIdentifierStart(ch)) {
              putChar(ch)
              nextChar()
              getIdentRest()
            }
            else if (isSpecial(ch)) {
              putChar(ch)
              nextChar()
              getOperatorRest()
            }
            else if isSupplementary(ch, isUnicodeIdentifierStart) then
              getIdentRest()
            else {
              error(s"illegal character '${toUnicode(ch)}'")
              nextChar()
            }
          fetchOther()
      }
    }

    private def skipComment(): Boolean = {
      def appendToComment(ch: Char) =
        if (keepComments) commentBuf.append(ch)
      def nextChar() = {
        appendToComment(ch)
        Scanner.this.nextChar()
      }
      def skipLine(): Unit = {
        nextChar()
        if ((ch != CR) && (ch != LF) && (ch != SU)) skipLine()
      }
      @tailrec
      def skipComment(): Unit =
        if (ch == '/') {
          nextChar()
          if (ch == '*') nestedComment()
          skipComment()
        }
        else if (ch == '*') {
          while ({ nextChar() ; ch == '*' }) ()
          if (ch == '/') nextChar()
          else skipComment()
        }
        else if (ch == SU) incompleteInputError("unclosed comment")
        else { nextChar(); skipComment() }
      def nestedComment() = { nextChar(); skipComment() }
      val start = lastCharOffset
      def finishComment(): Boolean = {
        if (keepComments) {
          val pos = Span(start, charOffset - 1, start)
          val comment = Comment(pos, commentBuf.toString)
          commentBuf.clear()
          commentPosBuf += pos

          if (comment.isDocComment)
            addComment(comment)
          else
            // "forward" doc comments over normal ones
            getDocComment(start).foreach(addComment)
        }

        true
      }
      nextChar()
      if (ch == '/') { skipLine(); finishComment() }
      else if (ch == '*') { nextChar(); skipComment(); finishComment() }
      else {
        // This was not a comment, remove the `/` from the buffer
        commentBuf.clear()
        false
      }
    }

// Lookahead ---------------------------------------------------------------

    /** The next token after this one.
     *  The token is computed via fetchToken, so complex two word
     *  tokens such as CASECLASS are not recognized.
     *  Newlines and indent/unindent tokens are skipped.
     *
     */
     def lookahead: TokenData =
      if next.token == EMPTY then
        lookAhead()
        reset()
      next

    class LookaheadScanner() extends Scanner(source, offset) {
      override def languageImportContext = Scanner.this.languageImportContext
    }

    /** Skip matching pairs of `(...)` or `[...]` parentheses.
     *  @pre  The current token is `(` or `[`
     */
    final def skipParens(multiple: Boolean = true): Unit =
      val opening = token
      nextToken()
      while token != EOF && token != opening + 1 do
        if token == opening && multiple then skipParens() else nextToken()
      nextToken()

    /** Is the current token in a position where a modifier is allowed? */
    def inModifierPosition(): Boolean = {
      val lookahead = LookaheadScanner()
      while
        lookahead.nextToken()
        lookahead.isNewLine || lookahead.isSoftModifier
      do ()
      modifierFollowers.contains(lookahead.token)
    }

// Identifiers ---------------------------------------------------------------

    private def getBackquotedIdent(): Unit = {
      nextChar()
      getLitChars('`')
      if (ch == '`') {
        nextChar()
        finishNamedToken(BACKQUOTED_IDENT, target = this)
        if (name.length == 0)
          error("empty quoted identifier")
        else if (name == nme.WILDCARD)
          error("wildcard invalid as backquoted identifier")
      }
      else error("unclosed quoted identifier")
    }

    private def getIdentRest(): Unit = (ch: @switch) match {
      case 'A' | 'B' | 'C' | 'D' | 'E' |
           'F' | 'G' | 'H' | 'I' | 'J' |
           'K' | 'L' | 'M' | 'N' | 'O' |
           'P' | 'Q' | 'R' | 'S' | 'T' |
           'U' | 'V' | 'W' | 'X' | 'Y' |
           'Z' | '$' |
           'a' | 'b' | 'c' | 'd' | 'e' |
           'f' | 'g' | 'h' | 'i' | 'j' |
           'k' | 'l' | 'm' | 'n' | 'o' |
           'p' | 'q' | 'r' | 's' | 't' |
           'u' | 'v' | 'w' | 'x' | 'y' |
           'z' |
           '0' | '1' | '2' | '3' | '4' |
           '5' | '6' | '7' | '8' | '9' =>
        putChar(ch)
        nextChar()
        getIdentRest()
      case '_' =>
        putChar(ch)
        nextChar()
        getIdentOrOperatorRest()
      case SU => // strangely enough, Character.isUnicodeIdentifierPart(SU) returns true!
        finishNamed()
      case _ =>
        if isUnicodeIdentifierPart(ch) then
          putChar(ch)
          nextChar()
          getIdentRest()
        else if isSupplementary(ch, isUnicodeIdentifierPart) then
          getIdentRest()
        else
          finishNamed()
    }

    private def getOperatorRest(): Unit = (ch: @switch) match {
      case '~' | '!' | '@' | '#' | '%' |
           '^' | '*' | '+' | '-' | '<' |
           '>' | '?' | ':' | '=' | '&' |
           '|' | '\\' =>
        putChar(ch); nextChar(); getOperatorRest()
      case '/' =>
        val nxch = lookaheadChar()
        if nxch == '/' || nxch == '*' then finishNamed()
        else { putChar(ch); nextChar(); getOperatorRest() }
      case _ =>
        if (isSpecial(ch)) { putChar(ch); nextChar(); getOperatorRest() }
        else finishNamed()
    }

    private def getIdentOrOperatorRest(): Unit =
      if (isIdentifierPart(ch))
        getIdentRest()
      else ch match {
        case '~' | '!' | '@' | '#' | '%' |
             '^' | '*' | '+' | '-' | '<' |
             '>' | '?' | ':' | '=' | '&' |
             '|' | '\\' | '/' =>
          getOperatorRest()
        case _ =>
          if (isSpecial(ch)) getOperatorRest()
          else finishNamed()
      }

    def isSoftModifier: Boolean =
      token == IDENTIFIER
      && (softModifierNames.contains(name) || name == nme.erased && erasedEnabled)

    def isSoftModifierInModifierPosition: Boolean =
      isSoftModifier && inModifierPosition()

    def isSoftModifierInParamModifierPosition: Boolean =
      isSoftModifier && lookahead.token != COLON

    def isErased: Boolean = isIdent(nme.erased) && erasedEnabled

    def canStartStatTokens =
      if migrateTo3 then canStartStatTokens2 else canStartStatTokens3

    def canStartExprTokens =
      if migrateTo3 then canStartExprTokens2 else canStartExprTokens3

// Literals -----------------------------------------------------------------

    private def getStringLit() = {
      getLitChars('"')
      if (ch == '"') {
        setStrVal()
        nextChar()
        token = STRINGLIT
      }
      else error("unclosed string literal")
    }

    private def getRawStringLit(): Unit =
      if (ch == '\"') {
        nextRawChar()
        if (isTripleQuote()) {
          setStrVal()
          token = STRINGLIT
        }
        else
          getRawStringLit()
      }
      else if (ch == SU)
        incompleteInputError("unclosed multi-line string literal")
      else {
        putChar(ch)
        nextRawChar()
        getRawStringLit()
      }

    // for interpolated strings
    @tailrec private def getStringPart(multiLine: Boolean): Unit =
      if (ch == '"')
        if (multiLine) {
          nextRawChar()
          if (isTripleQuote()) {
            setStrVal()
            token = STRINGLIT
          }
          else
            getStringPart(multiLine)
        }
        else {
          nextChar()
          setStrVal()
          token = STRINGLIT
        }
      else if (ch == '\\' && !multiLine) {
        putChar(ch)
        nextRawChar()
        if (ch == '"' || ch == '\\')
          putChar(ch)
          nextRawChar()
        getStringPart(multiLine)
      }
      else if (ch == '$') {
        def getInterpolatedIdentRest(hasSupplement: Boolean): Unit =
          @tailrec def loopRest(): Unit =
            if ch != SU && isUnicodeIdentifierPart(ch) then
              putChar(ch) ; nextRawChar()
              loopRest()
            else if atSupplementary(ch, isUnicodeIdentifierPart) then
              putChar(ch) ; nextRawChar()
              putChar(ch) ; nextRawChar()
              loopRest()
            else
              finishNamedToken(IDENTIFIER, target = next)
          end loopRest
          setStrVal()
          token = STRINGPART
          next.lastOffset = charOffset - 1
          next.offset = charOffset - 1
          putChar(ch) ; nextRawChar()
          if hasSupplement then
            putChar(ch) ; nextRawChar()
          loopRest()
        end getInterpolatedIdentRest

        nextRawChar()
        if (ch == '$' || ch == '"') {
          putChar(ch)
          nextRawChar()
          getStringPart(multiLine)
        }
        else if (ch == '{') {
          setStrVal()
          token = STRINGPART
        }
        else if isUnicodeIdentifierStart(ch) || ch == '_' then
          getInterpolatedIdentRest(hasSupplement = false)
        else if atSupplementary(ch, isUnicodeIdentifierStart) then
          getInterpolatedIdentRest(hasSupplement = true)
        else
          error("invalid string interpolation: `$$`, `$\"`, `$`ident or `$`BlockExpr expected", off = charOffset - 2)
          putChar('$')
          getStringPart(multiLine)
      }
      else {
        val isUnclosedLiteral = !isUnicodeEscape && (ch == SU || (!multiLine && (ch == CR || ch == LF)))
        if (isUnclosedLiteral)
          if (multiLine)
            incompleteInputError("unclosed multi-line string literal")
          else
            error("unclosed string literal")
        else {
          putChar(ch)
          nextRawChar()
          getStringPart(multiLine)
        }
      }
    end getStringPart

    private def fetchStringPart(multiLine: Boolean) = {
      offset = charOffset - 1
      getStringPart(multiLine)
    }

    private def isTripleQuote(): Boolean =
      if (ch == '"') {
        nextRawChar()
        if (ch == '"') {
          nextChar()
          while (ch == '"') {
            putChar('"')
            nextChar()
          }
          true
        }
        else {
          putChar('"')
          putChar('"')
          false
        }
      }
      else {
        putChar('"')
        false
      }

    /** Copy current character into cbuf, interpreting any escape sequences,
     *  and advance to next character. Surrogate pairs are consumed (see check
     *  at fetchSingleQuote), but orphan surrogate is allowed.
     */
    protected def getLitChar(): Unit =
      if ch == '\\' then
        nextChar()
        charEscape()
      else if !isSupplementary(ch, _ => true, strict = false) then
        putChar(ch)
        nextChar()

    private def charEscape(): Unit =
      var bump = true
      ch match
        case 'b'  => putChar('\b')
        case 't'  => putChar('\t')
        case 'n'  => putChar('\n')
        case 'f'  => putChar('\f')
        case 'r'  => putChar('\r')
        case '\"' => putChar('\"')
        case '\'' => putChar('\'')
        case '\\' => putChar('\\')
        case 'u' |
             'U'  => uEscape(); bump = false
        case x if '0' <= x && x <= '7' => octalEscape(); bump = false
        case _    => invalidEscape()
      if bump then nextChar()
    end charEscape

    private def uEscape(): Unit =
      while ch == 'u' || ch == 'U' do nextChar()
      var i  = 0
      var cp = 0
      while i < 4 do
        val digit = digit2int(ch, 16)
        if digit < 0 then
          error("invalid character in unicode escape sequence", charOffset - 1)
          putChar(ch)
          return
        val shift = (3 - i) * 4
        cp += digit << shift
        nextChar()
        i += 1
      end while
      putChar(cp.asInstanceOf[Char])
    end uEscape

    private def octalEscape(): Unit =
      val start = charOffset - 2
      val leadch: Char = ch
      var oct: Int = digit2int(ch, 8)
      nextChar()
      if '0' <= ch && ch <= '7' then
        oct = oct * 8 + digit2int(ch, 8)
        nextChar()
        if leadch <= '3' && '0' <= ch && ch <= '7' then
          oct = oct * 8 + digit2int(ch, 8)
          nextChar()
      val alt = if oct == LF then raw"\n" else toUnicode(oct.toChar)
      error(s"octal escape literals are unsupported: use $alt instead", start)
      putChar(oct.toChar)
    end octalEscape

    protected def invalidEscape(): Unit =
      error("invalid escape character", charOffset - 1)
      putChar(ch)

    private def getLitChars(delimiter: Char) =
      while (ch != delimiter && !isAtEnd && (ch != SU && ch != CR && ch != LF || isUnicodeEscape))
        getLitChar()

    /** read fractional part and exponent of floating point number
     *  if one is present.
     */
    protected def getFraction(): Unit = {
      token = DECILIT
      while ('0' <= ch && ch <= '9' || isNumberSeparator(ch)) {
        putChar(ch)
        nextChar()
      }
      checkNoTrailingSeparator()
      if (ch == 'e' || ch == 'E') {
        val lookahead = lookaheadReader()
        lookahead.nextChar()
        if (lookahead.ch == '+' || lookahead.ch == '-')
          lookahead.nextChar()
        if ('0' <= lookahead.ch && lookahead.ch <= '9' || isNumberSeparator(ch)) {
          putChar(ch)
          nextChar()
          if (ch == '+' || ch == '-') {
            putChar(ch)
            nextChar()
          }
          while ('0' <= ch && ch <= '9' || isNumberSeparator(ch)) {
            putChar(ch)
            nextChar()
          }
          checkNoTrailingSeparator()
        }
        token = EXPOLIT
      }
      if (ch == 'd' || ch == 'D') {
        putChar(ch)
        nextChar()
        token = DOUBLELIT
      }
      else if (ch == 'f' || ch == 'F') {
        putChar(ch)
        nextChar()
        token = FLOATLIT
      }
      checkNoLetter()
    }
    def checkNoLetter(): Unit =
      if (isIdentifierPart(ch) && ch >= ' ')
        error("Invalid literal number")

    /** Read a number into strVal and set base
    */
    protected def getNumber(): Unit = {
      while (isNumberSeparator(ch) || digit2int(ch, base) >= 0) {
        putChar(ch)
        nextChar()
      }
      checkNoTrailingSeparator()
      token = INTLIT
      if (base == 10 && ch == '.') {
        val lch = lookaheadChar()
        if ('0' <= lch && lch <= '9') {
          putChar('.')
          nextChar()
          getFraction()
        }
      }
      else (ch: @switch) match {
        case 'e' | 'E' | 'f' | 'F' | 'd' | 'D' =>
          if (base == 10) getFraction()
        case 'l' | 'L' =>
          nextChar()
          token = LONGLIT
        case _ =>
      }

      checkNoTrailingSeparator()

      setStrVal()
    }

    private def finishCharLit(): Unit = {
      nextChar()
      token = CHARLIT
      setStrVal()
    }

    /** Parse character literal if current character is followed by \',
     *  or follow with given op and return a symbol literal token
     */
    def charLitOr(op: => Token): Unit = {
      putChar(ch)
      nextChar()
      if (ch == '\'') finishCharLit()
      else {
        token = op
        strVal = if (name != null) name.toString else null
        litBuf.clear()
      }
    }

    override def toString: String =
      showTokenDetailed(token) + {
        if identifierTokens.contains(token) then s" $name"
        else if literalTokens.contains(token) then s" $strVal"
        else ""
      }

    def show: String = token match {
      case IDENTIFIER | BACKQUOTED_IDENT => s"id($name)"
      case CHARLIT => s"char($strVal)"
      case INTLIT => s"int($strVal, base = $base)"
      case LONGLIT => s"long($strVal, base = $base)"
      case FLOATLIT => s"float($strVal)"
      case DOUBLELIT => s"double($strVal)"
      case STRINGLIT => s"string($strVal)"
      case STRINGPART => s"stringpart($strVal)"
      case INTERPOLATIONID => s"interpolationid($name)"
      case SEMI => ";"
      case NEWLINE => ";"
      case NEWLINES => ";;"
      case COMMA => ","
      case _ => showToken(token)
    }

    /* Resume normal scanning after XML */
    def resume(lastTokenData: TokenData): Unit = {
      this.copyFrom(lastTokenData)
      if (next.token != EMPTY && !ctx.reporter.hasErrors)
        error("unexpected end of input: possible missing '}' in XML block")

      nextToken()
    }

   /* Initialization: read first char, then first token */
    nextChar()
    nextToken()
    currentRegion = topLevelRegion(indentWidth(offset))
  }
  end Scanner

  /** A Region indicates what encloses the current token. It can be one of the following
   *
   *   InString    a string interpolation
   *   InParens    a pair of parentheses (...) or brackets [...]
   *   InBraces    a pair of braces { ... }
   *   Indented    a pair of <indent> ... <outdent> tokens
   */
  abstract class Region(val closedBy: Token):

   /** The region enclosing this one, or `null` for the outermost region */
    def outer: Region | Null

    /** Is this region the outermost region? */
    def isOutermost = outer == null

    /** The enclosing region, which is required to exist */
    def enclosing: Region = outer.asInstanceOf[Region]

    var knownWidth: IndentWidth | Null = null

    /** The indentation width, Zero if not known */
    final def indentWidth: IndentWidth =
      if knownWidth == null then IndentWidth.Zero else knownWidth

    def proposeKnownWidth(width: IndentWidth, lastToken: Token) =
      if knownWidth == null then
        this match
          case InParens(_, _) if lastToken != LPAREN =>
            useOuterWidth()
          case _ =>
            knownWidth = width

    private def useOuterWidth(): Unit =
      if enclosing.knownWidth == null then enclosing.useOuterWidth()
      knownWidth = enclosing.knownWidth

    /** Does `width` represent an undent of an enclosing indentation region?
     *  This is the case if there is an indentation region that goes deeper than `width`
     *  and that is enclosed in a region that contains `width` as an indentation width.
     */
    def isClosedByUndentAt(width: IndentWidth): Boolean = this match
      case _: Indented =>
        !isOutermost && width <= indentWidth && enclosing.coversIndent(width)
      case _ =>
        enclosing.isClosedByUndentAt(width)

    /** A region "covers" an indentation with `width` if it has `width` as known
     *  indentation width (either as primary, or in case of an Indent region as
     *  alternate width).
     */
    protected def coversIndent(w: IndentWidth): Boolean =
      knownWidth != null && w == indentWidth

    private var myCommasExpected: Boolean = false

    inline def withCommasExpected[T](inline op: => T): T =
      val saved = myCommasExpected
      myCommasExpected = true
      val res = op
      myCommasExpected = false
      res

    def commasExpected = myCommasExpected

    def toList: List[Region] =
      this :: (if outer == null then Nil else outer.toList)

    private def delimiter = this match
      case _: InString => "}(in string)"
      case InParens(LPAREN, _) => ")"
      case InParens(LBRACKET, _) => "]"
      case _: InBraces => "}"
      case _: InCase => "=>"
      case _: Indented => "UNDENT"

    /** Show open regions as list of lines with decreasing indentations */
    def visualize: String =
      toList.map(r => s"${r.indentWidth.toPrefix}${r.delimiter}").mkString("\n")

    override def toString: String =
      toList.map(r => s"(${r.indentWidth}, ${r.delimiter})").mkString(" in ")
  end Region

  case class InString(multiLine: Boolean, outer: Region) extends Region(RBRACE)
  case class InParens(prefix: Token, outer: Region) extends Region(prefix + 1)
  case class InBraces(outer: Region) extends Region(RBRACE)
  case class InCase(outer: Region) extends Region(OUTDENT)

  /** A class describing an indentation region.
   *  @param width   The principal indendation width
   *  @param prefix  The token before the initial <indent> of the region
   */
  case class Indented(width: IndentWidth, prefix: Token, outer: Region | Null) extends Region(OUTDENT):
    knownWidth = width

    /** Other indendation widths > width of lines in the same region */
    var otherIndentWidths: Set[IndentWidth] = Set()

    override def coversIndent(w: IndentWidth) = width == w || otherIndentWidths.contains(w)
  end Indented

  def topLevelRegion(width: IndentWidth) = Indented(width, EMPTY, null)

  enum IndentWidth {
    case Run(ch: Char, n: Int)
    case Conc(l: IndentWidth, r: Run)

    def <= (that: IndentWidth): Boolean = this match {
      case Run(ch1, n1) =>
        that match {
          case Run(ch2, n2) => n1 <= n2 && (ch1 == ch2 || n1 == 0)
          case Conc(l, r) => this <= l
        }
      case Conc(l1, r1) =>
        that match {
          case Conc(l2, r2) => l1 == l2 && r1 <= r2
          case _ => false
        }
    }

    def < (that: IndentWidth): Boolean = this <= that && !(that <= this)

    def toPrefix: String = this match {
      case Run(ch, n) => ch.toString * n
      case Conc(l, r) => l.toPrefix ++ r.toPrefix
    }

    override def toString: String = {
      def kind(ch: Char) = ch match {
        case ' ' => "space"
        case '\t' => "tab"
        case _ => s"'$ch'-character"
      }
      this match {
        case Run(ch, n) => s"$n ${kind(ch)}${if (n == 1) "" else "s"}"
        case Conc(l, r) => s"$l, $r"
      }
    }
  }

  object IndentWidth {
    private inline val MaxCached = 40
    private val spaces = Array.tabulate(MaxCached + 1)(new Run(' ', _))
    private val tabs = Array.tabulate(MaxCached + 1)(new Run('\t', _))

    def Run(ch: Char, n: Int): Run =
      if (n <= MaxCached && ch == ' ') spaces(n)
      else if (n <= MaxCached && ch == '\t') tabs(n)
      else new Run(ch, n)

    val Zero = Run(' ', 0)
  }

  // ------------- keyword configuration -----------------------------------

  private val (lastKeywordStart, kwArray) = buildKeywordArray(keywords)
}

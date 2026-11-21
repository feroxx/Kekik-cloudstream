package com.keyiflerolsun

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Matcher
import java.util.regex.Pattern

/*
 * MiniJs — oynatıcı sayfalarındaki karartılmış (obfuscated) çözücü kodunu
 * ÇALIŞTIRMAK için küçük, saf-Kotlin bir JavaScript yorumlayıcısı.
 *
 * Neden: closeload / rapidrame oynatıcıları video adresini her istekte
 * değişen değişken adları, rastgele eklenen ölü dallar ve sayfa host adına
 * (location.hostname) bağlı bir anahtar ile üretiyor. Sabit regex ile
 * "taklit" etmek her şablon değişiminde kırılıyordu; burada sitenin kendi
 * kodu çalıştırılır. Rhino/WebView gerekmez (Kitsugi Rhino'yu içermiyor).
 *
 * Desteklenen alt küme: var/let/const, function (bildirim/ifade/ok), if/else,
 * for/for-in/for-of/while/do, switch, try/catch/finally, throw, return,
 * break/continue, tüm aritmetik/bit/karşılaştırma/mantık operatörleri,
 * nesne/dizi/regex/şablon literalleri, String/Array/Math/atob/btoa vb.
 */

internal object Undefined {
    override fun toString() = "undefined"
}

internal open class JsObject(val props: MutableMap<String, Any?> = LinkedHashMap()) {
    /** Tanımsız özellik okunduğunda dönecek değer (izin verici tarayıcı nesneleri için). */
    open fun missing(key: String): Any? = Undefined
}

internal class JsArray(val list: MutableList<Any?> = ArrayList()) : JsObject()

internal class JsRegExp(val source: String, val flags: String) : JsObject() {
    val global = flags.contains('g')
    var lastIndex = 0
    val pattern: Pattern = Pattern.compile(
        MiniJs.translateRegex(source),
        (if (flags.contains('i')) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0) or
            (if (flags.contains('m')) Pattern.MULTILINE else 0) or
            (if (flags.contains('s')) Pattern.DOTALL else 0)
    )
}

internal abstract class JsFunction : JsObject() {
    abstract fun call(self: Any?, args: List<Any?>): Any?
    open val fnName: String get() = ""
}

internal class NativeFunction(
    override val fnName: String,
    val impl: (Any?, List<Any?>) -> Any?
) : JsFunction() {
    override fun call(self: Any?, args: List<Any?>): Any? = impl(self, args)
}

/** Her şeye "evet" diyen tarayıcı taklidi: her özellik/çağrı kendisini döndürür. */
internal object BlackHole : JsFunction() {
    override fun call(self: Any?, args: List<Any?>): Any? = this
    override fun missing(key: String): Any? = this
}

/** JS `throw` ile atılan değer (veya yorumlayıcı hatası). */
internal class JsThrow(val value: Any?) : RuntimeException(null, null, false, false) {
    override val message: String
        get() = when (val v = value) {
            is JsObject -> "${v.props["name"] ?: "Error"}: ${v.props["message"] ?: ""}"
            else -> MiniJs.toStr(v)
        }
}

internal class JsSyntaxError(msg: String) : RuntimeException(msg)

private object BreakSignal : RuntimeException(null, null, false, false) {
    private fun readResolve(): Any = BreakSignal
}

private object ContinueSignal : RuntimeException(null, null, false, false) {
    private fun readResolve(): Any = ContinueSignal
}

private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)

// ───────────────────────────── Sözcük çözümleyici ─────────────────────────────

internal class Tok(
    val type: Int,
    val text: String,
    val nl: Boolean,
    val pos: Int,
    val num: Double = 0.0,
    val extra: Any? = null
) {
    companion object {
        const val EOF = 0
        const val NUM = 1
        const val STR = 2
        const val IDENT = 3
        const val PUNCT = 4
        const val REGEX = 5
        const val TEMPLATE = 6
    }

    override fun toString() = text
}

internal class Lexer(private val src: String) {
    private var i = 0
    private val toks = ArrayList<Tok>()

    fun run(): List<Tok> {
        var nl = false
        while (true) {
            while (i < src.length) {
                val c = src[i]
                if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
                    nl = true; i++
                } else if (c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '\u00A0' ||
                    c == '\uFEFF' || Character.isSpaceChar(c)
                ) {
                    i++
                } else if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                    while (i < src.length && src[i] != '\n' && src[i] != '\r') i++
                } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                    val end = src.indexOf("*/", i + 2)
                    val stop = if (end < 0) src.length else end + 2
                    if (src.substring(i, stop).indexOf('\n') >= 0) nl = true
                    i = stop
                } else if (c == '<' && src.startsWith("<!--", i)) {
                    while (i < src.length && src[i] != '\n') i++
                } else if (c == '-' && (nl || toks.isEmpty()) && src.startsWith("-->", i)) {
                    while (i < src.length && src[i] != '\n') i++
                } else {
                    break
                }
            }
            if (i >= src.length) {
                toks.add(Tok(Tok.EOF, "", nl, i))
                return toks
            }
            val start = i
            val c = src[i]
            when {
                isIdStart(c) -> {
                    i++
                    while (i < src.length && isIdPart(src[i])) i++
                    toks.add(Tok(Tok.IDENT, src.substring(start, i), nl, start))
                }
                c in '0'..'9' || (c == '.' && i + 1 < src.length && src[i + 1] in '0'..'9') -> readNumber(nl)
                c == '"' || c == '\'' -> toks.add(Tok(Tok.STR, readString(c), nl, start))
                c == '`' -> readTemplate(nl)
                c == '/' && regexAllowed() -> readRegex(nl)
                else -> readPunct(nl)
            }
            nl = false
        }
    }

    private fun isIdStart(c: Char) = c == '_' || c == '$' || c in 'a'..'z' || c in 'A'..'Z' ||
        (c.code > 127 && Character.isLetter(c))

    private fun isIdPart(c: Char) = isIdStart(c) || c in '0'..'9' || (c.code > 127 && Character.isLetterOrDigit(c))

    private fun regexAllowed(): Boolean {
        val prev = toks.lastOrNull() ?: return true
        return when (prev.type) {
            Tok.NUM, Tok.STR, Tok.REGEX, Tok.TEMPLATE -> false
            Tok.IDENT -> prev.text in REGEX_KEYWORDS
            Tok.PUNCT -> prev.text != ")" && prev.text != "]" && prev.text != "}"
            else -> true
        }
    }

    private fun readNumber(nl: Boolean) {
        val start = i
        val c = src[i]
        if (c == '0' && i + 1 < src.length && src[i + 1].lowercaseChar() in "xob") {
            val radix = when (src[i + 1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
            i += 2
            val ds = i
            while (i < src.length && (Character.digit(src[i], radix) >= 0 || src[i] == '_')) i++
            val digits = src.substring(ds, i).replace("_", "")
            if (i < src.length && src[i] == 'n') i++
            val v = if (digits.isEmpty()) 0.0 else java.math.BigInteger(digits, radix).toDouble()
            toks.add(Tok(Tok.NUM, src.substring(start, i), nl, start, v))
            return
        }
        while (i < src.length && (src[i] in '0'..'9' || src[i] == '_')) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && (src[i] in '0'..'9' || src[i] == '_')) i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            val save = i
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            if (i < src.length && src[i] in '0'..'9') {
                while (i < src.length && src[i] in '0'..'9') i++
            } else {
                i = save
            }
        }
        val text = src.substring(start, i).replace("_", "")
        if (i < src.length && src[i] == 'n') i++
        // Eski tarz sekizlik (0123) — nadiren; ondalık kabul edilir.
        toks.add(Tok(Tok.NUM, text, nl, start, text.toDoubleOrNull() ?: Double.NaN))
    }

    private fun readEscape(sb: StringBuilder) {
        // src[i] == '\\'
        i++
        if (i >= src.length) return
        val e = src[i]
        i++
        when (e) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'v' -> sb.append('\u000B')
            'x' -> {
                val hex = src.substring(i, minOf(i + 2, src.length))
                val v = hex.toIntOrNull(16)
                if (v != null && hex.length == 2) { sb.append(v.toChar()); i += 2 } else sb.append('x')
            }
            'u' -> {
                if (i < src.length && src[i] == '{') {
                    val end = src.indexOf('}', i)
                    val cp = if (end > i) src.substring(i + 1, end).toIntOrNull(16) else null
                    if (cp != null) { sb.appendCodePoint(cp); i = end + 1 } else sb.append('u')
                } else {
                    val hex = src.substring(i, minOf(i + 4, src.length))
                    val v = hex.toIntOrNull(16)
                    if (v != null && hex.length == 4) { sb.append(v.toChar()); i += 4 } else sb.append('u')
                }
            }
            '\r' -> { if (i < src.length && src[i] == '\n') i++ }
            '\n', '\u2028', '\u2029' -> {}
            in '0'..'7' -> {
                var v = e - '0'
                var n = 1
                while (n < 3 && i < src.length && src[i] in '0'..'7' && v * 8 + (src[i] - '0') <= 255) {
                    v = v * 8 + (src[i] - '0'); i++; n++
                }
                sb.append(v.toChar())
            }
            else -> sb.append(e)
        }
    }

    private fun readString(q: Char): String {
        i++
        val sb = StringBuilder()
        while (i < src.length) {
            val c = src[i]
            if (c == q) { i++; return sb.toString() }
            if (c == '\\') { readEscape(sb); continue }
            if (c == '\n') throw JsSyntaxError("kapanmamış metin")
            sb.append(c); i++
        }
        throw JsSyntaxError("kapanmamış metin")
    }

    private fun readTemplate(nl: Boolean) {
        val start = i
        i++
        val quasis = ArrayList<String>()
        val exprs = ArrayList<String>()
        val sb = StringBuilder()
        while (i < src.length && src[i] != '`') {
            val c = src[i]
            if (c == '\\') { readEscape(sb); continue }
            if (c == '$' && i + 1 < src.length && src[i + 1] == '{') {
                quasis.add(sb.toString()); sb.setLength(0)
                i += 2
                val es = i
                skipBalanced()
                exprs.add(src.substring(es, i))
                i++ // }
                continue
            }
            sb.append(c); i++
        }
        if (i >= src.length) throw JsSyntaxError("kapanmamış şablon")
        i++
        quasis.add(sb.toString())
        toks.add(Tok(Tok.TEMPLATE, src.substring(start, i), nl, start, 0.0, Pair(quasis, exprs)))
    }

    /** `${` sonrasından eşleşen `}` karakterine kadar ilerler (i, `}` üzerinde durur). */
    private fun skipBalanced() {
        var depth = 1
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return }
                '"', '\'' -> { readString(src[i]); continue }
                '`' -> {
                    i++
                    while (i < src.length && src[i] != '`') {
                        if (src[i] == '\\') i++
                        else if (src[i] == '$' && i + 1 < src.length && src[i + 1] == '{') { i += 2; skipBalanced() }
                        i++
                    }
                }
            }
            i++
        }
    }

    private fun readRegex(nl: Boolean) {
        val start = i
        i++
        val sb = StringBuilder()
        var inClass = false
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                sb.append(c)
                if (i + 1 < src.length) sb.append(src[i + 1])
                i += 2
                continue
            }
            if (c == '\n' || c == '\r') throw JsSyntaxError("kapanmamış regex")
            if (inClass) {
                if (c == ']') inClass = false
            } else if (c == '[') {
                inClass = true
            } else if (c == '/') {
                break
            }
            sb.append(c); i++
        }
        if (i >= src.length) throw JsSyntaxError("kapanmamış regex")
        i++
        val fs = i
        while (i < src.length && isIdPart(src[i])) i++
        toks.add(Tok(Tok.REGEX, sb.toString(), nl, start, 0.0, src.substring(fs, i)))
    }

    private fun readPunct(nl: Boolean) {
        val start = i
        for (p in PUNCTS) {
            if (src.startsWith(p, i)) {
                if (p == "?." && i + 2 < src.length && src[i + 2] in '0'..'9') continue
                i += p.length
                toks.add(Tok(Tok.PUNCT, p, nl, start))
                return
            }
        }
        val c = src[i]
        if (c in "{}()[];,<>+-*/%&|^!~?:=.@#") {
            i++
            toks.add(Tok(Tok.PUNCT, c.toString(), nl, start))
            return
        }
        throw JsSyntaxError("beklenmeyen karakter '$c'")
    }

    companion object {
        private val PUNCTS = listOf(
            ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=",
            "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=",
            "/=", "%=", "&=", "|=", "^=", "**", "<<", ">>"
        )
        private val REGEX_KEYWORDS = setOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw",
            "case", "do", "else", "yield", "await"
        )
    }
}

// ───────────────────────────── Sözdizimi ağacı ─────────────────────────────

internal sealed class Node
internal object EmptyStmt : Node()
internal class ExprStmt(val e: Node) : Node()
internal class VarDecl(val decls: List<Pair<String, Node?>>) : Node()
internal class FunDecl(val name: String, val def: FunctionDef) : Node()
internal class IfStmt(val test: Node, val cons: Node, val alt: Node?) : Node()
internal class ForStmt(val init: Node?, val test: Node?, val update: Node?, val body: Node) : Node()
internal class ForInStmt(val name: String, val declare: Boolean, val obj: Node, val body: Node, val isOf: Boolean) : Node()
internal class WhileStmt(val test: Node, val body: Node) : Node()
internal class DoWhileStmt(val body: Node, val test: Node) : Node()
internal class ReturnStmt(val arg: Node?) : Node()
internal object BreakStmt : Node()
internal object ContinueStmt : Node()
internal class ThrowStmt(val arg: Node) : Node()
internal class TryStmt(val block: BlockStmt, val param: String?, val handler: BlockStmt?, val finalizer: BlockStmt?) : Node()
internal class SwitchStmt(val disc: Node, val cases: List<Pair<Node?, List<Node>>>) : Node()
internal class BlockStmt(val body: List<Node>) : Node()

internal class NumLit(val v: Double) : Node()
internal class StrLit(val v: String) : Node()
internal class BoolLit(val v: Boolean) : Node()
internal object NullLit : Node()
internal object ThisExpr : Node()
internal class Ident(val name: String) : Node()
internal class RegexLit(val source: String, val flags: String) : Node()
internal class TemplateLit(val quasis: List<String>, val exprs: List<Node>) : Node()
internal class ArrayLit(val items: List<Node?>) : Node()
internal class PropDef(val key: Node?, val value: Node, val computed: Boolean = false, val spread: Boolean = false)
internal class ObjectLit(val props: List<PropDef>) : Node()
internal class Param(val name: String, val default: Node?, val rest: Boolean)
internal class FunctionDef(
    val name: String?,
    val params: List<Param>,
    val body: List<Node>,
    val exprBody: Node?,
    val arrow: Boolean
)
internal class FunExpr(val def: FunctionDef) : Node()
internal class MemberExpr(val obj: Node, val prop: Node, val computed: Boolean, val optional: Boolean) : Node()
internal class CallExpr(val callee: Node, val args: List<Node>, val optional: Boolean) : Node()
internal class NewExpr(val callee: Node, val args: List<Node>) : Node()
internal class SpreadExpr(val arg: Node) : Node()
internal class UnaryExpr(val op: String, val arg: Node) : Node()
internal class UpdateExpr(val op: String, val prefix: Boolean, val target: Node) : Node()
internal class BinaryExpr(val op: String, val l: Node, val r: Node) : Node()
internal class LogicalExpr(val op: String, val l: Node, val r: Node) : Node()
internal class CondExpr(val test: Node, val cons: Node, val alt: Node) : Node()
internal class AssignExpr(val op: String, val target: Node, val value: Node) : Node()
internal class SeqExpr(val list: List<Node>) : Node()

// ───────────────────────────── Ayrıştırıcı ─────────────────────────────

internal class Parser(private val toks: List<Tok>, private var p: Int = 0) {

    val position: Int get() = p

    private fun peek(o: Int = 0): Tok = toks[minOf(p + o, toks.size - 1)]
    private fun next(): Tok {
        val t = toks[minOf(p, toks.size - 1)]
        if (p < toks.size - 1) p++ else if (t.type != Tok.EOF) p++
        return t
    }

    private fun isP(text: String, o: Int = 0): Boolean {
        val t = peek(o)
        return t.type == Tok.PUNCT && t.text == text
    }

    private fun isKw(text: String, o: Int = 0): Boolean {
        val t = peek(o)
        return t.type == Tok.IDENT && t.text == text
    }

    private fun eatP(text: String): Boolean {
        if (isP(text)) { p++; return true }
        return false
    }

    private fun expectP(text: String) {
        if (!eatP(text)) fail("'$text' bekleniyordu, bulunan '${peek().text}'")
    }

    private fun fail(msg: String): Nothing = throw JsSyntaxError("$msg (konum ${peek().pos})")

    fun parseProgram(): List<Node> {
        val list = ArrayList<Node>()
        while (peek().type != Tok.EOF) list.add(parseStatement())
        return list
    }

    /** Programı ifade ifade ayrıştırır; ayrıştırılamayan ifade atlanır (tolerans). */
    fun parseProgramTolerant(): List<Node> {
        val list = ArrayList<Node>()
        while (peek().type != Tok.EOF) {
            val start = p
            try {
                list.add(parseStatement())
            } catch (e: JsSyntaxError) {
                // Bir sonraki üst seviye ifade sınırına atla.
                p = start + 1
                var depth = 0
                while (peek().type != Tok.EOF) {
                    val t = peek()
                    if (t.type == Tok.PUNCT) {
                        when (t.text) {
                            "{", "(", "[" -> depth++
                            "}", ")", "]" -> depth--
                            ";" -> if (depth <= 0) { p++; break }
                        }
                    }
                    p++
                    if (depth < 0) depth = 0
                }
            }
        }
        return list
    }

    fun parseStatement(): Node {
        val t = peek()
        if (t.type == Tok.PUNCT) {
            when (t.text) {
                "{" -> return BlockStmt(parseBlockBody())
                ";" -> { p++; return EmptyStmt }
            }
        }
        if (t.type == Tok.IDENT) {
            when (t.text) {
                "var", "const" -> { val d = parseVarDecl(); consumeSemi(); return d }
                "let" -> if (peek(1).type == Tok.IDENT) { val d = parseVarDecl(); consumeSemi(); return d }
                "function" -> return parseFunctionDeclaration()
                "async" -> if (isKw("function", 1)) { p++; return parseFunctionDeclaration() }
                "if" -> {
                    p++; expectP("(")
                    val test = parseExpression()
                    expectP(")")
                    val cons = parseStatement()
                    val alt = if (isKw("else")) { p++; parseStatement() } else null
                    return IfStmt(test, cons, alt)
                }
                "for" -> return parseFor()
                "while" -> {
                    p++; expectP("(")
                    val test = parseExpression()
                    expectP(")")
                    return WhileStmt(test, parseStatement())
                }
                "do" -> {
                    p++
                    val body = parseStatement()
                    if (!isKw("while")) fail("'while' bekleniyordu")
                    p++; expectP("(")
                    val test = parseExpression()
                    expectP(")")
                    eatP(";")
                    return DoWhileStmt(body, test)
                }
                "return" -> {
                    p++
                    val arg = if (isP(";") || isP("}") || peek().nl || peek().type == Tok.EOF) null else parseExpression()
                    consumeSemi()
                    return ReturnStmt(arg)
                }
                "break", "continue" -> {
                    p++
                    if (peek().type == Tok.IDENT && !peek().nl) p++ // etiket yok sayılır
                    consumeSemi()
                    return if (t.text == "break") BreakStmt else ContinueStmt
                }
                "throw" -> {
                    p++
                    val arg = parseExpression()
                    consumeSemi()
                    return ThrowStmt(arg)
                }
                "try" -> {
                    p++
                    val block = BlockStmt(parseBlockBody())
                    var param: String? = null
                    var handler: BlockStmt? = null
                    var fin: BlockStmt? = null
                    if (isKw("catch")) {
                        p++
                        if (eatP("(")) { param = next().text; expectP(")") }
                        handler = BlockStmt(parseBlockBody())
                    }
                    if (isKw("finally")) { p++; fin = BlockStmt(parseBlockBody()) }
                    return TryStmt(block, param, handler, fin)
                }
                "switch" -> {
                    p++; expectP("(")
                    val disc = parseExpression()
                    expectP(")"); expectP("{")
                    val cases = ArrayList<Pair<Node?, List<Node>>>()
                    while (!eatP("}")) {
                        val test: Node? = when {
                            isKw("case") -> { p++; parseExpression() }
                            isKw("default") -> { p++; null }
                            else -> fail("'case' bekleniyordu")
                        }
                        expectP(":")
                        val body = ArrayList<Node>()
                        while (!isKw("case") && !isKw("default") && !isP("}")) {
                            if (peek().type == Tok.EOF) fail("beklenmeyen son")
                            body.add(parseStatement())
                        }
                        cases.add(test to body)
                    }
                    return SwitchStmt(disc, cases)
                }
                "class" -> fail("class desteklenmiyor")
            }
            if (isP(":", 1) && t.text !in RESERVED) {
                p += 2 // etiket
                return parseStatement()
            }
        }
        val e = parseExpression()
        consumeSemi()
        return ExprStmt(e)
    }

    private fun consumeSemi() {
        if (eatP(";")) return
        if (isP("}") || peek().type == Tok.EOF || peek().nl) return
        fail("';' bekleniyordu, bulunan '${peek().text}'")
    }

    private fun parseVarDecl(): VarDecl {
        p++ // var/let/const
        val decls = ArrayList<Pair<String, Node?>>()
        do {
            val nameTok = next()
            if (nameTok.type != Tok.IDENT) fail("değişken adı bekleniyordu")
            val init = if (eatP("=")) parseAssign() else null
            decls.add(nameTok.text to init)
        } while (eatP(","))
        return VarDecl(decls)
    }

    private fun parseFor(): Node {
        p++ // for
        expectP("(")
        var init: Node? = null
        if (!isP(";")) {
            if (isKw("var") || isKw("let") || isKw("const")) {
                if (peek(1).type == Tok.IDENT && (isKw("in", 2) || isKw("of", 2))) {
                    p++
                    val name = next().text
                    val isOf = next().text == "of"
                    val obj = parseExpression()
                    expectP(")")
                    return ForInStmt(name, true, obj, parseStatement(), isOf)
                }
                init = parseVarDecl()
            } else {
                if (peek().type == Tok.IDENT && (isKw("in", 1) || isKw("of", 1))) {
                    val name = next().text
                    val isOf = next().text == "of"
                    val obj = parseExpression()
                    expectP(")")
                    return ForInStmt(name, false, obj, parseStatement(), isOf)
                }
                init = ExprStmt(parseExpression())
            }
        }
        expectP(";")
        val test = if (isP(";")) null else parseExpression()
        expectP(";")
        val update = if (isP(")")) null else parseExpression()
        expectP(")")
        return ForStmt(init, test, update, parseStatement())
    }

    private fun parseFunctionDeclaration(): Node {
        p++ // function
        eatP("*")
        val nameTok = next()
        if (nameTok.type != Tok.IDENT) fail("fonksiyon adı bekleniyordu")
        val params = parseParams()
        val body = parseBlockBody()
        return FunDecl(nameTok.text, FunctionDef(nameTok.text, params, body, null, false))
    }

    private fun parseParams(): List<Param> {
        expectP("(")
        val list = ArrayList<Param>()
        while (!eatP(")")) {
            val rest = eatP("...")
            val nameTok = next()
            if (nameTok.type != Tok.IDENT) fail("parametre adı bekleniyordu")
            val def = if (eatP("=")) parseAssign() else null
            list.add(Param(nameTok.text, def, rest))
            if (!isP(")")) expectP(",")
        }
        return list
    }

    private fun parseBlockBody(): List<Node> {
        expectP("{")
        val list = ArrayList<Node>()
        while (!eatP("}")) {
            if (peek().type == Tok.EOF) fail("beklenmeyen son")
            list.add(parseStatement())
        }
        return list
    }

    fun parseExpression(): Node {
        val first = parseAssign()
        if (!isP(",")) return first
        val list = arrayListOf(first)
        while (eatP(",")) list.add(parseAssign())
        return SeqExpr(list)
    }

    fun parseAssign(): Node {
        arrowOrNull()?.let { return it }
        val left = parseConditional()
        val t = peek()
        if (t.type == Tok.PUNCT && t.text in ASSIGN_OPS) {
            if (left !is Ident && left !is MemberExpr) fail("geçersiz atama hedefi")
            p++
            return AssignExpr(t.text, left, parseAssign())
        }
        return left
    }

    private fun arrowOrNull(): Node? {
        val t = peek()
        if (t.type == Tok.IDENT && t.text == "async" && (peek(1).type == Tok.IDENT || isP("(", 1)) && !peek(1).nl) {
            // async ok fonksiyonu: senkron gibi ele alınır
            val save = p
            p++
            val r = arrowOrNull()
            if (r != null) return r
            p = save
            return null
        }
        if (t.type == Tok.IDENT && isP("=>", 1) && t.text !in RESERVED) {
            p += 2
            return parseArrowBody(listOf(Param(t.text, null, false)))
        }
        if (isP("(")) {
            var depth = 0
            var j = p
            while (j < toks.size) {
                val tk = toks[j]
                if (tk.type == Tok.EOF) return null
                if (tk.type == Tok.PUNCT) {
                    when (tk.text) {
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> { depth--; if (depth == 0) break }
                    }
                }
                j++
            }
            if (j + 1 < toks.size && toks[j + 1].type == Tok.PUNCT && toks[j + 1].text == "=>") {
                val params = parseParams()
                expectP("=>")
                return parseArrowBody(params)
            }
        }
        return null
    }

    private fun parseArrowBody(params: List<Param>): Node {
        if (isP("{")) return FunExpr(FunctionDef(null, params, parseBlockBody(), null, true))
        return FunExpr(FunctionDef(null, params, emptyList(), parseAssign(), true))
    }

    private fun parseConditional(): Node {
        val test = parseBinary(1)
        if (!eatP("?")) return test
        val cons = parseAssign()
        expectP(":")
        val alt = parseAssign()
        return CondExpr(test, cons, alt)
    }

    private fun binOp(): String? {
        val t = peek()
        return when {
            t.type == Tok.PUNCT && PREC.containsKey(t.text) -> t.text
            t.type == Tok.IDENT && (t.text == "instanceof" || t.text == "in") -> t.text
            else -> null
        }
    }

    private fun parseBinary(minPrec: Int): Node {
        var left = parseUnary()
        while (true) {
            val op = binOp() ?: break
            val prec = PREC.getValue(op)
            if (prec < minPrec) break
            p++
            val right = if (op == "**") parseBinary(prec) else parseBinary(prec + 1)
            left = if (op == "||" || op == "&&" || op == "??") LogicalExpr(op, left, right) else BinaryExpr(op, left, right)
        }
        return left
    }

    private fun parseUnary(): Node {
        val t = peek()
        if (t.type == Tok.PUNCT) {
            when (t.text) {
                "!", "~", "+", "-" -> { p++; return UnaryExpr(t.text, parseUnary()) }
                "++", "--" -> { p++; return UpdateExpr(t.text, true, parseUnary()) }
            }
        }
        if (t.type == Tok.IDENT && (t.text == "typeof" || t.text == "void" || t.text == "delete")) {
            p++
            return UnaryExpr(t.text, parseUnary())
        }
        if (t.type == Tok.IDENT && t.text == "await") {
            p++
            return parseUnary()
        }
        var e = parseCallMember()
        val n = peek()
        if (n.type == Tok.PUNCT && (n.text == "++" || n.text == "--") && !n.nl) {
            p++
            e = UpdateExpr(n.text, false, e)
        }
        return e
    }

    private fun parseArgs(): List<Node> {
        expectP("(")
        val list = ArrayList<Node>()
        while (!eatP(")")) {
            if (eatP("...")) list.add(SpreadExpr(parseAssign())) else list.add(parseAssign())
            if (!isP(")")) expectP(",")
        }
        return list
    }

    private fun memberName(): String {
        val t = next()
        if (t.type != Tok.IDENT && t.type != Tok.STR && t.type != Tok.NUM) fail("özellik adı bekleniyordu")
        return t.text
    }

    private fun parseCallMember(): Node {
        var e: Node = if (isKw("new")) {
            p++
            var callee = if (isKw("new")) parseCallMember() else parsePrimary()
            while (true) {
                callee = when {
                    isP(".") -> { p++; MemberExpr(callee, StrLit(memberName()), false, false) }
                    isP("[") -> { p++; val pr = parseExpression(); expectP("]"); MemberExpr(callee, pr, true, false) }
                    else -> break
                }
            }
            NewExpr(callee, if (isP("(")) parseArgs() else emptyList())
        } else {
            parsePrimary()
        }
        while (true) {
            val t = peek()
            if (t.type != Tok.PUNCT) break
            e = when (t.text) {
                "." -> { p++; MemberExpr(e, StrLit(memberName()), false, false) }
                "?." -> {
                    p++
                    when {
                        isP("(") -> CallExpr(e, parseArgs(), true)
                        isP("[") -> { p++; val pr = parseExpression(); expectP("]"); MemberExpr(e, pr, true, true) }
                        else -> MemberExpr(e, StrLit(memberName()), false, true)
                    }
                }
                "[" -> { p++; val pr = parseExpression(); expectP("]"); MemberExpr(e, pr, true, false) }
                "(" -> CallExpr(e, parseArgs(), false)
                else -> return e
            }
        }
        return e
    }

    private fun parsePrimary(): Node {
        val t = next()
        when (t.type) {
            Tok.NUM -> return NumLit(t.num)
            Tok.STR -> return StrLit(t.text)
            Tok.REGEX -> return RegexLit(t.text, t.extra as String)
            Tok.TEMPLATE -> {
                @Suppress("UNCHECKED_CAST")
                val parts = t.extra as Pair<List<String>, List<String>>
                return TemplateLit(parts.first, parts.second.map { Parser(Lexer(it).run()).parseExpression() })
            }
            Tok.IDENT -> {
                return when (t.text) {
                    "function" -> {
                        eatP("*")
                        val name = if (peek().type == Tok.IDENT) next().text else null
                        val params = parseParams()
                        val body = parseBlockBody()
                        FunExpr(FunctionDef(name, params, body, null, false))
                    }
                    "true" -> BoolLit(true)
                    "false" -> BoolLit(false)
                    "null" -> NullLit
                    "this" -> ThisExpr
                    else -> Ident(t.text)
                }
            }
            Tok.PUNCT -> when (t.text) {
                "(" -> { val e = parseExpression(); expectP(")"); return e }
                "[" -> {
                    val items = ArrayList<Node?>()
                    while (!eatP("]")) {
                        if (isP(",")) { p++; items.add(null); continue }
                        if (eatP("...")) items.add(SpreadExpr(parseAssign())) else items.add(parseAssign())
                        if (!isP("]")) expectP(",")
                    }
                    return ArrayLit(items)
                }
                "{" -> {
                    val props = ArrayList<PropDef>()
                    while (!eatP("}")) {
                        if (eatP("...")) {
                            props.add(PropDef(null, parseAssign(), spread = true))
                        } else {
                            var kt = next()
                            // get/set erişimcileri: değer gibi ele alınır
                            if (kt.type == Tok.IDENT && (kt.text == "get" || kt.text == "set") &&
                                (peek().type == Tok.IDENT || peek().type == Tok.STR) && isP("(", 1)
                            ) {
                                kt = next()
                            }
                            var computed = false
                            val keyNode: Node = when (kt.type) {
                                Tok.PUNCT -> if (kt.text == "[") {
                                    computed = true
                                    val k = parseAssign(); expectP("]"); k
                                } else fail("nesne anahtarı bekleniyordu")
                                Tok.NUM -> StrLit(MiniJs.numToStr(kt.num))
                                Tok.IDENT, Tok.STR -> StrLit(kt.text)
                                else -> fail("nesne anahtarı bekleniyordu")
                            }
                            when {
                                isP("(") -> {
                                    val params = parseParams()
                                    val body = parseBlockBody()
                                    props.add(PropDef(keyNode, FunExpr(FunctionDef(null, params, body, null, false)), computed))
                                }
                                eatP(":") -> props.add(PropDef(keyNode, parseAssign(), computed))
                                else -> props.add(PropDef(keyNode, Ident(kt.text), computed))
                            }
                        }
                        if (!isP("}")) expectP(",")
                    }
                    return ObjectLit(props)
                }
            }
        }
        p--
        fail("beklenmeyen '${t.text}'")
    }

    companion object {
        private val ASSIGN_OPS = setOf(
            "=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "**=", "&&=", "||=", "??="
        )
        private val PREC = mapOf(
            "??" to 1, "||" to 2, "&&" to 3, "|" to 4, "^" to 5, "&" to 6,
            "==" to 7, "!=" to 7, "===" to 7, "!==" to 7,
            "<" to 8, ">" to 8, "<=" to 8, ">=" to 8, "instanceof" to 8, "in" to 8,
            "<<" to 9, ">>" to 9, ">>>" to 9,
            "+" to 10, "-" to 10, "*" to 11, "/" to 11, "%" to 11, "**" to 12
        )
        val RESERVED = setOf(
            "var", "let", "const", "function", "if", "else", "for", "while", "do", "return", "break",
            "continue", "throw", "try", "catch", "finally", "switch", "case", "default", "new", "delete",
            "typeof", "void", "instanceof", "in", "this", "null", "true", "false", "class"
        )
    }
}

// ───────────────────────────── Kapsam ─────────────────────────────

internal class Scope(val parent: Scope?, val funcScope: Boolean) {
    val vars: MutableMap<String, Any?> = HashMap()
    var thisValue: Any? = Undefined

    fun find(name: String): Scope? {
        var s: Scope? = this
        while (s != null) {
            if (s.vars.containsKey(name)) return s
            s = s.parent
        }
        return null
    }

    fun functionScope(): Scope {
        var s = this
        while (!s.funcScope && s.parent != null) s = s.parent!!
        return s
    }
}

// ───────────────────────────── Yorumlayıcı ─────────────────────────────

/**
 * @param hostname sayfanın host adı (`location.hostname`) — çözücü anahtarı buna bağlı.
 * @param pageUrl  sayfanın tam adresi (`location.href`).
 */
internal class MiniJs(
    private val pageUrl: String = "https://localhost/",
    private val stepLimit: Long = 3_000_000L
) {
    val global = Scope(null, true)
    private var steps = 0L
    private var depth = 0

    /** `jwplayer(...).setup(cfg)` çağrılarında yakalanan yapılandırmalar. */
    val capturedSetups = ArrayList<Any?>()

    private val evalFn: NativeFunction

    init {
        evalFn = NativeFunction("eval") { _, args ->
            val code = args.firstOrNull()
            if (code is String) runCode(code, global, tolerant = false) else code
        }
        installGlobals()
    }

    // ── Genel API ──

    /** Kodu üst seviyede çalıştırır; tolerant=true ise hatalı ifadeler atlanır. */
    fun runCode(code: String, scope: Scope = global, tolerant: Boolean = true): Any? {
        val toks = Lexer(code).run()
        val parser = Parser(toks)
        val program = if (tolerant) parser.parseProgramTolerant() else parser.parseProgram()
        return runProgram(program, scope, tolerant)
    }

    fun runProgram(program: List<Node>, scope: Scope = global, tolerant: Boolean = true): Any? {
        hoist(program, scope.functionScope())
        var last: Any? = Undefined
        for (st in program) {
            if (tolerant) {
                try {
                    last = execStatementValue(st, scope)
                } catch (e: StepLimitException) {
                    throw e
                } catch (e: StackOverflowError) {
                    throw e
                } catch (e: Exception) {
                    // tolerans: tarayıcıdaki gibi bir hata sonraki ifadeleri durdurmaz
                }
            } else {
                last = execStatementValue(st, scope)
            }
        }
        return last
    }

    fun evalNode(node: Node, scope: Scope = global): Any? = eval(node, scope)

    fun evalExpression(code: String): Any? = eval(Parser(Lexer(code).run()).parseExpression(), global)

    class StepLimitException : RuntimeException("adım sınırı aşıldı")

    private fun tick() {
        if (++steps > stepLimit) throw StepLimitException()
    }

    private fun execStatementValue(st: Node, scope: Scope): Any? {
        if (st is ExprStmt) return eval(st.e, scope)
        exec(st, scope)
        return Undefined
    }

    // ── Kaldırma (hoisting) ──

    private fun hoist(body: List<Node>, scope: Scope) {
        for (st in body) hoistNode(st, scope)
    }

    private fun hoistNode(n: Node, scope: Scope) {
        when (n) {
            is VarDecl -> n.decls.forEach { (name, _) -> if (!scope.vars.containsKey(name)) scope.vars[name] = Undefined }
            is FunDecl -> scope.vars[n.name] = Closure(n.def, scope)
            is IfStmt -> { hoistNode(n.cons, scope); n.alt?.let { hoistNode(it, scope) } }
            is ForStmt -> { n.init?.let { hoistNode(it, scope) }; hoistNode(n.body, scope) }
            is ForInStmt -> {
                if (n.declare && !scope.vars.containsKey(n.name)) scope.vars[n.name] = Undefined
                hoistNode(n.body, scope)
            }
            is WhileStmt -> hoistNode(n.body, scope)
            is DoWhileStmt -> hoistNode(n.body, scope)
            is BlockStmt -> n.body.forEach { hoistNode(it, scope) }
            is TryStmt -> {
                hoistNode(n.block, scope)
                n.handler?.let { hoistNode(it, scope) }
                n.finalizer?.let { hoistNode(it, scope) }
            }
            is SwitchStmt -> n.cases.forEach { c -> c.second.forEach { hoistNode(it, scope) } }
            else -> {}
        }
    }

    // ── İfadeler (statements) ──

    private fun exec(n: Node, scope: Scope) {
        tick()
        when (n) {
            is ExprStmt -> eval(n.e, scope)
            is VarDecl -> for ((name, init) in n.decls) {
                if (init != null) {
                    val v = eval(init, scope)
                    val s = scope.find(name) ?: scope.functionScope()
                    s.vars[name] = v
                }
            }
            is FunDecl -> {}
            is EmptyStmt -> {}
            is BlockStmt -> for (st in n.body) exec(st, scope)
            is IfStmt -> if (truthy(eval(n.test, scope))) exec(n.cons, scope) else n.alt?.let { exec(it, scope) }
            is ForStmt -> {
                n.init?.let { exec(it, scope) }
                while (n.test == null || truthy(eval(n.test, scope))) {
                    tick()
                    try {
                        exec(n.body, scope)
                    } catch (b: BreakSignal) {
                        break
                    } catch (c: ContinueSignal) {
                        // devam
                    }
                    n.update?.let { eval(it, scope) }
                }
            }
            is ForInStmt -> {
                val obj = eval(n.obj, scope)
                val items: List<Any?> = if (n.isOf) iterate(obj) else keysOf(obj)
                for (item in items) {
                    tick()
                    if (n.declare) {
                        (scope.find(n.name) ?: scope.functionScope()).vars[n.name] = item
                    } else {
                        assignIdent(n.name, item, scope)
                    }
                    try {
                        exec(n.body, scope)
                    } catch (b: BreakSignal) {
                        break
                    } catch (c: ContinueSignal) {
                        // devam
                    }
                }
            }
            is WhileStmt -> while (truthy(eval(n.test, scope))) {
                tick()
                try {
                    exec(n.body, scope)
                } catch (b: BreakSignal) {
                    break
                } catch (c: ContinueSignal) {
                    // devam
                }
            }
            is DoWhileStmt -> do {
                tick()
                try {
                    exec(n.body, scope)
                } catch (b: BreakSignal) {
                    break
                } catch (c: ContinueSignal) {
                    // devam
                }
            } while (truthy(eval(n.test, scope)))
            is ReturnStmt -> throw ReturnSignal(if (n.arg == null) Undefined else eval(n.arg, scope))
            is BreakStmt -> throw BreakSignal
            is ContinueStmt -> throw ContinueSignal
            is ThrowStmt -> throw JsThrow(eval(n.arg, scope))
            is TryStmt -> execTry(n, scope)
            is SwitchStmt -> execSwitch(n, scope)
            else -> eval(n, scope)
        }
    }

    private fun execTry(n: TryStmt, scope: Scope) {
        try {
            exec(n.block, scope)
        } catch (e: Exception) {
            if (e is ReturnSignal || e === BreakSignal || e === ContinueSignal || e is StepLimitException) throw e
            val handler = n.handler ?: throw e
            val value: Any? = when (e) {
                is JsThrow -> e.value
                else -> makeError("Error", e.message ?: e.javaClass.simpleName)
            }
            val cs = Scope(scope, false)
            cs.thisValue = scope.thisValue
            if (n.param != null) cs.vars[n.param] = value
            exec(handler, cs)
        } finally {
            n.finalizer?.let { exec(it, scope) }
        }
    }

    private fun execSwitch(n: SwitchStmt, scope: Scope) {
        val d = eval(n.disc, scope)
        var matched = -1
        for ((idx, c) in n.cases.withIndex()) {
            if (c.first != null && strictEq(d, eval(c.first!!, scope))) { matched = idx; break }
        }
        if (matched < 0) matched = n.cases.indexOfFirst { it.first == null }
        if (matched < 0) return
        try {
            for (idx in matched until n.cases.size) {
                for (st in n.cases[idx].second) exec(st, scope)
            }
        } catch (b: BreakSignal) {
            // switch sonu
        }
    }

    // ── İfadeler (expressions) ──

    private fun eval(n: Node, scope: Scope): Any? {
        return when (n) {
            is NumLit -> n.v
            is StrLit -> n.v
            is BoolLit -> n.v
            is NullLit -> null
            is ThisExpr -> lookupThis(scope)
            is Ident -> lookup(n.name, scope)
            is RegexLit -> JsRegExp(n.source, n.flags)
            is TemplateLit -> {
                val sb = StringBuilder()
                for (i in n.quasis.indices) {
                    sb.append(n.quasis[i])
                    if (i < n.exprs.size) sb.append(toStr(eval(n.exprs[i], scope)))
                }
                sb.toString()
            }
            is ArrayLit -> {
                val list = ArrayList<Any?>(n.items.size)
                for (it in n.items) {
                    when (it) {
                        null -> list.add(Undefined)
                        is SpreadExpr -> list.addAll(iterate(eval(it.arg, scope)))
                        else -> list.add(eval(it, scope))
                    }
                }
                JsArray(list)
            }
            is ObjectLit -> {
                val o = JsObject()
                for (pd in n.props) {
                    if (pd.spread) {
                        val src = eval(pd.value, scope)
                        if (src is JsArray) src.list.forEachIndexed { i, v -> o.props[i.toString()] = v }
                        else if (src is JsObject) o.props.putAll(src.props)
                    } else {
                        val key = if (pd.computed) toPropKey(eval(pd.key!!, scope)) else (pd.key as StrLit).v
                        o.props[key] = eval(pd.value, scope)
                    }
                }
                o
            }
            is FunExpr -> Closure(n.def, scope)
            is MemberExpr -> {
                val obj = eval(n.obj, scope)
                if (n.optional && (obj == null || obj === Undefined)) Undefined
                else getProp(obj, propKey(n, scope))
            }
            is CallExpr -> evalCall(n, scope)
            is NewExpr -> evalNew(n, scope)
            is UnaryExpr -> evalUnary(n, scope)
            is UpdateExpr -> {
                val ref = resolveRef(n.target, scope)
                val old = toNum(ref.get())
                val nv = if (n.op == "++") old + 1 else old - 1
                ref.set(nv)
                if (n.prefix) nv else old
            }
            is BinaryExpr -> binary(n.op, eval(n.l, scope), eval(n.r, scope))
            is LogicalExpr -> {
                val l = eval(n.l, scope)
                when (n.op) {
                    "||" -> if (truthy(l)) l else eval(n.r, scope)
                    "&&" -> if (!truthy(l)) l else eval(n.r, scope)
                    else -> if (l == null || l === Undefined) eval(n.r, scope) else l
                }
            }
            is CondExpr -> if (truthy(eval(n.test, scope))) eval(n.cons, scope) else eval(n.alt, scope)
            is AssignExpr -> evalAssign(n, scope)
            is SeqExpr -> {
                var v: Any? = Undefined
                for (e in n.list) v = eval(e, scope)
                v
            }
            else -> throw JsThrow(makeError("SyntaxError", "desteklenmeyen ifade ${n.javaClass.simpleName}"))
        }
    }

    private fun propKey(n: MemberExpr, scope: Scope): String =
        if (n.computed) toPropKey(eval(n.prop, scope)) else (n.prop as StrLit).v

    private fun lookupThis(scope: Scope): Any? {
        var s: Scope? = scope
        while (s != null) {
            if (s.funcScope) return s.thisValue
            s = s.parent
        }
        return Undefined
    }

    private fun lookup(name: String, scope: Scope): Any? {
        val s = scope.find(name)
        if (s != null) return s.vars[name]
        throw JsThrow(makeError("ReferenceError", "$name is not defined"))
    }

    private fun assignIdent(name: String, v: Any?, scope: Scope) {
        val s = scope.find(name) ?: global
        s.vars[name] = v
    }

    private interface Ref {
        fun get(): Any?
        fun set(v: Any?)
    }

    private fun resolveRef(target: Node, scope: Scope): Ref {
        return when (target) {
            is Ident -> object : Ref {
                override fun get() = lookup(target.name, scope)
                override fun set(v: Any?) = assignIdent(target.name, v, scope)
            }
            is MemberExpr -> {
                val obj = eval(target.obj, scope)
                val key = propKey(target, scope)
                object : Ref {
                    override fun get() = getProp(obj, key)
                    override fun set(v: Any?) = setProp(obj, key, v)
                }
            }
            else -> throw JsThrow(makeError("SyntaxError", "geçersiz atama hedefi"))
        }
    }

    private fun evalAssign(n: AssignExpr, scope: Scope): Any? {
        if (n.op == "=") {
            if (n.target is Ident) {
                val v = eval(n.value, scope)
                assignIdent(n.target.name, v, scope)
                return v
            }
            val ref = resolveRef(n.target, scope)
            val v = eval(n.value, scope)
            ref.set(v)
            return v
        }
        val ref = resolveRef(n.target, scope)
        val cur = ref.get()
        val v = when (n.op) {
            "&&=" -> if (!truthy(cur)) return cur else eval(n.value, scope)
            "||=" -> if (truthy(cur)) return cur else eval(n.value, scope)
            "??=" -> if (cur != null && cur !== Undefined) return cur else eval(n.value, scope)
            else -> binary(n.op.dropLast(1), cur, eval(n.value, scope))
        }
        ref.set(v)
        return v
    }

    private fun evalArgs(args: List<Node>, scope: Scope): List<Any?> {
        val out = ArrayList<Any?>(args.size)
        for (a in args) {
            if (a is SpreadExpr) out.addAll(iterate(eval(a.arg, scope))) else out.add(eval(a, scope))
        }
        return out
    }

    private fun evalCall(n: CallExpr, scope: Scope): Any? {
        val callee = n.callee
        // Packer (eval(function(p,a,c,k,e,d){..}(..))) — yerel açıcı ile hızlı yol.
        if (callee is Ident && callee.name == "eval" && n.args.size == 1) {
            val inner = n.args[0]
            if (inner is CallExpr && Packer.isPackerFunction(inner.callee)) {
                val unpacked = Packer.unpackCall(inner) { node -> eval(node, scope) }
                if (unpacked != null) return runCode(unpacked, scope, tolerant = false)
            }
        }
        var self: Any? = Undefined
        val fn: Any?
        if (callee is MemberExpr) {
            val obj = eval(callee.obj, scope)
            if (callee.optional && (obj == null || obj === Undefined)) return Undefined
            val key = propKey(callee, scope)
            self = obj
            // İlkel/dizi yöntemleri için doğrudan çağrı (ara nesne üretmeden)
            val direct = Builtins.directMethod(this, obj, key)
            if (direct != null) {
                val args = evalArgs(n.args, scope)
                return direct(args)
            }
            fn = getProp(obj, key)
        } else {
            fn = eval(callee, scope)
        }
        if (n.optional && (fn == null || fn === Undefined)) return Undefined
        val args = evalArgs(n.args, scope)
        if (fn === evalFn && callee is Ident) {
            val code = args.firstOrNull()
            return if (code is String) runCode(code, scope, tolerant = false) else code
        }
        if (fn !is JsFunction) {
            throw JsThrow(makeError("TypeError", "${describe(callee)} is not a function"))
        }
        return fn.call(self, args)
    }

    private fun describe(n: Node): String = when (n) {
        is Ident -> n.name
        is MemberExpr -> describe(n.obj) + "." + ((n.prop as? StrLit)?.v ?: "[...]")
        else -> "expression"
    }

    private fun evalNew(n: NewExpr, scope: Scope): Any? {
        val ctor = eval(n.callee, scope)
        val args = evalArgs(n.args, scope)
        return construct(ctor, args)
    }

    fun construct(ctor: Any?, args: List<Any?>): Any? {
        return when {
            ctor is NativeFunction && ctor.props.containsKey("__ctor") -> {
                @Suppress("UNCHECKED_CAST")
                (ctor.props["__ctor"] as (List<Any?>) -> Any?)(args)
            }
            ctor is Closure -> {
                val obj = JsObject()
                val proto = ctor.props["prototype"]
                if (proto is JsObject) obj.props.putAll(proto.props)
                val r = ctor.call(obj, args)
                if (r is JsObject) r else obj
            }
            ctor is JsFunction -> ctor.call(Undefined, args)
            else -> throw JsThrow(makeError("TypeError", "not a constructor"))
        }
    }

    private fun evalUnary(n: UnaryExpr, scope: Scope): Any? {
        return when (n.op) {
            "typeof" -> {
                if (n.arg is Ident && scope.find(n.arg.name) == null) "undefined"
                else typeOf(eval(n.arg, scope))
            }
            "delete" -> {
                if (n.arg is MemberExpr) {
                    val o = eval(n.arg.obj, scope)
                    val k = propKey(n.arg, scope)
                    if (o is JsArray) {
                        val idx = arrayIndex(k)
                        if (idx in 0 until o.list.size) o.list[idx] = Undefined
                    } else if (o is JsObject) {
                        o.props.remove(k)
                    }
                }
                true
            }
            "void" -> { eval(n.arg, scope); Undefined }
            "!" -> !truthy(eval(n.arg, scope))
            "-" -> -toNum(eval(n.arg, scope))
            "+" -> toNum(eval(n.arg, scope))
            "~" -> toInt32(eval(n.arg, scope)).inv().toDouble()
            else -> throw JsThrow(makeError("SyntaxError", "bilinmeyen operatör ${n.op}"))
        }
    }

    fun binary(op: String, a: Any?, b: Any?): Any? {
        return when (op) {
            "+" -> {
                val pa = toPrimitive(a)
                val pb = toPrimitive(b)
                if (pa is String || pb is String) toStr(pa) + toStr(pb) else toNum(pa) + toNum(pb)
            }
            "-" -> toNum(a) - toNum(b)
            "*" -> toNum(a) * toNum(b)
            "/" -> toNum(a) / toNum(b)
            "%" -> toNum(a) % toNum(b)
            "**" -> Math.pow(toNum(a), toNum(b))
            "<<" -> (toInt32(a) shl (toInt32(b) and 31)).toDouble()
            ">>" -> (toInt32(a) shr (toInt32(b) and 31)).toDouble()
            ">>>" -> ((toInt32(a).toLong() and 0xFFFFFFFFL) ushr (toInt32(b) and 31)).toDouble()
            "&" -> (toInt32(a) and toInt32(b)).toDouble()
            "|" -> (toInt32(a) or toInt32(b)).toDouble()
            "^" -> (toInt32(a) xor toInt32(b)).toDouble()
            "==" -> looseEq(a, b)
            "!=" -> !looseEq(a, b)
            "===" -> strictEq(a, b)
            "!==" -> !strictEq(a, b)
            "<" -> lessThan(a, b) == true
            ">" -> lessThan(b, a) == true
            "<=" -> lessThan(b, a) == false
            ">=" -> lessThan(a, b) == false
            "instanceof" -> when {
                b is NativeFunction && b.fnName == "Array" -> a is JsArray
                b is NativeFunction && b.fnName == "RegExp" -> a is JsRegExp
                b is NativeFunction && b.fnName == "Function" -> a is JsFunction
                b is NativeFunction && b.fnName == "Object" -> a is JsObject
                else -> false
            }
            "in" -> when (b) {
                is JsArray -> { val k = toPropKey(a); k == "length" || arrayIndex(k) in 0 until b.list.size }
                is JsObject -> b.props.containsKey(toPropKey(a))
                else -> throw JsThrow(makeError("TypeError", "'in' nesne olmayan değerde"))
            }
            else -> throw JsThrow(makeError("SyntaxError", "bilinmeyen operatör $op"))
        }
    }

    // ── Özellik erişimi ──

    fun getProp(obj: Any?, key: String): Any? {
        return when (obj) {
            null, Undefined -> throw JsThrow(makeError("TypeError", "Cannot read properties of ${toStr(obj)} (reading '$key')"))
            is String -> Builtins.stringProp(this, obj, key)
            is Double -> Builtins.numberProp(this, obj, key)
            is Boolean -> if (key == "toString" || key == "valueOf") NativeFunction(key) { _, _ -> if (key == "toString") obj.toString() else obj } else Undefined
            is JsArray -> Builtins.arrayProp(this, obj, key)
            is JsRegExp -> Builtins.regexpProp(this, obj, key)
            is JsFunction -> {
                if (obj.props.containsKey(key)) obj.props[key]
                else Builtins.functionProp(this, obj, key)
            }
            is JsObject -> {
                if (obj.props.containsKey(key)) obj.props[key]
                else Builtins.objectProp(this, obj, key)
            }
            else -> Undefined
        }
    }

    fun setProp(obj: Any?, key: String, v: Any?) {
        when (obj) {
            null, Undefined -> throw JsThrow(makeError("TypeError", "Cannot set properties of ${toStr(obj)} (setting '$key')"))
            is JsArray -> {
                if (key == "length") {
                    val n = toNum(v).toInt().coerceAtLeast(0)
                    while (obj.list.size > n) obj.list.removeAt(obj.list.size - 1)
                    while (obj.list.size < n) obj.list.add(Undefined)
                } else {
                    val idx = arrayIndex(key)
                    if (idx >= 0) {
                        if (idx > 50_000_000) throw JsThrow(makeError("RangeError", "dizi çok büyük"))
                        while (obj.list.size <= idx) obj.list.add(Undefined)
                        obj.list[idx] = v
                    } else {
                        obj.props[key] = v
                    }
                }
            }
            BlackHole -> {}
            is JsRegExp -> if (key == "lastIndex") obj.lastIndex = toNum(v).toInt() else obj.props[key] = v
            is JsObject -> obj.props[key] = v
            else -> {}
        }
    }

    fun keysOf(obj: Any?): List<Any?> = when (obj) {
        is JsArray -> obj.list.indices.map { it.toString() } + obj.props.keys
        is String -> obj.indices.map { it.toString() }
        is JsObject -> obj.props.keys.toList()
        else -> emptyList()
    }

    fun iterate(obj: Any?): List<Any?> = when (obj) {
        is JsArray -> ArrayList(obj.list)
        is String -> obj.map { it.toString() }
        null, Undefined -> throw JsThrow(makeError("TypeError", "${toStr(obj)} is not iterable"))
        is JsObject -> {
            val len = obj.props["length"]
            if (len is Double) (0 until len.toInt()).map { obj.props[it.toString()] ?: Undefined } else emptyList()
        }
        else -> throw JsThrow(makeError("TypeError", "not iterable"))
    }

    // ── Kapanış (closure) ──

    inner class Closure(val def: FunctionDef, val scope: Scope) : JsFunction() {
        override val fnName: String get() = def.name ?: ""

        init {
            if (!def.arrow) props["prototype"] = JsObject()
        }

        override fun call(self: Any?, args: List<Any?>): Any? {
            tick()
            if (++depth > 150) {
                depth--
                throw JsThrow(makeError("RangeError", "Maximum call stack size exceeded"))
            }
            try {
                val fs = Scope(scope, true)
                fs.thisValue = if (def.arrow) lookupThis(scope) else self
                if (!def.arrow) {
                    fs.vars["arguments"] = JsArray(ArrayList(args))
                    if (def.name != null && scope.find(def.name).let { it == null || it.vars[def.name] !== this }) {
                        fs.vars[def.name] = this
                    }
                }
                for ((i, prm) in def.params.withIndex()) {
                    fs.vars[prm.name] = if (prm.rest) {
                        JsArray(if (i < args.size) ArrayList(args.subList(i, args.size)) else ArrayList())
                    } else {
                        val a = if (i < args.size) args[i] else Undefined
                        if (a === Undefined && prm.default != null) eval(prm.default, fs) else a
                    }
                }
                if (def.exprBody != null) return eval(def.exprBody, fs)
                hoist(def.body, fs)
                try {
                    for (st in def.body) exec(st, fs)
                } catch (r: ReturnSignal) {
                    return r.value
                }
                return Undefined
            } finally {
                depth--
            }
        }
    }

    // ── Genel nesneler ──

    private fun installGlobals() {
        val g = global.vars
        val window = JsObject(g)
        global.thisValue = window
        g["window"] = window
        g["self"] = window
        g["globalThis"] = window
        g["top"] = window
        g["parent"] = window
        g["frames"] = window
        g["undefined"] = Undefined
        g["NaN"] = Double.NaN
        g["Infinity"] = Double.POSITIVE_INFINITY
        g["eval"] = evalFn
        Builtins.install(this, g)

        val loc = Builtins.locationObject(pageUrl)
        g["location"] = loc
        val document = object : JsObject() {
            override fun missing(key: String): Any? = BlackHole
        }
        document.props["location"] = loc
        document.props["URL"] = pageUrl
        document.props["domain"] = loc.props["hostname"]
        document.props["referrer"] = ""
        document.props["cookie"] = ""
        document.props["readyState"] = "complete"
        document.props["visibilityState"] = "visible"
        g["document"] = document

        val navigator = object : JsObject() {
            override fun missing(key: String): Any? = BlackHole
        }
        navigator.props["userAgent"] = "Mozilla/5.0 (Linux; Android 13; Kitsugi) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        navigator.props["platform"] = "Linux armv8l"
        navigator.props["language"] = "tr-TR"
        navigator.props["webdriver"] = false
        g["navigator"] = navigator

        for (name in listOf("localStorage", "sessionStorage")) {
            val store = JsObject()
            store.props["getItem"] = NativeFunction("getItem") { _, a -> store.props["__" + toStr(a.getOrNull(0))] ?: null }
            store.props["setItem"] = NativeFunction("setItem") { _, a -> store.props["__" + toStr(a.getOrNull(0))] = toStr(a.getOrNull(1)); Undefined }
            store.props["removeItem"] = NativeFunction("removeItem") { _, a -> store.props.remove("__" + toStr(a.getOrNull(0))); Undefined }
            g[name] = store
        }

        // jwplayer taklidi: setup() yapılandırmasını yakalar.
        val playerObj = object : JsObject() {
            override fun missing(key: String): Any? = BlackHole
        }
        playerObj.props["setup"] = NativeFunction("setup") { _, a ->
            capturedSetups.add(a.getOrNull(0))
            playerObj
        }
        val jw = NativeFunction("jwplayer") { _, _ -> playerObj }
        g["jwplayer"] = jw

        for (name in listOf(
            "$", "jQuery", "Hls", "videojs", "Plyr", "Clappr", "gtag", "ga", "fbq", "_paq",
            "MutationObserver", "IntersectionObserver", "ResizeObserver", "XMLHttpRequest", "fetch",
            "Image", "Audio", "Event", "CustomEvent", "addEventListener", "removeEventListener",
            "postMessage", "alert", "confirm", "prompt", "open", "close", "requestAnimationFrame",
            "cancelAnimationFrame", "getComputedStyle", "matchMedia", "screen", "history", "performance",
            "chrome", "cast", "initJWCastSender", "devtoolsDetector", "DisableDevtool"
        )) {
            if (!g.containsKey(name)) g[name] = BlackHole
        }
        g["dataLayer"] = JsArray()
    }

    fun makeError(name: String, message: String): JsObject {
        val e = JsObject()
        e.props["name"] = name
        e.props["message"] = message
        return e
    }

    // ── Dönüşümler ──

    fun toPrimitive(v: Any?): Any? {
        return when (v) {
            is JsArray -> v.list.joinToString(",") { if (it == null || it === Undefined) "" else toStr(it) }
            is JsRegExp -> "/" + v.source + "/" + v.flags
            BlackHole -> ""
            is JsFunction -> "function ${v.fnName}() { [native code] }"
            is JsObject -> {
                for (m in listOf("valueOf", "toString")) {
                    val f = v.props[m]
                    if (f is JsFunction) {
                        val r = f.call(v, emptyList())
                        if (r !is JsObject) return r
                    }
                }
                "[object Object]"
            }
            else -> v
        }
    }

    fun toStr(v: Any?): String = MiniJs.toStr(toPrimitive(v))

    fun toNum(v: Any?): Double = when (v) {
        is Double -> v
        null -> 0.0
        Undefined -> Double.NaN
        is Boolean -> if (v) 1.0 else 0.0
        is String -> MiniJs.strToNum(v)
        is JsObject -> {
            val p = toPrimitive(v)
            if (p is JsObject) Double.NaN else toNum(p)
        }
        else -> Double.NaN
    }

    fun toPropKey(v: Any?): String = when (v) {
        is String -> v
        is Double -> MiniJs.numToStr(v)
        else -> toStr(v)
    }

    fun toInt32(v: Any?): Int = MiniJs.toInt32(toNum(v))

    fun truthy(v: Any?): Boolean = when (v) {
        null, Undefined -> false
        is Boolean -> v
        is Double -> !(v == 0.0 || v.isNaN())
        is String -> v.isNotEmpty()
        else -> true
    }

    fun typeOf(v: Any?): String = when (v) {
        Undefined -> "undefined"
        null -> "object"
        is Boolean -> "boolean"
        is Double -> "number"
        is String -> "string"
        is JsFunction -> "function"
        else -> "object"
    }

    fun strictEq(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> numEq(a, b)
        a is String && b is String -> a == b
        a is Boolean && b is Boolean -> a == b
        else -> a === b
    }

    fun looseEq(a: Any?, b: Any?): Boolean {
        val an = a == null || a === Undefined
        val bn = b == null || b === Undefined
        if (an || bn) return an && bn
        if (a is Double && b is Double) return numEq(a, b)
        if (a is String && b is String) return a == b
        if (a is Boolean && b is Boolean) return a == b
        if (a is Boolean) return looseEq(if (a) 1.0 else 0.0, b)
        if (b is Boolean) return looseEq(a, if (b) 1.0 else 0.0)
        if (a is Double && b is String) return numEq(a, MiniJs.strToNum(b))
        if (a is String && b is Double) return numEq(MiniJs.strToNum(a), b)
        if ((a is String || a is Double) && b is JsObject) return looseEq(a, toPrimitive(b))
        if (a is JsObject && (b is String || b is Double)) return looseEq(toPrimitive(a), b)
        return a === b
    }

    private fun numEq(x: Double, y: Double): Boolean = x == y

    private fun lessThan(a: Any?, b: Any?): Boolean? {
        val pa = toPrimitive(a)
        val pb = toPrimitive(b)
        if (pa is String && pb is String) return pa < pb
        val x = toNum(pa)
        val y = toNum(pb)
        if (x.isNaN() || y.isNaN()) return null
        return x < y
    }

    companion object {
        fun arrayIndex(key: String): Int {
            if (key.isEmpty() || key.length > 9) return -1
            for (c in key) if (c !in '0'..'9') return -1
            if (key.length > 1 && key[0] == '0') return -1
            return key.toInt()
        }

        fun toStr(v: Any?): String = when (v) {
            null -> "null"
            Undefined -> "undefined"
            is String -> v
            is Double -> numToStr(v)
            is Boolean -> v.toString()
            is JsArray -> v.list.joinToString(",") { if (it == null || it === Undefined) "" else toStr(it) }
            is JsRegExp -> "/" + v.source + "/" + v.flags
            BlackHole -> ""
            is JsFunction -> "function ${v.fnName}() { [native code] }"
            is JsObject -> "[object Object]"
            else -> v.toString()
        }

        fun toInt32(d: Double): Int {
            if (d.isNaN() || d.isInfinite()) return 0
            return if (Math.abs(d) < 9.0E18) d.toLong().toInt()
            else BigDecimal(d).toBigInteger().toInt()
        }

        private val NUM_RE = Regex("""[+-]?(\d+\.?\d*([eE][+-]?\d+)?|\.\d+([eE][+-]?\d+)?)""")

        fun strToNum(s: String): Double {
            val t = s.trim()
            if (t.isEmpty()) return 0.0
            if (t.length > 2 && t[0] == '0' && (t[1] == 'x' || t[1] == 'X')) {
                return t.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
            }
            if (t == "Infinity" || t == "+Infinity") return Double.POSITIVE_INFINITY
            if (t == "-Infinity") return Double.NEGATIVE_INFINITY
            if (!NUM_RE.matches(t)) return Double.NaN
            return t.toDoubleOrNull() ?: Double.NaN
        }

        fun numToStr(d: Double): String {
            if (d.isNaN()) return "NaN"
            if (d == Double.POSITIVE_INFINITY) return "Infinity"
            if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
            if (d == 0.0) return "0"
            val abs = Math.abs(d)
            if (d == Math.floor(d) && abs < 1e21) {
                return if (abs < 9.0E18) d.toLong().toString() else BigDecimal(d).toPlainString()
            }
            val repr = d.toString()
            if (abs >= 1e-6 && abs < 1e21) {
                return BigDecimal(repr).stripTrailingZeros().toPlainString()
            }
            val bd = BigDecimal(repr).stripTrailingZeros()
            val unscaled = bd.unscaledValue().abs().toString()
            val exp = unscaled.length - 1 - bd.scale()
            val mant = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
            return (if (d < 0) "-" else "") + mant + "e" + (if (exp >= 0) "+" else "") + exp
        }

        /** JS regex sözdizimini java.util.regex'e uyarlar. */
        fun translateRegex(src: String): String {
            val sb = StringBuilder()
            var i = 0
            var inClass = false
            while (i < src.length) {
                val c = src[i]
                if (c == '\\' && i + 1 < src.length) {
                    val n = src[i + 1]
                    when (n) {
                        'd', 'D', 'w', 'W', 's', 'S', 'b', 'B', 'n', 'r', 't', 'f', 'v', '0',
                        '\\', '/', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|', '^', '$', '-' -> {
                            if (n == '/') sb.append('/') else if (n == 'v') sb.append("\\x0B") else sb.append(c).append(n)
                        }
                        'u' -> {
                            sb.append("\\u"); i += 2; continue
                        }
                        'x', 'c', 'k', 'p', 'P' -> sb.append(c).append(n)
                        in '1'..'9' -> sb.append(c).append(n)
                        else -> sb.append(Pattern.quote(n.toString()))
                    }
                    i += 2
                    continue
                }
                if (inClass) {
                    if (c == ']') inClass = false
                    if (c == '[') { sb.append("\\["); i++; continue }
                    if (c == '&' && i + 1 < src.length && src[i + 1] == '&') { sb.append("&\\&"); i += 2; continue }
                    sb.append(c)
                } else {
                    if (c == '[') {
                        if (src.startsWith("[^]", i)) { sb.append("[\\s\\S]"); i += 3; continue }
                        if (src.startsWith("[]", i)) { sb.append("(?!)"); i += 2; continue }
                        inClass = true
                    }
                    sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }
}

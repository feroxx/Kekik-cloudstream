package com.keyiflerolsun

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.regex.Matcher

/** MiniJs için yerleşik nesneler ve ilkel tür yöntemleri. */
internal object Builtins {

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_INDEX = IntArray(128) { -1 }.also { t -> B64.forEachIndexed { i, c -> t[c.code] = i } }

    fun atob(js: MiniJs, input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') continue
            sb.append(c)
        }
        var str = sb.toString()
        if (str.length % 4 == 0) {
            str = when {
                str.endsWith("==") -> str.substring(0, str.length - 2)
                str.endsWith("=") -> str.substring(0, str.length - 1)
                else -> str
            }
        }
        if (str.length % 4 == 1) throw JsThrow(js.makeError("InvalidCharacterError", "atob: geçersiz uzunluk"))
        val out = StringBuilder(str.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in str) {
            val v = if (c.code < 128) B64_INDEX[c.code] else -1
            if (v < 0) throw JsThrow(js.makeError("InvalidCharacterError", "atob: geçersiz karakter"))
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return out.toString()
    }

    fun btoa(js: MiniJs, input: String): String {
        val out = StringBuilder((input.length + 2) / 3 * 4)
        var i = 0
        while (i < input.length) {
            val b0 = input[i].code
            val b1 = if (i + 1 < input.length) input[i + 1].code else -1
            val b2 = if (i + 2 < input.length) input[i + 2].code else -1
            if (b0 > 255 || b1 > 255 || b2 > 255) throw JsThrow(js.makeError("InvalidCharacterError", "btoa: Latin1 dışı karakter"))
            val n = (b0 shl 16) or ((if (b1 < 0) 0 else b1) shl 8) or (if (b2 < 0) 0 else b2)
            out.append(B64[(n shr 18) and 63])
            out.append(B64[(n shr 12) and 63])
            out.append(if (b1 < 0) '=' else B64[(n shr 6) and 63])
            out.append(if (b2 < 0) '=' else B64[n and 63])
            i += 3
        }
        return out.toString()
    }

    private fun arg(args: List<Any?>, i: Int): Any? = if (i < args.size) args[i] else Undefined

    private fun toInteger(js: MiniJs, v: Any?): Double {
        val d = js.toNum(v)
        if (d.isNaN()) return 0.0
        if (d.isInfinite()) return d
        return if (d < 0) Math.ceil(d) else Math.floor(d)
    }

    private fun relIndex(js: MiniJs, v: Any?, len: Int, def: Int): Int {
        if (v === Undefined) return def
        val n = toInteger(js, v)
        return if (n < 0) maxOf(len + n, 0.0).toInt() else minOf(n, len.toDouble()).toInt()
    }

    // ── String ──

    private val STRING_METHODS: Map<String, (MiniJs, String, List<Any?>) -> Any?> = mapOf(
        "charAt" to { js, s, a ->
            val i = toInteger(js, arg(a, 0))
            if (i >= 0 && i < s.length) s[i.toInt()].toString() else ""
        },
        "charCodeAt" to { js, s, a ->
            val i = toInteger(js, arg(a, 0))
            if (i >= 0 && i < s.length) s[i.toInt()].code.toDouble() else Double.NaN
        },
        "codePointAt" to { js, s, a ->
            val i = toInteger(js, arg(a, 0))
            if (i >= 0 && i < s.length) s.codePointAt(i.toInt()).toDouble() else Undefined
        },
        "at" to { js, s, a ->
            var i = toInteger(js, arg(a, 0)).toInt()
            if (i < 0) i += s.length
            if (i >= 0 && i < s.length) s[i].toString() else Undefined
        },
        "indexOf" to { js, s, a ->
            val from = minOf(maxOf(toInteger(js, arg(a, 1)), 0.0), s.length.toDouble()).toInt()
            s.indexOf(js.toStr(arg(a, 0)), from).toDouble()
        },
        "lastIndexOf" to { js, s, a ->
            val search = js.toStr(arg(a, 0))
            val fromArg = arg(a, 1)
            val from = if (fromArg === Undefined || js.toNum(fromArg).isNaN()) s.length else toInteger(js, fromArg).toInt()
            s.lastIndexOf(search, from).toDouble()
        },
        "includes" to { js, s, a -> s.indexOf(js.toStr(arg(a, 0)), maxOf(toInteger(js, arg(a, 1)).toInt(), 0)) >= 0 },
        "startsWith" to { js, s, a -> s.startsWith(js.toStr(arg(a, 0)), maxOf(toInteger(js, arg(a, 1)).toInt(), 0)) },
        "endsWith" to { js, s, a ->
            val end = if (arg(a, 1) === Undefined) s.length else minOf(maxOf(toInteger(js, arg(a, 1)).toInt(), 0), s.length)
            s.substring(0, end).endsWith(js.toStr(arg(a, 0)))
        },
        "slice" to { js, s, a ->
            val st = relIndex(js, arg(a, 0), s.length, 0)
            val en = relIndex(js, arg(a, 1), s.length, s.length)
            if (st < en) s.substring(st, en) else ""
        },
        "substring" to { js, s, a ->
            var st = minOf(maxOf(toInteger(js, arg(a, 0)), 0.0), s.length.toDouble()).toInt()
            var en = if (arg(a, 1) === Undefined) s.length else minOf(maxOf(toInteger(js, arg(a, 1)), 0.0), s.length.toDouble()).toInt()
            if (st > en) { val t = st; st = en; en = t }
            s.substring(st, en)
        },
        "substr" to { js, s, a ->
            var st = toInteger(js, arg(a, 0)).toInt()
            if (st < 0) st = maxOf(s.length + st, 0)
            st = minOf(st, s.length)
            val len = if (arg(a, 1) === Undefined) s.length - st else minOf(maxOf(toInteger(js, arg(a, 1)).toInt(), 0), s.length - st)
            if (len <= 0) "" else s.substring(st, st + len)
        },
        "split" to { js, s, a -> split(js, s, arg(a, 0), arg(a, 1)) },
        "replace" to { js, s, a -> replace(js, s, arg(a, 0), arg(a, 1), false) },
        "replaceAll" to { js, s, a -> replace(js, s, arg(a, 0), arg(a, 1), true) },
        "toUpperCase" to { _, s, _ -> s.uppercase() },
        "toLowerCase" to { _, s, _ -> s.lowercase() },
        "toLocaleUpperCase" to { _, s, _ -> s.uppercase() },
        "toLocaleLowerCase" to { _, s, _ -> s.lowercase() },
        "trim" to { _, s, _ -> s.trim() },
        "trimStart" to { _, s, _ -> s.trimStart() },
        "trimEnd" to { _, s, _ -> s.trimEnd() },
        "trimLeft" to { _, s, _ -> s.trimStart() },
        "trimRight" to { _, s, _ -> s.trimEnd() },
        "concat" to { js, s, a -> s + a.joinToString("") { js.toStr(it) } },
        "repeat" to { js, s, a ->
            val n = toInteger(js, arg(a, 0)).toInt()
            if (n < 0 || n.toLong() * s.length > 10_000_000L) throw JsThrow(js.makeError("RangeError", "Invalid count value"))
            s.repeat(n)
        },
        "padStart" to { js, s, a -> pad(js, s, a, true) },
        "padEnd" to { js, s, a -> pad(js, s, a, false) },
        "match" to { js, s, a -> match(js, s, arg(a, 0)) },
        "search" to { js, s, a ->
            val re = toRegExp(js, arg(a, 0))
            val m = re.pattern.matcher(s)
            if (m.find()) m.start().toDouble() else -1.0
        },
        "localeCompare" to { js, s, a -> s.compareTo(js.toStr(arg(a, 0))).coerceIn(-1, 1).toDouble() },
        "normalize" to { _, s, _ -> s },
        "toString" to { _, s, _ -> s },
        "valueOf" to { _, s, _ -> s }
    )

    private fun pad(js: MiniJs, s: String, a: List<Any?>, start: Boolean): String {
        val target = toInteger(js, arg(a, 0)).toInt()
        val filler = if (arg(a, 1) === Undefined) " " else js.toStr(arg(a, 1))
        if (target <= s.length || filler.isEmpty()) return s
        val need = target - s.length
        val sb = StringBuilder()
        while (sb.length < need) sb.append(filler)
        sb.setLength(need)
        return if (start) sb.toString() + s else s + sb.toString()
    }

    private fun toRegExp(js: MiniJs, v: Any?): JsRegExp =
        if (v is JsRegExp) v else JsRegExp(if (v === Undefined) "(?:)" else java.util.regex.Pattern.quote(js.toStr(v)), "")

    private fun execAt(js: MiniJs, re: JsRegExp, s: String, m: Matcher): JsArray {
        val arr = JsArray()
        arr.list.add(m.group())
        for (g in 1..m.groupCount()) arr.list.add(m.group(g) ?: Undefined)
        arr.props["index"] = m.start().toDouble()
        arr.props["input"] = s
        return arr
    }

    private fun match(js: MiniJs, s: String, pattern: Any?): Any? {
        val re = toRegExp(js, pattern)
        val m = re.pattern.matcher(s)
        if (!re.global) {
            return if (m.find()) execAt(js, re, s, m) else null
        }
        val out = JsArray()
        while (m.find()) out.list.add(m.group())
        re.lastIndex = 0
        return if (out.list.isEmpty()) null else out
    }

    fun split(js: MiniJs, s: String, sep: Any?, limit: Any?): JsArray {
        val lim = if (limit === Undefined) Int.MAX_VALUE else (js.toInt32(limit).toLong() and 0xFFFFFFFFL).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = ArrayList<Any?>()
        if (lim == 0) return JsArray(out)
        when {
            sep === Undefined -> out.add(s)
            sep is JsRegExp -> {
                val size = s.length
                val m = sep.pattern.matcher(s)
                if (size == 0) {
                    if (!m.matches()) out.add(s)
                    return JsArray(out)
                }
                var p = 0
                var q = 0
                while (q < size) {
                    if (!m.find(q)) break
                    val st = m.start()
                    val e = m.end()
                    if (st >= size) break
                    if (e == p) { q = st + 1; continue }
                    out.add(s.substring(p, st))
                    if (out.size >= lim) return JsArray(out)
                    for (g in 1..m.groupCount()) {
                        out.add(m.group(g) ?: Undefined)
                        if (out.size >= lim) return JsArray(out)
                    }
                    p = e
                    q = p
                }
                out.add(s.substring(p))
            }
            else -> {
                val sp = js.toStr(sep)
                if (sp.isEmpty()) {
                    for (c in s) out.add(c.toString())
                } else {
                    var idx = 0
                    while (true) {
                        val f = s.indexOf(sp, idx)
                        if (f < 0) { out.add(s.substring(idx)); break }
                        out.add(s.substring(idx, f))
                        idx = f + sp.length
                    }
                }
            }
        }
        while (out.size > lim) out.removeAt(out.size - 1)
        return JsArray(out)
    }

    private fun expandReplacement(r: String, s: String, matched: String, start: Int, groups: List<String?>): String {
        if (r.indexOf('$') < 0) return r
        val sb = StringBuilder()
        var i = 0
        while (i < r.length) {
            val c = r[i]
            if (c == '$' && i + 1 < r.length) {
                val n = r[i + 1]
                when {
                    n == '$' -> { sb.append('$'); i += 2; continue }
                    n == '&' -> { sb.append(matched); i += 2; continue }
                    n == '`' -> { sb.append(s, 0, start); i += 2; continue }
                    n == '\'' -> { sb.append(s.substring(minOf(start + matched.length, s.length))); i += 2; continue }
                    n in '0'..'9' -> {
                        var num = n - '0'
                        var len = 2
                        if (i + 2 < r.length && r[i + 2] in '0'..'9') {
                            val two = num * 10 + (r[i + 2] - '0')
                            if (two in 1..groups.size) { num = two; len = 3 }
                        }
                        if (num in 1..groups.size) {
                            sb.append(groups[num - 1] ?: "")
                            i += len
                            continue
                        }
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun replace(js: MiniJs, s: String, pattern: Any?, repl: Any?, all: Boolean): String {
        val sb = StringBuilder()
        if (pattern is JsRegExp) {
            val m = pattern.pattern.matcher(s)
            var last = 0
            while (m.find()) {
                sb.append(s, last, m.start())
                val groups = (1..m.groupCount()).map { m.group(it) }
                if (repl is JsFunction) {
                    val args = ArrayList<Any?>()
                    args.add(m.group())
                    groups.forEach { args.add(it ?: Undefined) }
                    args.add(m.start().toDouble())
                    args.add(s)
                    sb.append(js.toStr(repl.call(Undefined, args)))
                } else {
                    sb.append(expandReplacement(js.toStr(repl), s, m.group(), m.start(), groups))
                }
                last = m.end()
                if (!pattern.global && !all) break
            }
            sb.append(s, last, s.length)
            if (pattern.global) pattern.lastIndex = 0
            return sb.toString()
        }
        val needle = js.toStr(pattern)
        var from = 0
        var last = 0
        while (true) {
            val f = s.indexOf(needle, from)
            if (f < 0) break
            sb.append(s, last, f)
            if (repl is JsFunction) {
                sb.append(js.toStr(repl.call(Undefined, listOf(needle, f.toDouble(), s))))
            } else {
                sb.append(expandReplacement(js.toStr(repl), s, needle, f, emptyList()))
            }
            last = f + needle.length
            from = if (needle.isEmpty()) f + 1 else last
            if (!all || from > s.length) break
        }
        sb.append(s, minOf(last, s.length), s.length)
        return sb.toString()
    }

    // ── Array ──

    private fun callback(a: List<Any?>): JsFunction =
        (arg(a, 0) as? JsFunction) ?: throw JsThrow(JsObject().apply { props["name"] = "TypeError"; props["message"] = "callback is not a function" })

    private val ARRAY_METHODS: Map<String, (MiniJs, JsArray, List<Any?>) -> Any?> = mapOf(
        "join" to { js, arr, a ->
            val sep = if (arg(a, 0) === Undefined) "," else js.toStr(arg(a, 0))
            arr.list.joinToString(sep) { if (it == null || it === Undefined) "" else js.toStr(it) }
        },
        "reverse" to { _, arr, _ -> arr.list.reverse(); arr },
        "push" to { _, arr, a -> arr.list.addAll(a); arr.list.size.toDouble() },
        "pop" to { _, arr, _ -> if (arr.list.isEmpty()) Undefined else arr.list.removeAt(arr.list.size - 1) },
        "shift" to { _, arr, _ -> if (arr.list.isEmpty()) Undefined else arr.list.removeAt(0) },
        "unshift" to { _, arr, a -> arr.list.addAll(0, a); arr.list.size.toDouble() },
        "splice" to { js, arr, a ->
            val len = arr.list.size
            val start = relIndex(js, arg(a, 0), len, 0)
            val delCount = when {
                a.isEmpty() -> 0
                a.size == 1 -> len - start
                else -> minOf(maxOf(toInteger(js, a[1]), 0.0), (len - start).toDouble()).toInt()
            }
            val removed = ArrayList<Any?>()
            repeat(delCount) { removed.add(arr.list.removeAt(start)) }
            if (a.size > 2) arr.list.addAll(start, a.subList(2, a.size))
            JsArray(removed)
        },
        "slice" to { js, arr, a ->
            val len = arr.list.size
            val st = relIndex(js, arg(a, 0), len, 0)
            val en = relIndex(js, arg(a, 1), len, len)
            JsArray(if (st < en) ArrayList(arr.list.subList(st, en)) else ArrayList())
        },
        "concat" to { _, arr, a ->
            val out = ArrayList(arr.list)
            for (x in a) if (x is JsArray) out.addAll(x.list) else out.add(x)
            JsArray(out)
        },
        "indexOf" to { js, arr, a ->
            val x = arg(a, 0)
            var r = -1.0
            for (i in maxOf(toInteger(js, arg(a, 1)).toInt(), 0) until arr.list.size) {
                if (js.strictEq(arr.list[i], x)) { r = i.toDouble(); break }
            }
            r
        },
        "lastIndexOf" to { js, arr, a ->
            val x = arg(a, 0)
            var r = -1.0
            for (i in arr.list.indices.reversed()) if (js.strictEq(arr.list[i], x)) { r = i.toDouble(); break }
            r
        },
        "includes" to { js, arr, a ->
            val x = arg(a, 0)
            arr.list.any { js.strictEq(it, x) || (it is Double && x is Double && it.isNaN() && x.isNaN()) }
        },
        "map" to { _, arr, a ->
            val f = callback(a)
            JsArray(ArrayList(arr.list.mapIndexed { i, v -> f.call(arg(a, 1), listOf(v, i.toDouble(), arr)) }))
        },
        "forEach" to { _, arr, a ->
            val f = callback(a)
            for (i in 0 until arr.list.size) f.call(arg(a, 1), listOf(arr.list[i], i.toDouble(), arr))
            Undefined
        },
        "filter" to { js, arr, a ->
            val f = callback(a)
            JsArray(ArrayList(arr.list.filterIndexed { i, v -> js.truthy(f.call(arg(a, 1), listOf(v, i.toDouble(), arr))) }))
        },
        "some" to { js, arr, a ->
            val f = callback(a)
            arr.list.withIndex().any { (i, v) -> js.truthy(f.call(arg(a, 1), listOf(v, i.toDouble(), arr))) }
        },
        "every" to { js, arr, a ->
            val f = callback(a)
            arr.list.withIndex().all { (i, v) -> js.truthy(f.call(arg(a, 1), listOf(v, i.toDouble(), arr))) }
        },
        "find" to { js, arr, a ->
            val f = callback(a)
            arr.list.withIndex().firstOrNull { (i, v) -> js.truthy(f.call(arg(a, 1), listOf(v, i.toDouble(), arr))) }?.value ?: Undefined
        },
        "findIndex" to { js, arr, a ->
            val f = callback(a)
            arr.list.withIndex().indexOfFirst { (i, v) -> js.truthy(f.call(arg(a, 1), listOf(v, i.toDouble(), arr))) }.toDouble()
        },
        "reduce" to { _, arr, a ->
            val f = callback(a)
            var i = 0
            var acc: Any? = if (a.size > 1) a[1] else {
                if (arr.list.isEmpty()) throw JsThrow(JsObject().apply { props["name"] = "TypeError"; props["message"] = "Reduce of empty array with no initial value" })
                i = 1
                arr.list[0]
            }
            while (i < arr.list.size) { acc = f.call(Undefined, listOf(acc, arr.list[i], i.toDouble(), arr)); i++ }
            acc
        },
        "reduceRight" to { _, arr, a ->
            val f = callback(a)
            var i = arr.list.size - 1
            var acc: Any? = if (a.size > 1) a[1] else {
                if (arr.list.isEmpty()) throw JsThrow(JsObject().apply { props["name"] = "TypeError"; props["message"] = "Reduce of empty array with no initial value" })
                i--
                arr.list[i + 1]
            }
            while (i >= 0) { acc = f.call(Undefined, listOf(acc, arr.list[i], i.toDouble(), arr)); i-- }
            acc
        },
        "sort" to { js, arr, a ->
            val cmp = arg(a, 0)
            val comparator: Comparator<Any?> = if (cmp is JsFunction) {
                Comparator { x, y ->
                    when {
                        x === Undefined && y === Undefined -> 0
                        x === Undefined -> 1
                        y === Undefined -> -1
                        else -> {
                            val r = js.toNum(cmp.call(Undefined, listOf(x, y)))
                            if (r < 0) -1 else if (r > 0) 1 else 0
                        }
                    }
                }
            } else {
                Comparator { x, y ->
                    when {
                        x === Undefined && y === Undefined -> 0
                        x === Undefined -> 1
                        y === Undefined -> -1
                        else -> js.toStr(x).compareTo(js.toStr(y))
                    }
                }
            }
            val sorted = arr.list.sortedWith(comparator)
            arr.list.clear()
            arr.list.addAll(sorted)
            arr
        },
        "fill" to { js, arr, a ->
            val len = arr.list.size
            val st = relIndex(js, arg(a, 1), len, 0)
            val en = relIndex(js, arg(a, 2), len, len)
            for (i in st until en) arr.list[i] = arg(a, 0)
            arr
        },
        "flat" to { _, arr, _ ->
            val out = ArrayList<Any?>()
            for (x in arr.list) if (x is JsArray) out.addAll(x.list) else out.add(x)
            JsArray(out)
        },
        "at" to { js, arr, a ->
            var i = toInteger(js, arg(a, 0)).toInt()
            if (i < 0) i += arr.list.size
            if (i >= 0 && i < arr.list.size) arr.list[i] else Undefined
        },
        "keys" to { _, arr, _ -> JsArray(ArrayList(arr.list.indices.map { it.toDouble() })) },
        "values" to { _, arr, _ -> JsArray(ArrayList(arr.list)) },
        "entries" to { _, arr, _ -> JsArray(ArrayList(arr.list.mapIndexed { i, v -> JsArray(arrayListOf(i.toDouble(), v)) })) },
        "toString" to { js, arr, _ -> js.toStr(arr) }
    )

    // ── Number ──

    fun numToRadix(d: Double, radix: Int): String {
        if (radix == 10 || d.isNaN() || d.isInfinite()) return MiniJs.numToStr(d)
        val neg = d < 0
        val x = Math.abs(d)
        val intPart = Math.floor(x)
        var frac = x - intPart
        val sb = StringBuilder(BigDecimal(intPart).toBigInteger().toString(radix))
        if (frac > 0) {
            sb.append('.')
            var count = 0
            while (frac > 0 && count < 20) {
                frac *= radix
                val digit = Math.floor(frac).toInt()
                sb.append(Character.forDigit(digit, radix))
                frac -= digit
                count++
            }
        }
        return (if (neg) "-" else "") + sb.toString()
    }

    private val NUMBER_METHODS: Map<String, (MiniJs, Double, List<Any?>) -> Any?> = mapOf(
        "toString" to { js, d, a ->
            val r = if (arg(a, 0) === Undefined) 10 else toInteger(js, arg(a, 0)).toInt()
            if (r < 2 || r > 36) throw JsThrow(js.makeError("RangeError", "toString() radix must be between 2 and 36"))
            numToRadix(d, r)
        },
        "toFixed" to { js, d, a ->
            val digits = toInteger(js, arg(a, 0)).toInt().coerceIn(0, 100)
            if (d.isNaN() || d.isInfinite() || Math.abs(d) >= 1e21) MiniJs.numToStr(d)
            else BigDecimal(d).setScale(digits, RoundingMode.HALF_UP).toPlainString()
        },
        "valueOf" to { _, d, _ -> d },
        "toLocaleString" to { _, d, _ -> MiniJs.numToStr(d) }
    )

    // ── RegExp ──

    private val REGEXP_METHODS: Map<String, (MiniJs, JsRegExp, List<Any?>) -> Any?> = mapOf(
        "test" to { js, re, a ->
            val s = js.toStr(arg(a, 0))
            val m = re.pattern.matcher(s)
            if (re.global) {
                val found = re.lastIndex <= s.length && m.find(re.lastIndex)
                re.lastIndex = if (found) m.end() else 0
                found
            } else {
                m.find()
            }
        },
        "exec" to { js, re, a ->
            val s = js.toStr(arg(a, 0))
            val m = re.pattern.matcher(s)
            val start = if (re.global) re.lastIndex else 0
            if (start <= s.length && m.find(start)) {
                if (re.global) re.lastIndex = m.end()
                execAt(js, re, s, m)
            } else {
                if (re.global) re.lastIndex = 0
                null
            }
        },
        "toString" to { _, re, _ -> "/" + re.source + "/" + re.flags }
    )

    fun directMethod(js: MiniJs, obj: Any?, key: String): ((List<Any?>) -> Any?)? = when (obj) {
        is String -> STRING_METHODS[key]?.let { m -> { a: List<Any?> -> m(js, obj, a) } }
        is JsArray -> if (obj.props.containsKey(key)) null else ARRAY_METHODS[key]?.let { m -> { a: List<Any?> -> m(js, obj, a) } }
        is Double -> NUMBER_METHODS[key]?.let { m -> { a: List<Any?> -> m(js, obj, a) } }
        is JsRegExp -> if (obj.props.containsKey(key)) null else REGEXP_METHODS[key]?.let { m -> { a: List<Any?> -> m(js, obj, a) } }
        else -> null
    }

    fun stringProp(js: MiniJs, s: String, key: String): Any? {
        if (key == "length") return s.length.toDouble()
        val idx = MiniJs.arrayIndex(key)
        if (idx >= 0) return if (idx < s.length) s[idx].toString() else Undefined
        val m = STRING_METHODS[key] ?: return Undefined
        return NativeFunction(key) { _, a -> m(js, s, a) }
    }

    fun numberProp(js: MiniJs, d: Double, key: String): Any? {
        val m = NUMBER_METHODS[key] ?: return Undefined
        return NativeFunction(key) { _, a -> m(js, d, a) }
    }

    fun arrayProp(js: MiniJs, arr: JsArray, key: String): Any? {
        if (key == "length") return arr.list.size.toDouble()
        val idx = MiniJs.arrayIndex(key)
        if (idx >= 0) return if (idx < arr.list.size) arr.list[idx] else Undefined
        if (arr.props.containsKey(key)) return arr.props[key]
        val m = ARRAY_METHODS[key] ?: return Undefined
        return NativeFunction(key) { _, a -> m(js, arr, a) }
    }

    fun regexpProp(js: MiniJs, re: JsRegExp, key: String): Any? {
        return when (key) {
            "source" -> re.source
            "flags" -> re.flags
            "global" -> re.global
            "ignoreCase" -> re.flags.contains('i')
            "multiline" -> re.flags.contains('m')
            "lastIndex" -> re.lastIndex.toDouble()
            else -> {
                if (re.props.containsKey(key)) return re.props[key]
                val m = REGEXP_METHODS[key] ?: return Undefined
                NativeFunction(key) { _, a -> m(js, re, a) }
            }
        }
    }

    fun functionProp(js: MiniJs, fn: JsFunction, key: String): Any? {
        if (fn === BlackHole) return BlackHole
        return when (key) {
            "call" -> NativeFunction("call") { _, a -> fn.call(arg(a, 0), if (a.size > 1) a.subList(1, a.size) else emptyList()) }
            "apply" -> NativeFunction("apply") { _, a ->
                val list = (arg(a, 1) as? JsArray)?.list ?: emptyList<Any?>()
                fn.call(arg(a, 0), ArrayList(list))
            }
            "bind" -> NativeFunction("bind") { _, a ->
                val boundThis = arg(a, 0)
                val boundArgs = if (a.size > 1) ArrayList(a.subList(1, a.size)) else ArrayList()
                NativeFunction(fn.fnName) { _, extra -> fn.call(boundThis, boundArgs + extra) }
            }
            "name" -> fn.fnName
            "length" -> 0.0
            "toString" -> NativeFunction("toString") { _, _ -> "function ${fn.fnName}() { [native code] }" }
            else -> objectProp(js, fn, key)
        }
    }

    fun objectProp(js: MiniJs, o: JsObject, key: String): Any? {
        return when (key) {
            "hasOwnProperty" -> NativeFunction("hasOwnProperty") { self, a ->
                val k = js.toPropKey(arg(a, 0))
                when (self) {
                    is JsArray -> MiniJs.arrayIndex(k) in 0 until self.list.size || self.props.containsKey(k)
                    is JsObject -> self.props.containsKey(k)
                    else -> false
                }
            }
            "toString" -> NativeFunction("toString") { _, _ -> "[object Object]" }
            "valueOf" -> NativeFunction("valueOf") { self, _ -> self }
            else -> o.missing(key)
        }
    }

    // ── Genel nesneler ──

    fun locationObject(pageUrl: String): JsObject {
        val uri = try { java.net.URI(pageUrl) } catch (e: Exception) { null }
        val host = uri?.host ?: "localhost"
        val scheme = uri?.scheme ?: "https"
        val port = uri?.port ?: -1
        val hostPort = if (port > 0) "$host:$port" else host
        val o = JsObject()
        o.props["href"] = pageUrl
        o.props["hostname"] = host
        o.props["host"] = hostPort
        o.props["protocol"] = "$scheme:"
        o.props["origin"] = "$scheme://$hostPort"
        o.props["pathname"] = uri?.rawPath?.ifEmpty { "/" } ?: "/"
        o.props["search"] = uri?.rawQuery?.let { "?$it" } ?: ""
        o.props["hash"] = uri?.rawFragment?.let { "#$it" } ?: ""
        o.props["port"] = if (port > 0) port.toString() else ""
        o.props["toString"] = NativeFunction("toString") { _, _ -> pageUrl }
        for (m in listOf("reload", "replace", "assign")) o.props[m] = NativeFunction(m) { _, _ -> Undefined }
        return o
    }

    private fun parseIntJs(js: MiniJs, sArg: Any?, radixArg: Any?): Double {
        var str = js.toStr(sArg).trim()
        var sign = 1.0
        if (str.startsWith("-")) { sign = -1.0; str = str.substring(1) } else if (str.startsWith("+")) str = str.substring(1)
        var r = js.toInt32(radixArg)
        var stripPrefix = true
        if (r != 0) {
            if (r < 2 || r > 36) return Double.NaN
            if (r != 16) stripPrefix = false
        } else {
            r = 10
        }
        if (stripPrefix && (str.startsWith("0x") || str.startsWith("0X"))) { str = str.substring(2); r = 16 }
        var end = 0
        while (end < str.length) {
            val c = str[end]
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 10
                else -> 99
            }
            if (d >= r) break
            end++
        }
        if (end == 0) return Double.NaN
        val digits = str.substring(0, end)
        return sign * (digits.toLongOrNull(r)?.toDouble() ?: BigInteger(digits, r).toDouble())
    }

    private val FLOAT_PREFIX = Regex("""^[+-]?(Infinity|\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?)""")

    private fun encodeUri(s: String, keep: String): String {
        val sb = StringBuilder()
        val bytes = s.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || keep.indexOf(c) >= 0) sb.append(c)
            else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun decodeUri(js: MiniJs, s: String): String = try {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        throw JsThrow(js.makeError("URIError", "URI malformed"))
    }

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 5 < s.length + 0 && s[i + 1] == 'u') {
                    val v = s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)
                    if (v != null && i + 6 <= s.length) { sb.append(v.toChar()); i += 6; continue }
                }
                if (i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { sb.append(v.toChar()); i += 3; continue }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun escapeJs(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || "@*_+-./".indexOf(c) >= 0 -> sb.append(c)
                c.code < 256 -> sb.append('%').append(String.format("%02X", c.code))
                else -> sb.append("%u").append(String.format("%04X", c.code))
            }
        }
        return sb.toString()
    }

    private fun jsonStringify(js: MiniJs, v: Any?, depth: Int = 0): String? {
        if (depth > 50) return "null"
        return when (v) {
            null -> "null"
            Undefined -> null
            is Boolean -> v.toString()
            is Double -> if (v.isNaN() || v.isInfinite()) "null" else MiniJs.numToStr(v)
            is String -> quoteJson(v)
            is JsFunction -> null
            is JsArray -> v.list.joinToString(",", "[", "]") { jsonStringify(js, it, depth + 1) ?: "null" }
            is JsObject -> v.props.entries.mapNotNull { (k, x) -> jsonStringify(js, x, depth + 1)?.let { quoteJson(k) + ":" + it } }
                .joinToString(",", "{", "}")
            else -> null
        }
    }

    private fun quoteJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun install(js: MiniJs, g: MutableMap<String, Any?>) {
        g["atob"] = NativeFunction("atob") { _, a -> atob(js, js.toStr(arg(a, 0))) }
        g["btoa"] = NativeFunction("btoa") { _, a -> btoa(js, js.toStr(arg(a, 0))) }

        val stringFn = NativeFunction("String") { _, a -> if (a.isEmpty()) "" else js.toStr(a[0]) }
        stringFn.props["fromCharCode"] = NativeFunction("fromCharCode") { _, a ->
            val sb = StringBuilder(a.size)
            for (x in a) sb.append((js.toInt32(x) and 0xFFFF).toChar())
            sb.toString()
        }
        stringFn.props["fromCodePoint"] = NativeFunction("fromCodePoint") { _, a ->
            val sb = StringBuilder()
            for (x in a) sb.appendCodePoint(js.toNum(x).toInt())
            sb.toString()
        }
        val stringProto = JsObject()
        for (name in STRING_METHODS.keys) {
            val m = STRING_METHODS.getValue(name)
            stringProto.props[name] = NativeFunction(name) { self, a -> m(js, js.toStr(self), a) }
        }
        stringFn.props["prototype"] = stringProto
        stringFn.props["__ctor"] = { a: List<Any?> -> if (a.isEmpty()) "" else js.toStr(a[0]) }
        g["String"] = stringFn

        val numberFn = NativeFunction("Number") { _, a -> if (a.isEmpty()) 0.0 else js.toNum(a[0]) }
        numberFn.props["isInteger"] = NativeFunction("isInteger") { _, a -> val v = arg(a, 0); v is Double && !v.isInfinite() && v == Math.floor(v) }
        numberFn.props["isNaN"] = NativeFunction("isNaN") { _, a -> val v = arg(a, 0); v is Double && v.isNaN() }
        numberFn.props["isFinite"] = NativeFunction("isFinite") { _, a -> val v = arg(a, 0); v is Double && !v.isNaN() && !v.isInfinite() }
        numberFn.props["parseInt"] = NativeFunction("parseInt") { _, a -> parseIntJs(js, arg(a, 0), arg(a, 1)) }
        numberFn.props["parseFloat"] = NativeFunction("parseFloat") { _, a ->
            val m = FLOAT_PREFIX.find(js.toStr(arg(a, 0)).trim())
            if (m == null) Double.NaN else if (m.value.endsWith("Infinity")) (if (m.value.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY) else m.value.toDouble()
        }
        numberFn.props["MAX_SAFE_INTEGER"] = 9007199254740991.0
        numberFn.props["MIN_SAFE_INTEGER"] = -9007199254740991.0
        numberFn.props["EPSILON"] = Math.ulp(1.0)
        numberFn.props["__ctor"] = { a: List<Any?> -> if (a.isEmpty()) 0.0 else js.toNum(a[0]) }
        g["Number"] = numberFn
        g["parseInt"] = numberFn.props["parseInt"]
        g["parseFloat"] = numberFn.props["parseFloat"]
        g["isNaN"] = NativeFunction("isNaN") { _, a -> js.toNum(arg(a, 0)).isNaN() }
        g["isFinite"] = NativeFunction("isFinite") { _, a -> val d = js.toNum(arg(a, 0)); !d.isNaN() && !d.isInfinite() }

        g["Boolean"] = NativeFunction("Boolean") { _, a -> js.truthy(arg(a, 0)) }

        val arrayFn = NativeFunction("Array") { _, a -> makeArray(js, a) }
        arrayFn.props["isArray"] = NativeFunction("isArray") { _, a -> arg(a, 0) is JsArray }
        arrayFn.props["from"] = NativeFunction("from") { _, a ->
            val src = arg(a, 0)
            val f = arg(a, 1) as? JsFunction
            val items = when (src) {
                is JsArray, is String -> js.iterate(src)
                is JsObject -> {
                    val len = js.toNum(src.props["length"] ?: 0.0)
                    if (len.isNaN()) emptyList() else (0 until len.toInt()).map { src.props[it.toString()] ?: Undefined }
                }
                else -> emptyList()
            }
            JsArray(ArrayList(if (f != null) items.mapIndexed { i, v -> f.call(Undefined, listOf(v, i.toDouble())) } else items))
        }
        arrayFn.props["of"] = NativeFunction("of") { _, a -> JsArray(ArrayList(a)) }
        val arrayProto = JsObject()
        for (name in ARRAY_METHODS.keys) {
            val m = ARRAY_METHODS.getValue(name)
            arrayProto.props[name] = NativeFunction(name) { self, a ->
                val arr = self as? JsArray ?: JsArray(ArrayList(js.iterate(self)))
                m(js, arr, a)
            }
        }
        arrayFn.props["prototype"] = arrayProto
        arrayFn.props["__ctor"] = { a: List<Any?> -> makeArray(js, a) }
        g["Array"] = arrayFn

        val objectFn = NativeFunction("Object") { _, a -> val v = arg(a, 0); if (v is JsObject) v else JsObject() }
        objectFn.props["keys"] = NativeFunction("keys") { _, a -> JsArray(ArrayList(js.keysOf(arg(a, 0)))) }
        objectFn.props["values"] = NativeFunction("values") { _, a ->
            when (val o = arg(a, 0)) {
                is JsArray -> JsArray(ArrayList(o.list))
                is JsObject -> JsArray(ArrayList(o.props.values))
                else -> JsArray()
            }
        }
        objectFn.props["entries"] = NativeFunction("entries") { _, a ->
            val o = arg(a, 0)
            JsArray(ArrayList(if (o is JsObject && o !is JsArray) o.props.entries.map { JsArray(arrayListOf(it.key, it.value)) } else emptyList()))
        }
        objectFn.props["assign"] = NativeFunction("assign") { _, a ->
            val target = arg(a, 0)
            if (target is JsObject) for (src in a.drop(1)) if (src is JsObject) target.props.putAll(src.props)
            target
        }
        objectFn.props["freeze"] = NativeFunction("freeze") { _, a -> arg(a, 0) }
        objectFn.props["seal"] = NativeFunction("seal") { _, a -> arg(a, 0) }
        objectFn.props["create"] = NativeFunction("create") { _, a ->
            val o = JsObject()
            val proto = arg(a, 0)
            if (proto is JsObject) o.props.putAll(proto.props)
            o
        }
        objectFn.props["defineProperty"] = NativeFunction("defineProperty") { _, a ->
            val o = arg(a, 0)
            val desc = arg(a, 2)
            if (o is JsObject && desc is JsObject) {
                val key = js.toPropKey(arg(a, 1))
                val getter = desc.props["get"]
                o.props[key] = if (desc.props.containsKey("value")) desc.props["value"]
                else if (getter is JsFunction) getter.call(o, emptyList()) else Undefined
            }
            o
        }
        objectFn.props["getPrototypeOf"] = NativeFunction("getPrototypeOf") { _, _ -> JsObject() }
        objectFn.props["prototype"] = JsObject()
        objectFn.props["__ctor"] = { a: List<Any?> -> val v = arg(a, 0); if (v is JsObject) v else JsObject() }
        g["Object"] = objectFn

        val rnd = java.util.Random(20261025L)
        val math = JsObject()
        fun m1(name: String, f: (Double) -> Double) {
            math.props[name] = NativeFunction(name) { _, a -> f(js.toNum(arg(a, 0))) }
        }
        m1("floor") { Math.floor(it) }
        m1("ceil") { Math.ceil(it) }
        m1("round") { if (it.isNaN() || it.isInfinite()) it else Math.floor(it + 0.5) }
        m1("abs") { Math.abs(it) }
        m1("sqrt") { Math.sqrt(it) }
        m1("cbrt") { Math.cbrt(it) }
        m1("trunc") { if (it < 0) Math.ceil(it) else Math.floor(it) }
        m1("sign") { Math.signum(it) }
        m1("log") { Math.log(it) }
        m1("log2") { Math.log(it) / Math.log(2.0) }
        m1("log10") { Math.log10(it) }
        m1("exp") { Math.exp(it) }
        m1("sin") { Math.sin(it) }
        m1("cos") { Math.cos(it) }
        m1("tan") { Math.tan(it) }
        m1("atan") { Math.atan(it) }
        math.props["atan2"] = NativeFunction("atan2") { _, a -> Math.atan2(js.toNum(arg(a, 0)), js.toNum(arg(a, 1))) }
        math.props["pow"] = NativeFunction("pow") { _, a -> Math.pow(js.toNum(arg(a, 0)), js.toNum(arg(a, 1))) }
        math.props["max"] = NativeFunction("max") { _, a ->
            var r = Double.NEGATIVE_INFINITY
            for (x in a) { val d = js.toNum(x); if (d.isNaN()) return@NativeFunction Double.NaN; if (d > r) r = d }
            r
        }
        math.props["min"] = NativeFunction("min") { _, a ->
            var r = Double.POSITIVE_INFINITY
            for (x in a) { val d = js.toNum(x); if (d.isNaN()) return@NativeFunction Double.NaN; if (d < r) r = d }
            r
        }
        math.props["random"] = NativeFunction("random") { _, _ -> rnd.nextDouble() }
        math.props["imul"] = NativeFunction("imul") { _, a -> (js.toInt32(arg(a, 0)) * js.toInt32(arg(a, 1))).toDouble() }
        math.props["PI"] = Math.PI
        math.props["E"] = Math.E
        g["Math"] = math

        val json = JsObject()
        json.props["stringify"] = NativeFunction("stringify") { _, a -> jsonStringify(js, arg(a, 0)) ?: Undefined }
        json.props["parse"] = NativeFunction("parse") { _, a -> js.evalExpression("(" + js.toStr(arg(a, 0)) + ")") }
        g["JSON"] = json

        g["encodeURIComponent"] = NativeFunction("encodeURIComponent") { _, a -> encodeUri(js.toStr(arg(a, 0)), "-_.!~*'()") }
        g["encodeURI"] = NativeFunction("encodeURI") { _, a -> encodeUri(js.toStr(arg(a, 0)), "-_.!~*'();/?:@&=+$,#") }
        g["decodeURIComponent"] = NativeFunction("decodeURIComponent") { _, a -> decodeUri(js, js.toStr(arg(a, 0))) }
        g["decodeURI"] = NativeFunction("decodeURI") { _, a -> decodeUri(js, js.toStr(arg(a, 0))) }
        g["escape"] = NativeFunction("escape") { _, a -> escapeJs(js.toStr(arg(a, 0))) }
        g["unescape"] = NativeFunction("unescape") { _, a -> unescapeJs(js.toStr(arg(a, 0))) }

        val regexpFn = NativeFunction("RegExp") { _, a -> makeRegExp(js, a) }
        regexpFn.props["__ctor"] = { a: List<Any?> -> makeRegExp(js, a) }
        g["RegExp"] = regexpFn

        val dateFn = NativeFunction("Date") { _, _ -> java.util.Date().toString() }
        dateFn.props["now"] = NativeFunction("now") { _, _ -> System.currentTimeMillis().toDouble() }
        dateFn.props["__ctor"] = { _: List<Any?> ->
            val now = System.currentTimeMillis().toDouble()
            val d = JsObject()
            d.props["getTime"] = NativeFunction("getTime") { _, _ -> now }
            d.props["valueOf"] = NativeFunction("valueOf") { _, _ -> now }
            d.props["getFullYear"] = NativeFunction("getFullYear") { _, _ -> java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toDouble() }
            d.props["toISOString"] = NativeFunction("toISOString") { _, _ -> java.util.Date(now.toLong()).toString() }
            d
        }
        g["Date"] = dateFn

        for (errName in listOf("Error", "TypeError", "RangeError", "SyntaxError", "ReferenceError", "EvalError", "URIError")) {
            val f = NativeFunction(errName) { _, a -> js.makeError(errName, js.toStr(if (a.isEmpty()) "" else a[0])) }
            f.props["__ctor"] = { a: List<Any?> -> js.makeError(errName, js.toStr(if (a.isEmpty()) "" else a[0])) }
            g[errName] = f
        }

        val console = JsObject()
        for (m in listOf("log", "warn", "error", "info", "debug", "trace", "table", "clear", "dir", "group", "groupEnd", "time", "timeEnd")) {
            console.props[m] = NativeFunction(m) { _, _ -> Undefined }
        }
        g["console"] = console

        val timers = NativeFunction("setTimeout") { _, _ -> 1.0 }
        g["setTimeout"] = timers
        g["setInterval"] = NativeFunction("setInterval") { _, _ -> 1.0 }
        g["clearTimeout"] = NativeFunction("clearTimeout") { _, _ -> Undefined }
        g["clearInterval"] = NativeFunction("clearInterval") { _, _ -> Undefined }
        g["Function"] = NativeFunction("Function") { _, _ -> throw JsThrow(js.makeError("EvalError", "Function() desteklenmiyor")) }
        g["Promise"] = BlackHole
        g["Symbol"] = NativeFunction("Symbol") { _, a -> "@@symbol:" + js.toStr(arg(a, 0)) }
    }

    private fun makeArray(js: MiniJs, a: List<Any?>): JsArray {
        if (a.size == 1 && a[0] is Double) {
            val n = (a[0] as Double)
            if (n < 0 || n != Math.floor(n) || n > 10_000_000) throw JsThrow(js.makeError("RangeError", "Invalid array length"))
            return JsArray(ArrayList<Any?>().also { l -> repeat(n.toInt()) { l.add(Undefined) } })
        }
        return JsArray(ArrayList(a))
    }

    private fun makeRegExp(js: MiniJs, a: List<Any?>): JsRegExp {
        val p = arg(a, 0)
        val flags = if (arg(a, 1) === Undefined) null else js.toStr(arg(a, 1))
        return if (p is JsRegExp) JsRegExp(p.source, flags ?: p.flags)
        else JsRegExp(if (p === Undefined) "(?:)" else js.toStr(p), flags ?: "")
    }
}

/** Dean Edwards "p,a,c,k,e,d" paketleyicisini yerel olarak açar. */
internal object Packer {

    fun isPackerFunction(n: Node): Boolean {
        if (n !is FunExpr) return false
        val names = n.def.params.map { it.name }
        return names.size >= 5 && names[0] == "p" && names[1] == "a" && names[2] == "c" && names[3] == "k" && names[4] == "e"
    }

    fun unpackCall(call: CallExpr, evalArg: (Node) -> Any?): String? {
        if (call.args.size < 4) return null
        val payload = evalArg(call.args[0]) as? String ?: return null
        val radix = (evalArg(call.args[1]) as? Double)?.toInt() ?: return null
        val count = (evalArg(call.args[2]) as? Double)?.toInt() ?: return null
        val words = evalArg(call.args[3]) as? JsArray ?: return null
        return unpack(payload, radix, count, words.list.map { it as? String ?: "" })
    }

    private val WORD = Regex("\\b\\w+\\b")

    fun unpack(payload: String, radix: Int, count: Int, words: List<String>): String {
        if (radix < 2 || radix > 62) return payload
        return WORD.replace(payload) { m ->
            val w = m.value
            val idx = decode(w, radix)
            if (idx in 0 until count && idx < words.size && words[idx].isNotEmpty()) words[idx] else w
        }
    }

    private fun decode(w: String, radix: Int): Int {
        var n = 0L
        for (ch in w) {
            val d = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (d >= radix) return -1
            n = n * radix + d
            if (n > Int.MAX_VALUE) return -1
        }
        return n.toInt()
    }

    /** Koddaki tüm `eval(function(p,a,c,k,e,d){...}(...))` bloklarını açar (iç içe olanlar dahil). */
    fun unpackAll(code: String, maxDepth: Int = 3): List<String> {
        val out = ArrayList<String>()
        var pending = listOf(code)
        var depth = 0
        while (pending.isNotEmpty() && depth < maxDepth) {
            val next = ArrayList<String>()
            for (c in pending) {
                if (!c.contains("eval(function(p,a,c,k,e,")) continue
                val toks = try { Lexer(c).run() } catch (e: Exception) { continue }
                val js = MiniJs()
                var i = 0
                while (i < toks.size - 6) {
                    val t = toks[i]
                    if (t.type == Tok.IDENT && t.text == "eval" && toks[i + 1].text == "(" && toks[i + 2].text == "function") {
                        try {
                            val parser = Parser(toks, i + 2)
                            val expr = parser.parseAssign()
                            if (expr is CallExpr && isPackerFunction(expr.callee)) {
                                val unpacked = unpackCall(expr) { node -> js.evalNode(node) }
                                if (unpacked != null) {
                                    out.add(unpacked)
                                    next.add(unpacked)
                                }
                            }
                            i = maxOf(parser.position, i + 1)
                            continue
                        } catch (e: Exception) {
                            // geç
                        }
                    }
                    i++
                }
            }
            pending = next
            depth++
        }
        return out
    }
}

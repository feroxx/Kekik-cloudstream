package com.keyiflerolsun

import android.util.Base64
import android.util.Log

/**
 * Rapid / CloseLoad embed sayfalarındaki gizlenmiş video URL'sini çözer.
 *
 * Embed'ler iki farklı biçimde karşımıza çıkıyor:
 *  - Eski tip: `var x = func(["..."])` + basit karıştırma
 *  - Yeni tip: `var x = func("...".split("|"))` + "splice" tabanlı algoritma
 *
 * Kaynak değişkeni her zaman `sources: [{file: <degisken>}]` içinden bulunur.
 */
object EmbedDecoder {
    private const val TAG = "EmbedDecoder"

    /** Packer (`eval(function(p,a,c,k,e,d){...})`) ile sıkıştırılmış JS'i açar. */
    fun unpackPackerJs(rawHtml: String): String? {
        return try {
            val startMarker = "eval(function(p,a,c,k,e,d){"
            val endMarker = ",0,{}))"

            val startIdx = rawHtml.indexOf(startMarker)
            if (startIdx == -1) return null

            val endIdx = rawHtml.indexOf(endMarker, startIdx + startMarker.length)
            if (endIdx == -1) return null

            val block = rawHtml.substring(startIdx, endIdx + endMarker.length)
            val packedStart = block.indexOf("}('") + 3
            val packedEnd = block.indexOf("',", packedStart)
            if (packedStart == -1 || packedEnd == -1) return null
            val packed = block.substring(packedStart, packedEnd)
            val afterPacked = block.substring(packedEnd + 2)
            val baseEnd = afterPacked.indexOf(",")
            if (baseEnd == -1) return null
            val base = afterPacked.substring(0, baseEnd).toInt()
            val afterBase = afterPacked.substring(baseEnd + 1)
            val countEnd = afterBase.indexOf(",")
            if (countEnd == -1) return null
            val count = afterBase.substring(0, countEnd).toInt()
            val dictQuoteStart = afterBase.indexOf("'") + 1
            val dictQuoteEnd = afterBase.indexOf("'.split", dictQuoteStart)
            if (dictQuoteStart == -1 || dictQuoteEnd == -1) return null
            val dictStr = afterBase.substring(dictQuoteStart, dictQuoteEnd)

            Log.d(TAG, "Packer: base=$base, count=$count, dict=${dictStr.length}, packed=${packed.length}")

            val dictionary = dictStr.split('|')
            val lookup = mutableMapOf<String, String>()

            var c = count - 1
            while (c >= 0) {
                val key = packerEncode(c, base)
                lookup[key] = if (c < dictionary.size && dictionary[c].isNotEmpty()) {
                    dictionary[c]
                } else {
                    key
                }
                c--
            }

            var result = packed
            val sortedKeys = lookup.keys.sortedByDescending { it.length }
            for (key in sortedKeys) {
                val value = lookup[key]!!
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), value)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Unpack hatası: ${e.message}")
            null
        }
    }

    /**
     * Verilen HTML (ve varsa açılmış JS) içinden gizlenmiş video URL'sini çözer.
     *
     * @param rawHtml sunucudan gelen ham HTML
     * @param unpackedJs [unpackPackerJs] sonucu (opsiyonel)
     * @param host `location.hostname` karşılığı; bazı sürümlerde seed'e eklenir
     */
    fun extractVideoUrl(rawHtml: String, unpackedJs: String?, host: String): String? {
        val searchTexts = listOfNotNull(unpackedJs, rawHtml).distinct()

        val targetVar = searchTexts.firstNotNullOfOrNull { findSourceVar(it) }
        Log.d(TAG, "sources değişkeni: $targetVar")

        val candidates = mutableListOf<Pair<String, String>>()
        if (!targetVar.isNullOrBlank()) {
            for (text in searchTexts) {
                findAssignment(text, targetVar)?.let { candidates.add(it) }
            }
        }

        // Değişken adı bulunamazsa, split içeren tüm atamaları dene
        val assignmentRegex = Regex(
            """(?:var|let|const)?\s*[\w$]+\s*=\s*([\w$]+)\s*\((.*?)\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (text in searchTexts) {
            assignmentRegex.findAll(text).forEach { match ->
                val args = match.groupValues[2]
                if (args.contains(".split(") || args.trim().startsWith("[")) {
                    candidates.add(match.groupValues[1] to args)
                }
            }
        }

        for ((funcName, argsRaw) in candidates.distinctBy { it.first + "|" + it.second.take(120) }) {
            val parts = parseParts(argsRaw)
            if (parts.isEmpty()) continue

            val funcBody = searchTexts.firstNotNullOfOrNull { extractFuncBody(it, funcName) } ?: continue
            Log.d(TAG, "Fonksiyon: $funcName, parça: ${parts.size}, splice: ${funcBody.contains("splice")}")

            val decoded = try {
                if (funcBody.contains("splice")) {
                    decryptSplice(funcBody, parts, host)
                } else {
                    parseAndExecuteJs(funcBody, parts)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Çözümleme hatası ($funcName): ${e.message}")
                null
            }

            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                return decoded
            }
        }

        // Eski tip: var x = func(["...","..."])
        val arrayRegex = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*(\w+)\s*\(\s*\[(.*?)\]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (text in searchTexts) {
            for (match in arrayRegex.findAll(text)) {
                val funcName = match.groupValues[1]
                val parts = parseParts("[${match.groupValues[2]}]")
                if (parts.isEmpty()) continue

                val funcBody = extractFuncBody(text, funcName) ?: continue
                val decoded = if (funcBody.contains("splice")) {
                    decryptSplice(funcBody, parts, host)
                } else {
                    parseAndExecuteJs(funcBody, parts)
                }
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    return decoded
                }
            }
        }

        return null
    }

    private fun findSourceVar(text: String): String? {
        return text.lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("//") && !it.startsWith("/*") }
            .mapNotNull {
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*([\w$]+)""").find(it)?.groupValues?.get(1)
            }
            .firstOrNull()
    }

    private fun findAssignment(text: String, varName: String): Pair<String, String>? {
        val pattern = Regex(
            """(?:var|let|const)?\s*${Regex.escape(varName)}\s*=\s*([\w$]+)\s*\((.*?)\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = pattern.find(text) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun parseParts(argsRaw: String): List<String> {
        val raw = argsRaw.trim()

        val splitMatch = Regex(
            """^["'](.*)["']\s*\.\s*split\s*\(\s*["'](.*?)["']\s*\)$""",
            RegexOption.DOT_MATCHES_ALL
        ).find(raw)

        if (splitMatch != null) {
            val data = splitMatch.groupValues[1]
            val delimiter = splitMatch.groupValues[2]
            return data.split(delimiter).map { unescapeJs(it) }.filter { it.isNotEmpty() }
        }

        if (raw.startsWith("[") && raw.endsWith("]")) {
            var list = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(raw)
                .map { unescapeJs(it.groupValues[1]) }
                .toList()

            if (list.isEmpty()) {
                list = raw.removeSurrounding("[", "]").split(",")
                    .map { unescapeJs(it.trim().trim('\'', '"')) }
            }
            return list.filter { it.isNotEmpty() }
        }

        return emptyList()
    }

    private fun unescapeJs(value: String): String {
        return value.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun decryptSplice(script: String, originalParts: List<String>, host: String): String? {
        if (originalParts.isEmpty()) return null

        val parts = originalParts.toMutableList()
        val total = parts.size

        // Obfuscator diziden iki elemanı çıkarıp şifre çözücü anahtar/operasyon olarak kullanır.
        val idxMatch = Regex(
            """length\s*-\s*(\d+)\s*,\s*\w+\s*=\s*\w+\s*%\s*(\d+)\s*,\s*\w+\s*=\s*(\d+)\s*\+\s*\(\s*\w+\s*%\s*(\d+)\s*\)"""
        ).find(script)

        val lengthOffset = idxMatch?.groupValues?.get(1)?.toIntOrNull() ?: 2
        val seedMod = idxMatch?.groupValues?.get(2)?.toIntOrNull() ?: 7
        val opsBase = idxMatch?.groupValues?.get(3)?.toIntOrNull() ?: 8
        val opsMod = idxMatch?.groupValues?.get(4)?.toIntOrNull() ?: 5

        val base = total - lengthOffset
        val seedIndex = base % seedMod
        val opsIndex = opsBase + (base % opsMod)

        if (opsIndex !in parts.indices || seedIndex !in 0 until parts.size - 1) return null

        // JS sırası: önce ops, sonra seed çıkarılır
        val opsStr = parts.removeAt(opsIndex)
        val seedRaw = parts.removeAt(seedIndex)

        val seedCandidates = if (script.contains("location.hostname")) {
            listOf(seedRaw + host, seedRaw)
        } else {
            listOf(seedRaw)
        }

        for (seedStr in seedCandidates) {
            val decoded = decryptSplice(script, parts, opsStr, seedStr)
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                return decoded
            }
        }

        return null
    }

    private fun decryptSplice(script: String, workingParts: List<String>, opsStr: String, seedStr: String): String? {
        var value = workingParts.joinToString("")

        val multMatch = Regex("""\*\s*(\d+)\s*\+\s*[\w$]+\)\s*%\s*(\d+)""").find(script)
        val hashMult = multMatch?.groupValues?.get(1)?.toIntOrNull() ?: 37
        val hashMod = multMatch?.groupValues?.get(2)?.toIntOrNull() ?: 241

        var hash1 = 0
        var hash2 = 0
        for (i in seedStr.indices) {
            val code = seedStr[i].code
            hash1 = (hash1 * hashMult + code) % hashMod
            hash2 = if (script.contains("<<")) {
                (hash2 + ((code shl 1) xor i)) and 255
            } else {
                (hash2 xor (code + i)) and 255
            }
        }

        val xorStart = (hash1 * 3 + hash2) % 256
        val xorIncrement = (hash2 % 11) + 5
        var prng = (((hash2 * 251 + hash1) % 65519) + 1).toLong()

        val rotBase = Regex("""charCodeAt\(0\)\s*-\s*(\d+)""")
            .find(script)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (script.contains("<<")) 96 else 64

        for (i in opsStr.length - 1 downTo 0) {
            val ch = opsStr[i]
            value = when (ch) {
                'b', '7' -> atob(value)
                'v', '3' -> value.reversed()
                else -> caesarShift(value, (26 - ((ch.code - rotBase) % 26)) % 26)
            }
        }

        val length = value.length
        if (length > 1) {
            val prngMatch = Regex("""\*\s*(\d+)\s*\+\s*(\d+)\)\s*%\s*(\d+)""").findAll(script).lastOrNull()
            val prngMult = prngMatch?.groupValues?.get(1)?.toLongOrNull() ?: 97L
            val prngAdd = prngMatch?.groupValues?.get(2)?.toLongOrNull() ?: 41L
            val prngMod = prngMatch?.groupValues?.get(3)?.toLongOrNull() ?: 65519L

            val swaps = IntArray(length)
            for (i in length - 1 downTo 1) {
                prng = (prng * prngMult + prngAdd) % prngMod
                swaps[i] = (prng % (i + 1)).toInt()
            }

            val chars = value.toCharArray()
            for (i in 1 until length) {
                val j = swaps[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }
            value = String(chars)
        }

        val xorMultMatch = Regex("""\*\s*(\d+)\s*\+\s*[\w$]+\)\s*%\s*256""").findAll(script).lastOrNull()
        val xorMult = xorMultMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

        val output = StringBuilder(length)
        var acc = xorStart
        for (ch in value) {
            val code = ch.code
            acc = (acc * xorMult + xorIncrement) % 256
            output.append((code xor acc).toChar())
            acc = (acc + code) % 256
        }

        return output.toString().trim().takeIf { it.startsWith("http") }
    }

    private fun parseAndExecuteJs(funcBody: String, parts: List<String>): String? {
        return try {
            val seedMatch = Regex(
                """var\s+(\w+)\s*=\s*"([^"]+)"\s*;\s*var\s+(\w+)\s*=\s*"([^"]+)""""
            ).find(funcBody) ?: run {
                Log.w(TAG, "Seed/ops string'leri bulunamadı")
                return null
            }
            val seedStr = seedMatch.groupValues[2]
            val opsStr = seedMatch.groupValues[4]
            Log.d(TAG, "Seed: '$seedStr', Ops: '$opsStr'")

            var value = parts.joinToString("")
            var hash1 = 0
            var hash2 = 0
            for (i in seedStr.indices) {
                val code = seedStr[i].code
                hash1 = (hash1 * 31 + code) % 251
                hash2 = (hash2 xor (code + i)) and 255
            }
            val xorStart = (hash1 + hash2) % 256
            val xorIncrement = (hash1 % 13) + 3
            var prng = ((hash1 * 256 + hash2) % 65521) + 1
            Log.d(TAG, "xorStart=$xorStart, xorIncrement=$xorIncrement, prng=$prng")
            for (i in opsStr.length - 1 downTo 0) {
                val ch = opsStr[i]
                value = when (ch) {
                    'b' -> atob(value)
                    'v' -> value.reversed()
                    else -> {
                        val shift = (26 - ((ch.code - 64) % 26)) % 26
                        caesarShift(value, shift)
                    }
                }
            }
            Log.d(TAG, "Operasyonlar sonrası uzunluk: ${value.length}")
            val length = value.length
            if (length > 1) {
                val swaps = IntArray(length)
                for (i in length - 1 downTo 1) {
                    prng = (prng * 75 + 74) % 65537
                    swaps[i] = prng % (i + 1)
                }
                val arr = value.toCharArray()
                for (i in 1 until length) {
                    val j = swaps[i]
                    val tmp = arr[i]
                    arr[i] = arr[j]
                    arr[j] = tmp
                }
                value = String(arr)
            }
            val sb = StringBuilder(length)
            var acc = xorStart
            for (c in value) {
                val code = c.code
                acc = (acc + xorIncrement) % 256
                sb.append((code xor acc).toChar())
                acc = (acc + code) % 256
            }

            val result = sb.toString()
            Log.d(TAG, "Çözülen değer: ${result.take(200)}")
            result.trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.e(TAG, "JS Parser hatası: ${e.message}")
            null
        }
    }

    private fun extractFuncBody(jsCode: String, funcName: String): String? {
        val patterns = listOf(
            "function $funcName",
            "$funcName=function",
            "$funcName = function",
            "$funcName:function",
            "$funcName : function"
        )
        var startIdx = -1
        for (pattern in patterns) {
            val index = jsCode.indexOf(pattern)
            if (index != -1) {
                startIdx = index
                break
            }
        }
        if (startIdx == -1) return null
        val braceIdx = jsCode.indexOf('{', startIdx)
        if (braceIdx == -1) return null
        var braceCount = 1
        var i = braceIdx + 1
        while (braceCount > 0 && i < jsCode.length) {
            when (jsCode[i]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            i++
        }
        return if (braceCount == 0) jsCode.substring(braceIdx + 1, i - 1) else null
    }

    private fun atob(s: String): String {
        var str = s.trim()
        val padding = 4 - str.length % 4
        if (padding != 4) str += "=".repeat(padding)
        return Base64.decode(str, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
    }

    private fun caesarShift(text: String, shift: Int): String {
        return text.map { c ->
            when {
                c in 'A'..'Z' -> ((c.code - 'A'.code + shift) % 26 + 'A'.code).toChar()
                c in 'a'..'z' -> ((c.code - 'a'.code + shift) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun packerEncode(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % base])
            n /= base
        }
        return sb.toString()
    }
}

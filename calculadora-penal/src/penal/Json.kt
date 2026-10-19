package penal

import java.time.LocalDate

/*
 * Geração de JSON sem bibliotecas externas.
 *
 * Suporta tudo o que a API devolve: Map, List, String, número, booleano,
 * LocalDate (vira "AAAA-MM-DD") e null. Mapas criados com mapOf() mantêm a
 * ordem de inserção, então o JSON sai na mesma ordem em que foi escrito.
 */
object Json {

    fun escapar(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // < e > escapados: o JSON nunca vira HTML executável, mesmo se for
                // colado dentro de uma página por engano.
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun paraJson(valor: Any?): String = when (valor) {
        null -> "null"
        is Boolean -> valor.toString()
        is Double -> if (valor.isFinite()) valor.toString() else "null"
        is Float -> if (valor.isFinite()) valor.toString() else "null"
        is Number -> valor.toString()
        is String -> escapar(valor)
        is LocalDate -> escapar(valor.toString())
        is Enum<*> -> escapar(valor.name.lowercase())
        is Map<*, *> -> valor.entries.joinToString(",", "{", "}") { (k, v) ->
            escapar(k.toString()) + ":" + paraJson(v)
        }
        is Iterable<*> -> valor.joinToString(",", "[", "]") { paraJson(it) }
        is Array<*> -> valor.joinToString(",", "[", "]") { paraJson(it) }
        else -> escapar(valor.toString())
    }
}

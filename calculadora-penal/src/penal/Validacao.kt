package penal

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/*
 * Validação dos dados de entrada.
 *
 * Regra geral: nunca confiar na entrada do usuário. Cada parâmetro chega como
 * texto, é convertido e conferido contra limites explícitos. Qualquer problema
 * vira uma mensagem clara (HTTP 400), nunca uma exceção sem tratamento.
 */

/** Resultado de uma validação: ou o valor pronto, ou a lista de erros. */
sealed class Validado<out T> {
    data class Ok<T>(val valor: T) : Validado<T>()
    data class Erros(val mensagens: List<String>) : Validado<Nothing>()
}

object Validacao {

    const val TAMANHO_MAX = 20          // caracteres aceitos por parâmetro numérico
    const val PENA_ANOS_MAX = 300       // somas de penas podem passar de 40 anos (Súmula 715 do STF)
    val DATA_MIN: LocalDate = LocalDate.of(1950, 1, 1)
    val DATA_MAX: LocalDate = LocalDate.of(2100, 12, 31)

    private val formatoBr = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
    private val formatoIso = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** Mostra no máximo 20 caracteres do que o usuário digitou, para não ecoar textos enormes. */
    private fun recorte(s: String) = if (s.length > 20) s.take(20) + "…" else s

    /**
     * Inteiro opcional entre [min] e [max]. Vazio vale [padrao].
     * Devolve o número ou adiciona uma mensagem em [erros].
     */
    fun inteiro(params: Map<String, String>, nome: String, rotulo: String, min: Int, max: Int,
                erros: MutableList<String>, padrao: Int = 0): Int {
        val bruto = params[nome]?.trim().orEmpty()
        if (bruto.isEmpty()) return padrao
        if (bruto.length > TAMANHO_MAX || !Regex("\\d+").matches(bruto)) {
            erros += "$rotulo inválido: '${recorte(bruto)}'. Informe um número inteiro, por exemplo 5."
            return padrao
        }
        val n = bruto.toIntOrNull()
        if (n == null || n !in min..max) {
            erros += "$rotulo deve estar entre $min e $max."
            return padrao
        }
        return n
    }

    /** Aceita "2024-03-15" (formato do navegador) ou "15/03/2024". */
    fun data(texto: String?): LocalDate? {
        val s = texto?.trim().orEmpty()
        if (s.isEmpty() || s.length > TAMANHO_MAX) return null
        for (formato in listOf(formatoIso, formatoBr)) {
            try {
                return LocalDate.parse(s, formato)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    /** "true", "on", "1", "sim" -> true. Qualquer outra coisa (ou ausente) -> false. */
    fun booleano(texto: String?): Boolean =
        texto?.trim()?.lowercase() in setOf("true", "on", "1", "sim", "s")

    private fun semAcento(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    fun tipoCrime(texto: String?): TipoCrime? = when (semAcento(texto?.trim()?.lowercase().orEmpty())) {
        "comum", "nao_hediondo", "nao hediondo" -> TipoCrime.COMUM
        "hediondo", "equiparado" -> TipoCrime.HEDIONDO
        else -> null
    }

    fun status(texto: String?): Status? = when (semAcento(texto?.trim()?.lowercase().orEmpty())) {
        "primario" -> Status.PRIMARIO
        "reincidente" -> Status.REINCIDENTE
        else -> null
    }

    /** Valida os parâmetros de GET /api/calculo e monta a [Entrada]. */
    fun validarEntrada(params: Map<String, String>): Validado<Entrada> {
        val erros = mutableListOf<String>()

        val pena = Duracao(
            inteiro(params, "pena_anos", "Anos da pena", 0, PENA_ANOS_MAX, erros),
            inteiro(params, "pena_meses", "Meses da pena", 0, 11, erros),
            inteiro(params, "pena_dias", "Dias da pena", 0, 30, erros),
        )
        val detracao = Duracao(
            inteiro(params, "detracao_anos", "Anos de detração", 0, PENA_ANOS_MAX, erros),
            inteiro(params, "detracao_meses", "Meses de detração", 0, 11, erros),
            inteiro(params, "detracao_dias", "Dias de detração", 0, 30, erros),
        )

        val textoData = params["data_inicio"]
        val inicio = data(textoData)
        when {
            textoData.isNullOrBlank() ->
                erros += "Informe a data de início da pena."
            inicio == null ->
                erros += "Data de início inválida: '${recorte(textoData.trim())}'. Use AAAA-MM-DD ou DD/MM/AAAA."
            inicio.isBefore(DATA_MIN) || inicio.isAfter(DATA_MAX) ->
                erros += "A data de início deve estar entre 01/01/1950 e 31/12/2100."
        }

        val tipo = tipoCrime(params["tipo_crime"])
        if (tipo == null) erros += "Tipo de crime inválido ou ausente: use 'comum' ou 'hediondo'."

        val status = status(params["status"])
        if (status == null) erros += "Status inválido ou ausente: use 'primario' ou 'reincidente'."

        if (erros.isEmpty()) {
            if (pena.vazia) {
                erros += "A pena total deve ser maior que zero."
            } else if (detracao.emDiasAte(inicio!!) >= pena.emDiasAPartirDe(inicio)) {
                erros += "A detração não pode ser igual ou maior que a pena total."
            }
        }

        if (erros.isNotEmpty()) return Validado.Erros(erros)

        val perfil = Perfil(
            tipo = tipo!!,
            status = status!!,
            violencia = booleano(params["violencia"]),
            resultadoMorte = booleano(params["resultado_morte"]),
            reincidenciaEspecifica = booleano(params["reincidencia_especifica"]),
        ).normalizado()

        return Validado.Ok(Entrada(pena, inicio!!, detracao, perfil))
    }
}

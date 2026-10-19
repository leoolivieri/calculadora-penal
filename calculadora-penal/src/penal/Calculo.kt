package penal

import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/*
 * Cálculo dos prazos da execução penal.
 *
 * Todas as funções deste arquivo são PURAS: recebem valores e devolvem valores,
 * sem ler relógio, rede ou console. Até a data de "hoje" chega por parâmetro.
 * Isso deixa o cálculo fácil de testar e de explicar.
 *
 * Como o cálculo funciona:
 *  1. A pena (anos, meses, dias) vira dias pelo calendário comum, contando a
 *     partir da data de início (art. 10 do CP). Assim, anos bissextos e meses de
 *     28/30/31 dias entram certos.
 *  2. A detração é tempo já cumprido. Por isso ela "recua" a data-base:
 *     dataBase = início - detração.
 *  3. Semiaberto:  dataBase + fração x pena total.
 *  4. Aberto:      data do semiaberto + fração x saldo de pena (pena - tempo do item 3).
 *     A fração da segunda progressão incide sobre o que falta cumprir, não
 *     sobre a pena inteira.
 *  5. Livramento condicional: dataBase + mais de [1/3 | 1/2 | 2/3] da pena total.
 *  6. Término:     dataBase + pena total, limitado a 40 anos (art. 75 do CP).
 */

/** Duração escrita como na sentença: "5 anos, 6 meses e 10 dias". */
data class Duracao(val anos: Int = 0, val meses: Int = 0, val dias: Int = 0) {
    val periodo: Period get() = Period.of(anos, meses, dias)
    val vazia: Boolean get() = anos == 0 && meses == 0 && dias == 0

    /** Quantidade de dias que esta duração ocupa no calendário, contando a partir de [a]. */
    fun emDiasAPartirDe(a: LocalDate): Long = ChronoUnit.DAYS.between(a, a.plus(periodo))

    /** Quantidade de dias que esta duração ocupa no calendário, contando para trás a partir de [a]. */
    fun emDiasAte(a: LocalDate): Long = ChronoUnit.DAYS.between(a.minus(periodo), a)

    override fun toString(): String = textoDuracao(anos, meses, dias)
}

data class Entrada(
    val pena: Duracao,
    val dataInicio: LocalDate,
    val detracao: Duracao,
    val perfil: Perfil,
)

/** Um prazo calculado: quando o requisito objetivo (tempo) é atingido. */
data class Marco(
    val data: LocalDate,
    val diasNecessarios: Long,
    val fracao: String,
    val fundamento: String,
    val explicacao: String,
    val jaAtingido: Boolean,
)

data class Livramento(
    val cabivel: Boolean,
    val marco: Marco?,
    val fracao: String,
    val fundamento: String,
    val motivo: String?,
)

data class Resultado(
    val entrada: Entrada,
    val penaTotalDias: Long,
    val detracaoDias: Long,
    val penaRestante: Duracao,
    val penaRestanteDias: Long,
    val dataBase: LocalDate,
    val semiaberto: Marco,
    val aberto: Marco,
    val livramento: Livramento,
    val termino: LocalDate,
    val terminoLimitadoA40Anos: Boolean,
    val observacoes: List<String>,
)

object Calculo {

    const val AVISO =
        "Estimativa educativa. As datas indicam quando o requisito de TEMPO é atingido; " +
            "o benefício também depende de bom comportamento e de decisão do juiz da execução. " +
            "Não substitui a análise de um advogado."

    /** Dias necessários para cumprir "ao menos" [percentual]% de [dias] (arredonda para cima). */
    fun diasParaPercentual(dias: Long, percentual: Int): Long =
        (dias * percentual + 99) / 100

    /** Dias necessários para cumprir "mais de" [num]/[den] de [dias]. */
    fun diasParaMaisDe(dias: Long, num: Int, den: Int): Long =
        dias * num / den + 1

    /**
     * Pena restante (pena - detração) escrita em anos, meses e dias.
     * Os meses são subtraídos como meses; se os dias ficarem negativos,
     * empresta-se um mês de 30 dias (só para exibição; as datas usam o calendário).
     */
    fun subtrair(pena: Duracao, detracao: Duracao): Duracao {
        var totalMeses = (pena.anos * 12 + pena.meses) - (detracao.anos * 12 + detracao.meses)
        var dias = pena.dias - detracao.dias
        if (dias < 0) {
            totalMeses -= 1
            dias += 30
        }
        if (totalMeses < 0) return Duracao()
        return Duracao(totalMeses / 12, totalMeses % 12, dias)
    }

    private fun marco(
        base: LocalDate,
        dias: Long,
        fracao: String,
        fundamento: String,
        explicacao: String,
        hoje: LocalDate,
    ): Marco {
        val data = base.plusDays(dias)
        return Marco(data, dias, fracao, fundamento, explicacao, jaAtingido = !data.isAfter(hoje))
    }

    fun calcular(entrada: Entrada, hoje: LocalDate): Resultado {
        val perfil = entrada.perfil.normalizado()
        val inicio = entrada.dataInicio

        val penaDias = entrada.pena.emDiasAPartirDe(inicio)
        val detracaoDias = entrada.detracao.emDiasAte(inicio)
        val dataBase = inicio.minusDays(detracaoDias)

        // --- Progressão para o semiaberto: fração sobre a pena total ---------------
        val regra = Regras.regraProgressao(perfil)
        val diasSemi = diasParaPercentual(penaDias, regra.percentual)
        val semiaberto = marco(
            dataBase, diasSemi, "${regra.percentual}%", regra.fundamento,
            "Cumprindo ${regra.percentual}% da pena total ($diasSemi dias, já descontada a detração), " +
                "o requisito de tempo para pedir o regime semiaberto é atingido.",
            hoje,
        )

        // --- Progressão para o aberto: mesma fração, sobre o saldo de pena ----------
        val saldo = penaDias - diasSemi
        val diasAberto = diasParaPercentual(saldo, regra.percentual)
        val aberto = marco(
            semiaberto.data, diasAberto, "${regra.percentual}% do saldo", regra.fundamento,
            "Depois de chegar ao semiaberto, conta-se de novo ${regra.percentual}%, agora sobre o " +
                "saldo de pena ($saldo dias). São mais $diasAberto dias até o requisito para o regime aberto.",
            hoje,
        )

        // --- Livramento condicional: fração sobre a pena total ----------------------
        val regraLc = Regras.regraLivramento(perfil)
        val minimoLcDias = Duracao(Regras.ANOS_MINIMOS_LIVRAMENTO.toInt()).emDiasAPartirDe(inicio)
        val livramento = when {
            regraLc.vedado -> Livramento(false, null, regraLc.fracao, regraLc.fundamento, regraLc.descricao)
            penaDias < minimoLcDias -> Livramento(
                false, null, regraLc.fracao, "Art. 83, caput, do Código Penal",
                "O livramento condicional só cabe para pena igual ou superior a 2 anos.",
            )
            else -> {
                val dias = diasParaMaisDe(penaDias, regraLc.numerador, regraLc.denominador)
                Livramento(
                    true,
                    marco(
                        dataBase, dias, regraLc.fracao, regraLc.fundamento,
                        "${regraLc.descricao} ($dias dias, já descontada a detração).",
                        hoje,
                    ),
                    regraLc.fracao, regraLc.fundamento, null,
                )
            }
        }

        // --- Término da pena (com o limite de 40 anos do art. 75 do CP) ----------
        val terminoPena = dataBase.plusDays(penaDias)
        val limite = dataBase.plusYears(Regras.LIMITE_CUMPRIMENTO_ANOS)
        val limitado = terminoPena.isAfter(limite)

        return Resultado(
            entrada = entrada.copy(perfil = perfil),
            penaTotalDias = penaDias,
            detracaoDias = detracaoDias,
            penaRestante = subtrair(entrada.pena, entrada.detracao),
            penaRestanteDias = penaDias - detracaoDias,
            dataBase = dataBase,
            semiaberto = semiaberto,
            aberto = aberto,
            livramento = livramento,
            termino = if (limitado) limite else terminoPena,
            terminoLimitadoA40Anos = limitado,
            observacoes = observacoes(entrada.copy(perfil = perfil), limitado),
        )
    }

    /** Avisos que ajudam o usuário leigo a entender os limites do cálculo. */
    fun observacoes(entrada: Entrada, limitado: Boolean): List<String> {
        val p = entrada.perfil
        val lista = mutableListOf(
            "O cálculo parte do regime fechado e considera que não houve falta grave. " +
                "A falta grave reinicia a contagem para a progressão (art. 112, § 6º, da LEP).",
            "Dias de trabalho ou estudo (remição) não foram descontados. Eles podem antecipar as datas.",
        )
        val mesesPena = entrada.pena.anos * 12 + entrada.pena.meses
        val ateOitoAnos = mesesPena < 96 || (mesesPena == 96 && entrada.pena.dias == 0)
        if (p.status == Status.PRIMARIO && ateOitoAnos) {
            lista += "Para não reincidente com pena de até 8 anos, a sentença pode fixar regime inicial " +
                "semiaberto ou aberto (art. 33, § 2º, do CP). Nesse caso, confira o regime inicial na sentença."
        }
        if (p.tipo == TipoCrime.HEDIONDO && p.status == Status.REINCIDENTE && !p.reincidenciaEspecifica) {
            lista += "Reincidente em crime não hediondo condenado agora por crime hediondo: aplica-se a " +
                "fração do primário (40% ou 50%), conforme o STJ (Tema 1084)."
        }
        if (p.tipo == TipoCrime.COMUM && p.status == Status.PRIMARIO) {
            lista += "O livramento com 1/3 da pena exige bons antecedentes. Sem eles, a fração costuma ser 1/2."
        }
        if (p.tipo == TipoCrime.HEDIONDO) {
            lista += "Casos de comando de organização criminosa ou milícia (art. 112, VI, b e c, da LEP) " +
                "têm regras próprias e não estão neste cálculo."
        }
        if (limitado) {
            lista += "O tempo de cumprimento foi limitado a 40 anos (art. 75 do CP). " +
                "As frações dos benefícios continuam calculadas sobre a pena total (Súmula 715 do STF)."
        }
        return lista
    }
}

fun textoDuracao(anos: Int, meses: Int, dias: Int): String {
    val partes = buildList {
        if (anos > 0) add(if (anos == 1) "1 ano" else "$anos anos")
        if (meses > 0) add(if (meses == 1) "1 mês" else "$meses meses")
        if (dias > 0) add(if (dias == 1) "1 dia" else "$dias dias")
    }
    return when (partes.size) {
        0 -> "0 dias"
        1 -> partes[0]
        else -> partes.dropLast(1).joinToString(", ") + " e " + partes.last()
    }
}

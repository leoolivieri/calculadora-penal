package penal

/*
 * Regras jurídicas da calculadora, no formato SE-ENTÃO pedido na especificação.
 *
 * Este é o ÚNICO arquivo que precisa mudar quando o escritório parceiro entregar
 * o "Documento de Regras dos Cálculos". O resto do sistema só consulta estas
 * tabelas: a ordem importa, vale a PRIMEIRA regra cuja condição for verdadeira.
 *
 * Base legal:
 *  - Progressão de regime: art. 112 da Lei de Execução Penal (Lei 7.210/84),
 *    com a redação dada pela Lei 13.964/19 (Pacote Anticrime).
 *  - Livramento condicional: art. 83 do Código Penal.
 */

enum class TipoCrime(val rotulo: String) {
    COMUM("Crime comum"),
    HEDIONDO("Crime hediondo ou equiparado"),
}

enum class Status(val rotulo: String) {
    PRIMARIO("Primário"),
    REINCIDENTE("Reincidente"),
}

/**
 * Perfil jurídico do caso, que decide qual regra se aplica.
 *
 * - [violencia]: só faz sentido para crime comum (incisos III e IV).
 * - [resultadoMorte]: só faz sentido para crime hediondo (incisos VI, "a", e VIII).
 * - [reincidenciaEspecifica]: só faz sentido para hediondo + reincidente. Indica
 *   que a condenação anterior também foi por crime hediondo ou equiparado.
 */
data class Perfil(
    val tipo: TipoCrime,
    val status: Status,
    val violencia: Boolean = false,
    val resultadoMorte: Boolean = false,
    val reincidenciaEspecifica: Boolean = false,
) {
    /** Zera as opções que não se aplicam ao tipo/status, para evitar combinações sem sentido. */
    fun normalizado(): Perfil = Perfil(
        tipo = tipo,
        status = status,
        violencia = tipo == TipoCrime.COMUM && violencia,
        resultadoMorte = tipo == TipoCrime.HEDIONDO && resultadoMorte,
        reincidenciaEspecifica = tipo == TipoCrime.HEDIONDO && status == Status.REINCIDENTE && reincidenciaEspecifica,
    )

    val reincidenteEspecificoEmHediondo: Boolean
        get() = tipo == TipoCrime.HEDIONDO && status == Status.REINCIDENTE && reincidenciaEspecifica
}

/** SE [condicao] ENTÃO a fração para progredir é [percentual]% da pena. */
data class RegraProgressao(
    val fundamento: String,
    val percentual: Int,
    val descricao: String,
    val condicao: (Perfil) -> Boolean,
)

/** SE [condicao] ENTÃO o livramento exige mais de [numerador]/[denominador] da pena (ou é vedado). */
data class RegraLivramento(
    val fundamento: String,
    val numerador: Int,
    val denominador: Int,
    val descricao: String,
    val vedado: Boolean = false,
    val condicao: (Perfil) -> Boolean,
) {
    val fracao: String get() = if (vedado) "vedado" else "$numerador/$denominador"
}

object Regras {

    private const val LEP = "da LEP (Lei 7.210/84, red. Lei 13.964/19)"

    val progressao: List<RegraProgressao> = listOf(
        RegraProgressao("Art. 112, I, $LEP", 16,
            "Primário, crime cometido sem violência ou grave ameaça à pessoa") {
            it.tipo == TipoCrime.COMUM && it.status == Status.PRIMARIO && !it.violencia
        },
        RegraProgressao("Art. 112, II, $LEP", 20,
            "Reincidente em crime cometido sem violência ou grave ameaça à pessoa") {
            it.tipo == TipoCrime.COMUM && it.status == Status.REINCIDENTE && !it.violencia
        },
        RegraProgressao("Art. 112, III, $LEP", 25,
            "Primário, crime cometido com violência ou grave ameaça à pessoa") {
            it.tipo == TipoCrime.COMUM && it.status == Status.PRIMARIO && it.violencia
        },
        RegraProgressao("Art. 112, IV, $LEP", 30,
            "Reincidente em crime cometido com violência ou grave ameaça à pessoa") {
            it.tipo == TipoCrime.COMUM && it.status == Status.REINCIDENTE && it.violencia
        },
        // Hediondo sem reincidência específica: primário ou reincidente "genérico".
        // O STJ (Tema 1084) decidiu que o reincidente não específico segue os
        // percentuais do primário (40% ou 50%), porque a lei é omissa nesse caso.
        RegraProgressao("Art. 112, V, $LEP", 40,
            "Crime hediondo ou equiparado, sem reincidência específica") {
            it.tipo == TipoCrime.HEDIONDO && !it.resultadoMorte && !it.reincidenteEspecificoEmHediondo
        },
        RegraProgressao("Art. 112, VI, a, $LEP", 50,
            "Crime hediondo ou equiparado com resultado morte, sem reincidência específica") {
            it.tipo == TipoCrime.HEDIONDO && it.resultadoMorte && !it.reincidenteEspecificoEmHediondo
        },
        RegraProgressao("Art. 112, VII, $LEP", 60,
            "Reincidente na prática de crime hediondo ou equiparado") {
            it.reincidenteEspecificoEmHediondo && !it.resultadoMorte
        },
        RegraProgressao("Art. 112, VIII, $LEP", 70,
            "Reincidente em crime hediondo ou equiparado com resultado morte") {
            it.reincidenteEspecificoEmHediondo && it.resultadoMorte
        },
    )

    val livramento: List<RegraLivramento> = listOf(
        RegraLivramento("Art. 112, VI, a, e VIII, $LEP", 0, 1,
            "Crime hediondo com resultado morte: livramento condicional vedado", vedado = true) {
            it.tipo == TipoCrime.HEDIONDO && it.resultadoMorte
        },
        RegraLivramento("Art. 83, V, do Código Penal", 0, 1,
            "Reincidente específico em crime hediondo: livramento condicional vedado", vedado = true) {
            it.reincidenteEspecificoEmHediondo
        },
        RegraLivramento("Art. 83, V, do Código Penal", 2, 3,
            "Crime hediondo ou equiparado: mais de 2/3 da pena") {
            it.tipo == TipoCrime.HEDIONDO
        },
        RegraLivramento("Art. 83, II, do Código Penal", 1, 2,
            "Reincidente em crime doloso: mais de 1/2 da pena") {
            it.tipo == TipoCrime.COMUM && it.status == Status.REINCIDENTE
        },
        RegraLivramento("Art. 83, I, do Código Penal", 1, 3,
            "Não reincidente com bons antecedentes: mais de 1/3 da pena") {
            it.tipo == TipoCrime.COMUM && it.status == Status.PRIMARIO
        },
    )

    /** Pena mínima para o livramento condicional (art. 83, caput, do CP): 2 anos. */
    const val ANOS_MINIMOS_LIVRAMENTO = 2L

    /** Limite de cumprimento de pena (art. 75 do CP, red. Lei 13.964/19): 40 anos. */
    const val LIMITE_CUMPRIMENTO_ANOS = 40L

    fun regraProgressao(perfil: Perfil): RegraProgressao =
        progressao.first { it.condicao(perfil.normalizado()) }

    fun regraLivramento(perfil: Perfil): RegraLivramento =
        livramento.first { it.condicao(perfil.normalizado()) }
}

package penal

import kotlin.system.exitProcess

/** Executa todos os testes. Sai com código 1 se algum falhar. */
object ExecutarTestes {

    @JvmStatic
    fun main(args: Array<String>) {
        val grupos = listOf(
            "Cálculo" to testesCalculo,
            "Validação" to testesValidacao,
            "HTTP" to testesHttp,
        )
        var falhas = 0
        var total = 0
        for ((grupo, casos) in grupos) {
            println("\n== $grupo")
            for (caso in casos) {
                total++
                try {
                    caso.bloco()
                    println("  [ok]    ${caso.nome}")
                } catch (e: Throwable) {
                    falhas++
                    println("  [FALHA] ${caso.nome}\n          ${e.message}")
                }
            }
        }
        println("\n$total testes, $verificacoes verificações, $falhas falha(s).")
        exitProcess(if (falhas == 0) 0 else 1)
    }
}

package penal

/*
 * Mini framework de testes, sem dependências: cada teste é um nome + um bloco.
 * As verificações lançam AssertionError com uma mensagem clara quando falham.
 */

class Caso(val nome: String, val bloco: () -> Unit)

fun teste(nome: String, bloco: () -> Unit) = Caso(nome, bloco)

var verificacoes = 0

fun <T> igual(esperado: T, obtido: T, mensagem: String = "") {
    verificacoes++
    if (esperado != obtido) {
        throw AssertionError("${if (mensagem.isEmpty()) "" else "$mensagem: "}esperado <$esperado>, obtido <$obtido>")
    }
}

fun verdadeiro(condicao: Boolean, mensagem: String) {
    verificacoes++
    if (!condicao) throw AssertionError(mensagem)
}

fun contem(trecho: String, texto: String) {
    verificacoes++
    if (!texto.contains(trecho)) throw AssertionError("esperava encontrar <$trecho> em <${texto.take(300)}>")
}

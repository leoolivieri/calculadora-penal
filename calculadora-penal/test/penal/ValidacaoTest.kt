package penal

import java.time.LocalDate

private val valido = mapOf(
    "pena_anos" to "5", "pena_meses" to "6", "pena_dias" to "0",
    "data_inicio" to "2024-03-15",
    "detracao_meses" to "6",
    "tipo_crime" to "comum", "status" to "primario",
)

private fun erros(params: Map<String, String>): List<String> =
    (Validacao.validarEntrada(params) as? Validado.Erros)?.mensagens ?: emptyList()

private fun contato(vararg extras: Pair<String, String>) = mapOf(
    "nome" to "Maria da Silva", "whatsapp" to "(11) 98765-4321", "consentimento" to "true",
) + extras

val testesValidacao = listOf(

    teste("Entrada válida vira Entrada") {
        val v = Validacao.validarEntrada(valido)
        verdadeiro(v is Validado.Ok, "deveria ser válida: $v")
        val e = (v as Validado.Ok).valor
        igual(Duracao(5, 6, 0), e.pena)
        igual(LocalDate.of(2024, 3, 15), e.dataInicio)
        igual(Duracao(0, 6, 0), e.detracao)
    },

    teste("Aceita data no formato brasileiro e acentos no status") {
        val v = Validacao.validarEntrada(valido + ("data_inicio" to "15/03/2024") + ("status" to "Primário"))
        verdadeiro(v is Validado.Ok, "deveria aceitar: $v")
    },

    teste("Campos obrigatórios ausentes geram uma mensagem por campo") {
        val e = erros(emptyMap())
        igual(3, e.size, "data, tipo e status")
        verdadeiro(e.any { it.contains("data de início") }, "falta data")
    },

    teste("Rejeita texto, negativos, datas impossíveis e valores fora da faixa") {
        contem("inválido", erros(valido + ("pena_anos" to "abc")).joinToString())
        contem("inválido", erros(valido + ("pena_anos" to "-5")).joinToString())
        contem("entre 0 e 11", erros(valido + ("pena_meses" to "12")).joinToString())
        contem("inválida", erros(valido + ("data_inicio" to "2024-02-30")).joinToString())
        contem("1950", erros(valido + ("data_inicio" to "1900-01-01")).joinToString())
        contem("Tipo de crime", erros(valido + ("tipo_crime" to "grave")).joinToString())
        contem("inválido", erros(valido + ("pena_anos" to "9".repeat(50))).joinToString())
    },

    teste("Pena zero e detração maior que a pena são rejeitadas") {
        contem("maior que zero", erros(valido + ("pena_anos" to "0") + ("pena_meses" to "0")).joinToString())
        contem("detração", erros(valido + ("detracao_anos" to "6")).joinToString())
    },

    teste("Mensagem de erro não ecoa textos enormes") {
        val e = erros(valido + ("data_inicio" to "<script>" + "x".repeat(500))).joinToString()
        verdadeiro(e.length < 300, "mensagem curta")
    },

    teste("Contato válido é normalizado") {
        val v = ValidacaoContato.validar(contato("email" to "Maria@Exemplo.com.br", "processo" to "12345671320248260100"))
        verdadeiro(v is Validado.Ok, "deveria ser válido: $v")
        val c = (v as Validado.Ok).valor
        igual("+5511987654321", c.whatsapp)
        igual("maria@exemplo.com.br", c.email)
        igual("1234567-13.2024.8.26.0100", c.processo)
    },

    teste("WhatsApp: aceita formatos comuns e rejeita números impossíveis") {
        igual("+5511987654321", ValidacaoContato.normalizarWhatsapp("+55 (11) 98765-4321"))
        igual("+551133334444", ValidacaoContato.normalizarWhatsapp("11 3333-4444"))
        igual(null, ValidacaoContato.normalizarWhatsapp("98765-4321"), "sem DDD")
        igual(null, ValidacaoContato.normalizarWhatsapp("(11) 88765-4321"), "celular sem 9")
        igual(null, ValidacaoContato.normalizarWhatsapp("(01) 98765-4321"), "DDD inválido")
        igual(null, ValidacaoContato.normalizarWhatsapp("11987654321; DROP TABLE"), "caracteres estranhos")
    },

    teste("Número do processo CNJ: confere o dígito verificador (módulo 97)") {
        igual("1234567-13.2024.8.26.0100", ValidacaoContato.normalizarProcesso("1234567-13.2024.8.26.0100"))
        igual(null, ValidacaoContato.normalizarProcesso("1234567-14.2024.8.26.0100"), "dígito errado")
        igual(null, ValidacaoContato.normalizarProcesso("123-13.2024.8.26.0100"), "formato errado")
    },

    teste("Contato: nome completo, WhatsApp e consentimento são obrigatórios") {
        val e = (ValidacaoContato.validar(mapOf("nome" to "Maria")) as Validado.Erros).mensagens.joinToString()
        contem("nome completo", e)
        contem("WhatsApp", e)
        contem("autorizar", e)
        val e2 = (ValidacaoContato.validar(contato("nome" to "<b>Maria</b> Silva")) as Validado.Erros).mensagens
        contem("apenas letras", e2.joinToString())
        val e3 = (ValidacaoContato.validar(contato("email" to "nao-e-email")) as Validado.Erros).mensagens
        contem("E-mail", e3.joinToString())
    },

    teste("CSV neutraliza fórmulas do Excel (CSV injection)") {
        igual("\"'=HYPERLINK(1)\"", Csv.celula("=HYPERLINK(1)"))
        igual("\"'+5511987654321\"", Csv.celula("+5511987654321"))
        igual("\"diz \"\"oi\"\"\"", Csv.celula("diz \"oi\""))
    },
)

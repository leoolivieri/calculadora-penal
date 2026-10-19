package penal

/*
 * Ponto de entrada. Configuração por variáveis de ambiente (todas opcionais):
 *   PORT                 porta HTTP (padrão 3000)
 *   CORS_ORIGIN          origem autorizada a chamar a API (padrão *)
 *   WHATSAPP_ESCRITORIO  número do botão "Quero falar com um advogado"
 *   ADMIN_TOKEN          libera GET /api/contatos para o escritório
 *   BANCO                arquivo do SQLite (padrão dados/contatos.db)
 */
fun lerConfig(env: (String) -> String? = System::getenv): Config {
    val padrao = Config()
    val porta = env("PORT")?.takeIf { Regex("\\d{2,5}").matches(it) }?.toInt() ?: padrao.porta
    val whatsapp = env("WHATSAPP_ESCRITORIO")?.filter(Char::isDigit)?.takeIf { it.length in 12..13 }
        ?: padrao.whatsappEscritorio
    // Token curto demais é fácil de adivinhar: nesse caso a rota fica desligada.
    val token = env("ADMIN_TOKEN")?.trim()?.takeIf { it.length >= 16 }
    if (env("ADMIN_TOKEN") != null && token == null) {
        System.err.println("AVISO: ADMIN_TOKEN precisa ter pelo menos 16 caracteres. GET /api/contatos desativado.")
    }
    return Config(
        porta = porta,
        corsOrigem = env("CORS_ORIGIN")?.takeIf { it.isNotBlank() } ?: padrao.corsOrigem,
        whatsappEscritorio = whatsapp,
        tokenAdmin = token,
        arquivoBanco = env("BANCO")?.takeIf { it.isNotBlank() } ?: padrao.arquivoBanco,
    )
}

fun main() {
    val config = lerConfig()
    val repositorio = criarRepositorio(config.arquivoBanco)
    val app = Aplicacao(config, repositorio)
    Servidor.iniciar(config.porta, app)

    println("==============================================")
    println(" Calculadora Penal rodando!")
    println(" Front-end: http://localhost:${config.porta}/")
    println(" API:       http://localhost:${config.porta}/api/calculo?pena_anos=5&data_inicio=2024-01-10&tipo_crime=comum&status=primario")
    println(" Contatos:  ${repositorio.descricao}")
    println(" Para encerrar: Ctrl+C")
    println("==============================================")
}

package penal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/*
 * Adaptador HTTP: liga a Aplicacao (Http.kt) ao servidor HTTP que já vem no
 * Java (com.sun.net.httpserver). Por isso o projeto não precisa de framework.
 */
object Servidor {

    /** Tamanho máximo do corpo de uma requisição (o formulário de contato é pequeno). */
    const val CORPO_MAX_BYTES = 8 * 1024

    private fun lerCorpo(ex: HttpExchange): String? {
        ex.requestBody.use { entrada ->
            val bytes = entrada.readNBytes(CORPO_MAX_BYTES + 1)
            return if (bytes.size > CORPO_MAX_BYTES) null else String(bytes, Charsets.UTF_8)
        }
    }

    private fun paraRequisicao(ex: HttpExchange, corpo: String) = Requisicao(
        metodo = ex.requestMethod,
        caminho = ex.requestURI.path,
        query = ex.requestURI.rawQuery,
        corpo = corpo,
        ip = ex.remoteAddress?.address?.hostAddress.orEmpty(),
        cabecalhos = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") },
    )

    private fun enviar(ex: HttpExchange, resp: Resposta) {
        val bytes = resp.corpo.toByteArray(Charsets.UTF_8)
        resp.cabecalhos.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
        if (bytes.isEmpty()) {
            ex.sendResponseHeaders(resp.status, -1)
        } else {
            ex.sendResponseHeaders(resp.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    /**
     * Log de acesso. Só o caminho de /api/calculo leva a query (não tem dado pessoal);
     * o corpo do POST de contatos e o cabeçalho Authorization nunca vão para o log.
     */
    private fun log(req: Requisicao, resp: Resposta) {
        val query = if (req.caminho.startsWith("/api/calculo") && req.query != null) "?${req.query}" else ""
        println("${req.metodo} ${req.caminho}$query -> ${resp.status}")
    }

    fun iniciar(porta: Int, app: Aplicacao): HttpServer {
        val servidor = HttpServer.create(InetSocketAddress(porta), 0)
        servidor.createContext("/") { ex ->
            try {
                val corpo = lerCorpo(ex)
                val req = paraRequisicao(ex, corpo.orEmpty())
                val resp = when {
                    corpo == null -> app.respostaJson(413, mapOf("erro" to "Requisição grande demais."))
                    else -> try {
                        app.atender(req)
                    } catch (e: Exception) {
                        // Erro inesperado: registra no console, mas não expõe detalhes
                        // internos (stack trace) para quem chamou a API.
                        System.err.println("Erro interno: ${e.javaClass.simpleName}: ${e.message}")
                        app.respostaJson(500, mapOf("erro" to "Erro interno do servidor"))
                    }
                }
                log(req, resp)
                enviar(ex, resp)
            } finally {
                ex.close()
            }
        }
        servidor.executor = Executors.newFixedThreadPool(8)
        servidor.start()
        return servidor
    }
}

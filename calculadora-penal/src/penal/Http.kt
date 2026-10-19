package penal

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Camada HTTP: rotas e handlers.
 *
 * Mesmo modelo da calculadora de trastes (inspirado no Ring): a requisição é
 * um objeto de dados e a resposta também. O servidor de verdade fica em
 * Servidor.kt; aqui não há rede, então os handlers são testados direto,
 * sem subir servidor nenhum.
 */

data class Requisicao(
    val metodo: String,
    val caminho: String,
    val query: String? = null,
    val corpo: String = "",
    val ip: String = "",
    /** Nomes dos cabeçalhos sempre em minúsculas. */
    val cabecalhos: Map<String, String> = emptyMap(),
)

data class Resposta(
    val status: Int,
    val cabecalhos: Map<String, String>,
    val corpo: String,
)

data class Config(
    val porta: Int = 3000,
    val corsOrigem: String = "*",
    val escritorio: String = "Cespedes Lourenço Advogados",
    val whatsappEscritorio: String = "5511989498044",
    /** Token para GET /api/contatos. Se for null, a rota fica desativada. */
    val tokenAdmin: String? = null,
    val arquivoBanco: String = "dados/contatos.db",
)

/**
 * Limite de envios por IP numa janela de tempo. Evita que o formulário de
 * contato seja usado para encher o banco com spam.
 */
class LimitadorDeTaxa(
    private val maximo: Int = 5,
    private val janela: Duration = Duration.ofMinutes(10),
    private val relogio: () -> Instant = Instant::now,
) {
    private val registros = HashMap<String, ArrayDeque<Instant>>()

    @Synchronized
    fun permitir(chave: String): Boolean {
        val agora = relogio()
        val limite = agora.minus(janela)
        registros.values.forEach { fila -> while (fila.isNotEmpty() && fila.first().isBefore(limite)) fila.removeFirst() }
        registros.values.removeIf { it.isEmpty() }
        val fila = registros.getOrPut(chave) { ArrayDeque() }
        if (fila.size >= maximo) return false
        fila.addLast(agora)
        return true
    }
}

fun decodificar(s: String): String = URLDecoder.decode(s, StandardCharsets.UTF_8)

/** "pena_anos=5&tipo_crime=comum" -> {"pena_anos": "5", "tipo_crime": "comum"} */
fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split("&")
        .mapNotNull { par ->
            val partes = par.split("=", limit = 2)
            val chave = runCatching { decodificar(partes[0]) }.getOrNull()
            val valor = runCatching { decodificar(partes.getOrElse(1) { "" }) }.getOrNull()
            if (chave.isNullOrBlank() || valor == null) null else chave to valor
        }
        .toMap()
}

class Aplicacao(
    private val config: Config = Config(),
    private val repositorio: RepositorioContatos = RepositorioMemoria(),
    private val hoje: () -> LocalDate = { LocalDate.now(ZoneId.of("America/Sao_Paulo")) },
    private val relogio: () -> Instant = Instant::now,
    private val limitador: LimitadorDeTaxa = LimitadorDeTaxa(relogio = relogio),
) {
    companion object {
        const val VERSAO = "1.0.0"

        val CABECALHOS_SEGURANCA = mapOf(
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
        )

        const val CSP =
            "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'self'"
    }

    private fun cabecalhosCors() = mapOf(
        "Access-Control-Allow-Origin" to config.corsOrigem,
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type",
    )

    fun respostaJson(status: Int, dados: Any?, extras: Map<String, String> = emptyMap()) = Resposta(
        status,
        CABECALHOS_SEGURANCA + cabecalhosCors() + mapOf("Content-Type" to "application/json; charset=utf-8") + extras,
        Json.paraJson(dados),
    )

    // ------------------------------------------------------------------------
    // Conversão do resultado em dados para o JSON
    // ------------------------------------------------------------------------

    private fun duracaoJson(d: Duracao, totalDias: Long) = mapOf(
        "anos" to d.anos, "meses" to d.meses, "dias" to d.dias,
        "texto" to d.toString(), "total_dias" to totalDias,
    )

    private fun marcoJson(m: Marco) = mapOf(
        "data" to m.data,
        "ja_atingido" to m.jaAtingido,
        "fracao" to m.fracao,
        "dias_necessarios" to m.diasNecessarios,
        "fundamento" to m.fundamento,
        "explicacao" to m.explicacao,
    )

    fun resultadoJson(r: Resultado): Map<String, Any?> {
        val p = r.entrada.perfil
        return mapOf(
            "entrada" to mapOf(
                "pena" to r.entrada.pena.toString(),
                "data_inicio" to r.entrada.dataInicio,
                "detracao" to r.entrada.detracao.toString(),
                "tipo_crime" to p.tipo,
                "status" to p.status,
                "violencia" to p.violencia,
                "resultado_morte" to p.resultadoMorte,
                "reincidencia_especifica" to p.reincidenciaEspecifica,
                "perfil" to "${p.tipo.rotulo} · ${p.status.rotulo}",
            ),
            "pena_total" to duracaoJson(r.entrada.pena, r.penaTotalDias),
            "detracao" to duracaoJson(r.entrada.detracao, r.detracaoDias),
            "pena_restante" to duracaoJson(r.penaRestante, r.penaRestanteDias),
            "data_base" to r.dataBase,
            "regime_semiaberto" to marcoJson(r.semiaberto),
            "regime_aberto" to marcoJson(r.aberto),
            "livramento_condicional" to (
                if (r.livramento.cabivel) mapOf("cabivel" to true) + marcoJson(r.livramento.marco!!)
                else mapOf(
                    "cabivel" to false,
                    "fracao" to r.livramento.fracao,
                    "fundamento" to r.livramento.fundamento,
                    "motivo" to r.livramento.motivo,
                )
                ),
            "termino_pena" to mapOf(
                "data" to r.termino,
                "limitado_a_40_anos" to r.terminoLimitadoA40Anos,
            ),
            "observacoes" to r.observacoes,
            "aviso" to Calculo.AVISO,
        )
    }

    // ------------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------------

    fun saude(@Suppress("UNUSED_PARAMETER") req: Requisicao) =
        respostaJson(200, mapOf("status" to "ok", "servico" to "calculadora-penal", "versao" to VERSAO))

    /** GET /api/calculo?pena_anos=5&data_inicio=2024-01-10&tipo_crime=comum&status=primario */
    fun calculo(req: Requisicao): Resposta =
        when (val v = Validacao.validarEntrada(parseQuery(req.query))) {
            is Validado.Erros -> respostaJson(400, mapOf("erro" to "Parâmetros inválidos", "detalhes" to v.mensagens))
            is Validado.Ok -> respostaJson(200, resultadoJson(Calculo.calcular(v.valor, hoje())))
        }

    /** GET /api/regras: a tabela de regras, para transparência e fins educativos. */
    fun regras(@Suppress("UNUSED_PARAMETER") req: Requisicao) = respostaJson(
        200,
        mapOf(
            "progressao" to Regras.progressao.map {
                mapOf("fracao" to "${it.percentual}%", "descricao" to it.descricao, "fundamento" to it.fundamento)
            },
            "livramento_condicional" to Regras.livramento.map {
                mapOf("fracao" to it.fracao, "descricao" to it.descricao, "fundamento" to it.fundamento)
            },
        ),
    )

    /** GET /api/config: dados públicos do escritório usados pelo front-end. */
    fun configPublica(@Suppress("UNUSED_PARAMETER") req: Requisicao) = respostaJson(
        200,
        mapOf("escritorio" to config.escritorio, "whatsapp" to config.whatsappEscritorio),
    )

    /** POST /api/contatos (corpo application/x-www-form-urlencoded). */
    fun novoContato(req: Requisicao): Resposta {
        val tipo = req.cabecalhos["content-type"].orEmpty().substringBefore(';').trim().lowercase()
        if (tipo != "application/x-www-form-urlencoded") {
            return respostaJson(415, mapOf("erro" to "Envie os dados como application/x-www-form-urlencoded."))
        }
        if (!limitador.permitir(req.ip)) {
            return respostaJson(
                429, mapOf("erro" to "Muitos envios em pouco tempo. Tente de novo em alguns minutos."),
                mapOf("Retry-After" to "600"),
            )
        }
        return when (val v = ValidacaoContato.validar(parseQuery(req.corpo))) {
            is Validado.Erros -> respostaJson(400, mapOf("erro" to "Dados inválidos", "detalhes" to v.mensagens))
            is Validado.Ok -> {
                val id = repositorio.salvar(v.valor, relogio())
                respostaJson(
                    201,
                    mapOf("id" to id, "mensagem" to "Recebemos seus dados. O escritório vai entrar em contato pelo WhatsApp."),
                )
            }
        }
    }

    private fun tokenConfere(req: Requisicao): Boolean {
        val esperado = config.tokenAdmin ?: return false
        val enviado = req.cabecalhos["authorization"].orEmpty().removePrefix("Bearer ").trim()
        // Comparação em tempo constante: não vaza, pelo tempo de resposta, quantos caracteres acertou.
        return MessageDigest.isEqual(enviado.toByteArray(), esperado.toByteArray())
    }

    /** GET /api/contatos (uso interno do escritório, exige Authorization: Bearer <ADMIN_TOKEN>). */
    fun listarContatos(req: Requisicao): Resposta {
        if (config.tokenAdmin == null) return naoEncontrado()
        if (!tokenConfere(req)) {
            return respostaJson(401, mapOf("erro" to "Não autorizado"), mapOf("WWW-Authenticate" to "Bearer"))
        }
        val lista = repositorio.listar()
        if (parseQuery(req.query)["formato"] == "csv") {
            return Resposta(
                200,
                CABECALHOS_SEGURANCA + mapOf(
                    "Content-Type" to "text/csv; charset=utf-8",
                    "Content-Disposition" to "attachment; filename=\"contatos.csv\"",
                ),
                Csv.contatos(lista),
            )
        }
        return respostaJson(
            200,
            mapOf("total" to lista.size, "contatos" to lista.map {
                mapOf(
                    "id" to it.id, "criado_em" to it.criadoEm.toString(), "nome" to it.contato.nome,
                    "whatsapp" to it.contato.whatsapp, "email" to it.contato.email,
                    "processo" to it.contato.processo, "resumo" to it.contato.resumo,
                )
            }),
        )
    }

    fun pagina(@Suppress("UNUSED_PARAMETER") req: Requisicao): Resposta {
        val html = javaClass.classLoader.getResource("public/index.html")?.readText(Charsets.UTF_8)
            ?: return respostaJson(404, mapOf("erro" to "Front-end não encontrado"))
        return Resposta(
            200,
            CABECALHOS_SEGURANCA + mapOf("Content-Type" to "text/html; charset=utf-8", "Content-Security-Policy" to CSP),
            html,
        )
    }

    private fun preflight() = Resposta(204, CABECALHOS_SEGURANCA + cabecalhosCors(), "")

    private fun naoEncontrado() = respostaJson(
        404,
        mapOf(
            "erro" to "Rota não encontrada",
            "rotas_disponiveis" to listOf(
                "GET /api/calculo?pena_anos=5&data_inicio=2024-01-10&tipo_crime=comum&status=primario",
                "GET /api/regras", "GET /api/config", "POST /api/contatos", "GET /api/saude", "GET /",
            ),
        ),
    )

    // ------------------------------------------------------------------------
    // Roteamento
    // ------------------------------------------------------------------------

    /** Tabela de rotas: (método, caminho) -> handler. */
    private val rotas: Map<Pair<String, String>, (Requisicao) -> Resposta> = mapOf(
        ("GET" to "/") to ::pagina,
        ("GET" to "/index.html") to ::pagina,
        ("GET" to "/api/saude") to ::saude,
        ("GET" to "/api/calculo") to ::calculo,
        ("GET" to "/api/regras") to ::regras,
        ("GET" to "/api/config") to ::configPublica,
        ("POST" to "/api/contatos") to ::novoContato,
        ("GET" to "/api/contatos") to ::listarContatos,
    )

    /** Função principal: recebe uma requisição, devolve uma resposta. */
    fun atender(req: Requisicao): Resposta {
        val caminho = if (req.caminho.length > 1) req.caminho.trimEnd('/') else req.caminho
        val metodo = req.metodo.uppercase()
        val handler = rotas[metodo to caminho]
        val metodosDoCaminho = rotas.keys.filter { it.second == caminho }.map { it.first }
        return when {
            metodo == "OPTIONS" -> preflight()
            handler != null -> handler(req.copy(caminho = caminho))
            metodosDoCaminho.isNotEmpty() -> respostaJson(
                405,
                mapOf("erro" to "Método $metodo não permitido. Use ${metodosDoCaminho.joinToString(" ou ")}."),
                mapOf("Allow" to metodosDoCaminho.joinToString(", ")),
            )
            else -> naoEncontrado()
        }
    }
}

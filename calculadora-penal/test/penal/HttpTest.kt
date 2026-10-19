package penal

import java.time.Instant
import java.time.LocalDate

private const val TOKEN = "token-de-teste-com-32-caracteres!"
private const val FORM = "application/x-www-form-urlencoded"

private fun novaApp(repo: RepositorioContatos = RepositorioMemoria(), token: String? = TOKEN) = Aplicacao(
    config = Config(tokenAdmin = token),
    repositorio = repo,
    hoje = { LocalDate.of(2026, 10, 19) },
    relogio = { Instant.parse("2026-10-19T14:00:00Z") },
)

private fun get(caminho: String, query: String? = null, cabecalhos: Map<String, String> = emptyMap()) =
    Requisicao("GET", caminho, query, cabecalhos = cabecalhos)

private fun post(corpo: String, ip: String = "10.0.0.1", tipo: String = FORM) =
    Requisicao("POST", "/api/contatos", corpo = corpo, ip = ip, cabecalhos = mapOf("content-type" to tipo))

private const val CONTATO_OK =
    "nome=Jo%C3%A3o+de+Souza&whatsapp=%2811%29+98765-4321&consentimento=true&resumo=Semiaberto+em+17%2F12%2F2024"

val testesHttp = listOf(

    teste("GET /api/calculo devolve as datas em JSON") {
        val r = novaApp().atender(get("/api/calculo", "pena_anos=6&data_inicio=2024-01-01&tipo_crime=comum&status=primario"))
        igual(200, r.status)
        verdadeiro(r.cabecalhos["Content-Type"]!!.startsWith("application/json"), "content-type JSON")
        contem("\"regime_semiaberto\":{\"data\":\"2024-12-17\"", r.corpo)
        contem("\"regime_aberto\":{\"data\":\"2025-10-08\"", r.corpo)
        contem("\"livramento_condicional\":{\"cabivel\":true,\"data\":\"2026-01-01\"", r.corpo)
        contem("\"fracao\":\"16%\"", r.corpo)
    },

    teste("Erros de entrada voltam 400 com a lista de mensagens") {
        val r = novaApp().atender(get("/api/calculo", "pena_anos=abc&data_inicio=ontem"))
        igual(400, r.status)
        contem("\"detalhes\":[", r.corpo)
        contem("inválid", r.corpo)
    },

    teste("Rotas, barra final, 404, 405 e preflight CORS") {
        val app = novaApp()
        igual(200, app.atender(get("/api/saude")).status)
        igual(200, app.atender(get("/api/regras")).status)
        igual(200, app.atender(get("/api/config")).status)
        igual(200, app.atender(get("/api/saude/")).status, "aceita barra final")
        igual(404, app.atender(get("/nao-existe")).status)
        val r405 = app.atender(Requisicao("DELETE", "/api/calculo"))
        igual(405, r405.status)
        igual("GET", r405.cabecalhos["Allow"])
        igual(204, app.atender(Requisicao("OPTIONS", "/api/calculo")).status)
    },

    teste("Cabeçalhos de segurança em todas as respostas") {
        val r = novaApp().atender(get("/nao-existe"))
        igual("nosniff", r.cabecalhos["X-Content-Type-Options"])
        igual("no-store", r.cabecalhos["Cache-Control"])
    },

    teste("POST /api/contatos grava o contato e responde 201") {
        val repo = RepositorioMemoria()
        val r = novaApp(repo).atender(post(CONTATO_OK))
        igual(201, r.status)
        igual(1, repo.listar().size)
        igual("João de Souza", repo.listar()[0].contato.nome)
        igual("+5511987654321", repo.listar()[0].contato.whatsapp)
    },

    teste("POST /api/contatos: 400 sem consentimento, 415 com outro Content-Type") {
        val app = novaApp()
        igual(400, app.atender(post(CONTATO_OK.replace("consentimento=true", "consentimento=false"))).status)
        igual(415, app.atender(post(CONTATO_OK, tipo = "application/json")).status)
    },

    teste("POST /api/contatos: limite de 5 envios por IP em 10 minutos") {
        val app = novaApp()
        repeat(5) { igual(201, app.atender(post(CONTATO_OK, ip = "10.0.0.9")).status, "envio ${it + 1}") }
        val bloqueado = app.atender(post(CONTATO_OK, ip = "10.0.0.9"))
        igual(429, bloqueado.status)
        igual("600", bloqueado.cabecalhos["Retry-After"])
        igual(201, app.atender(post(CONTATO_OK, ip = "10.0.0.10")).status, "outro IP continua liberado")
    },

    teste("GET /api/contatos exige token; sem token configurado a rota nem existe") {
        val repo = RepositorioMemoria()
        val app = novaApp(repo)
        app.atender(post(CONTATO_OK))
        igual(401, app.atender(get("/api/contatos")).status)
        igual(401, app.atender(get("/api/contatos", cabecalhos = mapOf("authorization" to "Bearer errado"))).status)
        val ok = app.atender(get("/api/contatos", cabecalhos = mapOf("authorization" to "Bearer $TOKEN")))
        igual(200, ok.status)
        contem("\"total\":1", ok.corpo)
        val csv = app.atender(get("/api/contatos", "formato=csv", mapOf("authorization" to "Bearer $TOKEN")))
        verdadeiro(csv.cabecalhos["Content-Type"]!!.startsWith("text/csv"), "CSV")
        contem("João de Souza", csv.corpo)
        igual(404, novaApp(token = null).atender(get("/api/contatos")).status)
    },

    teste("parseQuery decodifica %XX e ignora pares sem nome") {
        igual(mapOf("a" to "1", "nome" to "João Silva"), parseQuery("a=1&nome=Jo%C3%A3o+Silva&=x"))
        igual(emptyMap(), parseQuery(null))
        igual(mapOf("ok" to "1"), parseQuery("ok=1&ruim=%ZZ"), "sequência % inválida é descartada")
    },

    teste("JSON: escapa aspas, barra e < > (evita HTML dentro do JSON)") {
        igual("{\"a\":1,\"b\":[true,null,\"x\"]}", Json.paraJson(mapOf("a" to 1, "b" to listOf(true, null, "x"))))
        igual("\"aspas \\\" e barra \\\\\"", Json.paraJson("aspas \" e barra \\"))
        igual("\"\\u003cscript\\u003e\"", Json.paraJson("<script>"))
        igual("\"2024-01-01\"", Json.paraJson(LocalDate.of(2024, 1, 1)))
    },

    teste("Config: token curto é recusado, porta e WhatsApp vêm do ambiente") {
        val c = lerConfig(mapOf("PORT" to "8080", "ADMIN_TOKEN" to "curto", "WHATSAPP_ESCRITORIO" to "+55 11 91234-5678")::get)
        igual(8080, c.porta)
        igual(null, c.tokenAdmin)
        igual("5511912345678", c.whatsappEscritorio)
    },
)

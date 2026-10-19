package penal

import java.math.BigInteger
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/*
 * Captação de contatos (pedido do escritório parceiro).
 *
 * Depois de ver o resultado, o usuário pode deixar nome e WhatsApp para ser
 * atendido. São dados pessoais (LGPD), então:
 *  - só os campos necessários são obrigatórios, e o consentimento é explícito;
 *  - todo campo é validado e tem tamanho máximo;
 *  - o banco é acessado só com PreparedStatement (sem SQL montado com texto);
 *  - a listagem é protegida por token e não aparece nos logs.
 */

data class Contato(
    val nome: String,
    val whatsapp: String,       // normalizado: +55DDNNNNNNNNN
    val email: String?,
    val processo: String?,      // formato CNJ: NNNNNNN-DD.AAAA.J.TR.OOOO
    val resumo: String?,        // resumo do cálculo, montado pelo front-end
)

data class ContatoSalvo(val id: Long, val criadoEm: Instant, val contato: Contato)

object ValidacaoContato {

    const val NOME_MAX = 120
    const val EMAIL_MAX = 254
    const val RESUMO_MAX = 500

    private val regexNome = Regex("^[\\p{L}][\\p{L}'. -]*[\\p{L}.]$")
    private val regexEmail = Regex("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]+\\.[^\\s@<>\"]{2,}$")
    private val regexCnj = Regex("^(\\d{7})-(\\d{2})\\.(\\d{4})\\.(\\d)\\.(\\d{2})\\.(\\d{4})$")

    /** Remove caracteres de controle e espaços repetidos. */
    fun limpar(s: String?): String =
        s.orEmpty().replace(Regex("\\p{Cntrl}"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Normaliza um WhatsApp brasileiro para +55DDNNNNNNNNN.
     * Aceita "(11) 98949-8044", "11989498044", "+55 11 98949-8044"...
     * Devolve null se não for um número válido.
     */
    fun normalizarWhatsapp(texto: String?): String? {
        val bruto = texto.orEmpty().trim()
        if (bruto.length > 25 || !Regex("^[+\\d ().-]+$").matches(bruto)) return null
        var digitos = bruto.filter(Char::isDigit)
        if (digitos.length in 12..13 && digitos.startsWith("55")) digitos = digitos.drop(2)
        if (digitos.length !in 10..11) return null
        val ddd = digitos.take(2).toInt()
        if (ddd < 11 || digitos[0] == '0' || digitos[1] == '0') return null
        if (digitos.length == 11 && digitos[2] != '9') return null   // celular começa com 9
        return "+55$digitos"
    }

    /**
     * Confere o dígito verificador do número do processo no padrão CNJ
     * (Resolução CNJ 65/2008): NNNNNNN DD AAAA J TR OOOO, módulo 97.
     * Aceita com ou sem pontuação e devolve o número formatado.
     */
    fun normalizarProcesso(texto: String?): String? {
        val s = texto.orEmpty().trim()
        if (s.length > 30) return null
        val formatado = if (Regex("^\\d{20}$").matches(s)) {
            "${s.substring(0, 7)}-${s.substring(7, 9)}.${s.substring(9, 13)}.${s[13]}." +
                "${s.substring(14, 16)}.${s.substring(16, 20)}"
        } else s
        val m = regexCnj.matchEntire(formatado) ?: return null
        val (n, dd, aaaa, j, tr, oooo) = m.destructured
        val resto = BigInteger(n + aaaa + j + tr + oooo + dd).mod(BigInteger.valueOf(97))
        return if (resto == BigInteger.ONE) formatado else null
    }

    fun validar(params: Map<String, String>): Validado<Contato> {
        val erros = mutableListOf<String>()

        val nome = limpar(params["nome"])
        when {
            nome.isEmpty() -> erros += "Informe o nome completo."
            nome.length > NOME_MAX -> erros += "O nome deve ter no máximo $NOME_MAX caracteres."
            !regexNome.matches(nome) -> erros += "O nome deve conter apenas letras."
            nome.split(' ').count { it.length >= 2 } < 2 -> erros += "Informe o nome completo (nome e sobrenome)."
        }

        val whatsappBruto = params["whatsapp"].orEmpty().trim()
        val whatsapp = normalizarWhatsapp(whatsappBruto)
        when {
            whatsappBruto.isEmpty() -> erros += "Informe o número de WhatsApp com DDD."
            whatsapp == null -> erros += "WhatsApp inválido. Use DDD + número, por exemplo (11) 98765-4321."
        }

        val email = limpar(params["email"]).ifEmpty { null }
        if (email != null && (email.length > EMAIL_MAX || !regexEmail.matches(email))) {
            erros += "E-mail inválido."
        }

        val processoBruto = limpar(params["processo"]).ifEmpty { null }
        val processo = processoBruto?.let { normalizarProcesso(it) }
        if (processoBruto != null && processo == null) {
            erros += "Número do processo inválido. Use o formato NNNNNNN-DD.AAAA.J.TR.OOOO."
        }

        if (!Validacao.booleano(params["consentimento"])) {
            erros += "É preciso autorizar o contato do escritório para enviar os dados."
        }

        val resumo = limpar(params["resumo"]).take(RESUMO_MAX).ifEmpty { null }

        return if (erros.isEmpty()) Validado.Ok(Contato(nome, whatsapp!!, email?.lowercase(), processo, resumo))
        else Validado.Erros(erros)
    }
}

// ---------------------------------------------------------------------------
// Persistência
// ---------------------------------------------------------------------------

interface RepositorioContatos {
    val descricao: String
    fun salvar(contato: Contato, quando: Instant): Long
    fun listar(): List<ContatoSalvo>
}

/** Usado nos testes e quando o driver do SQLite não está disponível. */
class RepositorioMemoria : RepositorioContatos {
    override val descricao = "memória (os contatos se perdem ao encerrar)"
    private val itens = mutableListOf<ContatoSalvo>()

    @Synchronized
    override fun salvar(contato: Contato, quando: Instant): Long {
        val id = itens.size + 1L
        itens += ContatoSalvo(id, quando, contato)
        return id
    }

    @Synchronized
    override fun listar(): List<ContatoSalvo> = itens.toList()
}

/**
 * Banco SQLite acessado por JDBC (java.sql, que já vem no Java).
 * O driver (sqlite-jdbc) é carregado em tempo de execução a partir da pasta lib/.
 */
class RepositorioSqlite(private val arquivo: String) : RepositorioContatos {
    override val descricao = "SQLite ($arquivo)"
    private val url = "jdbc:sqlite:$arquivo"

    init {
        conectar().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS contatos (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        criado_em  TEXT    NOT NULL,
                        nome       TEXT    NOT NULL,
                        whatsapp   TEXT    NOT NULL,
                        email      TEXT,
                        processo   TEXT,
                        resumo     TEXT
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun conectar(): Connection = DriverManager.getConnection(url)

    @Synchronized
    override fun salvar(contato: Contato, quando: Instant): Long = conectar().use { c ->
        c.prepareStatement(
            "INSERT INTO contatos (criado_em, nome, whatsapp, email, processo, resumo) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.setString(1, quando.toString())
            st.setString(2, contato.nome)
            st.setString(3, contato.whatsapp)
            st.setString(4, contato.email)
            st.setString(5, contato.processo)
            st.setString(6, contato.resumo)
            st.executeUpdate()
        }
        c.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    @Synchronized
    override fun listar(): List<ContatoSalvo> = conectar().use { c ->
        c.prepareStatement(
            "SELECT id, criado_em, nome, whatsapp, email, processo, resumo FROM contatos ORDER BY id DESC"
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ContatoSalvo(
                                rs.getLong(1), Instant.parse(rs.getString(2)),
                                Contato(rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun driverDisponivel(): Boolean = try {
            Class.forName("org.sqlite.JDBC"); true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}

/** Usa SQLite se o driver estiver na pasta lib/; senão, guarda em memória e avisa. */
fun criarRepositorio(arquivo: String): RepositorioContatos =
    if (RepositorioSqlite.driverDisponivel()) {
        java.io.File(arquivo).absoluteFile.parentFile?.mkdirs()
        RepositorioSqlite(arquivo)
    } else {
        System.err.println("AVISO: driver do SQLite não encontrado em lib/. Contatos ficarão só em memória.")
        RepositorioMemoria()
    }

// ---------------------------------------------------------------------------
// Exportação em CSV (para o escritório abrir no Excel)
// ---------------------------------------------------------------------------

object Csv {
    /**
     * Protege contra "CSV injection": uma célula que começa com = + - @ vira
     * fórmula no Excel. Prefixar com apóstrofo faz o Excel tratar como texto.
     */
    fun celula(valor: String?): String {
        var v = valor.orEmpty()
        if (v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    /** CSV com ";" (padrão do Excel em pt-BR) e BOM, para os acentos abrirem certo. */
    fun contatos(lista: List<ContatoSalvo>): String = buildString {
        append('﻿')
        append("id;criado_em;nome;whatsapp;email;processo;resumo\r\n")
        for (c in lista) {
            append(
                listOf(
                    c.id.toString(), c.criadoEm.toString(), c.contato.nome, c.contato.whatsapp,
                    c.contato.email, c.contato.processo, c.contato.resumo,
                ).joinToString(";") { celula(it) }
            )
            append("\r\n")
        }
    }
}

package penal

import java.time.LocalDate

private val HOJE = LocalDate.of(2026, 10, 19)

private fun entrada(
    anos: Int, meses: Int = 0, dias: Int = 0,
    inicio: String = "2024-01-01",
    detracao: Duracao = Duracao(),
    tipo: TipoCrime = TipoCrime.COMUM,
    status: Status = Status.PRIMARIO,
    violencia: Boolean = false,
    morte: Boolean = false,
    especifica: Boolean = false,
) = Entrada(
    Duracao(anos, meses, dias), LocalDate.parse(inicio), detracao,
    Perfil(tipo, status, violencia, morte, especifica),
)

private fun percentual(perfil: Perfil) = Regras.regraProgressao(perfil).percentual

val testesCalculo = listOf(

    teste("Regras do slide 25: comum + primário = 16%, hediondo + primário = 40%") {
        igual(16, percentual(Perfil(TipoCrime.COMUM, Status.PRIMARIO)))
        igual(40, percentual(Perfil(TipoCrime.HEDIONDO, Status.PRIMARIO)))
    },

    teste("Todas as frações do art. 112 da LEP") {
        val c = TipoCrime.COMUM
        val h = TipoCrime.HEDIONDO
        val p = Status.PRIMARIO
        val r = Status.REINCIDENTE
        igual(16, percentual(Perfil(c, p)), "I")
        igual(20, percentual(Perfil(c, r)), "II")
        igual(25, percentual(Perfil(c, p, violencia = true)), "III")
        igual(30, percentual(Perfil(c, r, violencia = true)), "IV")
        igual(40, percentual(Perfil(h, p)), "V")
        igual(50, percentual(Perfil(h, p, resultadoMorte = true)), "VI a")
        igual(60, percentual(Perfil(h, r, reincidenciaEspecifica = true)), "VII")
        igual(70, percentual(Perfil(h, r, resultadoMorte = true, reincidenciaEspecifica = true)), "VIII")
    },

    teste("Reincidente não específico em hediondo segue a fração do primário (STJ, Tema 1084)") {
        igual(40, percentual(Perfil(TipoCrime.HEDIONDO, Status.REINCIDENTE)))
        igual(50, percentual(Perfil(TipoCrime.HEDIONDO, Status.REINCIDENTE, resultadoMorte = true)))
    },

    teste("Opções que não se aplicam ao tipo de crime são ignoradas") {
        // "violência" só muda a fração de crime comum; "resultado morte", só a de hediondo.
        igual(40, percentual(Perfil(TipoCrime.HEDIONDO, Status.PRIMARIO, violencia = true)))
        igual(16, percentual(Perfil(TipoCrime.COMUM, Status.PRIMARIO, resultadoMorte = true)))
        igual(20, percentual(Perfil(TipoCrime.COMUM, Status.REINCIDENTE, reincidenciaEspecifica = true)))
    },

    teste("Frações do livramento condicional (art. 83 do CP)") {
        igual("1/3", Regras.regraLivramento(Perfil(TipoCrime.COMUM, Status.PRIMARIO)).fracao)
        igual("1/2", Regras.regraLivramento(Perfil(TipoCrime.COMUM, Status.REINCIDENTE)).fracao)
        igual("2/3", Regras.regraLivramento(Perfil(TipoCrime.HEDIONDO, Status.PRIMARIO)).fracao)
        igual("2/3", Regras.regraLivramento(Perfil(TipoCrime.HEDIONDO, Status.REINCIDENTE)).fracao)
        igual("vedado", Regras.regraLivramento(Perfil(TipoCrime.HEDIONDO, Status.REINCIDENTE, reincidenciaEspecifica = true)).fracao)
        igual("vedado", Regras.regraLivramento(Perfil(TipoCrime.HEDIONDO, Status.PRIMARIO, resultadoMorte = true)).fracao)
    },

    teste("Arredondamento: 'ao menos X%' arredonda para cima; 'mais de 1/3' soma um dia") {
        igual(351L, Calculo.diasParaPercentual(2192, 16))     // 350,72 -> 351
        igual(16L, Calculo.diasParaPercentual(100, 16))       // exato
        igual(731L, Calculo.diasParaMaisDe(2192, 1, 3))      // 730,67 -> mais de 730
        igual(1001L, Calculo.diasParaMaisDe(3000, 1, 3))     // exato 1000 -> 1001
    },

    teste("Exemplo do slide 14: pena de 5 anos com 6 meses de detração -> restam 4 anos e 6 meses") {
        val r = Calculo.calcular(entrada(5, detracao = Duracao(0, 6, 0)), HOJE)
        igual(Duracao(4, 6, 0), r.penaRestante)
        igual("4 anos e 6 meses", r.penaRestante.toString())
        // As datas usam o calendário: a data-base recua 6 meses e o término cai 4 anos e meio depois do início.
        igual(LocalDate.of(2023, 7, 1), r.dataBase)
        igual(LocalDate.of(2028, 7, 1), r.termino)
    },

    teste("Caso completo: 6 anos, início em 01/01/2024, comum, primário, sem detração") {
        val r = Calculo.calcular(entrada(6), HOJE)
        igual(2192L, r.penaTotalDias, "6 anos com 2 bissextos (2024 e 2028)")
        igual(351L, r.semiaberto.diasNecessarios)
        igual(LocalDate.of(2024, 12, 17), r.semiaberto.data)
        igual(295L, r.aberto.diasNecessarios, "16% do saldo de 1841 dias")
        igual(LocalDate.of(2025, 10, 8), r.aberto.data)
        igual(LocalDate.of(2026, 1, 1), r.livramento.marco!!.data, "mais de 1/3 = 731 dias")
        igual(LocalDate.of(2030, 1, 1), r.termino)
        verdadeiro(r.semiaberto.jaAtingido && r.aberto.jaAtingido, "datas antes de 'hoje' aparecem como atingidas")
    },

    teste("Caso hediondo: 8 anos, início em 10/03/2025, primário, 1 ano de detração") {
        val r = Calculo.calcular(
            entrada(8, inicio = "2025-03-10", detracao = Duracao(1), tipo = TipoCrime.HEDIONDO), HOJE,
        )
        igual(2922L, r.penaTotalDias)
        igual(365L, r.detracaoDias, "10/03/2024 -> 10/03/2025")
        igual(1169L, r.semiaberto.diasNecessarios, "40% de 2922 = 1168,8")
        igual(LocalDate.of(2027, 5, 23), r.semiaberto.data)
        igual(702L, r.aberto.diasNecessarios, "40% do saldo de 1753 = 701,2")
        igual(LocalDate.of(2029, 4, 24), r.aberto.data)
        igual(LocalDate.of(2029, 7, 11), r.livramento.marco!!.data, "mais de 2/3 = 1949 dias")
        verdadeiro(!r.semiaberto.jaAtingido, "data futura não está atingida")
    },

    teste("Livramento não cabe para pena menor que 2 anos") {
        val r = Calculo.calcular(entrada(1, 11, 30), HOJE)
        verdadeiro(!r.livramento.cabivel, "pena < 2 anos")
        contem("2 anos", r.livramento.motivo!!)
        verdadeiro(Calculo.calcular(entrada(2), HOJE).livramento.cabivel, "pena de exatamente 2 anos admite")
    },

    teste("Livramento vedado para hediondo com resultado morte") {
        val r = Calculo.calcular(entrada(20, tipo = TipoCrime.HEDIONDO, morte = true), HOJE)
        verdadeiro(!r.livramento.cabivel, "vedado")
        igual(50, Regras.regraProgressao(r.entrada.perfil).percentual)
    },

    teste("Término limitado a 40 anos (art. 75 do CP), frações sobre a pena total (Súmula 715)") {
        val r = Calculo.calcular(entrada(60, tipo = TipoCrime.HEDIONDO), HOJE)
        verdadeiro(r.terminoLimitadoA40Anos, "60 anos é limitado")
        igual(LocalDate.of(2064, 1, 1), r.termino)
        igual(Calculo.diasParaPercentual(r.penaTotalDias, 40), r.semiaberto.diasNecessarios)
        verdadeiro(r.observacoes.any { it.contains("40 anos") }, "observação do limite")
    },

    teste("Ordem dos marcos: semiaberto <= aberto <= término, para vários perfis") {
        for (tipo in TipoCrime.entries) for (status in Status.entries) for (anos in listOf(2, 5, 12, 35)) {
            val r = Calculo.calcular(entrada(anos, 3, 7, tipo = tipo, status = status), HOJE)
            verdadeiro(!r.aberto.data.isBefore(r.semiaberto.data), "aberto depois do semiaberto ($tipo $status $anos)")
            verdadeiro(!r.termino.isBefore(r.aberto.data), "término depois do aberto ($tipo $status $anos)")
        }
    },

    teste("Texto das durações") {
        igual("2 anos, 3 meses e 10 dias", Duracao(2, 3, 10).toString())
        igual("1 ano e 1 dia", Duracao(1, 0, 1).toString())
        igual("6 meses", Duracao(0, 6, 0).toString())
        igual("0 dias", Duracao().toString())
        igual(Duracao(2, 2, 20), Calculo.subtrair(Duracao(2, 3, 10), Duracao(0, 0, 20)), "empresta um mês")
    },
)

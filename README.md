# Calculadora Penal

**Projeto de Extensão — Desenvolvimento de Aplicativos com a Linguagem Kotlin**
Instituto Mauá de Tecnologia · Prof. Dr. Aparecido V. de Freitas
Empresa parceira: **Cespedes Lourenço Advogados**

---

## 1. Componentes do grupo

| Nome | RA |
|---|---|
| Leonardo Souza Olivieri | |

---

## 2. Descrição geral do projeto

A execução penal brasileira segue uma legislação complexa, alterada recentemente pela **Lei 13.964/19 (Pacote Anticrime)**. Para o cidadão leigo, principalmente para familiares de apenados, é difícil entender quando a pessoa poderá pedir a **progressão de regime** ou o **livramento condicional**. Essa falta de informação gera angústia e atrapalha o planejamento da ressocialização.

Este projeto é uma **aplicação web escrita em Kotlin** (API REST + front-end) que recebe:

- a pena total (anos, meses e dias);
- a data de início da pena;
- o tempo de detração;
- o tipo de crime (comum ou hediondo/equiparado);
- o status do apenado (primário ou reincidente);

e devolve, de forma clara e educativa:

- a data de progressão para o **regime semiaberto**;
- a data de progressão para o **regime aberto**;
- a data estimada de **livramento condicional** (ou o motivo de não ser cabível);
- a data de término da pena;
- a fração aplicada, o fundamento legal e uma explicação em linguagem simples de cada data.

**Público-alvo:** familiares de apenados, cidadãos leigos e estudantes de Direito (nível introdutório).

**Caráter extensionista:** a ferramenta leva informação jurídica à comunidade de forma gratuita. Ela também serve como canal de captação de contatos para o escritório parceiro. Depois do resultado, o usuário vê o botão **"Quero falar com um advogado"** (WhatsApp) e um formulário para pedir contato.

### Regras de cálculo

As regras ficam em `src/penal/Regras.kt`, no formato SE-ENTÃO pedido na especificação. Vale a primeira regra cuja condição for verdadeira.

**Progressão de regime: art. 112 da LEP (red. Lei 13.964/19)**

| SE | ENTÃO | Fundamento |
|---|---|---|
| comum, primário, sem violência ou grave ameaça | 16% | art. 112, I |
| comum, reincidente, sem violência ou grave ameaça | 20% | art. 112, II |
| comum, primário, com violência ou grave ameaça | 25% | art. 112, III |
| comum, reincidente, com violência ou grave ameaça | 30% | art. 112, IV |
| hediondo, sem reincidência específica | 40% | art. 112, V |
| hediondo com resultado morte, sem reincidência específica | 50% | art. 112, VI, a |
| hediondo, reincidente específico | 60% | art. 112, VII |
| hediondo com resultado morte, reincidente específico | 70% | art. 112, VIII |

O reincidente em crime comum que agora foi condenado por crime hediondo segue as frações do primário (40% ou 50%), conforme a tese do STJ no Tema 1084.

**Livramento condicional: art. 83 do CP**

| SE | ENTÃO |
|---|---|
| hediondo com resultado morte | vedado (art. 112, VI, a, e VIII, da LEP) |
| hediondo, reincidente específico | vedado (art. 83, V) |
| hediondo | mais de 2/3 da pena (art. 83, V) |
| comum, reincidente | mais de 1/2 da pena (art. 83, II) |
| comum, primário | mais de 1/3 da pena (art. 83, I) |
| pena menor que 2 anos | não cabível (art. 83, caput) |

**Como as datas são calculadas**

1. A pena vira dias pelo **calendário comum**, contando a partir da data de início (art. 10 do CP). Anos bissextos e meses de 28 a 31 dias entram corretamente.
2. A detração é tempo já cumprido, então ela recua a data-base: `data-base = início − detração`.
3. **Semiaberto** = data-base + fração × pena total.
4. **Aberto** = data do semiaberto + fração × **saldo** de pena. A segunda progressão é contada sobre o que falta cumprir, não sobre a pena inteira.
5. **Livramento** = data-base + mais de 1/3, 1/2 ou 2/3 da pena total.
6. **Término** = data-base + pena total, limitado a 40 anos (art. 75 do CP). As frações continuam calculadas sobre a pena total (Súmula 715 do STF).

Arredondamentos: "ao menos 16%" arredonda os dias para cima, e "mais de 1/3" soma um dia à fração exata.

**Exemplo (slide 14 da especificação):** pena de 5 anos com 6 meses de detração. Restam 4 anos e 6 meses a cumprir.

> As datas indicam quando o requisito de **tempo** é atingido. O benefício também depende de bom comportamento e de decisão do juiz da execução. O sistema avisa isso em todo resultado e não substitui a análise de um advogado.

---

## 3. Arquitetura do sistema

O código segue a mesma organização da Calculadora de Trastes (Clojure). As regras e o cálculo ficam isolados em **funções puras**, e os efeitos colaterais (rede, banco, console) ficam só nas bordas do sistema.

```
      Navegador (front-end web)                  Site do escritório / outro cliente
                 |                                              |
                 |   GET /api/calculo?...   POST /api/contatos  |
                 v                                              v
   +---------------------------------------------------------------------------+
   |  Servidor.kt   Adaptador HTTP (servidor embutido no Java)                 |
   |                HttpExchange <-> Requisicao/Resposta, limite de corpo, log |
   +---------------------------------------------------------------------------+
   |  Http.kt       Rotas e handlers (Requisicao -> Resposta)                  |
   |                CORS, cabeçalhos de segurança, códigos HTTP, token admin   |
   +-----------------------------+---------------------------------------------+
   |  Validacao.kt               |  Contatos.kt           |  Json.kt          |
   |  entrada do cálculo         |  validação + LGPD      |  gera o JSON      |
   |                             |  SQLite via JDBC       |                   |
   +-----------------------------+------------------------+-------------------+
   |  Calculo.kt    Funções puras: dias, frações, datas, observações          |
   +---------------------------------------------------------------------------+
   |  Regras.kt     Tabela de regras SE-ENTÃO (art. 112 LEP e art. 83 CP)      |
   +---------------------------------------------------------------------------+
```

| Arquivo | Responsabilidade |
|---|---|
| `src/penal/Regras.kt` | Tabelas de regras jurídicas: o único arquivo a mudar quando chegar o documento de regras do escritório |
| `src/penal/Calculo.kt` | Cálculo das datas (100% funções puras; "hoje" chega por parâmetro) |
| `src/penal/Validacao.kt` | Conversão e validação dos parâmetros de entrada |
| `src/penal/Contatos.kt` | Validação do contato (WhatsApp, e-mail, nº CNJ), repositório SQLite e exportação CSV |
| `src/penal/Json.kt` | Geração de JSON, sem bibliotecas externas |
| `src/penal/Http.kt` | Tabela de rotas e handlers, limitador de envios por IP |
| `src/penal/Servidor.kt` | Liga a aplicação ao servidor HTTP do Java |
| `src/penal/Main.kt` | Ponto de entrada e leitura das variáveis de ambiente |
| `resources/public/index.html` | Front-end web que consome a API |
| `test/penal/*.kt` | Testes automatizados |
| `executar.ps1` / `executar.sh` | Compilar, testar e rodar (Windows / Linux e macOS) |

**Decisão de projeto: nenhum framework.** O servidor HTTP é o `com.sun.net.httpserver`, que já vem no Java. O JSON é gerado por uma função própria, e o banco é acessado pelo `java.sql` (JDBC), também do Java. A única peça externa é o **driver do SQLite**, carregado em tempo de execução a partir da pasta `lib/`. O script de execução baixa o driver na primeira vez. Sem ele, o sistema continua funcionando e guarda os contatos só em memória. Isso traz três vantagens:

- o projeto compila só com o `kotlinc`, o mesmo compilador usado nas aulas;
- a superfície de ataque é menor, porque há menos dependências de terceiros para manter atualizadas;
- todo o código pode ser lido e explicado pelo grupo.

---

## 4. Fluxo operacional

1. O usuário preenche a pena, a data de início, a detração, o tipo de crime e o status. O front-end mostra só as perguntas extras que fazem sentido: violência para crime comum, resultado morte para hediondo e reincidência específica para hediondo + reincidente.
2. O navegador faz `GET /api/calculo?pena_anos=8&data_inicio=2025-03-10&detracao_anos=1&tipo_crime=hediondo&status=primario`.
3. `Servidor` converte a requisição Java em uma `Requisicao` e chama `Aplicacao.atender`.
4. `atender` consulta a tabela de rotas e chama o handler `calculo`.
5. `Validacao.validarEntrada` converte os textos e confere os limites:
   - se a entrada for inválida, a API responde **HTTP 400** com a lista de erros;
   - se for válida, segue para o cálculo.
6. `Regras` escolhe a fração de progressão e a de livramento, e `Calculo.calcular` gera as datas e as observações.
7. A resposta volta em JSON com **HTTP 200**. O front-end monta a linha do tempo, marca as datas já atingidas e conta os dias que faltam para as próximas.
8. Na tela de resultado, o usuário pode:
   - tocar em **"Quero falar com um advogado"**, que abre o WhatsApp do escritório com o resumo do cálculo já escrito;
   - preencher o formulário de contato, que faz `POST /api/contatos` e grava os dados no SQLite.
9. O escritório consulta os contatos em `GET /api/contatos` (JSON ou CSV para o Excel), com o token de acesso.

---

## 5. Funcionalidades principais

- Cálculo das datas de **semiaberto, aberto, livramento condicional e término da pena**, com as frações do art. 112 da LEP pós-Pacote Anticrime e do art. 83 do CP.
- Contagem pelo calendário (bissextos e meses de tamanhos diferentes), detração e fração da segunda progressão sobre o saldo de pena.
- Casos especiais tratados:
  - livramento vedado (hediondo com morte e reincidente específico);
  - pena menor que 2 anos;
  - limite de 40 anos;
  - Tema 1084 do STJ.
- **Resultado educativo:** cada data vem com a fração, o fundamento legal e uma explicação em linguagem simples, além de observações (falta grave, remição, regime inicial) e o aviso de que é uma estimativa.
- Tabela "Como o cálculo é feito?" na própria página, alimentada por `GET /api/regras`.
- **Captação de contatos**, como pede a especificação:
  - campos obrigatórios: nome completo e WhatsApp;
  - campos opcionais: e-mail e número do processo;
  - botão **"Quero falar com um advogado"** em destaque na tela de resultados.
- Validação de entrada com mensagens claras:
  - aceita data em `AAAA-MM-DD` ou `DD/MM/AAAA`;
  - rejeita texto, negativos, datas impossíveis (30/02), valores fora da faixa e detração maior que a pena;
  - WhatsApp brasileiro é normalizado para `+55DDNNNNNNNNN`;
  - o número do processo tem o **dígito verificador CNJ conferido (módulo 97)**.
- Segurança e LGPD:
  - consentimento explícito para o contato;
  - SQL só com `PreparedStatement`;
  - limite de 5 envios por IP a cada 10 minutos (HTTP 429);
  - corpo das requisições limitado a 8 KB (HTTP 413);
  - listagem de contatos protegida por token, comparado em tempo constante;
  - CSV protegido contra *CSV injection*;
  - logs sem dados pessoais;
  - cabeçalhos `X-Content-Type-Options`, `Content-Security-Policy` e `Referrer-Policy`;
  - erros internos não expõem stack trace.
- Front-end responsivo, pensado primeiro para celular. Os dados são inseridos na página só com `textContent`, para evitar XSS.
- **36 testes automatizados com 179 verificações**, incluindo os exemplos da especificação e datas conferidas por um cálculo independente.

![Tela de resultado da calculadora](docs/front-end.png)

---

## 6. Tecnologias utilizadas

| Tecnologia | Uso |
|---|---|
| **Kotlin 2.2** (`kotlinc`) | Linguagem de todo o back-end e dos testes |
| `com.sun.net.httpserver` (JDK) | Servidor HTTP embutido no Java |
| `java.time` (JDK) | Manipulação de datas (`LocalDate`, `Period`, `ChronoUnit`) |
| `java.sql` / JDBC (JDK) + **SQLite** (`sqlite-jdbc`) | Banco de dados dos contatos |
| Java 21 (Temurin) | Plataforma de execução (funciona a partir do Java 17) |
| HTML + CSS + JavaScript | Front-end web (sem frameworks) |
| PowerShell / Bash | Scripts de compilação, testes e execução |

Conceitos da disciplina aplicados:

- variáveis e tipos (`val`, `data class`, `enum class`);
- estruturas condicionais (`if`/`else` e `when` como expressão);
- funções, incluindo lambdas como condição das regras;
- manipulação de datas;
- validação de entrada e null-safety (`?.`, `?:`, `toIntOrNull`);
- organização do código em camadas;
- desenvolvimento de API;
- interface com banco de dados;
- interface gráfica web.

---

## 7. Infraestrutura de execução do projeto

- **Execução local:** um único processo Java, que serve a API e o front-end na mesma porta (padrão `3000`).
- **Banco de dados:** arquivo SQLite em `dados/contatos.db`, criado automaticamente.
- **Requisitos de hardware:** mínimos. Qualquer computador do laboratório ou notebook pessoal atende.
- **Variáveis de ambiente (todas opcionais):**

| Variável | Padrão | Descrição |
|---|---|---|
| `PORT` | `3000` | Porta HTTP |
| `CORS_ORIGIN` | `*` | Origem autorizada a chamar a API (ex.: `https://cespedeslourencoadvogados.com.br`) |
| `WHATSAPP_ESCRITORIO` | `5511989498044` | Número do botão "Quero falar com um advogado" |
| `ADMIN_TOKEN` | (vazio) | Libera `GET /api/contatos`. Mínimo de 16 caracteres; sem ele, a rota fica desativada |
| `BANCO` | `dados/contatos.db` | Caminho do arquivo SQLite |

- **Produção (proposta):** o mesmo processo pode rodar em qualquer serviço que execute Java (VM na nuvem, contêiner Docker, Render, Railway, Fly.io), atrás de um proxy HTTPS. Nesse cenário:
  - `CORS_ORIGIN` fica restrito ao domínio do escritório;
  - o `ADMIN_TOKEN` é gerado aleatoriamente;
  - o arquivo do banco fica em um volume com backup.

---

## 8. Configuração do ambiente para instalação

### Windows

1. **Java:** instale o Temurin 21 (LTS) em <https://adoptium.net/>, deixando marcada a opção que coloca o Java no PATH. Para conferir, rode `java --version`.
2. **Kotlin:**
   - baixe `kotlin-compiler-2.2.20.zip` em <https://github.com/JetBrains/kotlin/releases/tag/v2.2.20> e extraia;
   - adicione a pasta `kotlinc\bin` ao PATH. No laboratório, isso vale só para a janela aberta:
     ```powershell
     $env:Path += ";C:\Users\SEU_RA\Downloads\kotlin-compiler-2.2.20\kotlinc\bin"
     ```
   - para conferir, rode `kotlinc -version`.
3. **Projeto:** clone o repositório ou extraia o `.zip` e, no PowerShell, entre na pasta `calculadora-penal`.

### Linux / macOS

- Java: `sudo apt install openjdk-21-jdk` ou `brew install openjdk@21`.
- Kotlin: `sdk install kotlin 2.2.20` (SDKMAN), `brew install kotlin`, ou o mesmo zip do GitHub.

### Executando

```powershell
# Windows (PowerShell). Se o script for bloqueado, rode antes:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\executar.ps1            # compila, baixa o driver do SQLite na 1ª vez e sobe o servidor
.\executar.ps1 testar     # roda os testes automatizados
```

```bash
# Linux / macOS
./executar.sh
./executar.sh testar
```

Com o servidor no ar:

- Front-end: <http://localhost:3000/>
- API: <http://localhost:3000/api/calculo?pena_anos=5&data_inicio=2024-01-10&tipo_crime=comum&status=primario>

Para encerrar, use `Ctrl+C`.

Para usar outra porta e liberar a lista de contatos no PowerShell:

```powershell
$env:PORT=8080; $env:ADMIN_TOKEN="troque-por-um-token-longo"; .\executar.ps1
```

<details>
<summary>Comandos manuais (sem script)</summary>

```powershell
kotlinc src -include-runtime -d build/calculadora-penal.jar
java -cp "build/calculadora-penal.jar;resources;lib/*" penal.MainKt

kotlinc src test -include-runtime -d build/testes.jar
java -cp "build/testes.jar;resources" penal.ExecutarTestes
```

No Linux e no macOS, troque `;` por `:` no `-cp`.
</details>

---

## 9. Endpoints – API

### `GET /api/calculo`

Calcula as datas da execução penal.

| Parâmetro | Obrigatório | Tipo | Faixa | Descrição |
|---|---|---|---|---|
| `pena_anos` / `pena_meses` / `pena_dias` | pelo menos um > 0 | inteiro | 0–300 / 0–11 / 0–30 | Pena total |
| `data_inicio` | sim | data | 1950–2100 | `AAAA-MM-DD` ou `DD/MM/AAAA` |
| `detracao_anos` / `detracao_meses` / `detracao_dias` | não | inteiro | 0–300 / 0–11 / 0–30 | Tempo preso antes do início; precisa ser menor que a pena |
| `tipo_crime` | sim | texto | `comum` ou `hediondo` | |
| `status` | sim | texto | `primario` ou `reincidente` | |
| `violencia` | não | booleano | `true`/`false` | Só para crime comum |
| `resultado_morte` | não | booleano | `true`/`false` | Só para hediondo |
| `reincidencia_especifica` | não | booleano | `true`/`false` | Só para hediondo + reincidente |

**Exemplo:**

```
GET /api/calculo?pena_anos=6&data_inicio=2024-01-01&tipo_crime=comum&status=primario
```

**Resposta 200 (resumida):**

```json
{
  "entrada": {"pena": "6 anos", "data_inicio": "2024-01-01", "tipo_crime": "comum", "status": "primario", "perfil": "Crime comum · Primário"},
  "pena_total": {"anos": 6, "meses": 0, "dias": 0, "texto": "6 anos", "total_dias": 2192},
  "detracao": {"anos": 0, "meses": 0, "dias": 0, "texto": "0 dias", "total_dias": 0},
  "pena_restante": {"anos": 6, "meses": 0, "dias": 0, "texto": "6 anos", "total_dias": 2192},
  "data_base": "2024-01-01",
  "regime_semiaberto": {
    "data": "2024-12-17", "ja_atingido": true, "fracao": "16%", "dias_necessarios": 351,
    "fundamento": "Art. 112, I, da LEP (Lei 7.210/84, red. Lei 13.964/19)",
    "explicacao": "Cumprindo 16% da pena total (351 dias, já descontada a detração), ..."
  },
  "regime_aberto": {"data": "2025-10-08", "ja_atingido": true, "fracao": "16% do saldo", "dias_necessarios": 295, "...": "..."},
  "livramento_condicional": {"cabivel": true, "data": "2026-01-01", "fracao": "1/3", "dias_necessarios": 731, "...": "..."},
  "termino_pena": {"data": "2030-01-01", "limitado_a_40_anos": false},
  "observacoes": ["O cálculo parte do regime fechado ...", "..."],
  "aviso": "Estimativa educativa. ..."
}
```

Quando o livramento não cabe:
`"livramento_condicional": {"cabivel": false, "fracao": "vedado", "fundamento": "...", "motivo": "..."}`

**Resposta 400 (entrada inválida):**

```json
{
  "erro": "Parâmetros inválidos",
  "detalhes": [
    "Anos da pena inválido: 'abc'. Informe um número inteiro, por exemplo 5.",
    "Data de início inválida: '2024-02-30'. Use AAAA-MM-DD ou DD/MM/AAAA."
  ]
}
```

### `GET /api/regras`

Devolve as tabelas de regras (fração, descrição e fundamento), usadas na seção "Como o cálculo é feito?".

### `GET /api/config`

Devolve os dados públicos do escritório: `{"escritorio": "...", "whatsapp": "5511989498044"}`.

### `POST /api/contatos`

Registra um pedido de contato. Corpo `application/x-www-form-urlencoded`:

| Campo | Obrigatório | Regra |
|---|---|---|
| `nome` | sim | Nome e sobrenome, só letras, até 120 caracteres |
| `whatsapp` | sim | DDD + número (celular com 9). Aceita `(11) 98765-4321`, `+55 11 ...` |
| `email` | não | E-mail válido, até 254 caracteres |
| `processo` | não | `NNNNNNN-DD.AAAA.J.TR.OOOO` (ou 20 dígitos), com dígito verificador válido |
| `consentimento` | sim | `true`: autorização para o contato (LGPD) |
| `resumo` | não | Resumo do cálculo, preenchido pelo front-end (até 500 caracteres) |

Respostas:

- `201 {"id": 1, "mensagem": "..."}`;
- `400` com `detalhes`;
- `415` quando o `Content-Type` está errado;
- `429` quando o IP passa do limite de envios.

### `GET /api/contatos` (uso interno do escritório)

Exige o cabeçalho `Authorization: Bearer <ADMIN_TOKEN>`. Use `?formato=csv` para baixar a planilha, que abre direto no Excel.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:3000/api/contatos?formato=csv" -o contatos.csv
```

### `GET /api/saude`

```json
{"status": "ok", "servico": "calculadora-penal", "versao": "1.0.0"}
```

### `GET /`

Front-end web da calculadora.

### Códigos de resposta

| Código | Quando |
|---|---|
| 200 | Cálculo ou consulta realizados |
| 201 | Contato registrado |
| 204 | Pré-verificação CORS (`OPTIONS`) |
| 400 | Parâmetros ausentes ou inválidos |
| 401 | Token ausente ou errado em `/api/contatos` |
| 404 | Rota inexistente (ou `/api/contatos` sem `ADMIN_TOKEN` configurado) |
| 405 | Método não permitido em uma rota existente (com cabeçalho `Allow`) |
| 413 | Corpo da requisição maior que 8 KB |
| 415 | `Content-Type` diferente de formulário no `POST /api/contatos` |
| 429 | Muitos envios de contato pelo mesmo IP |
| 500 | Erro interno (detalhes ficam só no log do servidor) |

---

## 10. Futuras melhorias (Roadmap)

- **Documento de Regras dos Cálculos:** quando o escritório entregar o documento oficial, revisar `Regras.kt` com o advogado e transformar cada regra em um caso de teste.
- **Remição:** descontar dias de trabalho e estudo (art. 126 da LEP).
- **Falta grave:** informar a data da falta para reiniciar a contagem da progressão.
- **Mais de uma condenação:** somar penas de crimes diferentes, cada uma com sua fração (unificação).
- **Regime inicial semiaberto ou aberto** e casos do art. 112, VI, b e c (organização criminosa e milícia).
- **App Android** (Jetpack Compose) consumindo a mesma API.
- **Painel do escritório** para ver e marcar os contatos atendidos, com login no lugar do token fixo.
- **Exportar o resultado em PDF** para o familiar levar ao atendimento.
- **Publicação:** deploy em nuvem com HTTPS e integração na página de Direito Penal do site do escritório.
- **Política de retenção LGPD:** apagar automaticamente contatos antigos e permitir que o titular peça a exclusão.
- **Build com Gradle** e migração para Ktor, caso a API cresça. Os handlers já são funções `Requisicao -> Resposta`, então só o adaptador muda.

---

## 11. Referências

- FREITAS, A. V. _Desenvolvimento de Aplicativos com a Linguagem Kotlin — Especificação do Projeto de Extensão_. Instituto Mauá de Tecnologia, 2026.
- CESPEDES LOURENÇO ADVOGADOS. _Documentação: Aplicativo Calculadora Penal_, versão 1.0, 28 abr. 2026.
- BRASIL. Lei nº 7.210, de 11 de julho de 1984 (Lei de Execução Penal), art. 112. <https://www.planalto.gov.br/ccivil_03/leis/l7210.htm>
- BRASIL. Lei nº 13.964, de 24 de dezembro de 2019 (Pacote Anticrime). <https://www.planalto.gov.br/ccivil_03/_ato2019-2022/2019/lei/l13964.htm>
- BRASIL. Decreto-Lei nº 2.848, de 7 de dezembro de 1940 (Código Penal), arts. 10, 33, 42, 75 e 83. <https://www.planalto.gov.br/ccivil_03/decreto-lei/del2848compilado.htm>
- BRASIL. Lei nº 8.072, de 25 de julho de 1990 (Lei dos Crimes Hediondos). <https://www.planalto.gov.br/ccivil_03/leis/l8072.htm>
- STJ. Tema Repetitivo 1084 (fração de progressão do reincidente não específico em crime hediondo).
- STF. Súmula 715 (benefícios calculados sobre a pena total, e não sobre o limite de cumprimento).
- CNJ. Resolução nº 65/2008 (numeração única dos processos e dígito verificador).
- Calculadoras de referência indicadas pelo escritório: <https://buscacdp.com.br/calculadora> e <https://www.calcpena.com/>
- Kotlin — Documentação oficial. <https://kotlinlang.org/docs/home.html>
- Oracle — Java SE 21, `com.sun.net.httpserver.HttpServer`. <https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html>
- xerial — SQLite JDBC Driver. <https://github.com/xerial/sqlite-jdbc>
- Lei nº 13.709/2018 (LGPD). <https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm>

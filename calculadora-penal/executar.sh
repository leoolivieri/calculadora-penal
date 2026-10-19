#!/usr/bin/env bash
# Calculadora Penal - script de build e execução (Linux / macOS)
#
#   ./executar.sh            compila (se preciso) e sobe o servidor
#   ./executar.sh testar     compila e roda os testes automatizados
#   ./executar.sh compilar   só compila
set -euo pipefail
cd "$(dirname "$0")"

SQLITE_VERSAO="3.51.1.0"
JAR="build/calculadora-penal.jar"
JAR_TESTES="build/testes.jar"
DRIVER="lib/sqlite-jdbc-$SQLITE_VERSAO.jar"

command -v kotlinc >/dev/null || { echo "kotlinc não encontrado no PATH." >&2; exit 1; }

compilar() {  # compilar <saida> <pastas...>
  local saida="$1"; shift
  if [ ! -f "$saida" ] || [ -n "$(find "$@" -name '*.kt' -newer "$saida")" ]; then
    echo "Compilando $* ..."
    mkdir -p build
    kotlinc "$@" -include-runtime -d "$saida"
  fi
}

baixar_driver() {
  [ -f "$DRIVER" ] && return
  echo "Baixando o driver do SQLite ($SQLITE_VERSAO) para a pasta lib/ ..."
  mkdir -p lib
  curl -fsSL -o "$DRIVER" \
    "https://github.com/xerial/sqlite-jdbc/releases/download/$SQLITE_VERSAO/sqlite-jdbc-$SQLITE_VERSAO.jar" \
    || { rm -f "$DRIVER"; echo "Não foi possível baixar o driver. Os contatos ficarão só em memória."; }
}

case "${1:-rodar}" in
  compilar) compilar "$JAR" src ;;
  testar)
    compilar "$JAR_TESTES" src test
    exec java -Dstdout.encoding=UTF-8 -cp "$JAR_TESTES:resources" penal.ExecutarTestes ;;
  rodar)
    compilar "$JAR" src
    baixar_driver
    exec java -Dstdout.encoding=UTF-8 -cp "$JAR:resources:lib/*" penal.MainKt ;;
  *) echo "uso: $0 [rodar|testar|compilar]" >&2; exit 2 ;;
esac

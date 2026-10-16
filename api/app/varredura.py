import hashlib
import os
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from app.db import agora, connect, init_db
from app.importacao import importar_pdf, interpretar_nome

PASTA_PADRAO = "\\\\Srv3302\\projetos elétricos"
INDISPONIVEL = "Pasta de rede indisponível."


def caminho_configurado() -> str:
    return os.environ.get("QUADROS_PASTA_REDE", PASTA_PADRAO)


def ler_estado_pasta(conn) -> dict:
    row = conn.execute("SELECT caminho, acessivel, detalhe FROM estado_pasta_rede WHERE id = 1").fetchone()
    comparacoes = [
        dict(item)
        for item in conn.execute(
            """SELECT codigo_quadro, pasta, arquivo, alterado_em, resultado
               FROM comparacao_rede ORDER BY codigo_quadro, alterado_em"""
        ).fetchall()
    ]
    if row is None or not row["acessivel"]:
        return {
            "acessivel": False,
            "mensagem": INDISPONIVEL,
            "caminho": row["caminho"] if row else PASTA_PADRAO,
            "comparacoes": comparacoes,
        }
    return {
        "acessivel": True,
        "mensagem": "A pasta de rede está acessível.",
        "caminho": row["caminho"],
        "comparacoes": comparacoes,
    }


def registrar_estado(conn, caminho: str, acessivel: bool, detalhe: str) -> None:
    conn.execute(
        """INSERT INTO estado_pasta_rede (id, caminho, acessivel, detalhe, vista_em)
           VALUES (1, ?, ?, ?, ?)
           ON CONFLICT(id) DO UPDATE SET
             caminho = excluded.caminho,
             acessivel = excluded.acessivel,
             detalhe = excluded.detalhe,
             vista_em = excluded.vista_em""",
        (caminho, 1 if acessivel else 0, detalhe, agora()),
    )


def pasta_aberta(caminho: str) -> bool:
    usuario = os.environ.get("QUADROS_PASTA_USUARIO", "").strip()
    senha = os.environ.get("QUADROS_PASTA_SENHA", "")
    if caminho.startswith("\\\\") and usuario and os.name == "nt":
        subprocess.run(
            ["net", "use", caminho, senha, f"/user:{usuario}"],
            capture_output=True,
            check=False,
        )
    senha = ""
    try:
        return Path(caminho).is_dir()
    except OSError:
        return False


def observar(estado: dict, arquivo: Path) -> bool:
    dados = arquivo.stat()
    atual = (dados.st_size, dados.st_mtime_ns)
    anterior = estado.get(str(arquivo))
    estado[str(arquivo)] = atual
    if not anterior or anterior != atual or dados.st_size <= 0:
        return False
    return True


def _iso_mtime(arquivo: Path) -> str:
    return datetime.fromtimestamp(arquivo.stat().st_mtime, timezone.utc).isoformat()


def _instante(valor: str | None) -> datetime | None:
    if not valor:
        return None
    try:
        return datetime.fromisoformat(valor)
    except ValueError:
        return None


def _referencia_vigente(importada_em: str | None) -> datetime | None:
    return _instante(importada_em)


def _gravar_comparacao(conn, codigo: str, pasta: str, arquivo: str, digest: str, alterado_em: str, resultado: str, revisao_id: str | None) -> None:
    conn.execute(
        """INSERT INTO comparacao_rede
           (id, codigo_quadro, pasta, arquivo, hash_sha256, alterado_em, resultado, revisao_id, atualizado_em)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(pasta, arquivo) DO UPDATE SET
             codigo_quadro = excluded.codigo_quadro,
             hash_sha256 = excluded.hash_sha256,
             alterado_em = excluded.alterado_em,
             resultado = excluded.resultado,
             revisao_id = excluded.revisao_id,
             atualizado_em = excluded.atualizado_em""",
        (str(uuid.uuid4()), codigo, pasta, arquivo, digest, alterado_em, resultado, revisao_id, agora()),
    )


def _tratar_arquivo(conn, raiz: Path, base: Path, item: Path) -> None:
    conteudo = item.read_bytes()
    if not conteudo:
        return
    digest = hashlib.sha256(conteudo).hexdigest()
    relativo = item.relative_to(base)
    pasta = "raiz" if str(relativo.parent) == "." else str(relativo.parent)
    alterado_em = _iso_mtime(item)
    tratado = conn.execute(
        "SELECT 1 FROM arquivo_rede_tratado WHERE nome = ? AND hash_sha256 = ?",
        (item.name, digest),
    ).fetchone()
    partes = interpretar_nome(item.name)
    if partes is None:
        if not tratado:
            importar_pdf(conn, raiz, item.name, conteudo, "rede", None)
            conn.execute(
                """INSERT INTO arquivo_rede_tratado (id, nome, hash_sha256, tratado_em, caminho)
                   VALUES (?, ?, ?, ?, ?)""",
                (str(uuid.uuid4()), item.name, digest, agora(), str(relativo)),
            )
        _gravar_comparacao(conn, "", pasta, item.name, digest, alterado_em, "Nome fora do padrão", None)
        conn.commit()
        return
    codigo, _revisao_codigo = partes
    quadros = conn.execute(
        "SELECT id, revisao_vigente_id FROM quadro WHERE lower(codigo) = lower(?)",
        (codigo,),
    ).fetchall()
    if len(quadros) != 1:
        if not tratado:
            importar_pdf(conn, raiz, item.name, conteudo, "rede", None)
            conn.execute(
                """INSERT INTO arquivo_rede_tratado (id, nome, hash_sha256, tratado_em, caminho)
                   VALUES (?, ?, ?, ?, ?)""",
                (str(uuid.uuid4()), item.name, digest, agora(), str(relativo)),
            )
        _gravar_comparacao(conn, codigo, pasta, item.name, digest, alterado_em, "Quadro não cadastrado", None)
        conn.commit()
        return
    quadro = quadros[0]
    igual = conn.execute(
        """SELECT r.id, r.importada_em FROM revisao r
           JOIN arquivo a ON a.id = r.arquivo_id
           WHERE r.quadro_id = ? AND a.hash_sha256 = ?""",
        (quadro["id"], digest),
    ).fetchone()
    vigente = None
    if quadro["revisao_vigente_id"]:
        vigente = conn.execute(
            "SELECT id, importada_em FROM revisao WHERE id = ?",
            (quadro["revisao_vigente_id"],),
        ).fetchone()
    referencia = _referencia_vigente(vigente["importada_em"] if vigente else None)
    modificado = _instante(alterado_em)
    mais_novo = bool(vigente and referencia and modificado and modificado > referencia and (not igual or igual["id"] != vigente["id"]))
    if igual and not mais_novo:
        resultado = "Igual ao que já foi importado"
        revisao_id = igual["id"]
    elif mais_novo:
        resultado = "Mais novo que a revisão vigente"
        revisao_id = igual["id"] if igual else None
    else:
        resultado = "Ainda não entrou"
        revisao_id = None
    if revisao_id is None and not tratado:
        resposta = importar_pdf(conn, raiz, item.name, conteudo, "rede", None)
        revisao_id = resposta.get("revisao_id")
        conn.execute(
            """INSERT INTO arquivo_rede_tratado (id, nome, hash_sha256, tratado_em, caminho)
               VALUES (?, ?, ?, ?, ?)""",
            (str(uuid.uuid4()), item.name, digest, agora(), str(relativo)),
        )
    _gravar_comparacao(conn, codigo, pasta, item.name, digest, alterado_em, resultado, revisao_id)
    conn.commit()


def varrer_uma_vez(db_path: str, arquivos_dir: str, caminho: str, estado: dict) -> None:
    init_db(db_path)
    base = Path(caminho)
    with connect(db_path) as conn:
        if not pasta_aberta(caminho):
            registrar_estado(conn, caminho, False, INDISPONIVEL)
            conn.commit()
            return
        registrar_estado(conn, caminho, True, "A pasta de rede está acessível.")
        conn.commit()
        arquivos = [item for item in base.rglob("*") if item.is_file()]
        for item in sorted(arquivos):
            if observar(estado, item):
                _tratar_arquivo(conn, Path(arquivos_dir), base, item)


def main() -> None:
    db = os.environ.get("QUADROS_DB", "data/quadros.sqlite")
    arquivos = os.environ.get("QUADROS_ARQUIVOS") or str(Path(db).parent / "arquivos")
    intervalo = float(os.environ.get("QUADROS_PASTA_INTERVALO_S", "5"))
    init_db(db)
    estado: dict = {}
    while True:
        try:
            varrer_uma_vez(db, arquivos, caminho_configurado(), estado)
        except Exception:
            with connect(db) as conn:
                registrar_estado(conn, caminho_configurado(), False, INDISPONIVEL)
                conn.commit()
        time.sleep(intervalo)


if __name__ == "__main__":
    main()

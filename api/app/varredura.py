import hashlib
import os
import re
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from app.db import agora, connect, init_db
from app.importacao import importar_no_quadro

PASTA_PADRAO = "\\\\Srv3302\\projetos elétricos"
INDISPONIVEL = "Pasta de rede indisponível."


def caminho_configurado() -> str:
    return os.environ.get("QUADROS_PASTA_REDE", PASTA_PADRAO)


def ler_estado_pasta(conn) -> dict:
    row = conn.execute("SELECT caminho, acessivel, detalhe FROM estado_pasta_rede WHERE id = 1").fetchone()
    comparacoes = [
        dict(item)
        for item in conn.execute(
            """SELECT codigo_quadro, grupo, pasta, arquivo, alterado_em, resultado
               FROM comparacao_rede ORDER BY grupo, codigo_quadro, alterado_em"""
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


def codigo_revisao_do_arquivo(nome: str) -> str:
    achados = re.findall(r"(?i)REV\.([A-Za-z0-9]{1,4})", nome)
    if achados:
        return achados[-1].upper()
    return nome


def pastas_de_projeto(base: Path) -> list[tuple[str, str, Path]]:
    achados = []
    for pasta in base.rglob("*"):
        if not pasta.is_dir() or pasta.name.lower() != "pdf":
            continue
        quadro_dir = pasta.parent
        if quadro_dir == base:
            continue
        relativo = quadro_dir.relative_to(base)
        grupo = relativo.parts[0] if relativo.parts else ""
        achados.append((grupo, quadro_dir.name, pasta))
    return achados


def garantir_quadro(conn, codigo: str, grupo: str) -> str:
    existente = conn.execute(
        "SELECT id FROM quadro WHERE lower(codigo) = lower(?)",
        (codigo,),
    ).fetchone()
    if existente:
        return existente["id"]
    cliente = conn.execute("SELECT id FROM cliente WHERE nome = ?", (grupo,)).fetchone()
    if cliente is None:
        cliente_id = str(uuid.uuid4())
        conn.execute(
            "INSERT INTO cliente (id, nome, codigo) VALUES (?, ?, NULL)",
            (cliente_id, grupo or codigo),
        )
    else:
        cliente_id = cliente["id"]
    ordem = conn.execute("SELECT id FROM ordem_producao WHERE numero = ?", (codigo,)).fetchone()
    if ordem is None:
        ordem_id = str(uuid.uuid4())
        conn.execute(
            """INSERT INTO ordem_producao
               (id, numero, cliente_id, descricao, status, prazo, criado_em)
               VALUES (?, ?, ?, ?, 'aberta', NULL, ?)""",
            (ordem_id, codigo, cliente_id, grupo, agora()),
        )
    else:
        ordem_id = ordem["id"]
    quadro_id = str(uuid.uuid4())
    momento = agora()
    conn.execute(
        """INSERT INTO quadro
           (id, codigo, ordem_producao_id, titulo, responsavel_id, bancada_id,
            revisao_vigente_id, status, criado_em, atualizado_em)
           VALUES (?, ?, ?, ?, NULL, NULL, NULL, 'planejado', ?, ?)""",
        (quadro_id, codigo, ordem_id, codigo, momento, momento),
    )
    return quadro_id


def _gravar_comparacao(conn, codigo: str, grupo: str, pasta: str, arquivo: str, digest: str, alterado_em: str, resultado: str, revisao_id: str | None) -> None:
    conn.execute(
        """INSERT INTO comparacao_rede
           (id, codigo_quadro, grupo, pasta, arquivo, hash_sha256, alterado_em, resultado, revisao_id, atualizado_em)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(pasta, arquivo) DO UPDATE SET
             codigo_quadro = excluded.codigo_quadro,
             grupo = excluded.grupo,
             hash_sha256 = excluded.hash_sha256,
             alterado_em = excluded.alterado_em,
             resultado = excluded.resultado,
             revisao_id = excluded.revisao_id,
             atualizado_em = excluded.atualizado_em""",
        (str(uuid.uuid4()), codigo, grupo, pasta, arquivo, digest, alterado_em, resultado, revisao_id, agora()),
    )


def _marcar_tratado(conn, nome: str, digest: str, caminho: str) -> None:
    conn.execute(
        """INSERT OR IGNORE INTO arquivo_rede_tratado (id, nome, hash_sha256, tratado_em, caminho)
           VALUES (?, ?, ?, ?, ?)""",
        (str(uuid.uuid4()), nome, digest, agora(), caminho),
    )


def _tratar_pdf(conn, raiz: Path, base: Path, grupo: str, codigo: str, pasta_pdf: Path, item: Path) -> None:
    conteudo = item.read_bytes()
    if not conteudo or item.suffix.lower() != ".pdf":
        return
    digest = hashlib.sha256(conteudo).hexdigest()
    alterado_em = _iso_mtime(item)
    pasta = str(pasta_pdf.relative_to(base))
    quadro_id = garantir_quadro(conn, codigo, grupo)
    caminho = str(pasta_pdf / item.name)
    tratado = conn.execute(
        "SELECT 1 FROM arquivo_rede_tratado WHERE caminho = ? AND hash_sha256 = ?",
        (caminho, digest),
    ).fetchone()
    igual = conn.execute(
        """SELECT r.id FROM revisao r
           JOIN arquivo a ON a.id = r.arquivo_id
           WHERE r.quadro_id = ? AND a.hash_sha256 = ?""",
        (quadro_id, digest),
    ).fetchone()
    vigente = conn.execute(
        """SELECT r.id, r.importada_em FROM quadro q
           JOIN revisao r ON r.id = q.revisao_vigente_id
           WHERE q.id = ?""",
        (quadro_id,),
    ).fetchone()
    referencia = _referencia_vigente(vigente["importada_em"] if vigente else None)
    modificado = _instante(alterado_em)
    mais_novo = bool(
        vigente and referencia and modificado and modificado > referencia and (not igual or igual["id"] != vigente["id"])
    )
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
        resposta = importar_no_quadro(
            conn,
            raiz,
            quadro_id,
            item.name,
            conteudo,
            codigo_revisao_do_arquivo(item.name),
            "rede",
            None,
        )
        revisao_id = resposta.get("revisao_id")
        _marcar_tratado(conn, item.name, digest, caminho)
    _gravar_comparacao(conn, codigo, grupo, pasta, item.name, digest, alterado_em, resultado, revisao_id)
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
        for grupo, codigo, pasta_pdf in pastas_de_projeto(base):
            garantir_quadro(conn, codigo, grupo)
            conn.commit()
            pdfs = [item for item in pasta_pdf.iterdir() if item.is_file() and item.suffix.lower() == ".pdf"]
            for item in sorted(pdfs, key=lambda arquivo: (arquivo.stat().st_mtime, arquivo.name)):
                if observar(estado, item):
                    _tratar_pdf(conn, Path(arquivos_dir), base, grupo, codigo, pasta_pdf, item)


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

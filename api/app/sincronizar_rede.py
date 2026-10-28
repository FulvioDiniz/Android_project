"""Descobre pastas PDF em \\\\Srv3302\\projetos elétricos e cadastra no Quadros."""

from __future__ import annotations

import hashlib
import uuid
from pathlib import Path

from app.db import agora
from app.varredura import PASTA_PADRAO, caminho_configurado, pasta_aberta

IGNORAR_PREFIXOS = ("0.", "!", "@")


def _clientes(raiz: Path) -> list[Path]:
    try:
        return sorted(
            [
                item
                for item in raiz.iterdir()
                if item.is_dir() and not item.name.startswith(IGNORAR_PREFIXOS)
            ],
            key=lambda item: item.name.lower(),
        )
    except OSError:
        return []


def descobrir_pastas_pdf(raiz: Path | None = None) -> list[dict[str, str]]:
    base = Path(raiz or caminho_configurado())
    achados: list[dict[str, str]] = []
    for cliente in _clientes(base):
        try:
            for pasta_pdf in cliente.rglob("PDF"):
                if not pasta_pdf.is_dir():
                    continue
                if pasta_pdf.name.lower() != "pdf":
                    continue
                projeto = pasta_pdf.parent
                achados.append(
                    {
                        "cliente": cliente.name,
                        "titulo": projeto.name,
                        "caminho": str(pasta_pdf),
                        "relativo": str(projeto.relative_to(base)),
                    }
                )
        except OSError:
            continue
    return achados


def _codigo_unico(conn, titulo: str, cliente: str, caminho: str) -> str:
    base = (titulo or "projeto").strip()[:100]
    if not conn.execute("SELECT 1 FROM quadro WHERE codigo = ?", (base,)).fetchone():
        return base
    com_cliente = f"{cliente[:40]} · {base}"[:120]
    if not conn.execute("SELECT 1 FROM quadro WHERE codigo = ?", (com_cliente,)).fetchone():
        return com_cliente
    sufixo = hashlib.sha1(caminho.encode("utf-8")).hexdigest()[:8]
    return f"{base[:90]} · {sufixo}"


def _cliente_id(conn, nome: str) -> str:
    row = conn.execute("SELECT id FROM cliente WHERE nome = ?", (nome,)).fetchone()
    if row:
        return row["id"]
    cid = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO cliente (id, nome, codigo) VALUES (?, ?, NULL)",
        (cid, nome),
    )
    return cid


def sincronizar_pastas(conn, responsavel_id: str | None = None) -> dict:
    caminho = caminho_configurado()
    if not pasta_aberta(caminho):
        return {
            "ok": False,
            "mensagem": f"Pasta de rede inacessível: {caminho or PASTA_PADRAO}",
            "criados": 0,
            "ja_existiam": 0,
            "total_rede": 0,
        }

    existentes = {
        (row["caminho_projeto"] or "").rstrip("\\/").lower(): row["id"]
        for row in conn.execute(
            "SELECT id, caminho_projeto FROM quadro WHERE caminho_projeto IS NOT NULL"
        ).fetchall()
    }
    achados = descobrir_pastas_pdf(Path(caminho))
    criados = 0
    ja = 0
    momento = agora()
    for item in achados:
        chave = item["caminho"].rstrip("\\/").lower()
        if chave in existentes:
            ja += 1
            continue
        codigo = _codigo_unico(conn, item["titulo"], item["cliente"], item["caminho"])
        cliente_id = _cliente_id(conn, item["cliente"])
        ordem_id = str(uuid.uuid4())
        conn.execute(
            """INSERT INTO ordem_producao
               (id, numero, cliente_id, descricao, status, prazo, criado_em)
               VALUES (?, ?, ?, ?, 'aberta', NULL, ?)""",
            (ordem_id, codigo, cliente_id, item["relativo"], momento),
        )
        qid = str(uuid.uuid4())
        conn.execute(
            """INSERT INTO quadro
               (id, codigo, ordem_producao_id, titulo, responsavel_id, bancada_id,
                revisao_vigente_id, status, criado_em, atualizado_em, caminho_projeto)
               VALUES (?, ?, ?, ?, ?, NULL, NULL, 'planejado', ?, ?, ?)""",
            (
                qid,
                codigo,
                ordem_id,
                item["titulo"],
                responsavel_id,
                momento,
                momento,
                item["caminho"],
            ),
        )
        existentes[chave] = qid
        criados += 1
    return {
        "ok": True,
        "mensagem": f"Rede lida. {criados} novos, {ja} já estavam, {len(achados)} pastas PDF.",
        "criados": criados,
        "ja_existiam": ja,
        "total_rede": len(achados),
        "caminho": caminho,
    }

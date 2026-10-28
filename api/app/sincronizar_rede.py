"""Sincroniza só projetos em PCP/execução do P&D (sem finalizados), com PDF da rede."""

from __future__ import annotations

import hashlib
import json
import re
import sys
import uuid
from pathlib import Path

from app.db import agora
from app.varredura import PASTA_PADRAO, caminho_configurado, pasta_aberta

IGNORAR_PREFIXOS = ("0.", "!", "@")
STATUS_ATIVOS = frozenset(
    {
        "q_cronograma",  # PCP quadro
        "q_execucao",  # execução quadro
        "m_pcp",  # PCP metalurgia
        "execucao",  # execução serviço (só se tipo quadro)
    }
)
STATUS_FINAL = ("final", "q_final", "m_final", "finalizado")

# Caminhos onde o P&D costuma estar neste PC.
_PD_SISTEMA_DIRS = [
    Path(r"C:\Users\Administrator\Desktop\SISTEMA PED\Desktop\Sistema P&D v18.0\Sistema P&D v18.0\Sistema P&D v17.0\DASHBOARD V17\Sistema P&D v13.0 INTEGRADO\Pasta_Sistema\sistema"),
]


def _numero_pedido(texto: str) -> str:
    m = re.search(r"\b(\d{4,5}-\d{4})\b", texto or "")
    return m.group(1) if m else ""


def _ler_projetos_pd() -> list[dict]:
    for pasta in _PD_SISTEMA_DIRS:
        if not pasta.is_dir():
            continue
        if str(pasta) not in sys.path:
            sys.path.insert(0, str(pasta))
        try:
            from database_config import get_sqlserver_connection  # type: ignore

            conn = get_sqlserver_connection()
            cur = conn.cursor()
            cur.execute("SELECT id, json_content FROM dbo.projetos")
            out: list[dict] = []
            for pid, raw in cur.fetchall():
                try:
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8", "ignore")
                    item = json.loads(raw)
                except Exception:
                    continue
                if isinstance(item, dict):
                    item.setdefault("id", pid)
                    out.append(item)
            try:
                conn.close()
            except Exception:
                pass
            return out
        except Exception:
            continue
    return []


def _ativo_pcp_execucao(projeto: dict) -> bool:
    status = (projeto.get("status") or "").strip()
    tipo = (projeto.get("tipo_projeto") or "").strip().lower()
    if not status:
        return False
    if any(x in status for x in STATUS_FINAL) or status.endswith("_finalizado"):
        return False
    if "solicitacao_nf" in status:
        return False
    if status in STATUS_ATIVOS:
        # Serviços de campo em "execucao" não entram no tablet de montagem.
        if status == "execucao" and tipo == "servico":
            return False
        return True
    if status.endswith("_pcp") or "cronograma" in status:
        return True
    if "execucao" in status and tipo in ("quadro", "metalurgia", ""):
        return True
    return False


def _indexar_pdfs(raiz: Path) -> list[tuple[str, Path]]:
    """Lista (nome_pasta_projeto, caminho_PDF)."""
    itens: list[tuple[str, Path]] = []
    try:
        clientes = [
            d
            for d in raiz.iterdir()
            if d.is_dir() and not d.name.startswith(IGNORAR_PREFIXOS)
        ]
    except OSError:
        return itens
    for cliente in clientes:
        try:
            for pasta_pdf in cliente.rglob("PDF"):
                if pasta_pdf.is_dir() and pasta_pdf.name.lower() == "pdf":
                    itens.append((pasta_pdf.parent.name, pasta_pdf))
        except OSError:
            continue
    return itens


def _pastas_para_projeto(projeto: dict, indice: list[tuple[str, Path]]) -> list[Path]:
    nome = (projeto.get("nome_projeto") or projeto.get("obra") or "").strip()
    numero = _numero_pedido(nome)
    if not numero:
        return []
    achados: list[Path] = []
    vistos: set[str] = set()
    for pasta_nome, caminho in indice:
        texto = f"{pasta_nome} {caminho}"
        if numero not in texto:
            continue
        chave = str(caminho).lower()
        if chave in vistos:
            continue
        vistos.add(chave)
        achados.append(caminho)
    return achados


def descobrir_ativos(raiz: Path | None = None) -> list[dict[str, str]]:
    """Projetos P&D em PCP/execução com pasta PDF na rede (última pasta por quadro)."""
    base = Path(raiz or caminho_configurado())
    projetos = [p for p in _ler_projetos_pd() if _ativo_pcp_execucao(p)]
    indice = _indexar_pdfs(base)
    achados: list[dict[str, str]] = []
    vistos_caminho: set[str] = set()
    for projeto in projetos:
        nome = (projeto.get("nome_projeto") or projeto.get("obra") or "").strip()
        cliente = (projeto.get("cliente") or "Cliente").strip() or "Cliente"
        status = (projeto.get("status") or "").strip()
        for pasta_pdf in _pastas_para_projeto(projeto, indice):
            chave = str(pasta_pdf).rstrip("\\/").lower()
            if chave in vistos_caminho:
                continue
            vistos_caminho.add(chave)
            achados.append(
                {
                    "cliente": cliente,
                    "titulo": pasta_pdf.parent.name,
                    "caminho": str(pasta_pdf),
                    "status_pd": status,
                    "nome_pd": nome,
                    "numero": _numero_pedido(nome) or _numero_pedido(pasta_pdf.parent.name),
                }
            )
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
            "removidos": 0,
            "total_rede": 0,
        }

    projetos_pd = _ler_projetos_pd()
    if not projetos_pd:
        return {
            "ok": False,
            "mensagem": "Não consegui ler os projetos do P&D (SQL). Nada foi alterado.",
            "criados": 0,
            "ja_existiam": 0,
            "removidos": 0,
            "total_rede": 0,
        }

    achados = descobrir_ativos(Path(caminho))
    caminhos_ativos = {item["caminho"].rstrip("\\/").lower() for item in achados}

    # Cancela o que não está em PCP/execução (limpa o monte importado antes).
    removidos = 0
    for row in conn.execute(
        """SELECT id, caminho_projeto, codigo FROM quadro
           WHERE status != 'cancelado'"""
    ).fetchall():
        chave = (row["caminho_projeto"] or "").rstrip("\\/").lower()
        if chave and chave in caminhos_ativos:
            continue
        # Mantém só se o código bater com algum título ativo sem caminho.
        if not chave and any(row["codigo"] == a["titulo"] for a in achados):
            continue
        conn.execute(
            "UPDATE quadro SET status = 'cancelado', atualizado_em = ? WHERE id = ?",
            (agora(), row["id"]),
        )
        removidos += 1

    existentes = {
        (row["caminho_projeto"] or "").rstrip("\\/").lower(): dict(row)
        for row in conn.execute(
            """SELECT id, codigo, titulo, caminho_projeto FROM quadro
               WHERE caminho_projeto IS NOT NULL AND status != 'cancelado'"""
        ).fetchall()
    }

    criados = 0
    ja = 0
    momento = agora()
    for item in achados:
        chave = item["caminho"].rstrip("\\/").lower()
        if chave in existentes:
            atual = existentes[chave]
            # Corrige nome genérico (ex.: "teste") para o da pasta.
            if atual["codigo"] != item["titulo"] or atual["titulo"] != item["titulo"]:
                codigo = item["titulo"]
                if conn.execute(
                    "SELECT 1 FROM quadro WHERE codigo = ? AND id != ?",
                    (codigo, atual["id"]),
                ).fetchone():
                    codigo = _codigo_unico(conn, item["titulo"], item["cliente"], item["caminho"])
                conn.execute(
                    """UPDATE quadro SET codigo = ?, titulo = ?, atualizado_em = ?
                       WHERE id = ?""",
                    (codigo, item["titulo"], momento, atual["id"]),
                )
            ja += 1
            continue
        codigo = _codigo_unico(conn, item["titulo"], item["cliente"], item["caminho"])
        cliente_id = _cliente_id(conn, item["cliente"])
        ordem_id = str(uuid.uuid4())
        conn.execute(
            """INSERT INTO ordem_producao
               (id, numero, cliente_id, descricao, status, prazo, criado_em)
               VALUES (?, ?, ?, ?, 'aberta', NULL, ?)""",
            (ordem_id, codigo, cliente_id, f"{item['status_pd']} · {item['nome_pd']}", momento),
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
        existentes[chave] = {"id": qid, "codigo": codigo, "titulo": item["titulo"], "caminho_projeto": item["caminho"]}
        criados += 1

    ativos = conn.execute(
        "SELECT COUNT(*) AS n FROM quadro WHERE status != 'cancelado'"
    ).fetchone()["n"]
    return {
        "ok": True,
        "mensagem": (
            f"Só PCP/execução do P&D. {criados} novos, {ja} mantidos, "
            f"{removidos} finalizados/fora cancelados. Ativos agora: {ativos}."
        ),
        "criados": criados,
        "ja_existiam": ja,
        "removidos": removidos,
        "total_rede": len(achados),
        "ativos": ativos,
        "caminho": caminho,
    }

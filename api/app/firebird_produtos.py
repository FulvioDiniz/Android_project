"""Busca produtos no Firebird (TGA/PED) para pedidos do almoxarifado."""

from __future__ import annotations

import os
from decimal import Decimal
from typing import Any

_DLL = os.environ.get(
    "FIREBIRD_CLIENT_DLL",
    r"C:\Program Files\Firebird\Firebird_2_5\bin\fbclient.dll",
)


def _cfg() -> dict[str, Any]:
    return {
        "host": os.environ.get("QUADROS_FB_HOST", os.environ.get("FIREBIRD_FABRICA_HOST", "192.168.0.150")),
        "database": os.environ.get(
            "QUADROS_FB_DB",
            os.environ.get("FIREBIRD_FABRICA_DB", r"C:\TGA\Dados\PED.FDB"),
        ),
        "user": os.environ.get("QUADROS_FB_USER", "SYSDBA"),
        "password": os.environ.get("QUADROS_FB_PASSWORD", "masterkey"),
        "port": int(os.environ.get("QUADROS_FB_PORT", "3050")),
        "charset": os.environ.get("QUADROS_FB_CHARSET", "WIN1252"),
    }


def _num(v: Any) -> float:
    if v is None or v == "":
        return 0.0
    if isinstance(v, Decimal):
        return float(v)
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def _txt(v: Any) -> str:
    if v is None:
        return ""
    if isinstance(v, (bytes, bytearray)):
        return v.decode("cp1252", errors="replace").strip()
    return str(v).strip()


def conectar():
    import fdb

    if os.path.isfile(_DLL):
        try:
            fdb.load_api(_DLL)
        except Exception:
            pass
    cfg = _cfg()
    return fdb.connect(
        host=str(cfg["host"]),
        database=str(cfg["database"]),
        user=str(cfg["user"]),
        password=str(cfg["password"]),
        port=int(cfg["port"]),
        charset=str(cfg["charset"]),
    )


def pesquisar_produtos(termo: str, limite: int = 40) -> list[dict[str, Any]]:
    q = (termo or "").strip()
    if len(q) < 2:
        return []
    lim = max(1, min(int(limite or 40), 80))
    needle = q.upper()
    conn = conectar()
    try:
        cur = conn.cursor()
        cur.execute(
            f"""
            SELECT FIRST {lim}
                TRIM(CAST(p.CODPRD AS VARCHAR(50))),
                COALESCE(p.NOMEFANTASIA, CAST(SUBSTRING(p.DESCRICAO FROM 1 FOR 200) AS VARCHAR(200))),
                COALESCE(p.SALDOGERALFISICO, 0),
                TRIM(COALESCE(CAST(p.UNIDADE AS VARCHAR(20)), '')),
                TRIM(COALESCE(CAST(p.NUMNOFABRIC AS VARCHAR(60)), '')),
                TRIM(COALESCE(CAST(p.CODFAB AS VARCHAR(60)), ''))
            FROM TPRODUTO p
            WHERE UPPER(TRIM(CAST(p.CODPRD AS VARCHAR(50)))) CONTAINING ?
               OR UPPER(COALESCE(p.NOMEFANTASIA, '')) CONTAINING ?
               OR UPPER(CAST(SUBSTRING(p.DESCRICAO FROM 1 FOR 200) AS VARCHAR(200))) CONTAINING ?
               OR UPPER(COALESCE(CAST(p.NUMNOFABRIC AS VARCHAR(60)), '')) CONTAINING ?
            ORDER BY COALESCE(p.SALDOGERALFISICO, 0) DESC
            """,
            (needle, needle, needle, needle),
        )
        saida: list[dict[str, Any]] = []
        vistos: set[str] = set()
        for row in cur.fetchall():
            codigo = _txt(row[0])
            nome = _txt(row[1])
            if not codigo or codigo in vistos:
                continue
            if "NAO UTILIZAR" in nome.upper():
                continue
            vistos.add(codigo)
            saida.append(
                {
                    "codigo": codigo,
                    "nome": nome,
                    "saldo": round(_num(row[2]), 4),
                    "unidade": _txt(row[3]) or "UN",
                    "modelo": _txt(row[4]),
                    "cod_fabricante": _txt(row[5]),
                }
            )
        return saida
    finally:
        conn.close()


def status_conexao() -> dict[str, Any]:
    cfg = _cfg()
    try:
        conn = conectar()
        conn.close()
        return {"ok": True, "host": cfg["host"], "database": cfg["database"]}
    except Exception as exc:
        return {
            "ok": False,
            "host": cfg["host"],
            "database": cfg["database"],
            "erro": str(exc)[:240],
        }

import hashlib
import re
import uuid
from pathlib import Path

from app.db import agora

NOME = re.compile(r"(?i)^(.+)-REV\.([A-Za-z0-9]{1,4})\.pdf$")


def gravar_bytes(raiz: Path, area: str, conteudo: bytes) -> tuple[str, str]:
    arquivo_id = str(uuid.uuid4())
    destino = raiz / area
    destino.mkdir(parents=True, exist_ok=True)
    parcial = destino / f"{arquivo_id}.part"
    final = destino / arquivo_id
    parcial.write_bytes(conteudo)
    digest = hashlib.sha256(conteudo).hexdigest()
    if hashlib.sha256(parcial.read_bytes()).hexdigest() != digest:
        parcial.unlink(missing_ok=True)
        raise OSError("hash divergente na copia")
    parcial.replace(final)
    return arquivo_id, digest


def interpretar_nome(nome: str) -> tuple[str, str] | None:
    bruto = nome.strip()
    if bruto.lower().count("-rev.") != 1:
        return None
    casou = NOME.match(bruto)
    if not casou:
        return None
    return casou.group(1), casou.group(2).upper()


def registrar_erro(conn, raiz: Path, nome: str, conteudo: bytes, motivo: str, detalhe: str) -> str:
    arquivo_id, digest = gravar_bytes(raiz, "quarentena", conteudo)
    conn.execute(
        """INSERT INTO arquivo
           (id, hash_sha256, tamanho_bytes, mime, nome_original, area, criado_em)
           VALUES (?, ?, ?, 'application/pdf', ?, 'quarentena', ?)""",
        (arquivo_id, digest, len(conteudo), nome, agora()),
    )
    erro_id = str(uuid.uuid4())
    conn.execute(
        """INSERT INTO erro_importacao
           (id, nome_original, arquivo_id, hash_sha256, motivo, detalhe, status, revisao_id, criado_em)
           VALUES (?, ?, ?, ?, ?, ?, 'aberto', NULL, ?)""",
        (erro_id, nome, arquivo_id, digest, motivo, detalhe, agora()),
    )
    return erro_id

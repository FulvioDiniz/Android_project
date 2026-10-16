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


def importar_pdf(conn, raiz: Path, nome: str, conteudo: bytes, origem: str, usuario_id: str | None) -> dict:
    if origem not in ("painel", "rede"):
        origem = "painel"
    partes = interpretar_nome(nome)
    if partes is None:
        erro_id = registrar_erro(
            conn,
            raiz,
            nome or "sem-nome",
            conteudo,
            "convencao_invalida",
            "O nome precisa ser CODIGO-REV.X.pdf, com um único marcador.",
        )
        return {"http": 202, "erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual", "origem": origem}
    codigo, revisao_codigo = partes
    quadros = conn.execute(
        "SELECT id, codigo, revisao_vigente_id FROM quadro WHERE lower(codigo) = lower(?)",
        (codigo,),
    ).fetchall()
    if len(quadros) == 0:
        erro_id = registrar_erro(conn, raiz, nome, conteudo, "quadro_nao_encontrado", codigo)
        return {"http": 202, "erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual", "origem": origem}
    if len(quadros) > 1:
        erro_id = registrar_erro(conn, raiz, nome, conteudo, "nome_ambiguo", codigo)
        return {"http": 202, "erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual", "origem": origem}
    quadro = quadros[0]
    arquivo_id, digest = gravar_bytes(raiz, "projetos", conteudo)
    existente = conn.execute(
        """SELECT r.id, a.hash_sha256 FROM revisao r
           JOIN arquivo a ON a.id = r.arquivo_id
           WHERE r.quadro_id = ? AND r.codigo = ?""",
        (quadro["id"], revisao_codigo),
    ).fetchone()
    if existente:
        (raiz / "projetos" / arquivo_id).unlink(missing_ok=True)
        if existente["hash_sha256"] == digest:
            ponteiro = quadro["revisao_vigente_id"]
            return {
                "http": 200,
                "revisao_id": existente["id"],
                "status": "pendente",
                "repetido": True,
                "origem": origem,
                "revisao_vigente_id": ponteiro,
                "hash_sha256": digest,
                "codigo": revisao_codigo,
            }
        erro_id = registrar_erro(conn, raiz, nome, conteudo, "revisao_duplicada", revisao_codigo)
        return {"http": 202, "erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual", "origem": origem}
    conn.execute(
        """INSERT INTO arquivo
           (id, hash_sha256, tamanho_bytes, mime, nome_original, area, criado_em)
           VALUES (?, ?, ?, 'application/pdf', ?, 'projetos', ?)""",
        (arquivo_id, digest, len(conteudo), nome, agora()),
    )
    revisao_id = str(uuid.uuid4())
    conn.execute(
        """INSERT INTO revisao
           (id, quadro_id, codigo, status, tipo, arquivo_id, motivo, importada_em, origem)
           VALUES (?, ?, ?, 'pendente', NULL, ?, NULL, ?, ?)""",
        (revisao_id, quadro["id"], revisao_codigo, arquivo_id, agora(), origem),
    )
    conn.execute(
        """INSERT INTO evento_auditoria
           (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
           VALUES (?, ?, NULL, 'importacao', 'revisao', ?, ?, NULL, ?)""",
        (agora(), usuario_id, revisao_id, quadro["id"], f"{nome} {digest}"),
    )
    ponteiro = conn.execute(
        "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
        (quadro["id"],),
    ).fetchone()["revisao_vigente_id"]
    return {
        "http": 201,
        "revisao_id": revisao_id,
        "codigo": revisao_codigo,
        "status": "pendente",
        "revisao_vigente_id": ponteiro,
        "hash_sha256": digest,
        "origem": origem,
    }


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

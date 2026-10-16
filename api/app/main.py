import asyncio
import hashlib
import json
import os
import re
import queue
import secrets
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import FastAPI, File, Request, UploadFile, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from app.backup import gerar_backup
from app.db import agora, connect, hash_token, init_db
from app.importacao import importar_pdf
from app.varredura import PASTA_PADRAO, ler_estado_pasta
from app.liberacao import motivo_recusa_etapa, salto_de_relogio

HASHER = PasswordHasher()
COOKIE = "quadros_sessao"


def erro(status: int, codigo: str, mensagem: str, extra: dict | None = None) -> JSONResponse:
    corpo: dict[str, Any] = {"erro": codigo, "mensagem": mensagem}
    if extra:
        corpo.update(extra)
    return JSONResponse(status_code=status, content=corpo)


def _vigente(conn: sqlite3.Connection, quadro_id: str | None) -> str | None:
    if not quadro_id:
        return None
    row = conn.execute(
        "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
        (quadro_id,),
    ).fetchone()
    return None if row is None else row["revisao_vigente_id"]


def _recusa(conn: sqlite3.Connection, status: int, codigo: str, mensagem: str, quadro_id: str) -> JSONResponse:
    return erro(
        status,
        codigo,
        mensagem,
        {"quadro_id": quadro_id, "revisao_vigente_id": _vigente(conn, quadro_id)},
    )


def _marcar_relogio(conn: sqlite3.Connection, dispositivo_id: str, ocorrido_em: str) -> None:
    ocorrido = datetime.fromisoformat(ocorrido_em)
    deslocamento = int((ocorrido - datetime.now(timezone.utc)).total_seconds() * 1000)
    conn.execute(
        "UPDATE dispositivo SET ultimo_sync_em = ?, deslocamento_relogio_ms = ? WHERE id = ?",
        (agora(), deslocamento, dispositivo_id),
    )


def _observacao(conn, sessao, corpo, operacao_id: str, digest: str, tipo: str = "observacao") -> JSONResponse:
    texto = str(corpo.get("texto") or "").strip()
    quadro_id = str(corpo.get("quadro_id") or "")
    ocorrido = str(corpo.get("ocorrido_em") or "")
    if not texto or not quadro_id or not ocorrido:
        return erro(422, "pedido_invalido", "Informe texto, quadro e horário.")
    if not conn.execute("SELECT 1 FROM quadro WHERE id = ?", (quadro_id,)).fetchone():
        return erro(404, "nao_encontrado", "Quadro não encontrado.")
    recebido = agora()
    obs_id = str(uuid.uuid4())
    conn.execute(
        """INSERT INTO observacao
           (id, quadro_id, revisao_id, tipo, texto, usuario_id, dispositivo_id, operacao_id, ocorrido_em, recebido_em)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (obs_id, quadro_id, corpo.get("revisao_id"), tipo, texto, sessao["usuario_id"], sessao["dispositivo_id"], operacao_id, ocorrido, recebido),
    )
    conn.execute(
        """INSERT INTO operacao_sincronizacao
           (operacao_id, dispositivo_id, usuario_id, tipo, payload_hash, resultado_http, erro_codigo, recurso_id, recebido_em)
           VALUES (?, ?, ?, ?, ?, 201, NULL, ?, ?)""",
        (operacao_id, sessao["dispositivo_id"], sessao["usuario_id"], tipo, digest, obs_id, recebido),
    )
    conn.execute(
        """INSERT INTO evento_auditoria
           (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
           VALUES (?, ?, ?, ?, 'observacao', ?, ?, ?, ?)""",
        (recebido, sessao["usuario_id"], sessao["dispositivo_id"], tipo, obs_id, quadro_id, operacao_id, texto),
    )
    if sessao["dispositivo_id"]:
        _marcar_relogio(conn, sessao["dispositivo_id"], ocorrido)
    ponteiro = _vigente(conn, quadro_id)
    conn.commit()
    return JSONResponse(
        {
            "id": obs_id,
            "quadro_id": quadro_id,
            "tipo": tipo,
            "texto": texto,
            "resposta": None,
            "ocorrido_em": ocorrido,
            "status": "aceita",
            "revisao_vigente_id": ponteiro,
        },
        status_code=201,
    )


def _pendencia(conn, sessao, corpo, operacao_id: str, digest: str) -> JSONResponse:
    descricao = str(corpo.get("descricao") or corpo.get("texto") or "").strip()
    quadro_id = str(corpo.get("quadro_id") or "")
    ocorrido = str(corpo.get("ocorrido_em") or "")
    if not descricao or not quadro_id or not ocorrido:
        return erro(422, "pedido_invalido", "Informe descrição, quadro e horário.")
    if not conn.execute("SELECT 1 FROM quadro WHERE id = ?", (quadro_id,)).fetchone():
        return erro(404, "nao_encontrado", "Quadro não encontrado.")
    bloqueia = 1 if corpo.get("bloqueia_etapa") in (True, 1, "1", "true") else 0
    recebido = agora()
    pendencia_id = str(uuid.uuid4())
    conn.execute(
        """INSERT INTO pendencia
           (id, quadro_id, revisao_id, descricao, status, bloqueia_etapa, usuario_id, dispositivo_id, operacao_id, ocorrido_em, recebido_em)
           VALUES (?, ?, ?, ?, 'aberta', ?, ?, ?, ?, ?, ?)""",
        (
            pendencia_id,
            quadro_id,
            corpo.get("revisao_id"),
            descricao,
            bloqueia,
            sessao["usuario_id"],
            sessao["dispositivo_id"],
            operacao_id,
            ocorrido,
            recebido,
        ),
    )
    conn.execute(
        """INSERT INTO operacao_sincronizacao
           (operacao_id, dispositivo_id, usuario_id, tipo, payload_hash, resultado_http, erro_codigo, recurso_id, recebido_em)
           VALUES (?, ?, ?, 'pendencia', ?, 201, NULL, ?, ?)""",
        (operacao_id, sessao["dispositivo_id"], sessao["usuario_id"], digest, pendencia_id, recebido),
    )
    conn.execute(
        """INSERT INTO evento_auditoria
           (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
           VALUES (?, ?, ?, 'pendencia', 'pendencia', ?, ?, ?, ?)""",
        (recebido, sessao["usuario_id"], sessao["dispositivo_id"], pendencia_id, quadro_id, operacao_id, descricao),
    )
    if sessao["dispositivo_id"]:
        _marcar_relogio(conn, sessao["dispositivo_id"], ocorrido)
    ponteiro = _vigente(conn, quadro_id)
    conn.commit()
    return JSONResponse(
        {
            "id": pendencia_id,
            "quadro_id": quadro_id,
            "descricao": descricao,
            "status": "aberta",
            "bloqueia_etapa": bool(bloqueia),
            "ocorrido_em": ocorrido,
            "revisao_vigente_id": ponteiro,
        },
        status_code=201,
    )


def _etapa(conn, sessao, corpo, operacao_id: str, digest: str, tipo: str) -> JSONResponse:
    quadro_id = str(corpo.get("quadro_id") or "")
    ocorrido = str(corpo.get("ocorrido_em") or "")
    if not quadro_id or not ocorrido or not sessao["dispositivo_id"]:
        return erro(422, "pedido_invalido", "Informe quadro, horário e use o tablet.")
    disp = conn.execute(
        "SELECT deslocamento_relogio_ms FROM dispositivo WHERE id = ?",
        (sessao["dispositivo_id"],),
    ).fetchone()
    offset = disp["deslocamento_relogio_ms"] if disp else None
    if salto_de_relogio(offset, ocorrido):
        conn.execute(
            """INSERT INTO evento_auditoria
               (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
               VALUES (?, ?, ?, 'bloqueio', 'etapa_producao', NULL, ?, ?, 'conflito de relogio')""",
            (agora(), sessao["usuario_id"], sessao["dispositivo_id"], quadro_id, operacao_id),
        )
        conn.commit()
        return _recusa(
            conn,
            409,
            "relogio_invalido",
            "Relógio do aparelho fora do deslocamento conhecido. A etapa não mudou.",
            quadro_id,
        )
    recusa = motivo_recusa_etapa(conn, sessao["dispositivo_id"], quadro_id, ocorrido)
    if recusa:
        conn.execute(
            """INSERT INTO evento_auditoria
               (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
               VALUES (?, ?, ?, 'bloqueio', 'etapa_producao', NULL, ?, ?, ?)""",
            (agora(), sessao["usuario_id"], sessao["dispositivo_id"], quadro_id, operacao_id, recusa),
        )
        conn.commit()
        return _recusa(conn, 409, recusa, "A API recusou a etapa.", quadro_id)
    if tipo == "etapa_concluir":
        aberta = conn.execute(
            """SELECT 1 FROM pendencia
               WHERE quadro_id = ? AND bloqueia_etapa = 1 AND status IN ('aberta', 'em_analise')""",
            (quadro_id,),
        ).fetchone()
        if aberta:
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, ?, 'bloqueio', 'etapa_producao', NULL, ?, ?, 'bloqueio_pendencia')""",
                (agora(), sessao["usuario_id"], sessao["dispositivo_id"], quadro_id, operacao_id),
            )
            conn.commit()
            return _recusa(conn, 409, "bloqueio_pendencia", "Pendência aberta impede concluir a etapa.", quadro_id)
    codigo = str(corpo.get("etapa_codigo") or "fiacao")
    catalogo = conn.execute("SELECT id FROM etapa_catalogo WHERE codigo = ?", (codigo,)).fetchone()
    if catalogo is None:
        return erro(422, "pedido_invalido", "Etapa desconhecida.")
    atual = conn.execute(
        "SELECT * FROM etapa_producao WHERE quadro_id = ? AND etapa_catalogo_id = ?",
        (quadro_id, catalogo["id"]),
    ).fetchone()
    if tipo == "etapa_concluir":
        if atual and atual["status"] == "concluida":
            status_final = "concluida"
            etapa_id = atual["id"]
        elif atual:
            conn.execute(
                """UPDATE etapa_producao
                   SET status = 'concluida', concluida_por = ?, concluida_em = ?, operacao_conclusao_id = ?
                   WHERE id = ?""",
                (sessao["usuario_id"], ocorrido, operacao_id, atual["id"]),
            )
            etapa_id = atual["id"]
            status_final = "concluida"
        else:
            etapa_id = str(uuid.uuid4())
            conn.execute(
                """INSERT INTO etapa_producao
                   (id, quadro_id, etapa_catalogo_id, status, concluida_por, concluida_em, operacao_conclusao_id)
                   VALUES (?, ?, ?, 'concluida', ?, ?, ?)""",
                (etapa_id, quadro_id, catalogo["id"], sessao["usuario_id"], ocorrido, operacao_id),
            )
            status_final = "concluida"
    else:
        if atual and atual["status"] == "em_andamento":
            etapa_id = atual["id"]
            status_final = "em_andamento"
        else:
            etapa_id = str(uuid.uuid4())
            conn.execute(
                """INSERT INTO etapa_producao
                   (id, quadro_id, etapa_catalogo_id, status, iniciada_por, iniciada_em, operacao_inicio_id)
                   VALUES (?, ?, ?, 'em_andamento', ?, ?, ?)""",
                (etapa_id, quadro_id, catalogo["id"], sessao["usuario_id"], ocorrido, operacao_id),
            )
            status_final = "em_andamento"
    conn.execute(
        """INSERT INTO operacao_sincronizacao
           (operacao_id, dispositivo_id, usuario_id, tipo, payload_hash, resultado_http, erro_codigo, recurso_id, recebido_em)
           VALUES (?, ?, ?, ?, ?, 200, NULL, ?, ?)""",
        (operacao_id, sessao["dispositivo_id"], sessao["usuario_id"], tipo, digest, etapa_id, agora()),
    )
    conn.execute(
        """INSERT INTO evento_auditoria
           (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
           VALUES (?, ?, ?, 'mudanca_etapa', 'etapa_producao', ?, ?, ?, ?)""",
        (agora(), sessao["usuario_id"], sessao["dispositivo_id"], etapa_id, quadro_id, operacao_id, status_final),
    )
    _marcar_relogio(conn, sessao["dispositivo_id"], ocorrido)
    ponteiro = _vigente(conn, quadro_id)
    conn.commit()
    return JSONResponse(
        {
            "id": etapa_id,
            "quadro_id": quadro_id,
            "status": status_final,
            "ocorrido_em": ocorrido,
            "revisao_vigente_id": ponteiro,
        }
    )


def create_app(db_path: str | None = None, arquivos_dir: str | None = None) -> FastAPI:
    path = db_path or os.environ.get("QUADROS_DB", "data/quadros.sqlite")
    init_db(path)
    app = FastAPI(title="Quadros")
    app.state.db_path = path
    app.state.arquivos = Path(arquivos_dir or os.environ.get("QUADROS_ARQUIVOS") or str(Path(path).parent / "arquivos"))
    app.state.avisos = []
    app.state.backups = Path(path).parent / "backups"
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    def db() -> sqlite3.Connection:
        return connect(app.state.db_path)

    def auditar(conn: sqlite3.Connection, acao: str, entidade: str, entidade_id: str | None, usuario_id: str | None, dispositivo_id: str | None, detalhe: str) -> None:
        conn.execute(
            """INSERT INTO evento_auditoria
               (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
               VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)""",
            (agora(), usuario_id, dispositivo_id, acao, entidade, entidade_id, detalhe),
        )

    def sessao_atual(request: Request, conn: sqlite3.Connection) -> sqlite3.Row | None:
        bruto = request.headers.get("authorization", "")
        token = ""
        if bruto.lower().startswith("bearer "):
            token = bruto[7:].strip()
        if not token:
            token = request.cookies.get(COOKIE, "")
        if not token:
            return None
        row = conn.execute(
            """SELECT s.*, u.nome AS usuario_nome, u.login, u.ativo AS usuario_ativo, p.codigo AS perfil
               FROM sessao s
               JOIN usuario u ON u.id = s.usuario_id
               JOIN perfil p ON p.id = u.perfil_id
               WHERE s.token_hash = ?""",
            (hash_token(token),),
        ).fetchone()
        if row is None or row["revogada_em"] or not row["usuario_ativo"]:
            return None
        if row["dispositivo_id"]:
            aparelho = conn.execute(
                "SELECT status FROM dispositivo WHERE id = ?",
                (row["dispositivo_id"],),
            ).fetchone()
            if aparelho is None or aparelho["status"] != "ativo":
                return None
        if datetime.fromisoformat(row["expira_em"]) <= datetime.now(timezone.utc):
            return None
        conn.execute("UPDATE sessao SET ultimo_uso_em = ? WHERE id = ?", (agora(), row["id"]))
        return row

    def exigir(request: Request, conn: sqlite3.Connection) -> sqlite3.Row | JSONResponse:
        sessao = sessao_atual(request, conn)
        if sessao is None:
            return erro(401, "sessao_revogada", "Sessão ausente ou vencida.")
        return sessao

    @app.get("/api/saude")
    def saude() -> JSONResponse:
        try:
            with db() as conn:
                conn.execute("SELECT 1").fetchone()
        except sqlite3.Error:
            return erro(503, "banco_indisponivel", "O arquivo do banco não abre.")
        return JSONResponse({"status": "ok"})

    @app.post("/api/sessao")
    def entrar(corpo: dict[str, Any]) -> JSONResponse:
        login = str(corpo.get("login") or "").strip()
        senha = str(corpo.get("senha") or "")
        instalacao = corpo.get("identificador_instalacao")
        if not login or not senha:
            return erro(422, "pedido_invalido", "Informe login e senha.")
        with db() as conn:
            usuario = conn.execute(
                """SELECT u.*, p.codigo AS perfil FROM usuario u
                   JOIN perfil p ON p.id = u.perfil_id WHERE u.login = ?""",
                (login,),
            ).fetchone()
            if usuario is None or not usuario["ativo"]:
                return erro(401, "credencial_invalida", "Login ou senha não confere.")
            try:
                HASHER.verify(usuario["senha_hash"], senha)
            except VerifyMismatchError:
                return erro(401, "credencial_invalida", "Login ou senha não confere.")
            dispositivo_id = None
            horas = 8
            if instalacao:
                horas = 12
                ident = str(instalacao).strip()
                aparelho = conn.execute(
                    "SELECT * FROM dispositivo WHERE identificador_instalacao = ?",
                    (ident,),
                ).fetchone()
                if aparelho is None:
                    novo = str(uuid.uuid4())
                    conn.execute(
                        """INSERT INTO dispositivo
                           (id, identificador_instalacao, apelido, status, ultimo_visto_em)
                           VALUES (?, ?, ?, 'pendente', ?)""",
                        (novo, ident, "Tablet novo", agora()),
                    )
                    conn.commit()
                    return erro(403, "dispositivo_inativo", "Tablet ainda não liberado.")
                if aparelho["status"] != "ativo":
                    return erro(403, "dispositivo_inativo", "Tablet bloqueado ou pendente.")
                dispositivo_id = aparelho["id"]
                conn.execute(
                    "UPDATE dispositivo SET ultimo_visto_em = ? WHERE id = ?",
                    (agora(), dispositivo_id),
                )
            elif usuario["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "Montagem entra pelo tablet.")
            token = secrets.token_urlsafe(32)
            expira = datetime.now(timezone.utc) + timedelta(hours=horas)
            sid = str(uuid.uuid4())
            conn.execute(
                """INSERT INTO sessao
                   (id, token_hash, usuario_id, dispositivo_id, criada_em, expira_em, revogada_em, ultimo_uso_em)
                   VALUES (?, ?, ?, ?, ?, ?, NULL, ?)""",
                (sid, hash_token(token), usuario["id"], dispositivo_id, agora(), expira.isoformat(), agora()),
            )
            auditar(conn, "sessao", "sessao", sid, usuario["id"], dispositivo_id, "abertura")
            conn.commit()
        resposta = JSONResponse(
            {
                "token": token,
                "expira_em": expira.isoformat(),
                "usuario": {"id": usuario["id"], "nome": usuario["nome"], "login": usuario["login"]},
                "perfil": usuario["perfil"],
                "dispositivo_id": dispositivo_id,
            }
        )
        resposta.set_cookie(
            COOKIE,
            token,
            httponly=True,
            samesite="strict",
            secure=False,
            max_age=horas * 3600,
        )
        return resposta

    @app.delete("/api/sessao")
    def sair(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            conn.execute(
                "UPDATE sessao SET revogada_em = ? WHERE id = ?",
                (agora(), sessao["id"]),
            )
            auditar(conn, "sessao", "sessao", sessao["id"], sessao["usuario_id"], sessao["dispositivo_id"], "revogacao")
            conn.commit()
        resposta = JSONResponse({"status": "encerrada"})
        resposta.delete_cookie(COOKIE)
        return resposta

    @app.get("/api/quadros")
    def listar_quadros(request: Request, q: str = "") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            termo = f"%{q.strip().lower()}%"
            rows = conn.execute(
                """SELECT q.id, q.codigo, q.titulo, q.status, q.revisao_vigente_id,
                          rv.codigo AS revisao_vigente_codigo,
                          rv.arquivo_id AS arquivo_id,
                          rv.tipo AS revisao_tipo,
                          op.numero AS ordem_numero, c.nome AS cliente_nome,
                          u.nome AS responsavel_nome, b.nome AS bancada_nome
                   FROM quadro q
                   JOIN ordem_producao op ON op.id = q.ordem_producao_id
                   JOIN cliente c ON c.id = op.cliente_id
                   LEFT JOIN revisao rv ON rv.id = q.revisao_vigente_id
                   LEFT JOIN usuario u ON u.id = q.responsavel_id
                   LEFT JOIN bancada b ON b.id = q.bancada_id
                   WHERE q.status != 'cancelado'
                     AND (? = '%%' OR lower(q.codigo) LIKE ? OR lower(op.numero) LIKE ?
                          OR lower(c.nome) LIKE ? OR lower(coalesce(u.nome, '')) LIKE ?)
                   ORDER BY q.codigo""",
                (termo, termo, termo, termo, termo),
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.get("/api/quadros/{quadro_id}")
    def obter_quadro(quadro_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            row = conn.execute(
                """SELECT q.id, q.codigo, q.titulo, q.status, q.revisao_vigente_id,
                          rv.codigo AS revisao_vigente_codigo, rv.arquivo_id AS arquivo_id,
                          op.id AS ordem_producao_id, op.numero AS ordem_numero,
                          c.nome AS cliente_nome
                   FROM quadro q
                   JOIN ordem_producao op ON op.id = q.ordem_producao_id
                   JOIN cliente c ON c.id = op.cliente_id
                   LEFT JOIN revisao rv ON rv.id = q.revisao_vigente_id
                   WHERE q.id = ?""",
                (quadro_id,),
            ).fetchone()
            if row is None:
                return erro(404, "nao_encontrado", "Quadro não encontrado.")
            dados = dict(row)
            arquivo_id = dados.get("arquivo_id")
            if not arquivo_id:
                dados["arquivo_disponivel"] = True
            else:
                guardado = conn.execute("SELECT area FROM arquivo WHERE id = ?", (arquivo_id,)).fetchone()
                dados["arquivo_disponivel"] = bool(
                    guardado and (app.state.arquivos / guardado["area"] / arquivo_id).is_file()
                )
            conn.commit()
        return JSONResponse(dados)

    @app.post("/api/ordens-producao")
    def criar_ordem(corpo: dict[str, Any], request: Request) -> JSONResponse:
        numero = str(corpo.get("numero") or "").strip()
        cliente_nome = str(corpo.get("cliente_nome") or "").strip()
        descricao = str(corpo.get("descricao") or "")
        if not numero or not cliente_nome:
            return erro(422, "pedido_invalido", "Informe o número da ordem e o cliente.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] != "engenharia":
                return erro(403, "perfil_insuficiente", "Só engenharia cadastra ordem.")
            existente = conn.execute(
                "SELECT id, numero FROM ordem_producao WHERE numero = ?",
                (numero,),
            ).fetchone()
            if existente:
                return JSONResponse({"id": existente["id"], "numero": existente["numero"]})
            cliente = conn.execute(
                "SELECT id FROM cliente WHERE nome = ?",
                (cliente_nome,),
            ).fetchone()
            if cliente is None:
                cliente_id = str(uuid.uuid4())
                conn.execute(
                    "INSERT INTO cliente (id, nome, codigo) VALUES (?, ?, NULL)",
                    (cliente_id, cliente_nome),
                )
            else:
                cliente_id = cliente["id"]
            ordem_id = str(uuid.uuid4())
            conn.execute(
                """INSERT INTO ordem_producao
                   (id, numero, cliente_id, descricao, status, prazo, criado_em)
                   VALUES (?, ?, ?, ?, 'aberta', NULL, ?)""",
                (ordem_id, numero, cliente_id, descricao, agora()),
            )
            conn.commit()
        return JSONResponse({"id": ordem_id, "numero": numero}, status_code=201)

    @app.post("/api/quadros")
    def criar_quadro(corpo: dict[str, Any], request: Request) -> JSONResponse:
        codigo = str(corpo.get("codigo") or "").strip()
        titulo = str(corpo.get("titulo") or "").strip()
        ordem_id = str(corpo.get("ordem_producao_id") or "").strip()
        if not codigo or not titulo or not ordem_id:
            return erro(422, "pedido_invalido", "Informe código, título e ordem.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] != "engenharia":
                return erro(403, "perfil_insuficiente", "Só engenharia cadastra quadro.")
            antes = conn.execute("SELECT COUNT(*) AS n FROM quadro").fetchone()["n"]
            if conn.execute("SELECT 1 FROM quadro WHERE codigo = ?", (codigo,)).fetchone():
                return erro(409, "pedido_invalido", "Esse código de quadro já existe.")
            if not conn.execute("SELECT 1 FROM ordem_producao WHERE id = ?", (ordem_id,)).fetchone():
                return erro(422, "pedido_invalido", "Ordem não encontrada.")
            qid = str(uuid.uuid4())
            momento = agora()
            conn.execute(
                """INSERT INTO quadro
                   (id, codigo, ordem_producao_id, titulo, responsavel_id, bancada_id,
                    revisao_vigente_id, status, criado_em, atualizado_em)
                   VALUES (?, ?, ?, ?, ?, NULL, NULL, 'planejado', ?, ?)""",
                (qid, codigo, ordem_id, titulo, sessao["usuario_id"], momento, momento),
            )
            conn.commit()
            depois = conn.execute("SELECT COUNT(*) AS n FROM quadro").fetchone()["n"]
        if depois != antes + 1:
            return erro(503, "banco_indisponivel", "O quadro não foi gravado.")
        return JSONResponse({"id": qid, "codigo": codigo}, status_code=201)

    @app.post("/api/projetos")
    async def enviar_projeto(request: Request, arquivo: UploadFile = File(...)) -> JSONResponse:
        nome = arquivo.filename or ""
        conteudo = await arquivo.read()
        if not conteudo:
            return erro(422, "pedido_invalido", "O arquivo chegou vazio.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] != "engenharia":
                return erro(403, "perfil_insuficiente", "Só engenharia envia projeto.")
            resultado = importar_pdf(conn, app.state.arquivos, nome, conteudo, "painel", sessao["usuario_id"])
            conn.commit()
        status = resultado.pop("http")
        return JSONResponse(resultado, status_code=status)

    @app.get("/api/revisoes")
    def listar_revisoes(request: Request, status: str = "pendente") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "A fila de revisão é do painel.")
            rows = conn.execute(
                """SELECT r.id, r.codigo, r.status, r.tipo, r.origem, q.codigo AS quadro_codigo, q.revisao_vigente_id
                   FROM revisao r JOIN quadro q ON q.id = r.quadro_id
                   WHERE r.status = ? ORDER BY r.importada_em""",
                (status,),
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.get("/api/erros-importacao")
    def listar_erros(request: Request, status: str = "aberto") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "A validação manual é do painel.")
            rows = conn.execute(
                """SELECT id, nome_original, motivo, detalhe, status, revisao_id
                   FROM erro_importacao WHERE status = ? ORDER BY criado_em""",
                (status,),
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.get("/api/pasta-rede")
    def estado_da_pasta(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "A pasta de rede é do painel.")
            estado = ler_estado_pasta(conn)
            conn.commit()
        return JSONResponse(estado)

    @app.get("/api/bancadas")
    def listar_bancadas(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "Bancadas são do painel.")
            rows = conn.execute(
                "SELECT id, codigo, nome FROM bancada WHERE ativa = 1 ORDER BY codigo"
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    def somente_engenharia(sessao: sqlite3.Row) -> JSONResponse | None:
        if sessao["perfil"] != "engenharia":
            return erro(403, "perfil_insuficiente", "Só engenharia decide revisão.")
        return None

    @app.post("/api/revisoes/{revisao_id}/aprovacao")
    def aprovar(revisao_id: str, corpo: dict[str, Any], request: Request) -> JSONResponse:
        tipo = str(corpo.get("tipo") or "")
        if tipo not in ("informativa", "reavaliacao", "bloqueio"):
            return erro(422, "pedido_invalido", "Tipo inválido.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_engenharia(sessao)
            if recusa:
                return recusa
            rev = conn.execute("SELECT * FROM revisao WHERE id = ?", (revisao_id,)).fetchone()
            if rev is None:
                return erro(404, "nao_encontrado", "Revisão não encontrada.")
            if rev["status"] != "pendente":
                return erro(409, "revisao_nao_aprovada", "Só revisão pendente pode ser aprovada.")
            ponteiro_antes = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (rev["quadro_id"],),
            ).fetchone()["revisao_vigente_id"]
            conn.execute(
                """UPDATE revisao SET status = 'aprovada', tipo = ?, decidida_por = ?, decidida_em = ?
                   WHERE id = ?""",
                (tipo, sessao["usuario_id"], agora(), revisao_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, NULL, 'aprovacao', 'revisao', ?, ?, NULL, ?)""",
                (agora(), sessao["usuario_id"], revisao_id, rev["quadro_id"], tipo),
            )
            ponteiro = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (rev["quadro_id"],),
            ).fetchone()["revisao_vigente_id"]
            conn.commit()
        return JSONResponse({"id": revisao_id, "status": "aprovada", "tipo": tipo, "revisao_vigente_id": ponteiro, "ponteiro_antes": ponteiro_antes})

    @app.post("/api/revisoes/{revisao_id}/rejeicao")
    def rejeitar(revisao_id: str, corpo: dict[str, Any], request: Request) -> JSONResponse:
        motivo = str(corpo.get("motivo") or "").strip()
        if not motivo:
            return erro(409, "motivo_obrigatorio", "Rejeitar exige motivo.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_engenharia(sessao)
            if recusa:
                return recusa
            rev = conn.execute("SELECT * FROM revisao WHERE id = ?", (revisao_id,)).fetchone()
            if rev is None:
                return erro(404, "nao_encontrado", "Revisão não encontrada.")
            if rev["status"] != "pendente":
                return erro(409, "revisao_nao_aprovada", "Só revisão pendente pode ser rejeitada.")
            ponteiro_antes = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (rev["quadro_id"],),
            ).fetchone()["revisao_vigente_id"]
            conn.execute(
                """UPDATE revisao SET status = 'rejeitada', motivo = ?, decidida_por = ?, decidida_em = ?
                   WHERE id = ?""",
                (motivo, sessao["usuario_id"], agora(), revisao_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, NULL, 'rejeicao', 'revisao', ?, ?, NULL, ?)""",
                (agora(), sessao["usuario_id"], revisao_id, rev["quadro_id"], motivo),
            )
            ponteiro = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (rev["quadro_id"],),
            ).fetchone()["revisao_vigente_id"]
            conn.commit()
        if ponteiro != ponteiro_antes:
            return erro(503, "banco_indisponivel", "O ponteiro mudou na rejeição.")
        return JSONResponse({"id": revisao_id, "status": "rejeitada", "revisao_vigente_id": ponteiro})

    @app.post("/api/quadros/{quadro_id}/publicacao")
    def publicar(quadro_id: str, corpo: dict[str, Any], request: Request) -> JSONResponse:
        revisao_id = str(corpo.get("revisao_id") or "").strip()
        if not revisao_id:
            return erro(422, "pedido_invalido", "Informe a revisão.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_engenharia(sessao)
            if recusa:
                return recusa
            visto = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (quadro_id,),
            ).fetchone()
            if visto is None:
                return erro(404, "nao_encontrado", "Quadro não encontrado.")
            ponteiro_visto = visto["revisao_vigente_id"]
            gancho = getattr(app.state, "antes_da_trava", None)
            if gancho:
                gancho()
            conn.execute("BEGIN IMMEDIATE")
            try:
                quadro = conn.execute(
                    "SELECT id, revisao_vigente_id FROM quadro WHERE id = ?",
                    (quadro_id,),
                ).fetchone()
                rev = conn.execute("SELECT * FROM revisao WHERE id = ?", (revisao_id,)).fetchone()
                if rev is None or rev["quadro_id"] != quadro_id:
                    conn.rollback()
                    return erro(404, "nao_encontrado", "Revisão não encontrada neste quadro.")
                if rev["status"] == "publicada" and quadro["revisao_vigente_id"] == revisao_id:
                    ja = conn.execute(
                        "SELECT * FROM publicacao WHERE revisao_id = ? ORDER BY publicada_em DESC LIMIT 1",
                        (revisao_id,),
                    ).fetchone()
                    conn.commit()
                    return JSONResponse(dict(ja) if ja else {"revisao_id": revisao_id, "status": "publicada"})
                if quadro["revisao_vigente_id"] != ponteiro_visto:
                    conn.rollback()
                    return erro(409, "revisao_nao_aprovada", "Outra publicação já entrou. Esta revisão continua como estava.")
                if rev["status"] != "aprovada" or not rev["tipo"]:
                    conn.rollback()
                    return erro(409, "revisao_nao_aprovada", "Só revisão aprovada com tipo pode ser publicada.")
                anterior = quadro["revisao_vigente_id"]
                momento = agora()
                conn.execute(
                    "INSERT INTO trava_publicacao (id, quadro_id, aberta_em) VALUES (1, ?, ?)",
                    (quadro_id, momento),
                )
                if anterior:
                    conn.execute(
                        "UPDATE revisao SET status = 'substituida' WHERE id = ? AND status = 'publicada'",
                        (anterior,),
                    )
                conn.execute(
                    "UPDATE revisao SET status = 'publicada' WHERE id = ?",
                    (revisao_id,),
                )
                conn.execute(
                    "UPDATE quadro SET revisao_vigente_id = ?, atualizado_em = ? WHERE id = ?",
                    (revisao_id, momento, quadro_id),
                )
                publicacao_id = str(uuid.uuid4())
                conn.execute(
                    """INSERT INTO publicacao
                       (id, quadro_id, revisao_id, revisao_anterior_id, publicada_por, publicada_em, motivo)
                       VALUES (?, ?, ?, ?, ?, ?, NULL)""",
                    (publicacao_id, quadro_id, revisao_id, anterior, sessao["usuario_id"], momento),
                )
                conn.execute(
                    """INSERT INTO evento_auditoria
                       (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                       VALUES (?, ?, NULL, 'publicacao', 'publicacao', ?, ?, NULL, ?)""",
                    (momento, sessao["usuario_id"], publicacao_id, quadro_id, revisao_id),
                )
                conn.execute(
                    """INSERT INTO notificacao (id, quadro_id, revisao_id, tipo, criada_em)
                       VALUES (?, ?, ?, 'revisao_publicada', ?)""",
                    (str(uuid.uuid4()), quadro_id, revisao_id, momento),
                )
                conn.execute("DELETE FROM trava_publicacao WHERE id = 1")
                conn.commit()
                aviso = {
                    "tipo": "revisao_publicada",
                    "quadro_id": quadro_id,
                    "revisao_id": revisao_id,
                    "codigo": rev["codigo"],
                    "tipo_revisao": rev["tipo"],
                }
                for fila in list(app.state.avisos):
                    fila.put(aviso)
            except sqlite3.Error:
                conn.rollback()
                return erro(409, "revisao_nao_aprovada", "A publicação não fechou.")
        return JSONResponse(
            {"id": publicacao_id, "revisao_id": revisao_id, "revisao_anterior_id": anterior, "status": "publicada"},
            status_code=201,
        )

    def somente_admin(sessao: sqlite3.Row) -> JSONResponse | None:
        if sessao["perfil"] != "admin":
            return erro(403, "perfil_insuficiente", "Usuário e dispositivo são do admin.")
        return None

    @app.post("/api/quadros/{quadro_id}/cancelamento")
    def cancelar(quadro_id: str, corpo: dict[str, Any], request: Request) -> JSONResponse:
        motivo = str(corpo.get("motivo") or "").strip()
        if not motivo:
            return erro(409, "motivo_obrigatorio", "Cancelar exige motivo.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_engenharia(sessao)
            if recusa:
                return recusa
            conn.execute("BEGIN IMMEDIATE")
            try:
                quadro = conn.execute("SELECT * FROM quadro WHERE id = ?", (quadro_id,)).fetchone()
                if quadro is None or not quadro["revisao_vigente_id"]:
                    conn.rollback()
                    return erro(404, "nao_encontrado", "Não há vigente para cancelar.")
                vigente_id = quadro["revisao_vigente_id"]
                if conn.execute(
                    "SELECT 1 FROM confirmacao_leitura WHERE revisao_id = ?",
                    (vigente_id,),
                ).fetchone():
                    conn.rollback()
                    return erro(409, "cancelamento_com_leitura", "A bancada já reconheceu esta revisão. A correção é uma revisão nova.")
                origem = conn.execute(
                    """SELECT revisao_anterior_id FROM publicacao
                       WHERE quadro_id = ? AND revisao_id = ? AND motivo IS NULL
                       ORDER BY publicada_em DESC LIMIT 1""",
                    (quadro_id, vigente_id),
                ).fetchone()
                if origem is None:
                    conn.rollback()
                    return erro(409, "revisao_nao_aprovada", "A vigente não tem publicação para desfazer.")
                anterior = origem["revisao_anterior_id"]
                momento = agora()
                conn.execute(
                    "INSERT INTO trava_publicacao (id, quadro_id, aberta_em) VALUES (1, ?, ?)",
                    (quadro_id, momento),
                )
                cancelada = conn.execute(
                    """UPDATE revisao SET status = 'cancelada', motivo = ?
                       WHERE id = ? AND status = 'publicada'""",
                    (motivo, vigente_id),
                )
                if cancelada.rowcount != 1:
                    conn.rollback()
                    return erro(409, "revisao_nao_aprovada", "A vigente não está publicada.")
                if anterior:
                    restaurada = conn.execute(
                        """UPDATE revisao SET status = 'publicada'
                           WHERE id = ? AND quadro_id = ? AND status = 'substituida'""",
                        (anterior, quadro_id),
                    )
                    if restaurada.rowcount != 1:
                        conn.rollback()
                        return erro(409, "revisao_nao_aprovada", "A revisão anterior não pôde voltar.")
                conn.execute(
                    "UPDATE quadro SET revisao_vigente_id = ?, atualizado_em = ? WHERE id = ?",
                    (anterior, momento, quadro_id),
                )
                publicacao_id = str(uuid.uuid4())
                conn.execute(
                    """INSERT INTO publicacao
                       (id, quadro_id, revisao_id, revisao_anterior_id, publicada_por, publicada_em, motivo)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    (publicacao_id, quadro_id, vigente_id, anterior, sessao["usuario_id"], momento, motivo),
                )
                conn.execute(
                    """INSERT INTO evento_auditoria
                       (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                       VALUES (?, ?, NULL, 'cancelamento', 'publicacao', ?, ?, NULL, ?)""",
                    (momento, sessao["usuario_id"], publicacao_id, quadro_id, motivo),
                )
                conn.execute("DELETE FROM trava_publicacao WHERE id = 1")
                conn.commit()
            except sqlite3.Error:
                conn.rollback()
                return erro(409, "revisao_nao_aprovada", "O cancelamento não fechou.")
        return JSONResponse(
            {
                "id": publicacao_id,
                "revisao_id": vigente_id,
                "revisao_vigente_id": anterior,
                "status": "cancelada",
                "motivo": motivo,
            }
        )

    @app.get("/api/usuarios")
    def listar_usuarios(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            rows = conn.execute(
                """SELECT u.id, u.nome, u.login, u.ativo, p.codigo AS perfil
                   FROM usuario u JOIN perfil p ON p.id = u.perfil_id
                   ORDER BY u.nome"""
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.post("/api/usuarios")
    def criar_usuario(corpo: dict[str, Any], request: Request) -> JSONResponse:
        nome = str(corpo.get("nome") or "").strip()
        login = str(corpo.get("login") or "").strip()
        senha = str(corpo.get("senha") or "")
        perfil = str(corpo.get("perfil") or "").strip()
        if not nome or not login or not senha or perfil not in ("admin", "engenharia", "supervisao", "montagem"):
            return erro(422, "pedido_invalido", "Informe nome, login, senha e um perfil.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            if conn.execute("SELECT 1 FROM usuario WHERE login = ?", (login,)).fetchone():
                return erro(422, "pedido_invalido", "Login já existe.")
            perfil_id = conn.execute("SELECT id FROM perfil WHERE codigo = ?", (perfil,)).fetchone()["id"]
            usuario_id = str(uuid.uuid4())
            conn.execute(
                """INSERT INTO usuario (id, nome, login, senha_hash, perfil_id, ativo, criado_em)
                   VALUES (?, ?, ?, ?, ?, 1, ?)""",
                (usuario_id, nome, login, HASHER.hash(senha), perfil_id, agora()),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, NULL, 'usuario', 'usuario', ?, NULL, NULL, ?)""",
                (agora(), sessao["usuario_id"], usuario_id, perfil),
            )
            conn.commit()
        return JSONResponse({"id": usuario_id, "nome": nome, "login": login, "perfil": perfil, "ativo": 1}, status_code=201)

    @app.post("/api/usuarios/{usuario_id}/bloqueio")
    def bloquear_usuario(usuario_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            alvo = conn.execute("SELECT id FROM usuario WHERE id = ?", (usuario_id,)).fetchone()
            if alvo is None:
                return erro(404, "nao_encontrado", "Usuário não encontrado.")
            momento = agora()
            conn.execute("UPDATE usuario SET ativo = 0 WHERE id = ?", (usuario_id,))
            conn.execute(
                "UPDATE sessao SET revogada_em = ? WHERE usuario_id = ? AND revogada_em IS NULL",
                (momento, usuario_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, NULL, 'usuario', 'usuario', ?, NULL, NULL, 'bloqueio')""",
                (momento, sessao["usuario_id"], usuario_id),
            )
            conn.commit()
        return JSONResponse({"id": usuario_id, "ativo": 0})

    @app.get("/api/dispositivos")
    def listar_dispositivos(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            rows = conn.execute(
                """SELECT id, identificador_instalacao, apelido, bancada_id, status, ultimo_visto_em
                   FROM dispositivo ORDER BY apelido"""
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.post("/api/dispositivos/{dispositivo_id}/vinculo")
    def vincular_dispositivo(dispositivo_id: str, corpo: dict[str, Any], request: Request) -> JSONResponse:
        bancada_id = str(corpo.get("bancada_id") or "").strip()
        if not bancada_id:
            return erro(422, "pedido_invalido", "Informe a bancada.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            if conn.execute("SELECT 1 FROM dispositivo WHERE id = ?", (dispositivo_id,)).fetchone() is None:
                return erro(404, "nao_encontrado", "Tablet não encontrado.")
            if conn.execute("SELECT 1 FROM bancada WHERE id = ?", (bancada_id,)).fetchone() is None:
                return erro(404, "nao_encontrado", "Bancada não encontrada.")
            conn.execute(
                "UPDATE dispositivo SET bancada_id = ? WHERE id = ?",
                (bancada_id, dispositivo_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, ?, 'dispositivo', 'dispositivo', ?, NULL, NULL, ?)""",
                (agora(), sessao["usuario_id"], dispositivo_id, dispositivo_id, bancada_id),
            )
            conn.commit()
        return JSONResponse({"id": dispositivo_id, "bancada_id": bancada_id})

    @app.post("/api/dispositivos/{dispositivo_id}/ativacao")
    def ativar_dispositivo(dispositivo_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            aparelho = conn.execute("SELECT * FROM dispositivo WHERE id = ?", (dispositivo_id,)).fetchone()
            if aparelho is None:
                return erro(404, "nao_encontrado", "Tablet não encontrado.")
            if aparelho["status"] == "bloqueado":
                return erro(422, "pedido_invalido", "Tablet bloqueado não volta por esta rota.")
            if aparelho["status"] != "ativo":
                conn.execute("UPDATE dispositivo SET status = 'ativo' WHERE id = ?", (dispositivo_id,))
                conn.execute(
                    """INSERT INTO evento_auditoria
                       (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                       VALUES (?, ?, ?, 'dispositivo', 'dispositivo', ?, NULL, NULL, 'ativacao')""",
                    (agora(), sessao["usuario_id"], dispositivo_id, dispositivo_id),
                )
                conn.commit()
        return JSONResponse({"id": dispositivo_id, "status": "ativo"})

    @app.post("/api/dispositivos/{dispositivo_id}/bloqueio")
    def bloquear_dispositivo(dispositivo_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            aparelho = conn.execute("SELECT id FROM dispositivo WHERE id = ?", (dispositivo_id,)).fetchone()
            if aparelho is None:
                return erro(404, "nao_encontrado", "Tablet não encontrado.")
            momento = agora()
            conn.execute("UPDATE dispositivo SET status = 'bloqueado' WHERE id = ?", (dispositivo_id,))
            conn.execute(
                "UPDATE sessao SET revogada_em = ? WHERE dispositivo_id = ? AND revogada_em IS NULL",
                (momento, dispositivo_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, ?, 'dispositivo', 'dispositivo', ?, NULL, NULL, 'bloqueio')""",
                (momento, sessao["usuario_id"], dispositivo_id, dispositivo_id),
            )
            conn.commit()
        return JSONResponse({"id": dispositivo_id, "status": "bloqueado"})

    @app.get("/api/auditoria")
    def listar_auditoria(request: Request, quadro_id: str = "", acao: str = "") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] not in ("admin", "engenharia", "supervisao"):
                return erro(403, "perfil_insuficiente", "Auditoria é do painel.")
            consulta = """SELECT e.id, e.ocorrido_em, e.acao, e.entidade, e.entidade_id, e.quadro_id,
                                 e.detalhe, e.ator_usuario_id, u.nome AS ator_nome, q.codigo AS quadro_codigo
                          FROM evento_auditoria e
                          LEFT JOIN usuario u ON u.id = e.ator_usuario_id
                          LEFT JOIN quadro q ON q.id = e.quadro_id
                          WHERE 1 = 1"""
            parametros: list[str] = []
            if quadro_id:
                consulta += " AND e.quadro_id = ?"
                parametros.append(quadro_id)
            if acao:
                consulta += " AND e.acao = ?"
                parametros.append(acao)
            consulta += " ORDER BY e.id"
            rows = conn.execute(consulta, parametros).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.post("/api/backup")
    def criar_backup(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            recusa = somente_admin(sessao)
            if recusa:
                return recusa
            conn.commit()
        pasta = gerar_backup(Path(app.state.db_path), app.state.arquivos, app.state.backups)
        return JSONResponse({"criado_em": pasta.name}, status_code=201)

    def pode_baixar(conn: sqlite3.Connection, sessao: sqlite3.Row, arquivo_id: str) -> bool:
        if conn.execute("SELECT 1 FROM foto WHERE arquivo_id = ?", (arquivo_id,)).fetchone():
            return True
        ligado = conn.execute(
            """SELECT q.status FROM revisao r
               JOIN quadro q ON q.id = r.quadro_id
               WHERE r.arquivo_id = ? AND q.status != 'cancelado'""",
            (arquivo_id,),
        ).fetchone()
        if ligado:
            return True
        return sessao["perfil"] != "montagem"

    @app.get("/api/arquivos/{arquivo_id}")
    def baixar_arquivo(arquivo_id: str, request: Request):
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            row = conn.execute("SELECT * FROM arquivo WHERE id = ?", (arquivo_id,)).fetchone()
            if row is None or not pode_baixar(conn, sessao, arquivo_id):
                return erro(403 if row else 404, "nao_encontrado" if row is None else "perfil_insuficiente", "Arquivo indisponível.")
            caminho = app.state.arquivos / row["area"] / arquivo_id
            etag = row["hash_sha256"]
            mime = row["mime"] or "application/octet-stream"
            conn.commit()
        if not caminho.is_file():
            return erro(404, "nao_encontrado", "Arquivo indisponível.")
        return FileResponse(caminho, media_type=mime, headers={"ETag": etag})

    @app.get("/api/notificacoes")
    def listar_notificacoes(request: Request, desde: str = "") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            rows = conn.execute(
                """SELECT id, quadro_id, revisao_id, tipo, criada_em
                   FROM notificacao WHERE criada_em > ? ORDER BY criada_em""",
                (desde,),
            ).fetchall()
            conn.commit()
        return JSONResponse([dict(row) for row in rows])

    @app.get("/api/quadros/{quadro_id}/revisoes")
    def historico(quadro_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            rows = conn.execute(
                """SELECT id, codigo, status, tipo, arquivo_id, importada_em, origem
                   FROM revisao WHERE quadro_id = ? ORDER BY importada_em""",
                (quadro_id,),
            ).fetchall()
            ponteiro = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (quadro_id,),
            ).fetchone()
            conn.commit()
        if ponteiro is None:
            return erro(404, "nao_encontrado", "Quadro não encontrado.")
        return JSONResponse({"revisao_vigente_id": ponteiro["revisao_vigente_id"], "revisoes": [dict(row) for row in rows]})

    @app.get("/api/quadros/{quadro_id}/confirmacoes")
    def listar_confirmacoes(quadro_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "Quem reconheceu é do painel.")
            vigente = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (quadro_id,),
            ).fetchone()
            if vigente is None:
                return erro(404, "nao_encontrado", "Quadro não encontrado.")
            revisao_id = vigente["revisao_vigente_id"]
            confirmados = []
            if revisao_id:
                confirmados = [
                    dict(row)
                    for row in conn.execute(
                        """SELECT c.ocorrido_em, u.nome AS usuario, d.apelido AS dispositivo, d.id AS dispositivo_id
                           FROM confirmacao_leitura c
                           JOIN usuario u ON u.id = c.usuario_id
                           JOIN dispositivo d ON d.id = c.dispositivo_id
                           WHERE c.revisao_id = ?""",
                        (revisao_id,),
                    ).fetchall()
                ]
            reconhecidos = {item["dispositivo_id"] for item in confirmados}
            faltam = [
                dict(row)
                for row in conn.execute(
                    """SELECT id AS dispositivo_id, apelido AS dispositivo
                       FROM dispositivo WHERE status = 'ativo'"""
                ).fetchall()
                if row["dispositivo_id"] not in reconhecidos
            ]
            conn.commit()
        return JSONResponse({"confirmados": confirmados, "faltam": faltam})

    @app.post("/api/sync/operacoes")
    def sincronizar(corpo: dict[str, Any], request: Request) -> JSONResponse:
        if corpo.get("revisao_vigente_id"):
            with db() as conn:
                sessao = exigir(request, conn)
                if not isinstance(sessao, JSONResponse):
                    conn.execute(
                        """INSERT INTO evento_auditoria
                           (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                           VALUES (?, ?, ?, 'publicacao', 'quadro', NULL, NULL, ?, 'ponteiro ignorado')""",
                        (agora(), sessao["usuario_id"], sessao["dispositivo_id"], str(corpo.get("operacao_id") or "")),
                    )
                    conn.commit()
            corpo = {k: v for k, v in corpo.items() if k != "revisao_vigente_id"}
        tipo = str(corpo.get("tipo") or "")
        operacao_id = str(corpo.get("operacao_id") or "")
        if tipo not in ("confirmacao_leitura", "observacao", "duvida", "pendencia", "etapa_iniciar", "etapa_concluir"):
            return erro(422, "pedido_invalido", "Tipo de sincronização ainda não aceito.")
        if not operacao_id:
            return erro(422, "pedido_invalido", "Informe operacao_id.")
        canonico = json.dumps(
            {
                "tipo": tipo,
                "revisao_id": corpo.get("revisao_id"),
                "quadro_id": corpo.get("quadro_id"),
                "ocorrido_em": corpo.get("ocorrido_em"),
                "texto": corpo.get("texto"),
                "descricao": corpo.get("descricao"),
                "bloqueia_etapa": corpo.get("bloqueia_etapa"),
                "etapa_codigo": corpo.get("etapa_codigo"),
            },
            sort_keys=True,
        )
        digest = hashlib.sha256(canonico.encode()).hexdigest()
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if not sessao["dispositivo_id"]:
                return erro(422, "pedido_invalido", "Confirmação sai do tablet.")
            previa = conn.execute(
                "SELECT * FROM operacao_sincronizacao WHERE operacao_id = ?",
                (operacao_id,),
            ).fetchone()
            if previa:
                if previa["payload_hash"] != digest:
                    return erro(409, "conflito_operacao", "Esta operação já foi usada com outro conteúdo.")
                tabela = {
                    "observacao": "observacao",
                    "duvida": "observacao",
                    "pendencia": "pendencia",
                    "foto": "foto",
                    "etapa_iniciar": "etapa_producao",
                    "etapa_concluir": "etapa_producao",
                }.get(previa["tipo"], "confirmacao_leitura")
                achada = conn.execute(
                    f"SELECT * FROM {tabela} WHERE id = ?",
                    (previa["recurso_id"],),
                ).fetchone()
                dados = dict(achada) if achada else {"id": previa["recurso_id"]}
                quadro_id = str(dados.get("quadro_id") or corpo.get("quadro_id") or "")
                if quadro_id:
                    dados["revisao_vigente_id"] = _vigente(conn, quadro_id)
                return JSONResponse(dados, status_code=previa["resultado_http"])
            if tipo in ("observacao", "duvida"):
                return _observacao(conn, sessao, corpo, operacao_id, digest, tipo)
            if tipo == "pendencia":
                return _pendencia(conn, sessao, corpo, operacao_id, digest)
            if tipo in ("etapa_iniciar", "etapa_concluir"):
                return _etapa(conn, sessao, corpo, operacao_id, digest, tipo)
            revisao_id = str(corpo.get("revisao_id") or "")
            ocorrido = str(corpo.get("ocorrido_em") or "")
            if not revisao_id or not ocorrido:
                return erro(422, "pedido_invalido", "Informe revisão e horário.")
            rev = conn.execute("SELECT * FROM revisao WHERE id = ?", (revisao_id,)).fetchone()
            if rev is None:
                return erro(404, "nao_encontrado", "Revisão não encontrada.")
            ja = conn.execute(
                """SELECT * FROM confirmacao_leitura
                   WHERE revisao_id = ? AND usuario_id = ? AND dispositivo_id = ?""",
                (revisao_id, sessao["usuario_id"], sessao["dispositivo_id"]),
            ).fetchone()
            if ja:
                conn.execute(
                    """INSERT INTO operacao_sincronizacao
                       (operacao_id, dispositivo_id, usuario_id, tipo, payload_hash, resultado_http, erro_codigo, recurso_id, recebido_em)
                       VALUES (?, ?, ?, ?, ?, 200, NULL, ?, ?)""",
                    (operacao_id, sessao["dispositivo_id"], sessao["usuario_id"], tipo, digest, ja["id"], agora()),
                )
                _marcar_relogio(conn, sessao["dispositivo_id"], ocorrido)
                conn.commit()
                dados = dict(ja)
                dados["revisao_vigente_id"] = _vigente(conn, rev["quadro_id"])
                return JSONResponse(dados)
            confirmacao_id = str(uuid.uuid4())
            recebido = agora()
            conn.execute(
                """INSERT INTO confirmacao_leitura
                   (id, revisao_id, quadro_id, usuario_id, dispositivo_id, operacao_id, ocorrido_em, recebido_em)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (confirmacao_id, revisao_id, rev["quadro_id"], sessao["usuario_id"], sessao["dispositivo_id"], operacao_id, ocorrido, recebido),
            )
            conn.execute(
                """INSERT INTO operacao_sincronizacao
                   (operacao_id, dispositivo_id, usuario_id, tipo, payload_hash, resultado_http, erro_codigo, recurso_id, recebido_em)
                   VALUES (?, ?, ?, ?, ?, 201, NULL, ?, ?)""",
                (operacao_id, sessao["dispositivo_id"], sessao["usuario_id"], tipo, digest, confirmacao_id, recebido),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, ?, 'confirmacao_leitura', 'confirmacao_leitura', ?, ?, ?, ?)""",
                (recebido, sessao["usuario_id"], sessao["dispositivo_id"], confirmacao_id, rev["quadro_id"], operacao_id, ocorrido),
            )
            _marcar_relogio(conn, sessao["dispositivo_id"], ocorrido)
            conn.commit()
            gravada = dict(
                conn.execute(
                    "SELECT * FROM confirmacao_leitura WHERE id = ?",
                    (confirmacao_id,),
                ).fetchone()
            )
            gravada["revisao_vigente_id"] = _vigente(conn, rev["quadro_id"])
        return JSONResponse(gravada, status_code=201)

    def _foto_pronta(conn: sqlite3.Connection, operacao_id: str) -> sqlite3.Row | None:
        return conn.execute("SELECT * FROM foto WHERE operacao_id = ?", (operacao_id,)).fetchone()

    def _json_foto(conn: sqlite3.Connection, foto: sqlite3.Row, completo: bool = True) -> dict[str, Any]:
        arquivo = conn.execute("SELECT hash_sha256 FROM arquivo WHERE id = ?", (foto["arquivo_id"],)).fetchone()
        return {
            "id": foto["id"],
            "quadro_id": foto["quadro_id"],
            "arquivo_id": foto["arquivo_id"],
            "observacao_id": foto["observacao_id"],
            "hash_sha256": arquivo["hash_sha256"] if arquivo else None,
            "ocorrido_em": foto["ocorrido_em"],
            "completo": completo,
            "revisao_vigente_id": _vigente(conn, foto["quadro_id"]),
        }

    @app.post("/api/uploads")
    def abrir_upload(corpo: dict[str, Any], request: Request) -> JSONResponse:
        operacao_id = str(corpo.get("operacao_id") or "")
        digest = str(corpo.get("hash_sha256") or "")
        quadro_id = str(corpo.get("quadro_id") or "")
        ocorrido = str(corpo.get("ocorrido_em") or "")
        try:
            tamanho = int(corpo.get("tamanho_bytes"))
        except (TypeError, ValueError):
            tamanho = 0
        if not operacao_id or len(digest) != 64 or not quadro_id or not ocorrido or tamanho <= 0:
            return erro(422, "pedido_invalido", "Informe operação, hash, tamanho, quadro e horário.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if not sessao["dispositivo_id"]:
                return erro(422, "pedido_invalido", "A foto sai do tablet.")
            pronta = _foto_pronta(conn, operacao_id)
            if pronta:
                return JSONResponse(_json_foto(conn, pronta))
            if not conn.execute("SELECT 1 FROM quadro WHERE id = ?", (quadro_id,)).fetchone():
                return erro(404, "nao_encontrado", "Quadro não encontrado.")
            parcial = conn.execute(
                "SELECT * FROM upload_parcial WHERE operacao_id = ?",
                (operacao_id,),
            ).fetchone()
            if parcial:
                if parcial["hash_sha256"] != digest or parcial["tamanho_bytes"] != tamanho or parcial["quadro_id"] != quadro_id:
                    return erro(409, "conflito_operacao", "Esta foto já começou com outro conteúdo.")
                return JSONResponse(
                    {
                        "operacao_id": operacao_id,
                        "recebido_bytes": parcial["recebido_bytes"],
                        "completo": False,
                        "revisao_vigente_id": _vigente(conn, quadro_id),
                    }
                )
            conn.execute(
                """INSERT INTO upload_parcial
                   (operacao_id, hash_sha256, tamanho_bytes, quadro_id, usuario_id, dispositivo_id, ocorrido_em, observacao_id, legenda, recebido_bytes)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                (
                    operacao_id,
                    digest,
                    tamanho,
                    quadro_id,
                    sessao["usuario_id"],
                    sessao["dispositivo_id"],
                    ocorrido,
                    corpo.get("observacao_id"),
                    str(corpo.get("legenda") or ""),
                ),
            )
            conn.commit()
        pasta = app.state.arquivos / "parciais"
        pasta.mkdir(parents=True, exist_ok=True)
        (pasta / f"{operacao_id}.part").touch()
        return JSONResponse(
            {"operacao_id": operacao_id, "recebido_bytes": 0, "completo": False},
            status_code=201,
        )

    @app.put("/api/uploads/{operacao_id}")
    async def receber_upload(operacao_id: str, request: Request) -> JSONResponse:
        faixa = request.headers.get("content-range") or ""
        bloco = await request.body()
        casou = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", faixa.strip())
        if not casou:
            return erro(422, "pedido_invalido", "Informe Content-Range bytes início-fim/total.")
        inicio, fim, total = (int(casou.group(i)) for i in range(1, 4))
        if fim < inicio or fim - inicio + 1 != len(bloco):
            return erro(422, "pedido_invalido", "A faixa não fecha com o corpo enviado.")
        caminho = app.state.arquivos / "parciais" / f"{operacao_id}.part"
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            pronta = _foto_pronta(conn, operacao_id)
            if pronta:
                return JSONResponse(_json_foto(conn, pronta))
            parcial = conn.execute(
                "SELECT * FROM upload_parcial WHERE operacao_id = ?",
                (operacao_id,),
            ).fetchone()
            if parcial is None:
                return erro(404, "nao_encontrado", "Upload não aberto.")
            if total != parcial["tamanho_bytes"]:
                return erro(422, "pedido_invalido", "O total da faixa não é o tamanho anunciado.")
            if inicio != parcial["recebido_bytes"]:
                return erro(
                    422,
                    "pedido_invalido",
                    "Retome do byte já gravado.",
                    {"recebido_bytes": parcial["recebido_bytes"]},
                )
            caminho.parent.mkdir(parents=True, exist_ok=True)
            if not caminho.exists():
                caminho.touch()
            with caminho.open("r+b") as destino:
                destino.truncate(inicio)
                destino.seek(inicio)
                destino.write(bloco)
            recebido = inicio + len(bloco)
            if recebido < parcial["tamanho_bytes"]:
                conn.execute(
                    "UPDATE upload_parcial SET recebido_bytes = ? WHERE operacao_id = ?",
                    (recebido, operacao_id),
                )
                conn.commit()
                return JSONResponse(
                    {
                        "operacao_id": operacao_id,
                        "recebido_bytes": recebido,
                        "completo": False,
                        "revisao_vigente_id": _vigente(conn, parcial["quadro_id"]),
                    }
                )
            digest = hashlib.sha256(caminho.read_bytes()).hexdigest()
            if digest != parcial["hash_sha256"]:
                with caminho.open("r+b") as destino:
                    destino.truncate(inicio)
                return erro(422, "pedido_invalido", "O hash da foto não confere. Nada foi gravado.")
            arquivo_id = str(uuid.uuid4())
            final = app.state.arquivos / "fotos" / arquivo_id
            final.parent.mkdir(parents=True, exist_ok=True)
            conn.execute("BEGIN IMMEDIATE")
            try:
                momento = agora()
                conn.execute(
                    """INSERT INTO arquivo
                       (id, hash_sha256, tamanho_bytes, mime, nome_original, area, criado_em)
                       VALUES (?, ?, ?, 'image/jpeg', ?, 'fotos', ?)""",
                    (arquivo_id, digest, parcial["tamanho_bytes"], f"{operacao_id}.jpg", momento),
                )
                foto_id = str(uuid.uuid4())
                conn.execute(
                    """INSERT INTO foto
                       (id, quadro_id, arquivo_id, observacao_id, pendencia_id, legenda, usuario_id, dispositivo_id, operacao_id, ocorrido_em, recebido_em)
                       VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)""",
                    (
                        foto_id,
                        parcial["quadro_id"],
                        arquivo_id,
                        parcial["observacao_id"],
                        parcial["legenda"],
                        parcial["usuario_id"],
                        parcial["dispositivo_id"],
                        operacao_id,
                        parcial["ocorrido_em"],
                        momento,
                    ),
                )
                conn.execute(
                    """INSERT INTO operacao_sincronizacao
                       (operacao_id, dispositivo_id, usuario_id, tipo, payload_hash, resultado_http, erro_codigo, recurso_id, recebido_em)
                       VALUES (?, ?, ?, 'foto', ?, 201, NULL, ?, ?)""",
                    (operacao_id, parcial["dispositivo_id"], parcial["usuario_id"], digest, foto_id, momento),
                )
                conn.execute(
                    """INSERT INTO evento_auditoria
                       (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                       VALUES (?, ?, ?, 'foto', 'foto', ?, ?, ?, ?)""",
                    (momento, parcial["usuario_id"], parcial["dispositivo_id"], foto_id, parcial["quadro_id"], operacao_id, digest),
                )
                conn.execute("DELETE FROM upload_parcial WHERE operacao_id = ?", (operacao_id,))
                caminho.replace(final)
                conn.commit()
            except Exception:
                conn.rollback()
                raise
            foto = conn.execute("SELECT * FROM foto WHERE id = ?", (foto_id,)).fetchone()
            if parcial["dispositivo_id"]:
                _marcar_relogio(conn, parcial["dispositivo_id"], parcial["ocorrido_em"])
                conn.commit()
            corpo_foto = _json_foto(conn, foto)
        return JSONResponse(corpo_foto, status_code=201)

    @app.get("/api/observacoes")
    def listar_observacoes(request: Request, tipo: str = "") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            rows = conn.execute(
                """SELECT o.id, o.quadro_id, q.codigo AS quadro_codigo, o.tipo, o.texto, o.resposta,
                          o.ocorrido_em, o.respondida_em, u.nome AS usuario
                   FROM observacao o
                   JOIN quadro q ON q.id = o.quadro_id
                   JOIN usuario u ON u.id = o.usuario_id
                   WHERE (? = '' OR o.tipo = ?)
                   ORDER BY o.recebido_em""",
                (tipo, tipo),
            ).fetchall()
            saida = []
            for row in rows:
                item = dict(row)
                item["fotos"] = [
                    {
                        "id": foto["id"],
                        "arquivo_id": foto["arquivo_id"],
                        "hash_sha256": foto["hash_sha256"],
                        "legenda": foto["legenda"],
                    }
                    for foto in conn.execute(
                        """SELECT f.id, f.arquivo_id, f.legenda, a.hash_sha256
                           FROM foto f JOIN arquivo a ON a.id = f.arquivo_id
                           WHERE f.observacao_id = ?""",
                        (row["id"],),
                    ).fetchall()
                ]
                saida.append(item)
            conn.commit()
        return JSONResponse(saida)

    @app.post("/api/observacoes/{observacao_id}/resposta")
    def responder_observacao(observacao_id: str, corpo: dict[str, Any], request: Request) -> JSONResponse:
        resposta = str(corpo.get("resposta") or "").strip()
        if not resposta:
            return erro(422, "pedido_invalido", "Informe a resposta.")
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] != "engenharia":
                return erro(403, "perfil_insuficiente", "Quem responde dúvida é a engenharia.")
            obs = conn.execute("SELECT * FROM observacao WHERE id = ?", (observacao_id,)).fetchone()
            if obs is None:
                return erro(404, "nao_encontrado", "Dúvida não encontrada.")
            if obs["resposta"]:
                return JSONResponse(
                    {"id": obs["id"], "resposta": obs["resposta"], "respondida_em": obs["respondida_em"]}
                )
            momento = agora()
            conn.execute(
                """UPDATE observacao
                   SET resposta = ?, respondida_por = ?, respondida_em = ?
                   WHERE id = ?""",
                (resposta, sessao["usuario_id"], momento, observacao_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, NULL, 'duvida', 'observacao', ?, ?, NULL, 'resposta')""",
                (momento, sessao["usuario_id"], observacao_id, obs["quadro_id"]),
            )
            conn.commit()
        return JSONResponse({"id": observacao_id, "resposta": resposta, "respondida_em": momento})

    @app.post("/api/pendencias/{pendencia_id}/resolucao")
    def resolver_pendencia(pendencia_id: str, request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] not in ("engenharia", "supervisao"):
                return erro(403, "perfil_insuficiente", "Quem resolve pendência é o painel.")
            item = conn.execute("SELECT * FROM pendencia WHERE id = ?", (pendencia_id,)).fetchone()
            if item is None:
                return erro(404, "nao_encontrado", "Pendência não encontrada.")
            if item["status"] == "resolvida":
                return JSONResponse({"id": item["id"], "status": "resolvida", "resolvida_em": item["resolvida_em"]})
            momento = agora()
            conn.execute(
                """UPDATE pendencia
                   SET status = 'resolvida', resolvida_por = ?, resolvida_em = ?
                   WHERE id = ?""",
                (sessao["usuario_id"], momento, pendencia_id),
            )
            conn.execute(
                """INSERT INTO evento_auditoria
                   (ocorrido_em, ator_usuario_id, ator_dispositivo_id, acao, entidade, entidade_id, quadro_id, operacao_id, detalhe)
                   VALUES (?, ?, NULL, 'mudanca_status', 'pendencia', ?, ?, NULL, 'resolvida')""",
                (momento, sessao["usuario_id"], pendencia_id, item["quadro_id"]),
            )
            conn.commit()
        return JSONResponse({"id": pendencia_id, "status": "resolvida", "resolvida_em": momento})

    @app.get("/api/bancadas/andamento")
    def andamento(request: Request) -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "Bancadas são do painel.")
            bancadas = conn.execute(
                "SELECT id, codigo, nome FROM bancada WHERE ativa = 1 ORDER BY codigo"
            ).fetchall()
            saida = []
            acoes = (
                "observacao",
                "duvida",
                "foto",
                "pendencia",
                "mudanca_etapa",
                "mudanca_status",
                "bloqueio",
                "desbloqueio",
            )
            for bancada in bancadas:
                quadros = conn.execute(
                    """SELECT id, codigo, titulo FROM quadro
                       WHERE bancada_id = ? AND status != 'cancelado' ORDER BY codigo""",
                    (bancada["id"],),
                ).fetchall()
                itens = []
                for quadro in quadros:
                    etapas = [
                        {"codigo": row["codigo"], "nome": row["nome"], "status": row["status"]}
                        for row in conn.execute(
                            """SELECT c.codigo, c.nome, e.status
                               FROM etapa_producao e
                               JOIN etapa_catalogo c ON c.id = e.etapa_catalogo_id
                               WHERE e.quadro_id = ?
                               ORDER BY c.ordem""",
                            (quadro["id"],),
                        ).fetchall()
                    ]
                    pendencias = [
                        {
                            "id": row["id"],
                            "descricao": row["descricao"],
                            "status": row["status"],
                            "bloqueia_etapa": bool(row["bloqueia_etapa"]),
                        }
                        for row in conn.execute(
                            """SELECT id, descricao, status, bloqueia_etapa FROM pendencia
                               WHERE quadro_id = ? AND status IN ('aberta', 'em_analise')
                               ORDER BY ocorrido_em""",
                            (quadro["id"],),
                        ).fetchall()
                    ]
                    eventos = [
                        {"acao": row["acao"], "detalhe": row["detalhe"], "ocorrido_em": row["ocorrido_em"]}
                        for row in conn.execute(
                            f"""SELECT acao, detalhe, ocorrido_em FROM evento_auditoria
                                WHERE quadro_id = ? AND acao IN ({",".join("?" for _ in acoes)})
                                ORDER BY id""",
                            (quadro["id"], *acoes),
                        ).fetchall()
                    ]
                    itens.append(
                        {
                            "id": quadro["id"],
                            "codigo": quadro["codigo"],
                            "titulo": quadro["titulo"],
                            "etapas": etapas,
                            "pendencias": pendencias,
                            "eventos": eventos,
                        }
                    )
                saida.append(
                    {
                        "id": bancada["id"],
                        "codigo": bancada["codigo"],
                        "nome": bancada["nome"],
                        "quadros": itens,
                    }
                )
            conn.commit()
        return JSONResponse(saida)

    @app.websocket("/api/ws")
    async def avisos(websocket: WebSocket) -> None:
        await websocket.accept()
        primeira = await websocket.receive_json()
        token = str(primeira.get("token") or "")
        with db() as conn:
            row = conn.execute(
                "SELECT id FROM sessao WHERE token_hash = ? AND revogada_em IS NULL",
                (hash_token(token),),
            ).fetchone()
        if row is None:
            await websocket.send_json({"erro": "sessao_revogada", "mensagem": "Sessão ausente ou vencida."})
            await websocket.close()
            return
        fila: queue.Queue = queue.Queue()
        app.state.avisos.append(fila)

        def pegar():
            try:
                return fila.get(timeout=0.5)
            except queue.Empty:
                return None

        try:
            while True:
                item = await asyncio.to_thread(pegar)
                if item is not None:
                    await websocket.send_json(item)
        finally:
            if fila in app.state.avisos:
                app.state.avisos.remove(fila)

    return app


app = create_app()

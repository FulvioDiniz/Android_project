import os
import secrets
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import FastAPI, File, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.db import agora, connect, hash_token, init_db
from app.importacao import gravar_bytes, interpretar_nome, registrar_erro

HASHER = PasswordHasher()
COOKIE = "quadros_sessao"


def erro(status: int, codigo: str, mensagem: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"erro": codigo, "mensagem": mensagem})


def create_app(db_path: str | None = None, arquivos_dir: str | None = None) -> FastAPI:
    path = db_path or os.environ.get("QUADROS_DB", "data/quadros.sqlite")
    init_db(path)
    app = FastAPI(title="Quadros")
    app.state.db_path = path
    app.state.arquivos = Path(arquivos_dir or os.environ.get("QUADROS_ARQUIVOS") or str(Path(path).parent / "arquivos"))
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
                          op.numero AS ordem_numero, c.nome AS cliente_nome,
                          u.nome AS responsavel_nome, b.nome AS bancada_nome
                   FROM quadro q
                   JOIN ordem_producao op ON op.id = q.ordem_producao_id
                   JOIN cliente c ON c.id = op.cliente_id
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
                          op.id AS ordem_producao_id, op.numero AS ordem_numero,
                          c.nome AS cliente_nome
                   FROM quadro q
                   JOIN ordem_producao op ON op.id = q.ordem_producao_id
                   JOIN cliente c ON c.id = op.cliente_id
                   WHERE q.id = ?""",
                (quadro_id,),
            ).fetchone()
            conn.commit()
        if row is None:
            return erro(404, "nao_encontrado", "Quadro não encontrado.")
        return JSONResponse(dict(row))

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
            partes = interpretar_nome(nome)
            if partes is None:
                erro_id = registrar_erro(
                    conn,
                    app.state.arquivos,
                    nome or "sem-nome",
                    conteudo,
                    "convencao_invalida",
                    "O nome precisa ser CODIGO-REV.X.pdf, com um único marcador.",
                )
                conn.commit()
                return JSONResponse(
                    {"erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual"},
                    status_code=202,
                )
            codigo, revisao_codigo = partes
            quadros = conn.execute(
                "SELECT id, codigo, revisao_vigente_id FROM quadro WHERE lower(codigo) = lower(?)",
                (codigo,),
            ).fetchall()
            if len(quadros) == 0:
                erro_id = registrar_erro(conn, app.state.arquivos, nome, conteudo, "quadro_nao_encontrado", codigo)
                conn.commit()
                return JSONResponse(
                    {"erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual"},
                    status_code=202,
                )
            if len(quadros) > 1:
                erro_id = registrar_erro(conn, app.state.arquivos, nome, conteudo, "nome_ambiguo", codigo)
                conn.commit()
                return JSONResponse(
                    {"erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual"},
                    status_code=202,
                )
            quadro = quadros[0]
            arquivo_id, digest = gravar_bytes(app.state.arquivos, "projetos", conteudo)
            existente = conn.execute(
                """SELECT r.id, a.hash_sha256 FROM revisao r
                   JOIN arquivo a ON a.id = r.arquivo_id
                   WHERE r.quadro_id = ? AND r.codigo = ?""",
                (quadro["id"], revisao_codigo),
            ).fetchone()
            if existente:
                (app.state.arquivos / "projetos" / arquivo_id).unlink(missing_ok=True)
                if existente["hash_sha256"] == digest:
                    conn.commit()
                    return JSONResponse(
                        {"revisao_id": existente["id"], "status": "pendente", "repetido": True}
                    )
                erro_id = registrar_erro(
                    conn,
                    app.state.arquivos,
                    nome,
                    conteudo,
                    "revisao_duplicada",
                    revisao_codigo,
                )
                conn.commit()
                return JSONResponse(
                    {"erro_importacao_id": erro_id, "revisao_id": None, "status": "validacao_manual"},
                    status_code=202,
                )
            conn.execute(
                """INSERT INTO arquivo
                   (id, hash_sha256, tamanho_bytes, mime, nome_original, area, criado_em)
                   VALUES (?, ?, ?, 'application/pdf', ?, 'projetos', ?)""",
                (arquivo_id, digest, len(conteudo), nome, agora()),
            )
            revisao_id = str(uuid.uuid4())
            conn.execute(
                """INSERT INTO revisao
                   (id, quadro_id, codigo, status, tipo, arquivo_id, motivo, importada_em)
                   VALUES (?, ?, ?, 'pendente', NULL, ?, NULL, ?)""",
                (revisao_id, quadro["id"], revisao_codigo, arquivo_id, agora()),
            )
            auditar(
                conn,
                "importacao",
                "revisao",
                revisao_id,
                sessao["usuario_id"],
                None,
                f"{nome} {digest}",
            )
            ponteiro = conn.execute(
                "SELECT revisao_vigente_id FROM quadro WHERE id = ?",
                (quadro["id"],),
            ).fetchone()["revisao_vigente_id"]
            conn.commit()
        return JSONResponse(
            {
                "revisao_id": revisao_id,
                "codigo": revisao_codigo,
                "status": "pendente",
                "revisao_vigente_id": ponteiro,
                "hash_sha256": digest,
            },
            status_code=201,
        )

    @app.get("/api/revisoes")
    def listar_revisoes(request: Request, status: str = "pendente") -> JSONResponse:
        with db() as conn:
            sessao = exigir(request, conn)
            if isinstance(sessao, JSONResponse):
                return sessao
            if sessao["perfil"] == "montagem":
                return erro(403, "perfil_insuficiente", "A fila de revisão é do painel.")
            rows = conn.execute(
                """SELECT r.id, r.codigo, r.status, q.codigo AS quadro_codigo, q.revisao_vigente_id
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

    return app


app = create_app()

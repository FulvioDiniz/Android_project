import hashlib
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from argon2 import PasswordHasher

SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS perfil (
    id INTEGER PRIMARY KEY,
    codigo TEXT NOT NULL UNIQUE,
    nome TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS usuario (
    id TEXT PRIMARY KEY,
    nome TEXT NOT NULL,
    login TEXT NOT NULL UNIQUE,
    senha_hash TEXT NOT NULL,
    perfil_id INTEGER NOT NULL REFERENCES perfil(id),
    ativo INTEGER NOT NULL DEFAULT 1,
    criado_em TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bancada (
    id TEXT PRIMARY KEY,
    codigo TEXT NOT NULL UNIQUE,
    nome TEXT NOT NULL,
    ativa INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS dispositivo (
    id TEXT PRIMARY KEY,
    identificador_instalacao TEXT NOT NULL UNIQUE,
    apelido TEXT NOT NULL,
    bancada_id TEXT REFERENCES bancada(id),
    usuario_responsavel_id TEXT REFERENCES usuario(id),
    status TEXT NOT NULL,
    ultimo_sync_em TEXT,
    deslocamento_relogio_ms INTEGER,
    ultimo_visto_em TEXT
);

CREATE TABLE IF NOT EXISTS cliente (
    id TEXT PRIMARY KEY,
    nome TEXT NOT NULL,
    codigo TEXT UNIQUE
);

CREATE TABLE IF NOT EXISTS ordem_producao (
    id TEXT PRIMARY KEY,
    numero TEXT NOT NULL UNIQUE,
    cliente_id TEXT NOT NULL REFERENCES cliente(id),
    descricao TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    prazo TEXT,
    criado_em TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS arquivo (
    id TEXT PRIMARY KEY,
    hash_sha256 TEXT NOT NULL,
    tamanho_bytes INTEGER NOT NULL,
    mime TEXT NOT NULL,
    nome_original TEXT NOT NULL,
    area TEXT NOT NULL,
    criado_em TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS revisao (
    id TEXT PRIMARY KEY,
    quadro_id TEXT NOT NULL,
    codigo TEXT NOT NULL,
    status TEXT NOT NULL,
    tipo TEXT,
    arquivo_id TEXT REFERENCES arquivo(id),
    motivo TEXT,
    importada_em TEXT NOT NULL,
    decidida_por TEXT REFERENCES usuario(id),
    decidida_em TEXT,
    UNIQUE (quadro_id, codigo)
);

CREATE TABLE IF NOT EXISTS quadro (
    id TEXT PRIMARY KEY,
    codigo TEXT NOT NULL UNIQUE,
    ordem_producao_id TEXT NOT NULL REFERENCES ordem_producao(id),
    titulo TEXT NOT NULL,
    responsavel_id TEXT REFERENCES usuario(id),
    bancada_id TEXT REFERENCES bancada(id),
    revisao_vigente_id TEXT REFERENCES revisao(id),
    status TEXT NOT NULL,
    criado_em TEXT NOT NULL,
    atualizado_em TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessao (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    usuario_id TEXT NOT NULL REFERENCES usuario(id),
    dispositivo_id TEXT REFERENCES dispositivo(id),
    criada_em TEXT NOT NULL,
    expira_em TEXT NOT NULL,
    revogada_em TEXT,
    ultimo_uso_em TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS evento_auditoria (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ocorrido_em TEXT NOT NULL,
    ator_usuario_id TEXT,
    ator_dispositivo_id TEXT,
    acao TEXT NOT NULL,
    entidade TEXT NOT NULL,
    entidade_id TEXT,
    quadro_id TEXT,
    operacao_id TEXT,
    detalhe TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS erro_importacao (
    id TEXT PRIMARY KEY,
    nome_original TEXT NOT NULL,
    arquivo_id TEXT REFERENCES arquivo(id),
    hash_sha256 TEXT,
    motivo TEXT NOT NULL,
    detalhe TEXT NOT NULL,
    status TEXT NOT NULL,
    revisao_id TEXT,
    criado_em TEXT NOT NULL,
    resolvido_por TEXT,
    resolvido_em TEXT
);

CREATE TABLE IF NOT EXISTS trava_publicacao (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    quadro_id TEXT,
    aberta_em TEXT
);

CREATE INDEX IF NOT EXISTS ix_quadro_op ON quadro(ordem_producao_id);
CREATE INDEX IF NOT EXISTS ix_sessao_dispositivo
    ON sessao(dispositivo_id) WHERE revogada_em IS NULL;
"""

TRIGGERS = """
CREATE TRIGGER IF NOT EXISTS auditoria_sem_update
BEFORE UPDATE ON evento_auditoria
BEGIN
    SELECT RAISE(ABORT, 'auditoria imutavel');
END;

CREATE TRIGGER IF NOT EXISTS auditoria_sem_delete
BEFORE DELETE ON evento_auditoria
BEGIN
    SELECT RAISE(ABORT, 'auditoria imutavel');
END;

CREATE TRIGGER IF NOT EXISTS publicacao_ponteiro
BEFORE UPDATE OF revisao_vigente_id ON quadro
WHEN NEW.revisao_vigente_id IS NOT OLD.revisao_vigente_id
BEGIN
    SELECT RAISE(ABORT, 'publicacao sem trava')
    WHERE NOT EXISTS (SELECT 1 FROM trava_publicacao WHERE id = 1);
END;
"""


def agora() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect(path: str) -> sqlite3.Connection:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


def init_db(path: str) -> None:
    with connect(path) as conn:
        conn.executescript(SCHEMA)
        conn.executescript(TRIGGERS)
        _seed(conn)
        conn.commit()


def _seed(conn: sqlite3.Connection) -> None:
    existe = conn.execute("SELECT COUNT(*) AS n FROM perfil").fetchone()["n"]
    if existe:
        return
    perfis = [
        (1, "admin", "Admin"),
        (2, "engenharia", "Engenharia"),
        (3, "supervisao", "Supervisão"),
        (4, "montagem", "Montagem"),
    ]
    conn.executemany("INSERT INTO perfil (id, codigo, nome) VALUES (?, ?, ?)", perfis)
    hasher = PasswordHasher()
    criado = agora()
    usuarios = [
        ("usr-rita", "Rita Sol", "rita.sol", "admin", 1),
        ("usr-ana", "Ana Ribeiro", "ana.ribeiro", "engenharia", 2),
        ("usr-helena", "Helena Dias", "helena.dias", "supervisao", 3),
        ("usr-carlos", "Carlos Mota", "carlos.mota", "montagem", 4),
    ]
    for uid, nome, login, senha, perfil_id in usuarios:
        conn.execute(
            """INSERT INTO usuario (id, nome, login, senha_hash, perfil_id, ativo, criado_em)
               VALUES (?, ?, ?, ?, ?, 1, ?)""",
            (uid, nome, login, hasher.hash(senha), perfil_id, criado),
        )
    conn.execute(
        "INSERT INTO bancada (id, codigo, nome, ativa) VALUES (?, ?, ?, 1)",
        ("bnc-2", "B2", "Bancada 2"),
    )
    conn.execute(
        """INSERT INTO dispositivo
           (id, identificador_instalacao, apelido, bancada_id, usuario_responsavel_id, status, ultimo_visto_em)
           VALUES (?, ?, ?, ?, ?, 'ativo', ?)""",
        ("dsp-tab", "tablet-fase-1", "Galaxy Tab A11+", "bnc-2", "usr-carlos", criado),
    )


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()

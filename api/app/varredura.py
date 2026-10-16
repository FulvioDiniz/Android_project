import hashlib
import os
import subprocess
import time
import uuid
from pathlib import Path

from app.db import agora, connect, init_db
from app.importacao import importar_pdf

PASTA_PADRAO = "\\\\Srv3302\\projetos elétricos"
INDISPONIVEL = "Pasta de rede indisponível."


def caminho_configurado() -> str:
    return os.environ.get("QUADROS_PASTA_REDE", PASTA_PADRAO)


def ler_estado_pasta(conn) -> dict:
    row = conn.execute("SELECT caminho, acessivel, detalhe FROM estado_pasta_rede WHERE id = 1").fetchone()
    if row is None or not row["acessivel"]:
        return {
            "acessivel": False,
            "mensagem": INDISPONIVEL,
            "caminho": row["caminho"] if row else PASTA_PADRAO,
        }
    return {
        "acessivel": True,
        "mensagem": "A pasta de rede está acessível.",
        "caminho": row["caminho"],
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


def varrer_uma_vez(db_path: str, arquivos_dir: str, caminho: str, estado: dict) -> None:
    init_db(db_path)
    with connect(db_path) as conn:
        if not pasta_aberta(caminho):
            registrar_estado(conn, caminho, False, INDISPONIVEL)
            conn.commit()
            return
        registrar_estado(conn, caminho, True, "A pasta de rede está acessível.")
        conn.commit()
        for item in sorted(Path(caminho).iterdir()):
            if not item.is_file() or not observar(estado, item):
                continue
            conteudo = item.read_bytes()
            digest = hashlib.sha256(conteudo).hexdigest()
            if conn.execute(
                "SELECT 1 FROM arquivo_rede_tratado WHERE nome = ? AND hash_sha256 = ?",
                (item.name, digest),
            ).fetchone():
                continue
            importar_pdf(conn, Path(arquivos_dir), item.name, conteudo, "rede", None)
            conn.execute(
                """INSERT INTO arquivo_rede_tratado (id, nome, hash_sha256, tratado_em)
                   VALUES (?, ?, ?, ?)""",
                (str(uuid.uuid4()), item.name, digest, agora()),
            )
            conn.commit()


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

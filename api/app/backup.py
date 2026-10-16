import os
import shutil
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path


def gerar_backup(db_path: Path, arquivos: Path, destino_raiz: Path) -> Path:
    destino_raiz.mkdir(parents=True, exist_ok=True)
    _podar(destino_raiz)
    nome = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    pasta = destino_raiz / nome
    pasta.mkdir()
    destino_db = pasta / "quadros.sqlite"
    origem = sqlite3.connect(db_path)
    try:
        destino = sqlite3.connect(destino_db)
        try:
            origem.backup(destino)
        finally:
            destino.close()
    finally:
        origem.close()
    os.chmod(destino_db, 0o600)
    disco = pasta / "arquivos"
    if arquivos.exists():
        shutil.copytree(arquivos, disco)
    else:
        disco.mkdir()
    for item in disco.rglob("*"):
        if item.is_file():
            os.chmod(item, 0o600)
    return pasta


def _podar(raiz: Path) -> None:
    limite = datetime.now(timezone.utc) - timedelta(days=30)
    for pasta in raiz.iterdir():
        if not pasta.is_dir():
            continue
        try:
            quando = datetime.strptime(pasta.name, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
        except ValueError:
            continue
        if quando < limite:
            shutil.rmtree(pasta)

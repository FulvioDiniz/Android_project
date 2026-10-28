import hashlib
import os
import re
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from app.db import agora, connect, init_db
from app.importacao import importar_no_quadro

PASTA_PADRAO = "\\\\Srv3302\\projetos elétricos"
INDISPONIVEL = "Pasta de rede indisponível."


def caminho_configurado() -> str:
    return os.environ.get("QUADROS_PASTA_REDE", PASTA_PADRAO)


def ler_estado_pasta(conn) -> dict:
    row = conn.execute("SELECT caminho, acessivel, detalhe FROM estado_pasta_rede WHERE id = 1").fetchone()
    comparacoes = [
        dict(item)
        for item in conn.execute(
            """SELECT codigo_quadro, grupo, pasta, arquivo, alterado_em, resultado
               FROM comparacao_rede ORDER BY grupo, codigo_quadro, alterado_em"""
        ).fetchall()
    ]
    if row is None or not row["acessivel"]:
        return {
            "acessivel": False,
            "mensagem": INDISPONIVEL,
            "caminho": row["caminho"] if row else PASTA_PADRAO,
            "comparacoes": comparacoes,
        }
    return {
        "acessivel": True,
        "mensagem": "A pasta de rede está acessível.",
        "caminho": row["caminho"],
        "comparacoes": comparacoes,
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


def _iso_mtime(arquivo: Path) -> str:
    return datetime.fromtimestamp(arquivo.stat().st_mtime, timezone.utc).isoformat()


def _instante(valor: str | None) -> datetime | None:
    if not valor:
        return None
    try:
        return datetime.fromisoformat(valor)
    except ValueError:
        return None


def _referencia_vigente(importada_em: str | None) -> datetime | None:
    return _instante(importada_em)


def codigo_revisao_do_arquivo(nome: str) -> str:
    achados = re.findall(r"(?i)REV\.([A-Za-z0-9]{1,4})", nome)
    if achados:
        return achados[-1].upper()
    return nome


IGNORAR_DESVIO = {
    "eplan",
    "documentos",
    "lista de materiais",
    "@recycle",
    "@recently-snapshot",
}


def pastas_de_projeto(base: Path):
    def visitar(pasta: Path):
        try:
            filhos = list(pasta.iterdir())
        except OSError:
            return
        for filho in filhos:
            try:
                diretorio = filho.is_dir()
            except OSError:
                continue
            if not diretorio:
                continue
            if filho.name.lower() == "pdf" and filho.parent != base:
                quadro_dir = filho.parent
                relativo = quadro_dir.relative_to(base)
                grupo = relativo.parts[0] if relativo.parts else ""
                yield grupo, quadro_dir.name, filho
                continue
            if filho.name.lower() in IGNORAR_DESVIO:
                continue
            yield from visitar(filho)

    yield from visitar(base)


def garantir_quadro(conn, codigo: str, grupo: str) -> str:
    existente = conn.execute(
        "SELECT id FROM quadro WHERE lower(codigo) = lower(?)",
        (codigo,),
    ).fetchone()
    if existente:
        return existente["id"]
    cliente = conn.execute("SELECT id FROM cliente WHERE nome = ?", (grupo,)).fetchone()
    if cliente is None:
        cliente_id = str(uuid.uuid4())
        conn.execute(
            "INSERT INTO cliente (id, nome, codigo) VALUES (?, ?, NULL)",
            (cliente_id, grupo or codigo),
        )
    else:
        cliente_id = cliente["id"]
    ordem = conn.execute("SELECT id FROM ordem_producao WHERE numero = ?", (codigo,)).fetchone()
    if ordem is None:
        ordem_id = str(uuid.uuid4())
        conn.execute(
            """INSERT INTO ordem_producao
               (id, numero, cliente_id, descricao, status, prazo, criado_em)
               VALUES (?, ?, ?, ?, 'aberta', NULL, ?)""",
            (ordem_id, codigo, cliente_id, grupo, agora()),
        )
    else:
        ordem_id = ordem["id"]
    quadro_id = str(uuid.uuid4())
    momento = agora()
    conn.execute(
        """INSERT INTO quadro
           (id, codigo, ordem_producao_id, titulo, responsavel_id, bancada_id,
            revisao_vigente_id, status, criado_em, atualizado_em)
           VALUES (?, ?, ?, ?, NULL, NULL, NULL, 'planejado', ?, ?)""",
        (quadro_id, codigo, ordem_id, codigo, momento, momento),
    )
    return quadro_id


def _gravar_comparacao(conn, codigo: str, grupo: str, pasta: str, arquivo: str, digest: str, alterado_em: str, resultado: str, revisao_id: str | None) -> None:
    conn.execute(
        """INSERT INTO comparacao_rede
           (id, codigo_quadro, grupo, pasta, arquivo, hash_sha256, alterado_em, resultado, revisao_id, atualizado_em)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(pasta, arquivo) DO UPDATE SET
             codigo_quadro = excluded.codigo_quadro,
             grupo = excluded.grupo,
             hash_sha256 = excluded.hash_sha256,
             alterado_em = excluded.alterado_em,
             resultado = excluded.resultado,
             revisao_id = excluded.revisao_id,
             atualizado_em = excluded.atualizado_em""",
        (str(uuid.uuid4()), codigo, grupo, pasta, arquivo, digest, alterado_em, resultado, revisao_id, agora()),
    )


def _marcar_tratado(conn, nome: str, digest: str, caminho: str) -> None:
    conn.execute(
        """INSERT OR IGNORE INTO arquivo_rede_tratado (id, nome, hash_sha256, tratado_em, caminho)
           VALUES (?, ?, ?, ?, ?)""",
        (str(uuid.uuid4()), nome, digest, agora(), caminho),
    )


def _tratar_pdf(conn, raiz: Path, base: Path, grupo: str, codigo: str, pasta_pdf: Path, item: Path) -> None:
    conteudo = item.read_bytes()
    if not conteudo or item.suffix.lower() != ".pdf":
        return
    digest = hashlib.sha256(conteudo).hexdigest()
    alterado_em = _iso_mtime(item)
    pasta = pasta_pdf.relative_to(base).as_posix()
    quadro_id = garantir_quadro(conn, codigo, grupo)
    caminho = str(pasta_pdf / item.name)
    tratado = conn.execute(
        "SELECT 1 FROM arquivo_rede_tratado WHERE caminho = ? AND hash_sha256 = ?",
        (caminho, digest),
    ).fetchone()
    igual = conn.execute(
        """SELECT r.id FROM revisao r
           JOIN arquivo a ON a.id = r.arquivo_id
           WHERE r.quadro_id = ? AND a.hash_sha256 = ?""",
        (quadro_id, digest),
    ).fetchone()
    vigente = conn.execute(
        """SELECT r.id, r.importada_em FROM quadro q
           JOIN revisao r ON r.id = q.revisao_vigente_id
           WHERE q.id = ?""",
        (quadro_id,),
    ).fetchone()
    referencia = _referencia_vigente(vigente["importada_em"] if vigente else None)
    modificado = _instante(alterado_em)
    mais_novo = bool(
        vigente and referencia and modificado and modificado > referencia and (not igual or igual["id"] != vigente["id"])
    )
    if igual and not mais_novo:
        resultado = "Igual ao que já foi importado"
        revisao_id = igual["id"]
    elif mais_novo:
        resultado = "Mais novo que a revisão vigente"
        revisao_id = igual["id"] if igual else None
    else:
        resultado = "Ainda não entrou"
        revisao_id = None
    if revisao_id is None and not tratado:
        resposta = importar_no_quadro(
            conn,
            raiz,
            quadro_id,
            item.name,
            conteudo,
            codigo_revisao_do_arquivo(item.name),
            "rede",
            None,
        )
        revisao_id = resposta.get("revisao_id")
        _marcar_tratado(conn, item.name, digest, caminho)
    _gravar_comparacao(conn, codigo, grupo, pasta, item.name, digest, alterado_em, resultado, revisao_id)
    conn.commit()


def varrer_uma_vez(db_path: str, arquivos_dir: str, caminho: str, estado: dict) -> None:
    init_db(db_path)
    base = Path(caminho)
    with connect(db_path) as conn:
        if not pasta_aberta(caminho):
            registrar_estado(conn, caminho, False, INDISPONIVEL)
            conn.commit()
            return
        registrar_estado(conn, caminho, True, "A pasta de rede está acessível.")
        conn.commit()
        vistos = 0
        for grupo, codigo, pasta_pdf in pastas_de_projeto(base):
            garantir_quadro(conn, codigo, grupo)
            conn.commit()
            try:
                pdfs = [item for item in pasta_pdf.iterdir() if item.is_file() and item.suffix.lower() == ".pdf"]
            except OSError:
                continue
            vistos += len(pdfs)
            for item in sorted(pdfs, key=lambda arquivo: (arquivo.stat().st_mtime, arquivo.name)):
                if observar(estado, item):
                    _tratar_pdf(conn, Path(arquivos_dir), base, grupo, codigo, pasta_pdf, item)
        print(f"pdfs_vistos {vistos}", flush=True)


def _peso_revisao(nome: str) -> tuple[int, int]:
    codigo = codigo_revisao_do_arquivo(nome)
    if re.fullmatch(r"[A-Z]+", codigo or ""):
        n = 0
        for ch in codigo:
            n = n * 26 + (ord(ch) - 64)
        return (1, n)
    if (codigo or "").isdigit():
        return (1, int(codigo))
    return (0, 0)


def _pdf_mais_recente(pasta: Path) -> Path | None:
    alvo = pasta / "PDF" if pasta.name.lower() != "pdf" and (pasta / "PDF").is_dir() else pasta
    try:
        pdfs = [item for item in alvo.iterdir() if item.is_file() and item.suffix.lower() == ".pdf"]
    except OSError:
        return None
    if not pdfs:
        return None
    # Prefere a letra/número da revisão; mtime só desempata.
    return max(
        pdfs,
        key=lambda item: (_peso_revisao(item.name), item.stat().st_mtime, item.name),
    )


def _substituir_bytes(conn, arquivos_dir: str, revisao_id: str, nome: str, conteudo: bytes) -> None:
    arquivo_id = conn.execute(
        "SELECT arquivo_id FROM revisao WHERE id = ?",
        (revisao_id,),
    ).fetchone()["arquivo_id"]
    destino = Path(arquivos_dir) / "projetos" / arquivo_id
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_bytes(conteudo)
    digest = hashlib.sha256(conteudo).hexdigest()
    conn.execute(
        "UPDATE arquivo SET hash_sha256 = ?, tamanho_bytes = ?, nome_original = ? WHERE id = ?",
        (digest, len(conteudo), nome, arquivo_id),
    )
    conn.execute(
        "UPDATE revisao SET importada_em = ? WHERE id = ?",
        (agora(), revisao_id),
    )


def atualizar_caminhos_indicados(db_path: str, arquivos_dir: str) -> None:
    """O supervisor indica a pasta. Um PDF salvo lá substitui o que está na tela."""
    init_db(db_path)
    with connect(db_path) as conn:
        rows = conn.execute(
            """SELECT id, caminho_projeto FROM quadro
               WHERE caminho_projeto IS NOT NULL AND trim(caminho_projeto) != ''
                 AND status != 'cancelado'"""
        ).fetchall()
        for row in rows:
            try:
                recente = _pdf_mais_recente(Path(row["caminho_projeto"]))
                if recente is None:
                    continue
                conteudo = recente.read_bytes()
                if not conteudo:
                    continue
                digest = hashlib.sha256(conteudo).hexdigest()
                codigo_rev = codigo_revisao_do_arquivo(recente.name)
                existente = conn.execute(
                    "SELECT id FROM revisao WHERE quadro_id = ? AND codigo = ?",
                    (row["id"], codigo_rev),
                ).fetchone()
                if existente:
                    atual = conn.execute(
                        """SELECT a.hash_sha256 FROM revisao r
                           JOIN arquivo a ON a.id = r.arquivo_id
                           WHERE r.id = ?""",
                        (existente["id"],),
                    ).fetchone()
                    if atual and atual["hash_sha256"] == digest:
                        continue
                    _substituir_bytes(conn, arquivos_dir, existente["id"], recente.name, conteudo)
                else:
                    importar_no_quadro(
                        conn,
                        Path(arquivos_dir),
                        row["id"],
                        recente.name,
                        conteudo,
                        codigo_rev,
                        "rede",
                        None,
                    )
                conn.commit()
            except OSError:
                continue


def _incorporar_projetos_novos(db_path: str) -> None:
    """Pega card novo de PCP/execução no P&D e deixa o PDF entrar na lista do tablet."""
    from app.db import connect
    from app.sincronizar_rede import sincronizar_pastas

    with connect(db_path) as conn:
        ana = conn.execute("SELECT id FROM usuario WHERE login = 'ana.ribeiro'").fetchone()
        sincronizar_pastas(conn, ana["id"] if ana else None)
        conn.commit()


def main() -> None:
    db = os.environ.get("QUADROS_DB", "data/quadros.sqlite")
    arquivos = os.environ.get("QUADROS_ARQUIVOS") or str(Path(db).parent / "arquivos")
    intervalo = float(os.environ.get("QUADROS_PASTA_INTERVALO_S", "5"))
    init_db(db)
    ultimo_catalogo = 0.0
    while True:
        agora_s = time.time()
        if agora_s - ultimo_catalogo >= 60:
            try:
                _incorporar_projetos_novos(db)
            except Exception:
                pass
            ultimo_catalogo = agora_s
        try:
            atualizar_caminhos_indicados(db, arquivos)
        except Exception:
            pass
        time.sleep(intervalo)


if __name__ == "__main__":
    main()

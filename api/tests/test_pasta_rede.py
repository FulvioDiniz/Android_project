import os
import sqlite3
import time

from fastapi.testclient import TestClient

from app.main import create_app
from app.varredura import PASTA_PADRAO, varrer_uma_vez
from test_fase5 import token

PDF = b"%PDF-1.4\nrede\n"


def test_caminho_padrao_e_o_compartilhamento_da_fabrica():
    assert PASTA_PADRAO == "\\\\Srv3302\\projetos elétricos"


def test_pasta_local_cria_pendente_e_nome_inseguro_nao_cria_revisao(tmp_path, monkeypatch):
    monkeypatch.setenv("QUADROS_PASTA_USUARIO", "engenheiro")
    monkeypatch.setenv("QUADROS_PASTA_SENHA", "segredo-que-nao-pode-aparecer")
    db = tmp_path / "quadros.sqlite"
    arquivos = tmp_path / "arquivos"
    pasta = tmp_path / "projetos eletricos"
    pasta.mkdir()
    app = create_app(str(db), str(arquivos))
    client = TestClient(app)
    assert client.get("/api/saude").status_code == 200
    engenharia = token(client, "ana.ribeiro", "engenharia")
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    assert ordem.status_code == 201
    quadro = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={
            "codigo": "PED-DEL-01890-2026-16",
            "titulo": "Projeto",
            "ordem_producao_id": ordem.json()["id"],
        },
    )
    assert quadro.status_code == 201
    estado: dict = {}
    alvo = pasta / "PED-DEL-01890-2026-16-REV.C.pdf"
    alvo.write_bytes(PDF[:8])
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 0
    alvo.write_bytes(PDF)
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 0
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        linha = conn.execute("SELECT status, origem, codigo FROM revisao").fetchone()
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro").fetchone()[0]
        arquivo = conn.execute(
            "SELECT hash_sha256 FROM arquivo WHERE area = 'projetos'"
        ).fetchone()[0]
    assert linha == ("pendente", "rede", "C")
    assert ponteiro is None
    import hashlib
    assert arquivo == hashlib.sha256(PDF).hexdigest()
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 1

    ruim = pasta / "quadro final.pdf"
    ruim.write_bytes(b"%PDF-1.4\nruim\n")
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 1
        erro = conn.execute(
            "SELECT motivo, revisao_id FROM erro_importacao"
        ).fetchone()
    assert erro[0] == "convencao_invalida"
    assert erro[1] is None

    painel = client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {engenharia}"},
        files={"arquivo": ("PED-DEL-01890-2026-16-REV.D.pdf", b"%PDF-1.4\npainel\n", "application/pdf")},
    )
    assert painel.status_code == 201
    assert painel.json()["revisao_vigente_id"] is None
    lista = client.get(
        f"/api/quadros/{quadro.json()['id']}/revisoes",
        headers={"Authorization": f"Bearer {engenharia}"},
    )
    origens = {item["codigo"]: item["origem"] for item in lista.json()["revisoes"]}
    assert origens["C"] == "rede"
    assert origens["D"] == "painel"

    varrer_uma_vez(str(db), str(arquivos), str(tmp_path / "sumiu"), estado)
    estado_pasta = client.get("/api/pasta-rede", headers={"Authorization": f"Bearer {engenharia}"})
    assert estado_pasta.status_code == 200
    assert estado_pasta.json()["acessivel"] is False
    assert estado_pasta.json()["mensagem"] == "Pasta de rede indisponível."
    assert "segredo-que-nao-pode-aparecer" not in estado_pasta.text
    assert client.get("/api/saude").status_code == 200
    with sqlite3.connect(db) as conn:
        detalhe = conn.execute("SELECT detalhe FROM estado_pasta_rede").fetchone()[0]
    assert "segredo" not in detalhe


def test_subpastas_comparam_sem_publicar(tmp_path):
    db = tmp_path / "quadros.sqlite"
    arquivos = tmp_path / "arquivos"
    pasta = tmp_path / "projetos eletricos"
    antiga = pasta / "lote-antigo"
    nova = pasta / "lote-novo"
    antiga.mkdir(parents=True)
    nova.mkdir()
    app = create_app(str(db), str(arquivos))
    client = TestClient(app)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    quadro = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={
            "codigo": "PED-DEL-01890-2026-16",
            "titulo": "Projeto",
            "ordem_producao_id": ordem.json()["id"],
        },
    )
    vigente_bytes = b"%PDF-1.4\nvigente-a\n"
    enviado = client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {engenharia}"},
        files={"arquivo": ("PED-DEL-01890-2026-16-REV.A.pdf", vigente_bytes, "application/pdf")},
    )
    revisao_a = enviado.json()["revisao_id"]
    assert client.post(
        f"/api/revisoes/{revisao_a}/aprovacao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"tipo": "informativa"},
    ).status_code == 200
    publicada = client.post(
        f"/api/quadros/{quadro.json()['id']}/publicacao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"revisao_id": revisao_a},
    )
    assert publicada.status_code == 201
    agora = time.time()
    (antiga / "PED-DEL-01890-2026-16-REV.A.pdf").write_bytes(vigente_bytes)
    os.utime(antiga / "PED-DEL-01890-2026-16-REV.A.pdf", (agora - 3 * 86400, agora - 3 * 86400))
    (antiga / "PED-DEL-01890-2026-16-REV.B.pdf").write_bytes(b"%PDF-1.4\nantiga-b\n")
    os.utime(antiga / "PED-DEL-01890-2026-16-REV.B.pdf", (agora - 2 * 86400, agora - 2 * 86400))
    (nova / "PED-DEL-01890-2026-16-REV.C.pdf").write_bytes(b"%PDF-1.4\nnova-c\n")
    os.utime(nova / "PED-DEL-01890-2026-16-REV.C.pdf", (agora + 3600, agora + 3600))
    estado: dict = {}
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro").fetchone()[0]
        codigos = {
            row[0]: row[1]
            for row in conn.execute("SELECT codigo, status FROM revisao").fetchall()
        }
        comparacao = {
            row[0]: row[1]
            for row in conn.execute("SELECT arquivo, resultado FROM comparacao_rede").fetchall()
        }
    assert ponteiro == revisao_a
    assert codigos["A"] == "publicada"
    assert codigos["B"] == "pendente"
    assert codigos["C"] == "pendente"
    assert comparacao["PED-DEL-01890-2026-16-REV.A.pdf"] == "Igual ao que já foi importado"
    assert comparacao["PED-DEL-01890-2026-16-REV.B.pdf"] == "Ainda não entrou"
    assert comparacao["PED-DEL-01890-2026-16-REV.C.pdf"] == "Mais novo que a revisão vigente"
    varrer_uma_vez(str(db), str(arquivos), str(pasta), estado)
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT revisao_vigente_id FROM quadro").fetchone()[0] == revisao_a
        assert conn.execute("SELECT COUNT(*) FROM revisao WHERE codigo = 'C'").fetchone()[0] == 1

import hashlib
import sqlite3

from fastapi.testclient import TestClient

from app.main import create_app


PDF = b"%PDF-1.4\nexemplo\n"


def cliente(tmp_path):
    app = create_app(str(tmp_path / "quadros.sqlite"), str(tmp_path / "arquivos"))
    return TestClient(app), tmp_path


def token(client: TestClient, login: str, senha: str, aparelho: str | None = None) -> str:
    corpo = {"login": login, "senha": senha}
    if aparelho:
        corpo["identificador_instalacao"] = aparelho
    resposta = client.post("/api/sessao", json=corpo)
    assert resposta.status_code == 200, resposta.text
    return resposta.json()["token"]


def quadro(client: TestClient, acesso: str) -> None:
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    assert ordem.status_code == 201
    criado = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {acesso}"},
        json={
            "codigo": "PED-DEL-01890-2026-16",
            "titulo": "Projeto do PDF de exemplo",
            "ordem_producao_id": ordem.json()["id"],
        },
    )
    assert criado.status_code == 201


def enviar(client: TestClient, acesso: str, nome: str, conteudo: bytes = PDF):
    return client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {acesso}"},
        files={"arquivo": (nome, conteudo, "application/pdf")},
    )


def test_upload_valido_fica_pendente_e_nao_publica(tmp_path):
    client, pasta = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    resposta = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf")
    assert resposta.status_code == 201
    corpo = resposta.json()
    assert corpo["status"] == "pendente"
    assert corpo["codigo"] == "C"
    assert corpo["revisao_vigente_id"] is None
    assert corpo["hash_sha256"] == hashlib.sha256(PDF).hexdigest()
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        status, ponteiro = conn.execute(
            """SELECT r.status, q.revisao_vigente_id
               FROM revisao r JOIN quadro q ON q.id = r.quadro_id"""
        ).fetchone()
        area = conn.execute("SELECT area FROM arquivo").fetchone()[0]
    assert status == "pendente"
    assert ponteiro is None
    assert area == "projetos"
    gravado = list((pasta / "arquivos" / "projetos").iterdir())
    assert len(gravado) == 1
    assert hashlib.sha256(gravado[0].read_bytes()).hexdigest() == corpo["hash_sha256"]


def test_repetir_o_mesmo_arquivo_nao_duplica(tmp_path):
    client, pasta = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    assert enviar(client, acesso, "ped-del-01890-2026-16-rev.c.pdf").status_code == 201
    segunda = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf")
    assert segunda.status_code == 200
    assert segunda.json()["repetido"] is True
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 1


def test_nome_inseguro_nao_cria_revisao(tmp_path):
    client, pasta = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    resposta = enviar(client, acesso, "quadro final.pdf")
    assert resposta.status_code == 202
    assert resposta.json()["revisao_id"] is None
    assert resposta.json()["status"] == "validacao_manual"
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 0
        motivo = conn.execute("SELECT motivo, status FROM erro_importacao").fetchone()
    assert motivo == ("convencao_invalida", "aberto")
    assert list((pasta / "arquivos" / "quarentena").iterdir())


def test_codigo_desconhecido_nao_cria_revisao(tmp_path):
    client, pasta = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    resposta = enviar(client, acesso, "NAO-EXISTE-REV.A.pdf")
    assert resposta.status_code == 202
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 0
        assert conn.execute("SELECT motivo FROM erro_importacao").fetchone()[0] == "quadro_nao_encontrado"


def test_montagem_nao_envia_nem_publica(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    quadro(client, engenharia)
    montagem = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    resposta = enviar(client, montagem, "PED-DEL-01890-2026-16-REV.C.pdf")
    assert resposta.status_code == 403
    assert resposta.json()["erro"] == "perfil_insuficiente"
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM arquivo").fetchone()[0] == 0
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0]
    assert ponteiro is None


def test_arquivo_vazio_nao_grava(tmp_path):
    client, pasta = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    resposta = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf", b"")
    assert resposta.status_code == 422
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM erro_importacao").fetchone()[0] == 0

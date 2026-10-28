import os
import sqlite3
import stat

from fastapi.testclient import TestClient

from app.main import create_app


def cliente(tmp_path):
    app = create_app(str(tmp_path / "quadros.sqlite"))
    return TestClient(app), str(tmp_path / "quadros.sqlite")


def entrar(client: TestClient, login: str, senha: str, aparelho: str | None = None):
    corpo = {"login": login, "senha": senha}
    if aparelho is not None:
        corpo["identificador_instalacao"] = aparelho
    return client.post("/api/sessao", json=corpo)


def test_saude_ok(tmp_path):
    client, _ = cliente(tmp_path)
    resposta = client.get("/api/saude")
    assert resposta.status_code == 200
    assert resposta.json()["status"] == "ok"


def test_saude_banco_indisponivel(tmp_path):
    caminho = tmp_path / "quadros.sqlite"
    client, _ = cliente(tmp_path)
    os.chmod(caminho, 0)
    try:
        resposta = client.get("/api/saude")
    finally:
        os.chmod(caminho, stat.S_IRUSR | stat.S_IWUSR)
    assert resposta.status_code == 503
    assert resposta.json()["erro"] == "banco_indisponivel"


def test_engenharia_cadastra_e_montagem_ve_no_tablet(tmp_path):
    client, caminho = cliente(tmp_path)
    painel = entrar(client, "ana.ribeiro", "engenharia")
    assert painel.status_code == 200
    token_painel = painel.json()["token"]
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {token_painel}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    assert ordem.status_code == 201
    quadro = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {token_painel}"},
        json={
            "codigo": "PED-DEL-01890-2026-16",
            "titulo": "Projeto do PDF de exemplo",
            "ordem_producao_id": ordem.json()["id"],
        },
    )
    assert quadro.status_code == 201
    tablet = entrar(client, "carlos.mota", "montagem", "tablet-fase-1")
    assert tablet.status_code == 200
    lista = client.get(
        "/api/quadros",
        headers={"Authorization": f"Bearer {tablet.json()['token']}"},
    )
    assert lista.status_code == 200
    codigos = [item["codigo"] for item in lista.json()]
    assert codigos == ["01855-2026-05 QT-1", "PED-DEL-01890-2026-16"]
    with sqlite3.connect(caminho) as conn:
        n = conn.execute("SELECT COUNT(*) FROM quadro").fetchone()[0]
    assert n == 2


def test_montagem_nao_cadastra_quadro(tmp_path):
    client, caminho = cliente(tmp_path)
    tablet = entrar(client, "carlos.mota", "montagem", "tablet-fase-1")
    resposta = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {tablet.json()['token']}"},
        json={
            "codigo": "QD-1",
            "titulo": "Não pode",
            "ordem_producao_id": "nao-existe",
        },
    )
    assert resposta.status_code == 403
    assert resposta.json()["erro"] == "perfil_insuficiente"
    with sqlite3.connect(caminho) as conn:
        linha = conn.execute("SELECT codigo, revisao_vigente_id FROM quadro").fetchone()
        revisoes = conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0]
    assert linha == ("01855-2026-05 QT-1", None)
    assert revisoes == 0


def test_senha_errada_nao_cria_quadro(tmp_path):
    client, caminho = cliente(tmp_path)
    resposta = entrar(client, "ana.ribeiro", "errada")
    assert resposta.status_code == 401
    assert resposta.json()["erro"] == "credencial_invalida"
    with sqlite3.connect(caminho) as conn:
        n = conn.execute("SELECT COUNT(*) FROM quadro").fetchone()[0]
        revisoes = conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0]
        auditoria = conn.execute(
            "SELECT COUNT(*) FROM evento_auditoria WHERE acao = 'sessao'"
        ).fetchone()[0]
    assert n == 1
    assert revisoes == 0
    assert auditoria == 0


def test_banco_novo_lista_o_exemplo_sem_revisao(tmp_path):
    client, caminho = cliente(tmp_path)
    with sqlite3.connect(caminho) as conn:
        cliente_nome = conn.execute(
            """SELECT c.nome FROM quadro q
               JOIN ordem_producao op ON op.id = q.ordem_producao_id
               JOIN cliente c ON c.id = op.cliente_id"""
        ).fetchone()[0]
        ponteiro = conn.execute(
            "SELECT revisao_vigente_id FROM quadro WHERE codigo = '01855-2026-05 QT-1'"
        ).fetchone()[0]
        revisoes = conn.execute("SELECT COUNT(*) FROM revisao").fetchone()[0]
    assert cliente_nome == "2T ENGENHARIA"
    assert ponteiro is None
    assert revisoes == 0
    tablet = entrar(client, "carlos.mota", "montagem", "tablet-fase-1")
    lista = client.get("/api/quadros", headers={"Authorization": f"Bearer {tablet.json()['token']}"})
    assert lista.status_code == 200
    item = lista.json()[0]
    assert item["codigo"] == "01855-2026-05 QT-1"
    assert item["cliente_nome"] == "2T ENGENHARIA"
    assert item["revisao_vigente_id"] is None


def test_montagem_nao_entra_no_painel(tmp_path):
    client, _ = cliente(tmp_path)
    resposta = entrar(client, "carlos.mota", "montagem")
    assert resposta.status_code == 403
    assert resposta.json()["erro"] == "perfil_insuficiente"


def test_tablet_desconhecido_fica_pendente(tmp_path):
    client, caminho = cliente(tmp_path)
    resposta = entrar(client, "carlos.mota", "montagem", "aparelho-novo")
    assert resposta.status_code == 403
    assert resposta.json()["erro"] == "dispositivo_inativo"
    with sqlite3.connect(caminho) as conn:
        status = conn.execute(
            "SELECT status FROM dispositivo WHERE identificador_instalacao = ?",
            ("aparelho-novo",),
        ).fetchone()[0]
        sessoes = conn.execute("SELECT COUNT(*) FROM sessao").fetchone()[0]
    assert status == "pendente"
    assert sessoes == 0

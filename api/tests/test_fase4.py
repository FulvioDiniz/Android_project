import hashlib
import sqlite3
import threading
import time
import uuid

from fastapi.testclient import TestClient

from app.main import create_app

PDF = b"%PDF-1.4\nfase4-conteudo-completo\n"


def cliente(tmp_path):
    app = create_app(str(tmp_path / "quadros.sqlite"), str(tmp_path / "arquivos"))
    return TestClient(app), tmp_path, app


def token(client, login, senha, aparelho=None):
    corpo = {"login": login, "senha": senha}
    if aparelho:
        corpo["identificador_instalacao"] = aparelho
    resposta = client.post("/api/sessao", json=corpo)
    assert resposta.status_code == 200, resposta.text
    return resposta.json()["token"]


def preparar(client, acesso):
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    quadro = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {acesso}"},
        json={
            "codigo": "PED-DEL-01890-2026-16",
            "titulo": "Projeto",
            "ordem_producao_id": ordem.json()["id"],
        },
    )
    assert quadro.status_code == 201
    enviado = client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {acesso}"},
        files={"arquivo": ("PED-DEL-01890-2026-16-REV.C.pdf", PDF, "application/pdf")},
    )
    revisao = enviado.json()["revisao_id"]
    client.post(
        f"/api/revisoes/{revisao}/aprovacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"tipo": "informativa"},
    )
    return revisao


def publicar(client, acesso, pasta, revisao):
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        quadro_id = conn.execute(
            """SELECT q.id FROM quadro q
               JOIN revisao r ON r.quadro_id = q.id
               WHERE r.id = ?""",
            (revisao,),
        ).fetchone()[0]
    return client.post(
        f"/api/quadros/{quadro_id}/publicacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"revisao_id": revisao},
    )


def test_aviso_em_tempo_real_e_notificacao_ao_reconectar(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia)
    with client.websocket_connect("/api/ws") as ws:
        ws.send_json({"token": tablet})

        def soltar():
            time.sleep(0.3)
            publicar(client, engenharia, pasta, revisao)

        fio = threading.Thread(target=soltar)
        fio.start()
        aviso = ws.receive_json()
        fio.join()
    assert aviso["tipo"] == "revisao_publicada"
    assert aviso["codigo"] == "C"
    marco = aviso and ""
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        marco = conn.execute("SELECT criada_em FROM notificacao").fetchone()[0]
    outro = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"numero": "OP-ENSAIO", "cliente_nome": "Ensaio"},
    )
    quadro = client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"codigo": "QD-ENSAIO", "titulo": "Segundo", "ordem_producao_id": outro.json()["id"]},
    )
    assert quadro.status_code == 201
    enviado = client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {engenharia}"},
        files={"arquivo": ("QD-ENSAIO-REV.A.pdf", b"%PDF-1.4\noutro\n", "application/pdf")},
    )
    segunda = enviado.json()["revisao_id"]
    client.post(
        f"/api/revisoes/{segunda}/aprovacao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"tipo": "informativa"},
    )
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        quadro_id = conn.execute("SELECT id FROM quadro WHERE codigo = 'QD-ENSAIO'").fetchone()[0]
    client.post(
        f"/api/quadros/{quadro_id}/publicacao",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"revisao_id": segunda},
    )
    perdidas = client.get(
        "/api/notificacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        params={"desde": marco},
    )
    assert perdidas.status_code == 200
    assert any(item["revisao_id"] == segunda for item in perdidas.json())


def test_download_retomavel_e_hash(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia)
    publicar(client, engenharia, pasta, revisao)
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        arquivo_id, digest = conn.execute(
            "SELECT id, hash_sha256 FROM arquivo WHERE area = 'projetos'"
        ).fetchone()
    cabeca = {"Authorization": f"Bearer {tablet}"}
    inicio = client.get(f"/api/arquivos/{arquivo_id}", headers={**cabeca, "Range": "bytes=0-7"})
    assert inicio.status_code == 206
    assert inicio.headers["etag"] == digest
    resto_inicio = 8
    fim = client.get(
        f"/api/arquivos/{arquivo_id}",
        headers={**cabeca, "Range": f"bytes={resto_inicio}-"},
    )
    assert fim.status_code == 206
    junto = inicio.content + fim.content
    assert junto == PDF
    assert hashlib.sha256(junto).hexdigest() == digest
    truncado = inicio.content
    assert hashlib.sha256(truncado).hexdigest() != digest
    client.cookies.clear()
    sem_sessao = client.get(f"/api/arquivos/{arquivo_id}")
    assert sem_sessao.status_code == 401
    assert not sem_sessao.content.startswith(b"%PDF")
    quarentena = client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {engenharia}"},
        files={"arquivo": ("quadro final.pdf", b"%PDF-1.4\nruim\n", "application/pdf")},
    )
    assert quarentena.status_code == 202
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        proibido = conn.execute(
            "SELECT arquivo_id FROM erro_importacao"
        ).fetchone()[0]
    negado = client.get(f"/api/arquivos/{proibido}", headers=cabeca)
    assert negado.status_code == 403
    assert not negado.content.startswith(b"%PDF")


def test_confirmacao_idempotente_e_painel(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    carlos = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia)
    publicar(client, engenharia, pasta, revisao)
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        quadro_id = conn.execute("SELECT id FROM quadro WHERE codigo = 'PED-DEL-01890-2026-16'").fetchone()[0]
        conn.execute(
            """INSERT INTO dispositivo
               (id, identificador_instalacao, apelido, status, ultimo_visto_em)
               VALUES (?, 'tablet-reserva', 'Reserva', 'ativo', '2026-10-16T00:00:00+00:00')""",
            (str(uuid.uuid4()),),
        )
        conn.commit()
    operacao = str(uuid.uuid4())
    corpo = {
        "operacao_id": operacao,
        "tipo": "confirmacao_leitura",
        "revisao_id": revisao,
        "ocorrido_em": "2026-10-16T07:00:00+00:00",
    }
    primeira = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {carlos}"}, json=corpo)
    assert primeira.status_code == 201
    assert primeira.json()["ocorrido_em"] != primeira.json()["recebido_em"]
    repetida = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {carlos}"}, json=corpo)
    assert repetida.status_code == 201
    assert repetida.json()["id"] == primeira.json()["id"]
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM confirmacao_leitura").fetchone()[0] == 1
    ana = token(client, "ana.ribeiro", "engenharia", "tablet-fase-1")
    segunda = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {ana}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "confirmacao_leitura",
            "revisao_id": revisao,
            "ocorrido_em": "2026-10-16T07:05:00+00:00",
        },
    )
    assert segunda.status_code == 201
    painel = client.get(
        f"/api/quadros/{quadro_id}/confirmacoes",
        headers={"Authorization": f"Bearer {engenharia}"},
    )
    assert painel.status_code == 200
    nomes = {item["usuario"] for item in painel.json()["confirmados"]}
    assert nomes == {"Carlos Mota", "Ana Ribeiro"}
    faltam = {item["dispositivo"] for item in painel.json()["faltam"]}
    assert "Reserva" in faltam
    assert "Galaxy Tab A11+" not in faltam

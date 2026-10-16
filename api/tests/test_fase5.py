import sqlite3
import uuid
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app.main import create_app

PDF = b"%PDF-1.4\nfase5\n"


def cliente(tmp_path):
    app = create_app(str(tmp_path / "quadros.sqlite"), str(tmp_path / "arquivos"))
    return TestClient(app), tmp_path


def token(client, login, senha, aparelho=None):
    corpo = {"login": login, "senha": senha}
    if aparelho:
        corpo["identificador_instalacao"] = aparelho
    resposta = client.post("/api/sessao", json=corpo)
    assert resposta.status_code == 200, resposta.text
    return resposta.json()["token"]


def preparar(client, acesso, nome="PED-DEL-01890-2026-16-REV.C.pdf", tipo="bloqueio", conteudo=PDF):
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    if ordem.status_code == 201:
        client.post(
            "/api/quadros",
            headers={"Authorization": f"Bearer {acesso}"},
            json={
                "codigo": "PED-DEL-01890-2026-16",
                "titulo": "Projeto",
                "ordem_producao_id": ordem.json()["id"],
            },
        )
    enviado = client.post(
        "/api/projetos",
        headers={"Authorization": f"Bearer {acesso}"},
        files={"arquivo": (nome, conteudo, "application/pdf")},
    )
    assert enviado.status_code == 201, enviado.text
    revisao = enviado.json()["revisao_id"]
    assert client.post(
        f"/api/revisoes/{revisao}/aprovacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"tipo": tipo},
    ).status_code == 200
    return revisao


def publicar(client, acesso, pasta, revisao):
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        quadro_id = conn.execute(
            "SELECT id FROM quadro WHERE codigo = 'PED-DEL-01890-2026-16'"
        ).fetchone()[0]
    resposta = client.post(
        f"/api/quadros/{quadro_id}/publicacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"revisao_id": revisao},
    )
    assert resposta.status_code == 201, resposta.text
    return quadro_id


def iso(delta=timedelta()):
    return (datetime.now(timezone.utc) + delta).isoformat()


def test_observacao_anterior_entra_e_ponteiro_ignorado(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        conn.execute(
            "UPDATE dispositivo SET ultimo_sync_em = ? WHERE identificador_instalacao = 'tablet-fase-1'",
            (iso(-timedelta(hours=2)),),
        )
        conn.commit()
    revisao = preparar(client, engenharia, tipo="bloqueio")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    operacao = str(uuid.uuid4())
    corpo = {
        "operacao_id": operacao,
        "tipo": "observacao",
        "quadro_id": quadro_id,
        "texto": "Régua conferida antes da revisão nova",
        "ocorrido_em": iso(-timedelta(hours=1)),
        "revisao_vigente_id": "ponteiro-falso",
    }
    resposta = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {tablet}"}, json=corpo)
    assert resposta.status_code == 201
    assert resposta.json()["ocorrido_em"] == corpo["ocorrido_em"]
    assert resposta.json()["revisao_vigente_id"] == revisao
    repetida = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {tablet}"}, json=corpo)
    assert repetida.json()["id"] == resposta.json()["id"]
    assert repetida.json()["revisao_vigente_id"] == revisao
    outro_texto = dict(corpo, texto="texto trocado")
    conflito = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {tablet}"}, json=outro_texto)
    assert conflito.status_code == 409
    assert conflito.json()["erro"] == "conflito_operacao"
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM observacao").fetchone()[0] == 1
        assert conn.execute("SELECT texto FROM observacao").fetchone()[0] == corpo["texto"]
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0]
        assert ponteiro == revisao
        assert conn.execute(
            "SELECT COUNT(*) FROM evento_auditoria WHERE detalhe = 'ponteiro ignorado'"
        ).fetchone()[0] >= 1


def test_bloqueio_recusa_ate_todos_os_tablets_confirmarem(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia, tipo="bloqueio")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        conn.execute(
            "UPDATE quadro SET bancada_id = 'bnc-2' WHERE id = ?",
            (quadro_id,),
        )
        conn.execute(
            """INSERT INTO dispositivo
               (id, identificador_instalacao, apelido, bancada_id, status, ultimo_visto_em)
               VALUES ('dsp-2', 'tablet-2', 'Tablet 2', 'bnc-2', 'ativo', ?)""",
            (iso(),),
        )
        conn.execute(
            "UPDATE dispositivo SET ultimo_sync_em = ?, ultimo_visto_em = ? WHERE id = 'dsp-tab'",
            (iso(), iso()),
        )
        conn.commit()
    corpo_etapa = {
        "operacao_id": str(uuid.uuid4()),
        "tipo": "etapa_concluir",
        "quadro_id": quadro_id,
        "etapa_codigo": "fiacao",
        "ocorrido_em": iso(),
    }
    recusa = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json=corpo_etapa,
    )
    assert recusa.status_code == 409
    assert recusa.json()["erro"] == "bloqueio_vigencia"
    assert recusa.json()["revisao_vigente_id"] == revisao
    observacao = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "observacao",
            "quadro_id": quadro_id,
            "texto": "Anotação com a etapa recusada",
            "ocorrido_em": iso(),
        },
    )
    assert observacao.status_code == 201
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM etapa_producao").fetchone()[0] == 0
        assert conn.execute(
            "SELECT COUNT(*) FROM operacao_sincronizacao WHERE operacao_id = ?",
            (corpo_etapa["operacao_id"],),
        ).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM observacao").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM evento_auditoria WHERE acao = 'bloqueio'").fetchone()[0] == 1
    client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "confirmacao_leitura",
            "revisao_id": revisao,
            "ocorrido_em": iso(),
        },
    )
    ainda = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "etapa_concluir",
            "quadro_id": quadro_id,
            "etapa_codigo": "fiacao",
            "ocorrido_em": iso(),
        },
    )
    assert ainda.status_code == 409
    outro = token(client, "ana.ribeiro", "engenharia", "tablet-2")
    client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {outro}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "confirmacao_leitura",
            "revisao_id": revisao,
            "ocorrido_em": iso(),
        },
    )
    aceita = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json=corpo_etapa,
    )
    assert aceita.status_code == 200
    assert aceita.json()["status"] == "concluida"


def test_informativa_libera_e_reavaliacao_e_por_aparelho(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia, nome="PED-DEL-01890-2026-16-REV.A.pdf", tipo="informativa", conteudo=b"%PDF-1.4\na\n")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    livre = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "etapa_concluir",
            "quadro_id": quadro_id,
            "etapa_codigo": "teste",
            "ocorrido_em": iso(),
        },
    )
    assert livre.status_code == 200
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM confirmacao_leitura").fetchone()[0] == 0
    segunda = preparar(client, engenharia, nome="PED-DEL-01890-2026-16-REV.B.pdf", tipo="reavaliacao", conteudo=b"%PDF-1.4\nb\n")
    publicar(client, engenharia, pasta, segunda)
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        conn.execute(
            """INSERT INTO dispositivo
               (id, identificador_instalacao, apelido, bancada_id, status, ultimo_visto_em, ultimo_sync_em)
               VALUES ('dsp-3', 'tablet-3', 'Tablet 3', 'bnc-2', 'ativo', ?, ?)""",
            (iso(), iso()),
        )
        conn.execute(
            "UPDATE dispositivo SET ultimo_sync_em = ? WHERE id = 'dsp-tab'",
            (iso(),),
        )
        conn.commit()
    sem = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "etapa_concluir",
            "quadro_id": quadro_id,
            "etapa_codigo": "mecanica",
            "ocorrido_em": iso(),
        },
    )
    assert sem.status_code == 409
    outro = token(client, "ana.ribeiro", "engenharia", "tablet-3")
    client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {outro}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "confirmacao_leitura",
            "revisao_id": segunda,
            "ocorrido_em": iso(),
        },
    )
    com = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {outro}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "etapa_concluir",
            "quadro_id": quadro_id,
            "etapa_codigo": "mecanica",
            "ocorrido_em": iso(),
        },
    )
    assert com.status_code == 200
    assert com.json()["status"] == "concluida"


def test_relogio_fora_nao_aplica_etapa(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia, tipo="informativa", nome="PED-DEL-01890-2026-16-REV.D.pdf", conteudo=b"%PDF-1.4\nd\n")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "observacao",
            "quadro_id": quadro_id,
            "texto": "ancora o relogio",
            "ocorrido_em": iso(),
        },
    )
    pulo = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "etapa_concluir",
            "quadro_id": quadro_id,
            "etapa_codigo": "expedicao",
            "ocorrido_em": iso(timedelta(minutes=10)),
        },
    )
    assert pulo.status_code == 409
    assert pulo.json()["erro"] == "relogio_invalido"
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute(
            "SELECT COUNT(*) FROM etapa_producao WHERE operacao_conclusao_id IS NOT NULL AND etapa_catalogo_id = 'et-6'"
        ).fetchone()[0] == 0

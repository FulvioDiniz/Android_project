import hashlib
import sqlite3
import uuid

from test_fase5 import cliente, iso, preparar, publicar, token

IMAGEM = b"\xff\xd8" + b"foto-fase6-imagem-de-ensaio-0123456789"


def _quadro(client, acesso) -> str:
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"numero": "PED-DEL-01890-2026-16", "cliente_nome": "Exemplo"},
    )
    assert ordem.status_code in (200, 201), ordem.text
    if ordem.status_code == 201:
        criado = client.post(
            "/api/quadros",
            headers={"Authorization": f"Bearer {acesso}"},
            json={
                "codigo": "PED-DEL-01890-2026-16",
                "titulo": "Projeto",
                "ordem_producao_id": ordem.json()["id"],
            },
        )
        assert criado.status_code == 201, criado.text
        return criado.json()["id"]
    with sqlite3.connect(client.app.state.db_path) as conn:
        return conn.execute(
            "SELECT id FROM quadro WHERE codigo = 'PED-DEL-01890-2026-16'"
        ).fetchone()[0]


def _bancada(pasta, quadro_id: str) -> None:
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        conn.execute("UPDATE quadro SET bancada_id = 'bnc-2' WHERE id = ?", (quadro_id,))
        conn.commit()


def _andamento(client, acesso, quadro_id: str) -> dict:
    resposta = client.get("/api/bancadas/andamento", headers={"Authorization": f"Bearer {acesso}"})
    assert resposta.status_code == 200, resposta.text
    for bancada in resposta.json():
        for quadro in bancada["quadros"]:
            if quadro["id"] == quadro_id:
                return quadro
    raise AssertionError("quadro fora do andamento")


def test_duvida_com_foto_retomavel_aparece_uma_vez(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    quadro_id = _quadro(client, engenharia)
    duvida_id = str(uuid.uuid4())
    duvida = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": duvida_id,
            "tipo": "duvida",
            "quadro_id": quadro_id,
            "texto": "O borne 12 não fecha",
            "ocorrido_em": iso(),
        },
    )
    assert duvida.status_code == 201, duvida.text
    observacao_id = duvida.json()["id"]
    operacao = str(uuid.uuid4())
    digest = hashlib.sha256(IMAGEM).hexdigest()
    abertura = client.post(
        "/api/uploads",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": operacao,
            "hash_sha256": digest,
            "tamanho_bytes": len(IMAGEM),
            "quadro_id": quadro_id,
            "ocorrido_em": iso(),
            "observacao_id": observacao_id,
            "legenda": "Borne 12",
        },
    )
    assert abertura.status_code == 201, abertura.text
    corte = 12
    primeira = client.put(
        f"/api/uploads/{operacao}",
        content=IMAGEM[:corte],
        headers={
            "Authorization": f"Bearer {tablet}",
            "Content-Range": f"bytes 0-{corte - 1}/{len(IMAGEM)}",
        },
    )
    assert primeira.status_code == 200, primeira.text
    assert primeira.json()["recebido_bytes"] == corte
    assert primeira.json()["completo"] is False
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM foto").fetchone()[0] == 0
    segunda = client.put(
        f"/api/uploads/{operacao}",
        content=IMAGEM[corte:],
        headers={
            "Authorization": f"Bearer {tablet}",
            "Content-Range": f"bytes {corte}-{len(IMAGEM) - 1}/{len(IMAGEM)}",
        },
    )
    assert segunda.status_code == 201, segunda.text
    repetida = client.put(
        f"/api/uploads/{operacao}",
        content=IMAGEM[corte:],
        headers={
            "Authorization": f"Bearer {tablet}",
            "Content-Range": f"bytes {corte}-{len(IMAGEM) - 1}/{len(IMAGEM)}",
        },
    )
    assert repetida.json()["id"] == segunda.json()["id"]
    arquivos = list((pasta / "arquivos" / "fotos").iterdir())
    assert len(arquivos) == 1
    assert hashlib.sha256(arquivos[0].read_bytes()).hexdigest() == digest
    assert not list((pasta / "arquivos" / "parciais").glob("*.part"))
    painel = client.get(
        "/api/observacoes?tipo=duvida",
        headers={"Authorization": f"Bearer {engenharia}"},
    )
    de_novo = client.get(
        "/api/observacoes?tipo=duvida",
        headers={"Authorization": f"Bearer {engenharia}"},
    )
    assert painel.status_code == 200
    assert len(painel.json()) == 1
    assert len(de_novo.json()) == 1
    assert painel.json()[0]["texto"] == "O borne 12 não fecha"
    assert painel.json()[0]["fotos"][0]["hash_sha256"] == digest
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM observacao WHERE tipo = 'duvida'").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM foto").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM arquivo WHERE area = 'fotos'").fetchone()[0] == 1


def test_resposta_chega_na_consulta_e_montagem_nao_responde(tmp_path):
    client, _pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    quadro_id = _quadro(client, engenharia)
    duvida = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "duvida",
            "quadro_id": quadro_id,
            "texto": "Falta o prensa-cabo",
            "ocorrido_em": iso(),
        },
    )
    observacao_id = duvida.json()["id"]
    recusa = client.post(
        f"/api/observacoes/{observacao_id}/resposta",
        headers={"Authorization": f"Bearer {tablet}"},
        json={"resposta": "montagem não responde"},
    )
    assert recusa.status_code == 403
    assert recusa.json()["erro"] == "perfil_insuficiente"
    ok = client.post(
        f"/api/observacoes/{observacao_id}/resposta",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"resposta": "Está na revisão, lado direito."},
    )
    assert ok.status_code == 200, ok.text
    sync = client.get("/api/observacoes?tipo=duvida", headers={"Authorization": f"Bearer {tablet}"})
    assert sync.json()[0]["resposta"] == "Está na revisão, lado direito."
    with sqlite3.connect(client.app.state.db_path) as conn:
        assert conn.execute("SELECT resposta FROM observacao").fetchone()[0] == "Está na revisão, lado direito."


def test_pendencia_bloqueia_ate_resolver_e_andamento_acompanha(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    supervisao = token(client, "helena.dias", "supervisao")
    revisao = preparar(client, engenharia, nome="PED-DEL-01890-2026-16-REV.A.pdf", tipo="informativa", conteudo=b"%PDF-1.4\na\n")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    _bancada(pasta, quadro_id)
    pendencia = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "pendencia",
            "quadro_id": quadro_id,
            "descricao": "Falta etiqueta",
            "bloqueia_etapa": True,
            "ocorrido_em": iso(),
        },
    )
    assert pendencia.status_code == 201, pendencia.text
    corpo = {
        "operacao_id": str(uuid.uuid4()),
        "tipo": "etapa_concluir",
        "quadro_id": quadro_id,
        "etapa_codigo": "fiacao",
        "ocorrido_em": iso(),
    }
    recusa = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {tablet}"}, json=corpo)
    assert recusa.status_code == 409
    assert recusa.json()["erro"] == "bloqueio_pendencia"
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM etapa_producao").fetchone()[0] == 0
    montagem = client.post(
        f"/api/pendencias/{pendencia.json()['id']}/resolucao",
        headers={"Authorization": f"Bearer {tablet}"},
    )
    assert montagem.status_code == 403
    resolvida = client.post(
        f"/api/pendencias/{pendencia.json()['id']}/resolucao",
        headers={"Authorization": f"Bearer {supervisao}"},
    )
    assert resolvida.status_code == 200, resolvida.text
    aceita = client.post("/api/sync/operacoes", headers={"Authorization": f"Bearer {tablet}"}, json=corpo)
    assert aceita.status_code == 200, aceita.text
    assert aceita.json()["status"] == "concluida"
    painel = _andamento(client, engenharia, quadro_id)
    assert painel["etapas"] == [{"codigo": "fiacao", "nome": "Fiação", "status": "concluida"}]
    assert painel["pendencias"] == []
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        status = conn.execute("SELECT status FROM etapa_producao").fetchone()[0]
    assert status == painel["etapas"][0]["status"]
    assert any(evento["acao"] == "mudanca_etapa" for evento in painel["eventos"])
    assert any(evento["acao"] == "bloqueio" for evento in painel["eventos"])


def test_bloqueio_de_revisao_nao_muda_andamento(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia, tipo="bloqueio")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    _bancada(pasta, quadro_id)
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        conn.execute(
            "UPDATE dispositivo SET ultimo_sync_em = ?, ultimo_visto_em = ? WHERE id = 'dsp-tab'",
            (iso(), iso()),
        )
        conn.commit()
    antes = _andamento(client, engenharia, quadro_id)
    recusa = client.post(
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
    assert recusa.status_code == 409
    assert recusa.json()["erro"] == "bloqueio_vigencia"
    depois = _andamento(client, engenharia, quadro_id)
    assert depois["etapas"] == antes["etapas"]
    assert depois["pendencias"] == antes["pendencias"]
    assert any(evento["acao"] == "bloqueio" for evento in depois["eventos"])
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM etapa_producao").fetchone()[0] == 0

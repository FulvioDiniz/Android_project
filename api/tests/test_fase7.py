import os
import sqlite3
import stat
import uuid

from test_fase5 import cliente, iso, preparar, publicar, token


def _modo(caminho) -> int:
    return stat.S_IMODE(os.stat(caminho).st_mode)


def test_admin_cria_usuarios_e_cada_perfil_fica_na_sua_rota(tmp_path):
    client, _pasta = cliente(tmp_path)
    admin = token(client, "rita.sol", "admin")
    montador = client.post(
        "/api/usuarios",
        headers={"Authorization": f"Bearer {admin}"},
        json={"nome": "João Pires", "login": "joao.pires", "senha": "montagem", "perfil": "montagem"},
    )
    assert montador.status_code == 201, montador.text
    engenheira = client.post(
        "/api/usuarios",
        headers={"Authorization": f"Bearer {admin}"},
        json={"nome": "Bia Nunes", "login": "bia.nunes", "senha": "engenharia", "perfil": "engenharia"},
    )
    assert engenheira.status_code == 201, engenheira.text
    painel = client.post("/api/sessao", json={"login": "joao.pires", "senha": "montagem"})
    assert painel.status_code == 403
    assert painel.json()["erro"] == "perfil_insuficiente"
    joao = token(client, "joao.pires", "montagem", "tablet-fase-1")
    assert client.get("/api/quadros", headers={"Authorization": f"Bearer {joao}"}).status_code == 200
    assert client.post(
        "/api/quadros",
        headers={"Authorization": f"Bearer {joao}"},
        json={"codigo": "X", "titulo": "X", "ordem_producao_id": "nenhuma"},
    ).status_code == 403
    assert client.get("/api/auditoria", headers={"Authorization": f"Bearer {joao}"}).status_code == 403
    assert client.get("/api/usuarios", headers={"Authorization": f"Bearer {joao}"}).status_code == 403
    bia = token(client, "bia.nunes", "engenharia")
    assert client.get("/api/auditoria", headers={"Authorization": f"Bearer {bia}"}).status_code == 200
    assert client.get("/api/usuarios", headers={"Authorization": f"Bearer {bia}"}).status_code == 403
    assert client.get("/api/dispositivos", headers={"Authorization": f"Bearer {bia}"}).status_code == 403
    ordem = client.post(
        "/api/ordens-producao",
        headers={"Authorization": f"Bearer {bia}"},
        json={"numero": "OP-BIA-1", "cliente_nome": "Bia"},
    )
    assert ordem.status_code == 201, ordem.text
    novo = client.post("/api/sessao", json={"login": "carlos.mota", "senha": "montagem", "identificador_instalacao": "tablet-novo"})
    assert novo.status_code == 403
    assert novo.json()["erro"] == "dispositivo_inativo"
    aparelhos = client.get("/api/dispositivos", headers={"Authorization": f"Bearer {admin}"}).json()
    pendente = next(item for item in aparelhos if item["identificador_instalacao"] == "tablet-novo")
    assert client.post(
        f"/api/dispositivos/{pendente['id']}/vinculo",
        headers={"Authorization": f"Bearer {admin}"},
        json={"bancada_id": "bnc-2"},
    ).status_code == 200
    assert client.post(
        f"/api/dispositivos/{pendente['id']}/ativacao",
        headers={"Authorization": f"Bearer {admin}"},
    ).status_code == 200
    assert token(client, "carlos.mota", "montagem", "tablet-novo")
    bloqueio = client.post(
        f"/api/usuarios/{montador.json()['id']}/bloqueio",
        headers={"Authorization": f"Bearer {admin}"},
    )
    assert bloqueio.status_code == 200
    assert client.get("/api/quadros", headers={"Authorization": f"Bearer {joao}"}).status_code == 401
    de_novo = client.post("/api/sessao", json={"login": "joao.pires", "senha": "montagem", "identificador_instalacao": "tablet-fase-1"})
    assert de_novo.status_code == 401


def test_bloquear_tablet_revoga_sessao_e_sai_do_bloqueio_critico(tmp_path):
    client, pasta = cliente(tmp_path)
    admin = token(client, "rita.sol", "admin")
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia, tipo="bloqueio")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        conn.execute("UPDATE quadro SET bancada_id = 'bnc-2' WHERE id = ?", (quadro_id,))
        conn.execute(
            """INSERT INTO dispositivo
               (id, identificador_instalacao, apelido, bancada_id, status, ultimo_visto_em, ultimo_sync_em)
               VALUES ('dsp-2', 'tablet-2', 'Tablet 2', 'bnc-2', 'ativo', ?, ?)""",
            (iso(), iso()),
        )
        conn.execute(
            "UPDATE dispositivo SET ultimo_sync_em = ?, ultimo_visto_em = ? WHERE id = 'dsp-tab'",
            (iso(), iso()),
        )
        conn.commit()
    outro = token(client, "carlos.mota", "montagem", "tablet-2")
    recusa = client.post(
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
    assert recusa.status_code == 409
    assert recusa.json()["erro"] == "bloqueio_vigencia"
    bloqueio = client.post(
        "/api/dispositivos/dsp-2/bloqueio",
        headers={"Authorization": f"Bearer {admin}"},
    )
    assert bloqueio.status_code == 200
    assert client.get("/api/quadros", headers={"Authorization": f"Bearer {outro}"}).status_code == 401
    assert client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "confirmacao_leitura",
            "revisao_id": revisao,
            "ocorrido_em": iso(),
        },
    ).status_code == 201
    aceita = client.post(
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
    assert aceita.status_code == 200, aceita.text


def test_montagem_nao_altera_auditoria_nem_publicacao(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    revisao = preparar(client, engenharia, tipo="informativa", nome="PED-DEL-01890-2026-16-REV.A.pdf", conteudo=b"%PDF-1.4\na\n")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    publicacao = client.post(
        f"/api/quadros/{quadro_id}/publicacao",
        headers={"Authorization": f"Bearer {tablet}"},
        json={"revisao_id": revisao},
    )
    assert publicacao.status_code == 403
    assert publicacao.json()["erro"] == "perfil_insuficiente"
    auditoria = client.get("/api/auditoria", headers={"Authorization": f"Bearer {tablet}"})
    assert auditoria.status_code == 403
    cancelamento = client.post(
        f"/api/quadros/{quadro_id}/cancelamento",
        headers={"Authorization": f"Bearer {tablet}"},
        json={"motivo": "montagem não cancela"},
    )
    assert cancelamento.status_code == 403
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0]
        try:
            conn.execute("UPDATE evento_auditoria SET detalhe = 'alterado'")
            raise AssertionError("o gatilho deixou alterar a auditoria")
        except sqlite3.DatabaseError as falha:
            assert "auditoria imutavel" in str(falha)
        try:
            conn.execute("UPDATE publicacao SET motivo = 'alterado'")
            raise AssertionError("o gatilho deixou alterar a publicação")
        except sqlite3.DatabaseError as falha:
            assert "publicacao imutavel" in str(falha)
        assert conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0] == ponteiro
    assert _modo(pasta / "quadros.sqlite") == 0o600


def test_cancelar_devolve_a_anterior_e_leitura_impede(tmp_path):
    client, pasta = cliente(tmp_path)
    engenharia = token(client, "ana.ribeiro", "engenharia")
    tablet = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    primeira = preparar(client, engenharia, nome="PED-DEL-01890-2026-16-REV.A.pdf", tipo="informativa", conteudo=b"%PDF-1.4\na\n")
    quadro_id = publicar(client, engenharia, pasta, primeira)
    segunda = preparar(client, engenharia, nome="PED-DEL-01890-2026-16-REV.B.pdf", tipo="bloqueio", conteudo=b"%PDF-1.4\nb\n")
    publicar(client, engenharia, pasta, segunda)
    sem_motivo = client.post(
        f"/api/quadros/{quadro_id}/cancelamento",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={},
    )
    assert sem_motivo.status_code == 409
    assert sem_motivo.json()["erro"] == "motivo_obrigatorio"
    cancelada = client.post(
        f"/api/quadros/{quadro_id}/cancelamento",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"motivo": "Saiu sem leitura"},
    )
    assert cancelada.status_code == 200, cancelada.text
    assert cancelada.json()["revisao_vigente_id"] == primeira
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0]
        status_b = conn.execute("SELECT status, motivo FROM revisao WHERE id = ?", (segunda,)).fetchone()
        status_a = conn.execute("SELECT status FROM revisao WHERE id = ?", (primeira,)).fetchone()[0]
        linhas = conn.execute("SELECT COUNT(*) FROM publicacao").fetchone()[0]
        assert ponteiro == primeira
        assert status_b[0] == "cancelada"
        assert status_b[1] == "Saiu sem leitura"
        assert status_a == "publicada"
        assert linhas == 3
    historico = client.get(f"/api/quadros/{quadro_id}/revisoes", headers={"Authorization": f"Bearer {engenharia}"})
    assert any(item["id"] == segunda and item["status"] == "cancelada" for item in historico.json()["revisoes"])
    trilha = client.get(
        f"/api/auditoria?quadro_id={quadro_id}&acao=cancelamento",
        headers={"Authorization": f"Bearer {engenharia}"},
    )
    assert trilha.status_code == 200
    assert len(trilha.json()) == 1
    assert trilha.json()[0]["detalhe"] == "Saiu sem leitura"
    outras = client.get(
        f"/api/auditoria?quadro_id={quadro_id}&acao=publicacao",
        headers={"Authorization": f"Bearer {engenharia}"},
    )
    assert all(item["acao"] == "publicacao" for item in outras.json())
    confirmacao = client.post(
        "/api/sync/operacoes",
        headers={"Authorization": f"Bearer {tablet}"},
        json={
            "operacao_id": str(uuid.uuid4()),
            "tipo": "confirmacao_leitura",
            "revisao_id": primeira,
            "ocorrido_em": iso(),
        },
    )
    assert confirmacao.status_code == 201, confirmacao.text
    impedido = client.post(
        f"/api/quadros/{quadro_id}/cancelamento",
        headers={"Authorization": f"Bearer {engenharia}"},
        json={"motivo": "Tarde demais"},
    )
    assert impedido.status_code == 409
    assert impedido.json()["erro"] == "cancelamento_com_leitura"
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0] == primeira
        assert conn.execute("SELECT status FROM revisao WHERE id = ?", (primeira,)).fetchone()[0] == "publicada"


def test_backup_restaurado_abre_o_pdf_e_falta_de_arquivo_nao_apaga_ponteiro(tmp_path):
    client, pasta = cliente(tmp_path)
    admin = token(client, "rita.sol", "admin")
    engenharia = token(client, "ana.ribeiro", "engenharia")
    revisao = preparar(client, engenharia, tipo="informativa", nome="PED-DEL-01890-2026-16-REV.C.pdf", conteudo=b"%PDF-1.4\ncopia\n")
    quadro_id = publicar(client, engenharia, pasta, revisao)
    velho = client.app.state.backups / "20200101T000000Z"
    velho.mkdir(parents=True)
    (velho / "quadros.sqlite").write_bytes(b"velho")
    copia = client.post("/api/backup", headers={"Authorization": f"Bearer {admin}"})
    assert copia.status_code == 201, copia.text
    assert not velho.exists()
    montagem = client.post("/api/backup", headers={"Authorization": f"Bearer {token(client, 'carlos.mota', 'montagem', 'tablet-fase-1')}"})
    assert montagem.status_code == 403
    origem = client.app.state.backups / copia.json()["criado_em"]
    assert _modo(origem / "quadros.sqlite") == 0o600
    from app.main import create_app
    from fastapi.testclient import TestClient

    restaurado = TestClient(create_app(str(origem / "quadros.sqlite"), str(origem / "arquivos")))
    lista = restaurado.get("/api/quadros", headers={"Authorization": f"Bearer {engenharia}"})
    assert lista.status_code == 200, lista.text
    publicado = next(item for item in lista.json() if item["codigo"] == "PED-DEL-01890-2026-16")
    arquivo_id = publicado["arquivo_id"]
    ponteiro = publicado["revisao_vigente_id"]
    assert ponteiro == revisao
    baixado = restaurado.get(f"/api/arquivos/{arquivo_id}", headers={"Authorization": f"Bearer {engenharia}"})
    assert baixado.status_code == 200
    assert baixado.headers["etag"].strip('"') == __import__("hashlib").sha256(baixado.content).hexdigest()
    (origem / "arquivos" / "projetos" / arquivo_id).unlink()
    quadro = restaurado.get(f"/api/quadros/{quadro_id}", headers={"Authorization": f"Bearer {engenharia}"})
    assert quadro.json()["revisao_vigente_id"] == ponteiro
    assert quadro.json()["arquivo_disponivel"] is False
    ausente = restaurado.get(f"/api/arquivos/{arquivo_id}", headers={"Authorization": f"Bearer {engenharia}"})
    assert ausente.status_code == 404
    assert ausente.json()["erro"] == "nao_encontrado"
    with sqlite3.connect(origem / "quadros.sqlite") as conn:
        assert conn.execute("SELECT revisao_vigente_id FROM quadro WHERE id = ?", (quadro_id,)).fetchone()[0] == ponteiro

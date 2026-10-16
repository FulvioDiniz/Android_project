import sqlite3
import threading

from fastapi.testclient import TestClient

from app.main import create_app

PDF = b"%PDF-1.4\nfase3\n"


def cliente(tmp_path):
    app = create_app(str(tmp_path / "quadros.sqlite"), str(tmp_path / "arquivos"))
    return TestClient(app), tmp_path, app


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


def test_rejeitar_com_motivo_nao_muda_ponteiro(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    revisao = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.A.pdf").json()["revisao_id"]
    sem_motivo = client.post(
        f"/api/revisoes/{revisao}/rejeicao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={},
    )
    assert sem_motivo.status_code == 409
    assert sem_motivo.json()["erro"] == "motivo_obrigatorio"
    resposta = client.post(
        f"/api/revisoes/{revisao}/rejeicao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"motivo": "Folha trocada"},
    )
    assert resposta.status_code == 200
    assert resposta.json()["status"] == "rejeitada"
    assert resposta.json()["revisao_vigente_id"] is None
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        status, ponteiro = conn.execute(
            "SELECT r.status, q.revisao_vigente_id FROM revisao r JOIN quadro q ON q.id = r.quadro_id"
        ).fetchone()
        acao = conn.execute(
            "SELECT acao FROM evento_auditoria WHERE acao = 'rejeicao'"
        ).fetchone()[0]
    assert status == "rejeitada"
    assert ponteiro is None
    assert acao == "rejeicao"


def test_aprovar_nao_publica_e_publicar_e_atomico(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    primeira = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.B.pdf").json()["revisao_id"]
    client.post(
        f"/api/revisoes/{primeira}/aprovacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"tipo": "informativa"},
    )
    publicada = client.post(
        "/api/quadros/" + _quadro_id(pasta) + "/publicacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"revisao_id": primeira},
    )
    assert publicada.status_code == 201
    segunda = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf", b"%PDF-1.4\nnova\n").json()["revisao_id"]
    aprovada = client.post(
        f"/api/revisoes/{segunda}/aprovacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"tipo": "bloqueio"},
    )
    assert aprovada.status_code == 200
    assert aprovada.json()["status"] == "aprovada"
    assert aprovada.json()["revisao_vigente_id"] == primeira
    arquivo_antigo = _arquivo_da_revisao(pasta, primeira)
    publicacao = client.post(
        "/api/quadros/" + _quadro_id(pasta) + "/publicacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"revisao_id": segunda},
    )
    assert publicacao.status_code == 201
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        ponteiro, codigo = conn.execute(
            """SELECT q.revisao_vigente_id, r.codigo FROM quadro q
               JOIN revisao r ON r.id = q.revisao_vigente_id"""
        ).fetchone()
        anterior = conn.execute(
            "SELECT status FROM revisao WHERE id = ?",
            (primeira,),
        ).fetchone()[0]
        n_pub = conn.execute("SELECT COUNT(*) FROM publicacao").fetchone()[0]
        n_aud = conn.execute(
            "SELECT COUNT(*) FROM evento_auditoria WHERE acao = 'publicacao'"
        ).fetchone()[0]
        n_nota = conn.execute("SELECT COUNT(*) FROM notificacao").fetchone()[0]
    assert ponteiro == segunda
    assert codigo == "C"
    assert anterior == "substituida"
    assert n_pub == 2
    assert n_aud == 2
    assert n_nota == 2
    assert arquivo_antigo.exists()
    de_novo = client.post(
        "/api/quadros/" + _quadro_id(pasta) + "/publicacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"revisao_id": segunda},
    )
    assert de_novo.status_code == 200
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        assert conn.execute("SELECT COUNT(*) FROM publicacao").fetchone()[0] == 2
    lista = client.get("/api/quadros", headers={"Authorization": f"Bearer {acesso}"})
    publicado = next(item for item in lista.json() if item["codigo"] == "PED-DEL-01890-2026-16")
    assert publicado["revisao_vigente_codigo"] == "C"


def test_publicacao_concorrente_deixa_a_perdedora_aprovada(tmp_path):
    client, pasta, app = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    rev_c = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf").json()["revisao_id"]
    rev_d = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.D.pdf", b"%PDF-1.4\nd\n").json()["revisao_id"]
    for rev in (rev_c, rev_d):
        assert client.post(
            f"/api/revisoes/{rev}/aprovacao",
            headers={"Authorization": f"Bearer {acesso}"},
            json={"tipo": "informativa"},
        ).status_code == 200
    barreira = threading.Barrier(2)

    def esperar():
        barreira.wait(timeout=5)

    app.state.antes_da_trava = esperar
    quadro_id = _quadro_id(pasta)
    respostas: list[int] = []

    def publicar(rev: str) -> None:
        local = TestClient(app)
        resp = local.post(
            f"/api/quadros/{quadro_id}/publicacao",
            headers={"Authorization": f"Bearer {acesso}"},
            json={"revisao_id": rev},
        )
        respostas.append(resp.status_code)

    fios = [threading.Thread(target=publicar, args=(rev,)) for rev in (rev_c, rev_d)]
    for fio in fios:
        fio.start()
    for fio in fios:
        fio.join()
    assert sorted(respostas) == [201, 409]
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        vigentes = conn.execute(
            "SELECT COUNT(*) FROM revisao WHERE status = 'publicada'"
        ).fetchone()[0]
        aprovadas = conn.execute(
            "SELECT COUNT(*) FROM revisao WHERE status = 'aprovada'"
        ).fetchone()[0]
        ponteiros = conn.execute(
            "SELECT COUNT(*) FROM quadro WHERE revisao_vigente_id IS NOT NULL"
        ).fetchone()[0]
    assert vigentes == 1
    assert aprovadas == 1
    assert ponteiros == 1


def test_upload_depois_da_publicacao_continua_pendente(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    rev = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf").json()["revisao_id"]
    client.post(
        f"/api/revisoes/{rev}/aprovacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"tipo": "bloqueio"},
    )
    client.post(
        "/api/quadros/" + _quadro_id(pasta) + "/publicacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"revisao_id": rev},
    )
    novo = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.E.pdf", b"%PDF-1.4\ne\n")
    assert novo.status_code == 201
    assert novo.json()["status"] == "pendente"
    assert novo.json()["revisao_vigente_id"] == rev


def test_montagem_nao_publica(tmp_path):
    client, pasta, _ = cliente(tmp_path)
    acesso = token(client, "ana.ribeiro", "engenharia")
    quadro(client, acesso)
    rev = enviar(client, acesso, "PED-DEL-01890-2026-16-REV.C.pdf").json()["revisao_id"]
    client.post(
        f"/api/revisoes/{rev}/aprovacao",
        headers={"Authorization": f"Bearer {acesso}"},
        json={"tipo": "informativa"},
    )
    montagem = token(client, "carlos.mota", "montagem", "tablet-fase-1")
    resposta = client.post(
        "/api/quadros/" + _quadro_id(pasta) + "/publicacao",
        headers={"Authorization": f"Bearer {montagem}"},
        json={"revisao_id": rev},
    )
    assert resposta.status_code == 403
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        ponteiro = conn.execute("SELECT revisao_vigente_id FROM quadro WHERE codigo != '01855-2026-05 QT-1'").fetchone()[0]
        status = conn.execute("SELECT status FROM revisao").fetchone()[0]
    assert ponteiro is None
    assert status == "aprovada"


def _quadro_id(pasta) -> str:
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        return conn.execute(
            "SELECT id FROM quadro WHERE codigo = 'PED-DEL-01890-2026-16'"
        ).fetchone()[0]


def _arquivo_da_revisao(pasta, revisao_id: str):
    with sqlite3.connect(pasta / "quadros.sqlite") as conn:
        arquivo_id = conn.execute(
            "SELECT arquivo_id FROM revisao WHERE id = ?",
            (revisao_id,),
        ).fetchone()[0]
    return pasta / "arquivos" / "projetos" / arquivo_id

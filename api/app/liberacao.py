from datetime import datetime, timedelta, timezone


def _instante(valor: str) -> datetime:
    return datetime.fromisoformat(valor)


def salto_de_relogio(offset_ms: int | None, ocorrido_em: str) -> bool:
    if offset_ms is None:
        return False
    ocorrido = _instante(ocorrido_em)
    agora = datetime.now(timezone.utc)
    observado = int((ocorrido - agora).total_seconds() * 1000)
    return abs(observado - offset_ms) > 5 * 60 * 1000


def trabalho_anterior_a_publicacao(ultimo_sync: str | None, publicada_em: str, ocorrido_em: str, offset_ms: int | None) -> bool:
    if not ultimo_sync or ultimo_sync >= publicada_em:
        return False
    ocorrido = _instante(ocorrido_em)
    if offset_ms is not None:
        ocorrido = ocorrido - timedelta(milliseconds=offset_ms)
    publicada = _instante(publicada_em)
    if ocorrido >= publicada:
        return False
    return datetime.now(timezone.utc) - ocorrido <= timedelta(hours=72)


def motivo_recusa_etapa(conn, dispositivo_id: str, quadro_id: str, ocorrido_em: str) -> str | None:
    quadro = conn.execute("SELECT * FROM quadro WHERE id = ?", (quadro_id,)).fetchone()
    if quadro is None or not quadro["revisao_vigente_id"]:
        return None
    rev = conn.execute("SELECT * FROM revisao WHERE id = ?", (quadro["revisao_vigente_id"],)).fetchone()
    if rev is None or rev["status"] != "publicada" or rev["tipo"] in (None, "informativa"):
        return None
    disp = conn.execute("SELECT * FROM dispositivo WHERE id = ?", (dispositivo_id,)).fetchone()
    pub = conn.execute(
        "SELECT publicada_em FROM publicacao WHERE revisao_id = ? ORDER BY publicada_em DESC LIMIT 1",
        (rev["id"],),
    ).fetchone()
    publicada_em = pub["publicada_em"] if pub else rev["importada_em"]
    offset = disp["deslocamento_relogio_ms"] if disp else None
    if trabalho_anterior_a_publicacao(disp["ultimo_sync_em"] if disp else None, publicada_em, ocorrido_em, offset):
        return None

    def confirmou(dispositivo: str) -> bool:
        return conn.execute(
            "SELECT 1 FROM confirmacao_leitura WHERE revisao_id = ? AND dispositivo_id = ?",
            (rev["id"], dispositivo),
        ).fetchone() is not None

    if rev["tipo"] == "reavaliacao":
        return None if confirmou(dispositivo_id) else "bloqueio_vigencia"
    if rev["tipo"] != "bloqueio":
        return None
    if not quadro["bancada_id"]:
        return "bancada_sem_tablet"
    limite = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat()
    ativos = conn.execute(
        """SELECT id FROM dispositivo
           WHERE status = 'ativo' AND bancada_id = ? AND ultimo_visto_em >= ?""",
        (quadro["bancada_id"], limite),
    ).fetchall()
    if not ativos:
        return "bancada_sem_tablet"
    if all(confirmou(item["id"]) for item in ativos):
        return None
    return "bloqueio_vigencia"

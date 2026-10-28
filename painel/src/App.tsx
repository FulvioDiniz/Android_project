import { FormEvent, useEffect, useRef, useState } from "react";
import { VisorPdf, lerMarca, type MarcaPdf } from "./VisorPdf";

type Tela = "quadros" | "quadro" | "revisoes" | "duvidas" | "bancadas" | "auditoria" | "pessoas";

type Quadro = {
  id: string;
  codigo: string;
  titulo: string;
  status: string;
  ordem_numero: string;
  cliente_nome: string;
  responsavel_nome: string | null;
  revisao_vigente_id: string | null;
  revisao_vigente_codigo: string | null;
};

type Sessao = {
  token: string;
  perfil: string;
  usuario: { nome: string };
};

type Revisao = {
  id: string;
  codigo: string;
  status: string;
  tipo: string | null;
  quadro_id: string;
  quadro_codigo: string;
  origem?: string;
};

type ComparacaoRede = {
  codigo_quadro: string;
  grupo?: string;
  pasta: string;
  arquivo: string;
  alterado_em: string;
  resultado: string;
};

type PastaRede = {
  acessivel: boolean;
  mensagem: string;
  caminho: string;
  comparacoes?: ComparacaoRede[];
};

type Duvida = {
  id: string;
  quadro_id: string;
  quadro_codigo: string;
  texto: string;
  resposta: string | null;
  usuario: string;
  ocorrido_em?: string;
  fotos: { id: string; hash_sha256: string; legenda: string }[];
};

type PendenciaPainel = {
  id: string;
  quadro_id: string;
  quadro_codigo: string;
  descricao: string;
  status: string;
  bloqueia_etapa: number;
  usuario: string;
  ocorrido_em?: string;
};

type BancadaAndamento = {
  id: string;
  nome: string;
  quadros: {
    id: string;
    codigo: string;
    etapas: { codigo: string; nome: string; status: string }[];
    pendencias: { id: string; descricao: string; bloqueia_etapa: boolean }[];
  }[];
};

type Evento = {
  id: number;
  acao: string;
  detalhe: string;
  quadro_id: string | null;
  quadro_codigo: string | null;
  ocorrido_em: string;
  ator_nome: string | null;
};

type Reconhecimento = {
  confirmados: { usuario: string; dispositivo: string; ocorrido_em: string }[];
  faltam: { dispositivo: string }[];
};

const ACOES: Record<string, string> = {
  publicacao: "Publicou a revisão",
  cancelamento: "Cancelou a publicação",
  aprovacao: "Aprovou a revisão",
  rejeicao: "Rejeitou a revisão",
  confirmacao_leitura: "Confirmou a leitura",
  bloqueio: "A API recusou a etapa",
  observacao: "Registrou uma observação",
  duvida: "Registrou uma dúvida",
  foto: "Enviou uma foto",
  pendencia: "Abriu uma pendência",
  mudanca_etapa: "Mudou a etapa",
  mudanca_status: "Mudou o status",
  usuario: "Alterou um usuário",
  dispositivo: "Alterou um tablet",
  sessao: "Entrou no sistema",
  importacao: "Recebeu o PDF",
  erro_importacao: "Mandou o nome para validação",
  desbloqueio: "Tirou a trava",
};

const FILTROS = [
  ["", "Todas as ações"],
  ["publicacao", "Publicação"],
  ["cancelamento", "Cancelamento"],
  ["aprovacao", "Aprovação"],
  ["rejeicao", "Rejeição"],
  ["confirmacao_leitura", "Confirmação de leitura"],
  ["bloqueio", "Bloqueio"],
  ["duvida", "Dúvida"],
  ["importacao", "Recebimento do PDF"],
] as const;

function horaBrasil(iso: string): string {
  const data = new Date(iso);
  if (Number.isNaN(data.getTime())) return "";
  return new Intl.DateTimeFormat("pt-BR", {
    timeZone: "America/Sao_Paulo",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(data);
}

function complemento(detalhe: string): string {
  const mapa: Record<string, string> = {
    informativa: "Tipo informativa",
    reavaliacao: "Tipo reavaliação",
    bloqueio: "Tipo bloqueio",
    bloqueio_vigencia: "Faltou confirmação da vigência",
    bloqueio_pendencia: "Pendência aberta",
    abertura: "",
    resposta: "Respondeu a dúvida",
    resolvida: "Pendência resolvida",
    ativacao: "Tablet ativado",
    concluida: "Etapa concluída",
    em_andamento: "Etapa em andamento",
  };
  if (!detalhe || mapa[detalhe] === "") return mapa[detalhe] ?? "";
  if (mapa[detalhe]) return mapa[detalhe];
  if (/^\d{4}-\d{2}-\d{2}T/.test(detalhe)) return "";
  if (/^[0-9a-f-]{16,}$/i.test(detalhe) || /^[0-9a-f]{32,}$/i.test(detalhe)) return "";
  if (detalhe.length > 90) return "";
  return detalhe;
}

function statusEtapa(status: string): string {
  if (status === "em_andamento") return "em andamento";
  if (status === "concluida") return "concluída";
  if (status === "nao_iniciada") return "não iniciada";
  return status;
}

function statusRevisao(status: string): string {
  const mapa: Record<string, string> = {
    pendente: "pendente",
    aprovada: "aprovada",
    publicada: "publicada",
    rejeitada: "rejeitada",
    substituida: "substituída",
    cancelada: "cancelada",
  };
  return mapa[status] ?? status;
}

function tipoRevisao(tipo: string | null): string {
  if (tipo === "reavaliacao") return "reavaliação";
  if (tipo === "informativa") return "informativa";
  if (tipo === "bloqueio") return "bloqueio";
  return "tipo ainda não escolhido";
}

async function api<T>(caminho: string, token: string, opcoes: RequestInit = {}): Promise<T> {
  const resposta = await fetch(caminho, {
    ...opcoes,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(opcoes.headers ?? {}),
    },
  });
  const corpo = await resposta.json();
  if (!resposta.ok) {
    throw new Error(corpo.mensagem ?? "Falha na API");
  }
  return corpo as T;
}

export function App() {
  const [sessao, setSessao] = useState<Sessao | null>(null);
  const [tela, setTela] = useState<Tela>("quadros");
  const [quadroId, setQuadroId] = useState<string | null>(null);
  const [login, setLogin] = useState("ana.ribeiro");
  const [senha, setSenha] = useState("");
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [quadros, setQuadros] = useState<Quadro[]>([]);
  const [revisoes, setRevisoes] = useState<Revisao[]>([]);
  const [pastaRede, setPastaRede] = useState<PastaRede | null>(null);
  const [nomeProjeto, setNomeProjeto] = useState("");
  const [caminhoNovo, setCaminhoNovo] = useState("");
  const [buscaQuadros, setBuscaQuadros] = useState("");
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const [arquivoPdfId, setArquivoPdfId] = useState<string | null>(null);
  const [caminhoProjeto, setCaminhoProjeto] = useState("");
  const marcaPdf = useRef("");
  const [pendentes, setPendentes] = useState<{ id: string; codigo: string; quadro_codigo: string }[]>([]);
  const [aprovadas, setAprovadas] = useState<{ id: string; codigo: string; quadro_codigo: string }[]>([]);
  const [tipos, setTipos] = useState<Record<string, string>>({});
  const [motivosRejeicao, setMotivosRejeicao] = useState<Record<string, string>>({});
  const [motivoCancelamento, setMotivoCancelamento] = useState("");
  const [erros, setErros] = useState<{ id: string; nome_original: string; motivo: string }[]>([]);
  const [reconhecimento, setReconhecimento] = useState<Reconhecimento | null>(null);
  const [duvidas, setDuvidas] = useState<Duvida[]>([]);
  const [pendenciasPainel, setPendenciasPainel] = useState<PendenciaPainel[]>([]);
  const [respostas, setRespostas] = useState<Record<string, string>>({});
  const [bancadas, setBancadas] = useState<BancadaAndamento[]>([]);
  const [auditoria, setAuditoria] = useState<Evento[]>([]);
  const [filtroQuadro, setFiltroQuadro] = useState("");
  const [filtroAcao, setFiltroAcao] = useState("");
  const [usuarios, setUsuarios] = useState<{ id: string; nome: string; login: string; perfil: string; ativo: number }[]>([]);
  const [dispositivos, setDispositivos] = useState<{ id: string; identificador_instalacao: string; apelido: string; bancada_id: string | null; status: string }[]>([]);
  const [novoNome, setNovoNome] = useState("");
  const [novoLogin, setNovoLogin] = useState("");
  const [novaSenha, setNovaSenha] = useState("");
  const [novoPerfil, setNovoPerfil] = useState("montagem");
  const [bancadaVinculo, setBancadaVinculo] = useState<Record<string, string>>({});

  async function carregar(token: string, quadroFiltro = filtroQuadro, acaoFiltro = filtroAcao) {
    const lista = await api<Quadro[]>("/api/quadros", token);
    setQuadros(lista);
    const historicos = await Promise.all(
      lista.map(async (quadro) => {
        const historia = await api<{ revisoes: Omit<Revisao, "quadro_id" | "quadro_codigo">[] }>(
          `/api/quadros/${quadro.id}/revisoes`,
          token,
        );
        return historia.revisoes.map((item) => ({
          ...item,
          quadro_id: quadro.id,
          quadro_codigo: quadro.codigo,
        }));
      }),
    );
    setRevisoes(historicos.flat());
    try {
      setPastaRede(await api<PastaRede>("/api/pasta-rede", token));
    } catch {
      setPastaRede({ acessivel: false, mensagem: "Pasta de rede indisponível.", caminho: "" });
    }
    try {
      setDuvidas(await api<Duvida[]>("/api/observacoes?tipo=duvida", token));
    } catch {
      /* mantém a lista anterior se a rede falhar no meio */
    }
    try {
      setPendenciasPainel(await api<PendenciaPainel[]>("/api/pendencias?status=aberta", token));
    } catch {
      setPendenciasPainel([]);
    }
    try {
      setPendentes(await api("/api/revisoes?status=pendente", token));
      setAprovadas(await api("/api/revisoes?status=aprovada", token));
      setErros(await api("/api/erros-importacao?status=aberto", token));
      setBancadas(await api<BancadaAndamento[]>("/api/bancadas/andamento", token));
    } catch {
      setPendentes([]);
      setAprovadas([]);
      setErros([]);
    }
    try {
      setAuditoria(
        await api(
          `/api/auditoria?quadro_id=${encodeURIComponent(quadroFiltro)}&acao=${encodeURIComponent(acaoFiltro)}`,
          token,
        ),
      );
    } catch {
      setAuditoria([]);
    }
    if (sessao?.perfil === "admin") {
      try {
        setUsuarios(await api("/api/usuarios", token));
        setDispositivos(await api("/api/dispositivos", token));
      } catch {
        setUsuarios([]);
        setDispositivos([]);
      }
    }
  }

  useEffect(() => {
    if (sessao) {
      carregar(sessao.token).catch((falha: Error) => setErro(falha.message));
    }
  }, [sessao]);

  useEffect(() => {
    if (!sessao) return;
    const id = window.setInterval(() => {
      api<Duvida[]>("/api/observacoes?tipo=duvida", sessao.token).then(setDuvidas).catch(() => undefined);
      api<PendenciaPainel[]>("/api/pendencias?status=aberta", sessao.token)
        .then(setPendenciasPainel)
        .catch(() => undefined);
    }, 10000);
    return () => window.clearInterval(id);
  }, [sessao]);

  useEffect(() => {
    if (!sessao || !quadroId) return;
    api<Reconhecimento>(`/api/quadros/${quadroId}/confirmacoes`, sessao.token)
      .then(setReconhecimento)
      .catch((falha: Error) => setErro(falha.message));
  }, [sessao, quadroId, quadros]);

  useEffect(() => {
    if (!sessao || !quadroId || tela !== "quadro") return;
    let ativo = true;
    async function sincronizarPdf() {
      if (!sessao || !quadroId) return;
      try {
        const historia = await api<{
          caminho_projeto?: string;
          revisoes: { id: string; arquivo_id: string | null; importada_em: string }[];
        }>(`/api/quadros/${quadroId}/revisoes`, sessao.token);
        if (!ativo) return;
        setCaminhoProjeto(historia.caminho_projeto || "");
        const ultima = [...historia.revisoes].reverse().find((item) => item.arquivo_id);
        const arquivoId = ultima?.arquivo_id || null;
        const marca = ultima ? `${ultima.arquivo_id}:${ultima.importada_em}` : "";
        setArquivoPdfId(arquivoId);
        if (!arquivoId) {
          marcaPdf.current = "";
          setPdfUrl((anterior) => {
            if (anterior) URL.revokeObjectURL(anterior);
            return null;
          });
          return;
        }
        if (marca === marcaPdf.current) return;
        marcaPdf.current = marca;
        const resposta = await fetch(`/api/arquivos/${arquivoId}`, {
          headers: { Authorization: `Bearer ${sessao.token}` },
        });
        if (!ativo || !resposta.ok) return;
        const bruto = await resposta.blob();
        const pdf = bruto.type === "application/pdf" ? bruto : new Blob([bruto], { type: "application/pdf" });
        const url = URL.createObjectURL(pdf);
        setPdfUrl((anterior) => {
          if (anterior) URL.revokeObjectURL(anterior);
          return url;
        });
      } catch {
        /* mantém o PDF já aberto */
      }
    }
    sincronizarPdf();
    const timer = window.setInterval(sincronizarPdf, 5000);
    return () => {
      ativo = false;
      window.clearInterval(timer);
    };
  }, [sessao, quadroId, tela]);

  async function entrar(evento: FormEvent) {
    evento.preventDefault();
    setErro("");
    const resposta = await fetch("/api/sessao", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ login, senha }),
    });
    const corpo = await resposta.json();
    if (!resposta.ok) {
      setErro(corpo.mensagem ?? "Não entrou");
      return;
    }
    setSessao(corpo);
    setTela("quadros");
  }

  async function sair() {
    if (!sessao) return;
    await api("/api/sessao", sessao.token, { method: "DELETE" });
    setSessao(null);
    setQuadroId(null);
    setQuadros([]);
  }

  async function enviarPdf(evento: FormEvent) {
    evento.preventDefault();
    if (!sessao || !arquivo) return;
    setAviso("");
    setErro("");
    const dados = new FormData();
    dados.append("arquivo", arquivo);
    const resposta = await fetch("/api/projetos", {
      method: "POST",
      headers: { Authorization: `Bearer ${sessao.token}` },
      body: dados,
    });
    const corpo = await resposta.json();
    if (!resposta.ok && resposta.status !== 202) {
      setErro(corpo.mensagem ?? "O PDF não entrou.");
      return;
    }
    setAviso(
      corpo.status === "pendente"
        ? `Rev ${corpo.codigo ?? ""} ficou pendente. O envio não publicou.`
        : "O nome foi para validação manual. Não nasceu revisão.",
    );
    setArquivo(null);
    await carregar(sessao.token);
  }

  async function cadastrar(evento: FormEvent) {
    evento.preventDefault();
    if (!sessao) return;
    const nome = nomeProjeto.trim();
    const caminho = caminhoNovo.trim();
    if (!nome || !caminho) {
      setErro("Informe o nome e o caminho da pasta.");
      return;
    }
    setAviso("");
    setErro("");
    try {
      const ordem = await api<{ id: string }>("/api/ordens-producao", sessao.token, {
        method: "POST",
        body: JSON.stringify({ numero: nome, cliente_nome: "Projetos" }),
      });
      const quadroNovo = await api<{ id: string; codigo: string }>("/api/quadros", sessao.token, {
        method: "POST",
        body: JSON.stringify({ codigo: nome, titulo: nome, ordem_producao_id: ordem.id }),
      });
      await api(`/api/quadros/${quadroNovo.id}/caminho`, sessao.token, {
        method: "POST",
        body: JSON.stringify({ caminho }),
      });
      setNomeProjeto("");
      setCaminhoNovo("");
      setAviso(`Projeto ${nome} criado. O PDF da pasta entra sozinho, sem publicar.`);
      await carregar(sessao.token);
      setQuadroId(quadroNovo.id);
      setTela("quadro");
    } catch (falha) {
      setErro(falha instanceof Error ? falha.message : "Falha ao cadastrar");
    }
  }

  async function salvarCaminho(evento: FormEvent) {
    evento.preventDefault();
    if (!sessao || !quadroId) return;
    setAviso("");
    setErro("");
    try {
      await api(`/api/quadros/${quadroId}/caminho`, sessao.token, {
        method: "POST",
        body: JSON.stringify({ caminho: caminhoProjeto.trim() }),
      });
      setAviso("Caminho guardado. Um PDF novo na pasta substitui o da tela.");
    } catch (falha) {
      setErro(falha instanceof Error ? falha.message : "Não guardou o caminho");
    }
  }

  if (!sessao) {
    return (
      <main className="login-tela">
        <h1>Quadros</h1>
        <p className="intro">Painel da engenharia. Montagem entra no tablet.</p>
        <form onSubmit={entrar}>
          <label>
            Login
            <input value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" />
          </label>
          <label>
            Senha
            <input
              type="password"
              value={senha}
              onChange={(e) => setSenha(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          {erro ? <p className="aviso">{erro}</p> : null}
          <button type="submit">Entrar</button>
        </form>
      </main>
    );
  }

  const semResposta = duvidas.filter((item) => !item.resposta).length;
  const engenharia = sessao.perfil === "engenharia";
  const podeResolver = engenharia || sessao.perfil === "supervisao";
  const quadro = quadros.find((item) => item.id === quadroId) ?? null;
  const termoBusca = buscaQuadros.trim().toLowerCase();
  const quadrosVisiveis = termoBusca
    ? quadros.filter(
        (item) =>
          item.codigo.toLowerCase().includes(termoBusca) ||
          item.cliente_nome.toLowerCase().includes(termoBusca) ||
          item.ordem_numero.toLowerCase().includes(termoBusca) ||
          (item.titulo || "").toLowerCase().includes(termoBusca),
      )
    : quadros;
  const pendentesDoQuadro = pendentes.filter((item) => item.quadro_codigo === quadro?.codigo);
  const aprovadasDoQuadro = aprovadas.filter((item) => item.quadro_codigo === quadro?.codigo);
  const menus: { id: Tela; rotulo: string }[] = [
    { id: "quadros", rotulo: "Projetos" },
    { id: "revisoes", rotulo: "Revisões" },
    { id: "duvidas", rotulo: "Dúvidas" },
    { id: "bancadas", rotulo: "Bancadas" },
    { id: "auditoria", rotulo: "Auditoria" },
  ];
  if (sessao.perfil === "admin") menus.push({ id: "pessoas", rotulo: "Pessoas" });

  function ir(destino: Tela) {
    setErro("");
    setTela(destino);
    if (destino !== "quadro") setQuadroId(null);
  }

  return (
    <>
      <header className="topo">
        <strong>Quadros</strong>
        <nav>
          {menus.map((item) => (
            <button
              key={item.id}
              type="button"
              className={tela === item.id || (tela === "quadro" && item.id === "quadros") ? "ativo" : "secundario"}
              onClick={() => ir(item.id)}
            >
              {item.rotulo}
              {item.id === "duvidas" && semResposta > 0 ? ` (${semResposta})` : ""}
            </button>
          ))}
          <button type="button" className="secundario" onClick={sair}>
            Sair
          </button>
        </nav>
        <p className="quem">
          {sessao.usuario.nome} · {sessao.perfil}
        </p>
      </header>
      <main>
        {erro ? <p className="aviso">{erro}</p> : null}
        {aviso ? <p className="ok">{aviso}</p> : null}
        {semResposta > 0 && tela !== "duvidas" ? (
          <button type="button" className="inbox" onClick={() => ir("duvidas")}>
            {semResposta} apontamento{semResposta === 1 ? "" : "s"} do tablet sem resposta
            {pendenciasPainel.length > 0 ? ` · ${pendenciasPainel.length} pendência` : ""}
          </button>
        ) : null}

        {tela === "quadros" ? (
          <>
            <header className="page-head">
              <div>
                <h1>Projetos</h1>
                <p className="intro">Nome + caminho. O PDF salvo na pasta entra sozinho, sem publicar.</p>
              </div>
              <span className="contador">{quadros.length} na lista</span>
            </header>

            {engenharia ? (
              <div className="bloco">
                <h2>PCP / execução</h2>
                <p className="miudo">
                  Lê o P&amp;D (só PCP e execução, sem finalizados) e amarra o PDF mais recente da pasta de rede.
                </p>
                <button
                  type="button"
                  className="secundario"
                  onClick={async () => {
                    if (!sessao) return;
                    setErro("");
                    setAviso("Sincronizando PCP/execução…");
                    try {
                      const r = await api<{
                        ok: boolean;
                        mensagem: string;
                        criados: number;
                        total_rede: number;
                      }>("/api/projetos/sincronizar-rede", sessao.token, { method: "POST", body: "{}" });
                      setAviso(r.mensagem);
                      await carregar(sessao.token);
                    } catch (falha) {
                      setErro(falha instanceof Error ? falha.message : "Falha ao sincronizar");
                      setAviso("");
                    }
                  }}
                >
                  Sincronizar PCP / execução
                </button>
              </div>
            ) : null}

            {engenharia ? (
              <form className="bloco destaque-criar" onSubmit={cadastrar}>
                <h2>Novo projeto</h2>
                <p className="miudo">Use o caminho que o supervisor indicar. Não precisa cadastrar OP à mão.</p>
                <div className="cadastro-linha">
                  <label>
                    Nome
                    <input
                      value={nomeProjeto}
                      onChange={(e) => setNomeProjeto(e.target.value)}
                      placeholder="Ex.: QD-01 Oficina"
                      required
                    />
                  </label>
                  <label>
                    Caminho da pasta
                    <input
                      value={caminhoNovo}
                      onChange={(e) => setCaminhoNovo(e.target.value)}
                      placeholder="\\Srv3302\projetos elétricos\...\PDF"
                      required
                    />
                  </label>
                  <button type="submit">Criar projeto</button>
                </div>
              </form>
            ) : null}

            {quadros.length === 0 ? (
              <div className="vazio-estado">
                <h2>Nenhum projeto ainda</h2>
                <p>Crie o primeiro com o nome e o caminho da pasta do desenho.</p>
              </div>
            ) : (
              <>
                <label className="busca">
                  Buscar
                  <input
                    value={buscaQuadros}
                    onChange={(e) => setBuscaQuadros(e.target.value)}
                    placeholder="Código, cliente ou OP"
                  />
                </label>
                <div className="lista-quadros">
                  {quadrosVisiveis.length === 0 ? (
                    <p className="vazio">Nada encontrado para “{buscaQuadros}”.</p>
                  ) : null}
                  {quadrosVisiveis.map((item) => {
                    const temPendente = revisoes.some(
                      (rev) => rev.quadro_id === item.id && rev.status === "pendente",
                    );
                    const seloTexto = item.revisao_vigente_codigo
                      ? `Rev ${item.revisao_vigente_codigo}`
                      : temPendente
                        ? "Pendente"
                        : "Sem PDF";
                    return (
                      <article key={item.id} className="linha-quadro">
                        <div>
                          <span className="codigo">{item.codigo}</span>
                          <span className="linha-sec">
                            {item.cliente_nome} · OP {item.ordem_numero}
                          </span>
                        </div>
                        <span className={seloTexto === "Sem PDF" ? "selo selo-vazio" : "selo"}>
                          {seloTexto}
                        </span>
                        <button
                          type="button"
                          className="secundario"
                          onClick={() => {
                            setQuadroId(item.id);
                            setMotivoCancelamento("");
                            setTela("quadro");
                          }}
                        >
                          Abrir
                        </button>
                      </article>
                    );
                  })}
                </div>
              </>
            )}
          </>
        ) : null}

        {tela === "quadro" && quadro ? (
          <>
            <button type="button" className="voltar" onClick={() => ir("quadros")}>
              ← Projetos
            </button>
            <header className="page-head">
              <div>
                <p className="codigo">{quadro.codigo}</p>
                <p className="miudo">
                  {quadro.titulo && quadro.titulo !== quadro.codigo ? `${quadro.titulo} · ` : ""}
                  {quadro.cliente_nome} · OP {quadro.ordem_numero}
                </p>
              </div>
              <span className={quadro.revisao_vigente_codigo ? "selo" : "selo selo-vazio"}>
                {quadro.revisao_vigente_codigo ? `Rev ${quadro.revisao_vigente_codigo}` : "Sem vigente"}
              </span>
            </header>

            <section className="bloco">
              <h2>Do tablet</h2>
              <p className="miudo">Erro, mudança e pendência que o montador enviou neste projeto.</p>
              {duvidas.filter((item) => item.quadro_id === quadro.id).length === 0 &&
              pendenciasPainel.filter((item) => item.quadro_id === quadro.id).length === 0 ? (
                <p>Nada chegou deste projeto ainda. A lista atualiza sozinha.</p>
              ) : null}
              {duvidas
                .filter((item) => item.quadro_id === quadro.id)
                .map((item) => (
                  <article key={item.id} className="apontamento">
                    <p className="codigo-menor">{item.usuario}</p>
                    <p className="texto-apontamento">{lerMarca(item.texto).visivel}</p>
                    {item.resposta ? <p className="ok">Resposta: {item.resposta}</p> : <p className="aviso">Sem resposta</p>}
                  </article>
                ))}
              {pendenciasPainel
                .filter((item) => item.quadro_id === quadro.id)
                .map((item) => (
                  <article key={item.id} className="apontamento">
                    <p className="codigo-menor">Pendência · {item.usuario}</p>
                    <p>{item.descricao}</p>
                    {podeResolver ? (
                      <button
                        type="button"
                        onClick={() =>
                          api(`/api/pendencias/${item.id}/resolucao`, sessao.token, { method: "POST", body: "{}" })
                            .then(() => carregar(sessao.token))
                            .catch((falha: Error) => setErro(falha.message))
                        }
                      >
                        Marcar resolvida
                      </button>
                    ) : null}
                  </article>
                ))}
            </section>

            <section className="bloco">
              <h2>Caminho do projeto</h2>
              <p className="miudo">O supervisor indica a pasta. Um PDF novo lá substitui o da tela, sem publicar.</p>
              {engenharia ? (
                <form onSubmit={salvarCaminho}>
                  <label>
                    Pasta
                    <input
                      value={caminhoProjeto}
                      onChange={(e) => setCaminhoProjeto(e.target.value)}
                      placeholder="\\Srv3302\projetos elétricos\...\PDF"
                    />
                  </label>
                  <button type="submit">Guardar caminho</button>
                </form>
              ) : (
                <p>{caminhoProjeto || "Sem caminho"}</p>
              )}
            </section>

            {pdfUrl ? (
              <VisorPdf
                url={pdfUrl}
                marcas={duvidas
                  .filter((item) => item.quadro_id === quadro.id)
                  .map((item) => lerMarca(item.texto).marca)
                  .filter((item): item is MarcaPdf => item !== null)}
              />
            ) : (
              <div className="vazio-estado">
                <h2>Sem desenho ainda</h2>
                <p>Guarde o caminho e salve um PDF na pasta, ou envie pelo painel.</p>
              </div>
            )}

            {engenharia ? (
              <form className="zona-envio" onSubmit={enviarPdf}>
                <h2>Envio manual</h2>
                <p>
                  Só se precisar furar a pasta. O nome segue <strong>CODIGO-REV.X.pdf</strong>. Grava como
                  pendente — não publica.
                </p>
                <label className="zona-arquivo">
                  <span className="zona-rotulo">PDF do projeto</span>
                  <span className="zona-nome">{arquivo ? arquivo.name : "Nenhum PDF escolhido"}</span>
                  <span className="botao-arquivo">Escolher PDF</span>
                  <input
                    className="arquivo-escondido"
                    type="file"
                    accept="application/pdf,.pdf"
                    onChange={(e) => setArquivo(e.target.files?.[0] ?? null)}
                  />
                </label>
                <button type="submit">Enviar para pendente</button>
                {erros
                  .filter((item) => item.nome_original.toLowerCase().includes(quadro.codigo.toLowerCase()))
                  .map((item) => (
                    <p key={item.id} className="aviso">
                      {item.nome_original} foi para validação manual: {item.motivo}
                    </p>
                  ))}
              </form>
            ) : null}

            <section className="bloco vigente-bloco">
              <h2>Vigente</h2>
              <p>O envio e a pasta não trocam a vigente. Só o botão Publicar faz isso.</p>
            </section>

            <section className="bloco">
              <h2>Pendentes</h2>
              {pendentesDoQuadro.length === 0 ? <p>Nenhuma revisão pendente neste quadro.</p> : null}
              {pendentesDoQuadro.map((item) => (
                <article key={item.id} className="item">
                  <p className="codigo-menor">Rev {item.codigo}</p>
                  <p>Escolha o tipo e aprove, ou rejeite com motivo. Aprovar não publica.</p>
                  {engenharia ? (
                    <>
                      <label>
                        Tipo
                        <select
                          value={tipos[item.id] ?? "bloqueio"}
                          onChange={(e) => setTipos({ ...tipos, [item.id]: e.target.value })}
                        >
                          <option value="informativa">Informativa</option>
                          <option value="reavaliacao">Reavaliação</option>
                          <option value="bloqueio">Bloqueio</option>
                        </select>
                      </label>
                      <button
                        type="button"
                        onClick={() =>
                          api(`/api/revisoes/${item.id}/aprovacao`, sessao.token, {
                            method: "POST",
                            body: JSON.stringify({ tipo: tipos[item.id] ?? "bloqueio" }),
                          })
                            .then(() => {
                              setAviso(`Rev ${item.codigo} aprovada. A vigente não mudou.`);
                              return carregar(sessao.token);
                            })
                            .catch((falha: Error) => setErro(falha.message))
                        }
                      >
                        Aprovar
                      </button>
                    </>
                  ) : null}
                </article>
              ))}
            </section>

            {engenharia
              ? pendentesDoQuadro.map((item) => (
                  <section key={`rejeitar-${item.id}`} className="bloco">
                    <h2>Rejeitar Rev {item.codigo}</h2>
                    <label>
                      Motivo
                      <input
                        value={motivosRejeicao[item.id] ?? ""}
                        onChange={(e) => setMotivosRejeicao({ ...motivosRejeicao, [item.id]: e.target.value })}
                        placeholder="Por que esta revisão não segue"
                      />
                    </label>
                    <button
                      type="button"
                      className="secundario"
                      onClick={() =>
                        api(`/api/revisoes/${item.id}/rejeicao`, sessao.token, {
                          method: "POST",
                          body: JSON.stringify({ motivo: motivosRejeicao[item.id] ?? "" }),
                        })
                          .then(() => carregar(sessao.token))
                          .catch((falha: Error) => setErro(falha.message))
                      }
                    >
                      Rejeitar
                    </button>
                  </section>
                ))
              : null}

            <section className="bloco">
              <h2>Publicar</h2>
              <p>Comando separado do envio. Só uma revisão aprovada deste quadro vira a vigente.</p>
              {aprovadasDoQuadro.length === 0 ? <p>Nenhuma revisão aprovada esperando publicação.</p> : null}
              {engenharia
                ? aprovadasDoQuadro.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() =>
                        api(`/api/quadros/${quadro.id}/publicacao`, sessao.token, {
                          method: "POST",
                          body: JSON.stringify({ revisao_id: item.id }),
                        })
                          .then(() => {
                            setAviso(`Vigente passou a ser Rev ${item.codigo}.`);
                            return carregar(sessao.token);
                          })
                          .catch((falha: Error) => setErro(falha.message))
                      }
                    >
                      Publicar Rev {item.codigo}
                    </button>
                  ))
                : null}
            </section>

            <section className="bloco">
              <h2>Quem reconheceu</h2>
              {reconhecimento ? (
                <>
                  <ul className="lista">
                    {reconhecimento.confirmados.length === 0 ? <li>Ninguém confirmou a leitura.</li> : null}
                    {reconhecimento.confirmados.map((item) => (
                      <li key={`${item.usuario}-${item.dispositivo}`}>
                        {item.usuario} · {item.dispositivo} · {horaBrasil(item.ocorrido_em)}
                      </li>
                    ))}
                  </ul>
                  <p>
                    Faltam: {reconhecimento.faltam.map((item) => item.dispositivo).join(", ") || "ninguém"}
                  </p>
                </>
              ) : (
                <p>Carregando o reconhecimento.</p>
              )}
            </section>

            {engenharia && quadro.revisao_vigente_id ? (
              <section className="bloco">
                <h2>Cancelar vigente</h2>
                <p>Só vale se ninguém confirmou a leitura. A revisão cancelada continua no histórico.</p>
                <label>
                  Motivo
                  <input
                    value={motivoCancelamento}
                    onChange={(e) => setMotivoCancelamento(e.target.value)}
                    placeholder="Por que esta vigente sai"
                  />
                </label>
                <button
                  type="button"
                  className="secundario"
                  onClick={() =>
                    api(`/api/quadros/${quadro.id}/cancelamento`, sessao.token, {
                      method: "POST",
                      body: JSON.stringify({ motivo: motivoCancelamento }),
                    })
                      .then(() => {
                        setAviso("Publicação cancelada. O ponteiro voltou.");
                        setMotivoCancelamento("");
                        return carregar(sessao.token);
                      })
                      .catch((falha: Error) => setErro(falha.message))
                  }
                >
                  Cancelar vigente
                </button>
              </section>
            ) : null}
          </>
        ) : null}

        {tela === "revisoes" ? (
          <>
            <header className="page-head">
              <div>
                <h1>Revisões</h1>
                <p className="intro">Fila inteira. Pasta e envio criam pendente. Nenhum dos dois publica.</p>
              </div>
              <span className="contador">{revisoes.length} na lista</span>
            </header>
            {pastaRede && !pastaRede.acessivel ? <p className="aviso">Pasta de rede indisponível.</p> : null}
            {pastaRede?.acessivel ? <p className="ok">A pasta de rede está acessível.</p> : null}
            {(pastaRede?.comparacoes ?? []).length > 0 ? (
              <>
                <h2>Comparação com a pasta</h2>
                <div className="lista-cartoes">
                  {(pastaRede?.comparacoes ?? []).map((item) => (
                    <article key={`${item.pasta}/${item.arquivo}`} className="bloco">
                      <p className="codigo-menor">{item.codigo_quadro || "Sem código"}</p>
                      {item.grupo ? <p>Grupo: {item.grupo}</p> : null}
                      <p>Pasta: {item.pasta}</p>
                      <p>Arquivo: {item.arquivo}</p>
                      <p>Alteração: {horaBrasil(item.alterado_em)}</p>
                      <p>{item.resultado}</p>
                    </article>
                  ))}
                </div>
              </>
            ) : null}
            <div className="lista-cartoes">
              {revisoes.length === 0 ? (
                <div className="vazio-estado">
                  <h2>Nenhuma revisão</h2>
                  <p>Quando um PDF entrar pelo caminho ou pelo envio, aparece aqui.</p>
                </div>
              ) : null}
              {revisoes.map((item) => (
                <article key={item.id} className="bloco">
                  <p className="codigo-menor">
                    {item.quadro_codigo} · Rev {item.codigo}
                  </p>
                  <p>
                    {statusRevisao(item.status)} · {tipoRevisao(item.tipo)}
                  </p>
                  <p>{item.origem === "rede" ? "Veio da rede" : "Veio do painel"}</p>
                  <button
                    type="button"
                    onClick={() => {
                      setQuadroId(item.quadro_id);
                      setTela("quadro");
                    }}
                  >
                    Abrir quadro
                  </button>
                </article>
              ))}
            </div>
          </>
        ) : null}

        {tela === "duvidas" ? (
          <>
            <header className="page-head">
              <div>
                <h1>Apontamentos do tablet</h1>
                <p className="intro">
                  Erro e mudança enviados pelo montador. A lista atualiza sozinha a cada 10 segundos.
                </p>
              </div>
              <span className="contador">{semResposta} sem resposta</span>
            </header>
            {duvidas.length === 0 && pendenciasPainel.length === 0 ? (
              <div className="vazio-estado">
                <h2>Nada do tablet ainda</h2>
                <p>Quando o montador marcar no desenho e tocar em enviar, aparece aqui.</p>
              </div>
            ) : null}
            <div className="lista-cartoes">
              {duvidas.map((item) => {
                const erro = item.texto.startsWith("[ERRO]");
                const mudanca = item.texto.startsWith("[MUDANCA]") || item.texto.startsWith("[MUDANÇA]");
                return (
                  <article key={item.id} className={item.resposta ? "bloco" : "bloco destaque-criar"}>
                    <p className="codigo-menor">
                      {erro ? "Erro" : mudanca ? "Mudança" : "Dúvida"} · {item.quadro_codigo}
                    </p>
                    <p className="miudo">{item.usuario}</p>
                    <p className="texto-apontamento">{lerMarca(item.texto).visivel}</p>
                    {item.fotos.length > 0 ? (
                      <p>{item.fotos.length === 1 ? "Uma foto anexada." : `${item.fotos.length} fotos anexadas.`}</p>
                    ) : null}
                    <button
                      type="button"
                      className="secundario"
                      onClick={() => {
                        setQuadroId(item.quadro_id);
                        setTela("quadro");
                      }}
                    >
                      Abrir projeto
                    </button>
                    {item.resposta ? (
                      <p className="ok">Resposta: {item.resposta}</p>
                    ) : engenharia ? (
                      <>
                        <label>
                          Resposta
                          <input
                            value={respostas[item.id] ?? ""}
                            onChange={(e) => setRespostas({ ...respostas, [item.id]: e.target.value })}
                            placeholder="O que a engenharia responde"
                          />
                        </label>
                        <button
                          type="button"
                          onClick={() =>
                            api(`/api/observacoes/${item.id}/resposta`, sessao.token, {
                              method: "POST",
                              body: JSON.stringify({ resposta: respostas[item.id] ?? "" }),
                            })
                              .then(() => carregar(sessao.token))
                              .catch((falha: Error) => setErro(falha.message))
                          }
                        >
                          Responder
                        </button>
                      </>
                    ) : (
                      <p>Sem resposta.</p>
                    )}
                  </article>
                );
              })}
              {pendenciasPainel.map((item) => (
                <article key={item.id} className="bloco">
                  <p className="codigo-menor">Pendência · {item.quadro_codigo}</p>
                  <p className="miudo">{item.usuario}</p>
                  <p>{item.descricao}</p>
                  {podeResolver ? (
                    <button
                      type="button"
                      onClick={() =>
                        api(`/api/pendencias/${item.id}/resolucao`, sessao.token, { method: "POST", body: "{}" })
                          .then(() => carregar(sessao.token))
                          .catch((falha: Error) => setErro(falha.message))
                      }
                    >
                      Marcar resolvida
                    </button>
                  ) : null}
                </article>
              ))}
            </div>
          </>
        ) : null}

        {tela === "bancadas" ? (
          <>
            <h1>Bancadas</h1>
            {bancadas.map((bancada) => (
              <section key={bancada.id} className="bloco">
                <h2>{bancada.nome}</h2>
                {bancada.quadros.length === 0 ? <p>Nenhum quadro nesta bancada.</p> : null}
                {bancada.quadros.map((item) => (
                  <article key={item.id} className="item">
                    <p className="codigo-menor">{item.codigo}</p>
                    {item.etapas.length === 0 ? <p>Sem etapa registrada.</p> : null}
                    <ul className="lista">
                      {item.etapas.map((etapa) => (
                        <li key={etapa.codigo}>
                          {etapa.nome}: {statusEtapa(etapa.status)}
                        </li>
                      ))}
                    </ul>
                    {item.pendencias.map((pendencia) => (
                      <div key={pendencia.id}>
                        <p>
                          {pendencia.descricao}
                          {pendencia.bloqueia_etapa ? " Esta pendência bloqueia a etapa." : ""}
                        </p>
                        {podeResolver ? (
                          <button
                            type="button"
                            onClick={() =>
                              api(`/api/pendencias/${pendencia.id}/resolucao`, sessao.token, {
                                method: "POST",
                                body: "{}",
                              })
                                .then(() => carregar(sessao.token))
                                .catch((falha: Error) => setErro(falha.message))
                            }
                          >
                            Resolver pendência
                          </button>
                        ) : null}
                      </div>
                    ))}
                  </article>
                ))}
              </section>
            ))}
          </>
        ) : null}

        {tela === "auditoria" ? (
          <>
            <h1>Auditoria</h1>
            <section className="bloco">
              <label>
                Quadro
                <select value={filtroQuadro} onChange={(e) => setFiltroQuadro(e.target.value)}>
                  <option value="">Todos os quadros</option>
                  {quadros.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.codigo}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Ação
                <select value={filtroAcao} onChange={(e) => setFiltroAcao(e.target.value)}>
                  {FILTROS.map(([valor, rotulo]) => (
                    <option key={valor || "todas"} value={valor}>
                      {rotulo}
                    </option>
                  ))}
                </select>
              </label>
              <button type="button" onClick={() => carregar(sessao.token, filtroQuadro, filtroAcao)}>
                Filtrar
              </button>
            </section>
            <ul className="lista eventos">
              {auditoria.length === 0 ? <li>Nenhum evento neste filtro.</li> : null}
              {auditoria.map((evento) => {
                const extra = complemento(evento.detalhe);
                return (
                  <li key={evento.id}>
                    <strong>{evento.ator_nome || "Sistema"}</strong>
                    <span>{ACOES[evento.acao] ?? "Registrou um fato"}</span>
                    {extra ? <span>{extra}</span> : null}
                    <span>{evento.quadro_codigo || "Sem quadro"}</span>
                    <time>{horaBrasil(evento.ocorrido_em)}</time>
                  </li>
                );
              })}
            </ul>
          </>
        ) : null}

        {tela === "pessoas" && sessao.perfil === "admin" ? (
          <>
            <h1>Pessoas e tablets</h1>
            <form
              className="bloco"
              onSubmit={(evento) => {
                evento.preventDefault();
                api("/api/usuarios", sessao.token, {
                  method: "POST",
                  body: JSON.stringify({ nome: novoNome, login: novoLogin, senha: novaSenha, perfil: novoPerfil }),
                })
                  .then(() => carregar(sessao.token))
                  .catch((falha: Error) => setErro(falha.message));
              }}
            >
              <h2>Novo usuário</h2>
              <input value={novoNome} onChange={(e) => setNovoNome(e.target.value)} placeholder="Nome" />
              <input value={novoLogin} onChange={(e) => setNovoLogin(e.target.value)} placeholder="Login" />
              <input type="password" value={novaSenha} onChange={(e) => setNovaSenha(e.target.value)} placeholder="Senha" />
              <select value={novoPerfil} onChange={(e) => setNovoPerfil(e.target.value)}>
                <option value="montagem">Montagem</option>
                <option value="engenharia">Engenharia</option>
                <option value="supervisao">Supervisão</option>
                <option value="admin">Admin</option>
              </select>
              <button type="submit">Criar usuário</button>
            </form>
            {usuarios.map((usuario) => (
              <p key={usuario.id}>
                {usuario.nome} · {usuario.login} · {usuario.perfil} · {usuario.ativo ? "ativo" : "inativo"}
                {usuario.ativo ? (
                  <button
                    type="button"
                    className="secundario"
                    onClick={() =>
                      api(`/api/usuarios/${usuario.id}/bloqueio`, sessao.token, { method: "POST", body: "{}" }).then(() =>
                        carregar(sessao.token),
                      )
                    }
                  >
                    Bloquear
                  </button>
                ) : null}
              </p>
            ))}
            <h2>Tablets</h2>
            {dispositivos.map((aparelho) => (
              <article key={aparelho.id} className="bloco">
                <p>
                  {aparelho.apelido} · {aparelho.status}
                </p>
                <label>
                  Bancada
                  <input
                    value={bancadaVinculo[aparelho.id] ?? aparelho.bancada_id ?? ""}
                    onChange={(e) => setBancadaVinculo({ ...bancadaVinculo, [aparelho.id]: e.target.value })}
                    placeholder="Identificador da bancada"
                  />
                </label>
                <button
                  type="button"
                  onClick={() =>
                    api(`/api/dispositivos/${aparelho.id}/vinculo`, sessao.token, {
                      method: "POST",
                      body: JSON.stringify({ bancada_id: bancadaVinculo[aparelho.id] ?? aparelho.bancada_id ?? "" }),
                    }).then(() => carregar(sessao.token))
                  }
                >
                  Vincular bancada
                </button>
                {aparelho.status === "pendente" ? (
                  <button
                    type="button"
                    className="secundario"
                    onClick={() =>
                      api(`/api/dispositivos/${aparelho.id}/ativacao`, sessao.token, { method: "POST", body: "{}" }).then(
                        () => carregar(sessao.token),
                      )
                    }
                  >
                    Ativar
                  </button>
                ) : null}
                {aparelho.status === "ativo" ? (
                  <button
                    type="button"
                    className="secundario"
                    onClick={() =>
                      api(`/api/dispositivos/${aparelho.id}/bloqueio`, sessao.token, { method: "POST", body: "{}" }).then(
                        () => carregar(sessao.token),
                      )
                    }
                  >
                    Bloquear
                  </button>
                ) : null}
              </article>
            ))}
          </>
        ) : null}
      </main>
    </>
  );
}

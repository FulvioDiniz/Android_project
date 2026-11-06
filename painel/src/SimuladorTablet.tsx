import { useEffect, useMemo, useState } from "react";

type Quadro = {
  id: string;
  codigo: string;
  titulo: string;
  cliente_nome: string;
  ordem_numero: string;
  caminho_projeto?: string;
  revisao_vigente_codigo: string | null;
  arquivo_id?: string | null;
  arquivo_disponivel?: boolean;
};

type Produto = {
  codigo: string;
  nome: string;
  saldo: number;
  unidade: string;
  modelo: string;
};

type ItemCarrinho = Produto & { quantidade: number };

type PedidoResumo = {
  id: string;
  status: string;
  pedido_em: string;
  itens: { id: string }[];
  observacao: string;
};

type AbaSim = "projetos" | "desenho" | "almox" | "oficina";

type Props = {
  token: string;
  quadros: Quadro[];
  api: <T>(caminho: string, token: string, opcoes?: RequestInit) => Promise<T>;
  horaBrasil: (iso: string) => string;
  ondePortal: (caminho?: string) => string;
  rotuloRev: (codigo?: string | null) => string | null;
  onPedidoEnviado: () => void;
  onAtualizar: () => void;
};

function seloProjeto(quadro: Quadro, rotuloRev: (c?: string | null) => string | null): string {
  const rev = rotuloRev(quadro.revisao_vigente_codigo);
  if (rev) return `Rev ${rev}`;
  if (quadro.arquivo_id) return "PDF pronto";
  return "Sem PDF";
}

export function SimuladorTablet({
  token,
  quadros,
  api,
  horaBrasil,
  ondePortal,
  rotuloRev,
  onPedidoEnviado,
  onAtualizar,
}: Props) {
  const comPdf = useMemo(
    () => quadros.filter((q) => (q.arquivo_id || "").trim() !== "" || q.arquivo_disponivel !== false),
    [quadros],
  );
  const [aba, setAba] = useState<AbaSim>("projetos");
  const [buscaProjeto, setBuscaProjeto] = useState("");
  const [quadroId, setQuadroId] = useState<string | null>(null);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const [pdfStatus, setPdfStatus] = useState("");
  const [busca, setBusca] = useState("");
  const [produtos, setProdutos] = useState<Produto[]>([]);
  const [carrinho, setCarrinho] = useState<ItemCarrinho[]>([]);
  const [obs, setObs] = useState("");
  const [status, setStatus] = useState("");
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [pedidos, setPedidos] = useState<PedidoResumo[]>([]);
  const [leituraOk, setLeituraOk] = useState(false);

  const quadro = comPdf.find((q) => q.id === quadroId) ?? null;
  const projetosVisiveis = useMemo(() => {
    const termo = buscaProjeto.trim().toLowerCase();
    const base = comPdf.length ? comPdf : quadros;
    if (!termo) return base;
    return base.filter((q) =>
      [q.codigo, q.cliente_nome, q.ordem_numero, q.titulo, ondePortal(q.caminho_projeto)]
        .join(" ")
        .toLowerCase()
        .includes(termo),
    );
  }, [comPdf, quadros, buscaProjeto, ondePortal]);

  function abrirProjeto(id: string) {
    setQuadroId(id);
    setAba("desenho");
    setErro("");
    setLeituraOk(false);
  }

  function voltarLista() {
    setQuadroId(null);
    setAba("projetos");
    setPdfUrl((u) => {
      if (u) URL.revokeObjectURL(u);
      return null;
    });
  }

  useEffect(() => {
    let ativo = true;
    async function carregarPdf() {
      if (!quadroId || aba !== "desenho") return;
      setPdfStatus("Carregando PDF…");
      try {
        const historia = await api<{
          revisoes: { arquivo_id?: string | null; status: string }[];
          caminho_projeto?: string;
        }>(`/api/quadros/${quadroId}/revisoes`, token);
        const comArquivo =
          historia.revisoes.find((r) => (r.arquivo_id || "").trim()) ||
          historia.revisoes[0];
        const arquivoId = (comArquivo?.arquivo_id || quadro?.arquivo_id || "").trim();
        if (!arquivoId) {
          if (ativo) {
            setPdfUrl((u) => {
              if (u) URL.revokeObjectURL(u);
              return null;
            });
            setPdfStatus("Este projeto ainda não tem PDF.");
          }
          return;
        }
        const resp = await fetch(`/api/arquivos/${arquivoId}`, {
          headers: { Authorization: `Bearer ${token}` },
        });
        if (!resp.ok) throw new Error("PDF indisponível");
        const bruto = await resp.blob();
        const pdf = bruto.type === "application/pdf" ? bruto : new Blob([bruto], { type: "application/pdf" });
        const url = URL.createObjectURL(pdf);
        if (!ativo) {
          URL.revokeObjectURL(url);
          return;
        }
        setPdfUrl((anterior) => {
          if (anterior) URL.revokeObjectURL(anterior);
          return url;
        });
        setPdfStatus("PDF pronto");
      } catch (e) {
        if (ativo) {
          setPdfStatus(e instanceof Error ? e.message : "Falha ao abrir PDF");
          setPdfUrl((u) => {
            if (u) URL.revokeObjectURL(u);
            return null;
          });
        }
      }
    }
    carregarPdf();
    return () => {
      ativo = false;
    };
  }, [quadroId, aba, token, quadro?.arquivo_id]);

  useEffect(() => {
    if (!quadroId || aba !== "almox") return;
    api<PedidoResumo[]>(`/api/almox/pedidos?status=todos&quadro_id=${encodeURIComponent(quadroId)}`, token)
      .then(setPedidos)
      .catch(() => setPedidos([]));
  }, [quadroId, token, aba]);

  useEffect(() => {
    if (aba !== "almox") return;
    const termo = busca.trim();
    if (termo.length < 2) {
      setProdutos([]);
      return;
    }
    const timer = window.setTimeout(() => {
      setStatus("Buscando no Firebird…");
      setErro("");
      api<{ itens: Produto[] }>(`/api/almox/produtos?q=${encodeURIComponent(termo)}`, token)
        .then((r) => {
          setProdutos(r.itens ?? []);
          setStatus(r.itens?.length ? `${r.itens.length} produto(s)` : `Nada para “${termo}”`);
        })
        .catch((e: Error) => {
          setProdutos([]);
          setStatus("");
          setErro(e.message);
        });
    }, 350);
    return () => window.clearTimeout(timer);
  }, [busca, token, aba]);

  function addProduto(p: Produto) {
    setCarrinho((atual) => {
      const ja = atual.find((i) => i.codigo === p.codigo);
      if (!ja) return [...atual, { ...p, quantidade: 1 }];
      return atual.map((i) => (i.codigo === p.codigo ? { ...i, quantidade: i.quantidade + 1 } : i));
    });
  }

  function alterarQtd(codigo: string, delta: number) {
    setCarrinho((atual) =>
      atual
        .map((i) => (i.codigo === codigo ? { ...i, quantidade: i.quantidade + delta } : i))
        .filter((i) => i.quantidade > 0),
    );
  }

  async function enviar() {
    if (!quadro || carrinho.length === 0) return;
    setEnviando(true);
    setErro("");
    try {
      await api("/api/almox/pedidos", token, {
        method: "POST",
        body: JSON.stringify({
          quadro_id: quadro.id,
          observacao: obs.trim(),
          itens: carrinho.map((i) => ({
            codigo: i.codigo,
            nome: i.nome,
            unidade: i.unidade,
            quantidade: i.quantidade,
            saldo: i.saldo,
            modelo: i.modelo,
          })),
        }),
      });
      setCarrinho([]);
      setObs("");
      setBusca("");
      setProdutos([]);
      setStatus("Pedido enviado · entrou na fila com horário");
      const lista = await api<PedidoResumo[]>(
        `/api/almox/pedidos?status=todos&quadro_id=${encodeURIComponent(quadro.id)}`,
        token,
      );
      setPedidos(lista);
      onPedidoEnviado();
    } catch (e) {
      setErro(e instanceof Error ? e.message : "Não enviou");
    } finally {
      setEnviando(false);
    }
  }

  function irAba(destino: AbaSim) {
    if (destino !== "projetos" && !quadroId) {
      setErro("Abra um projeto na lista primeiro.");
      setAba("projetos");
      return;
    }
    setErro("");
    setAba(destino);
  }

  const projetoAberto = Boolean(quadroId && quadro);

  return (
    <div className={projetoAberto ? "sim-wrap sim-wrap-cheio" : "sim-wrap"}>
      {!projetoAberto ? (
        <div>
          <h1>Simulador do tablet</h1>
          <p className="miudo">
            Teste como no APK: lista de projetos, desenho PDF, oficina e requisição ao almox.
            Ao abrir um projeto, a tela fica inteira.
          </p>
          <ul className="lista">
            <li>Projetos — busca e abre o quadro em tela cheia</li>
            <li>Desenho — PDF ocupando a tela</li>
            <li>Almox — pede peça (Firebird → fila)</li>
            <li>Oficina — confirma leitura (simulado)</li>
          </ul>
        </div>
      ) : null}
      <div className={projetoAberto ? "sim-tablet sim-tablet-cheio" : "sim-tablet"}>
        {!projetoAberto ? <div className="sim-notch" /> : null}
        <div className={projetoAberto ? "sim-tela sim-tela-cheia" : "sim-tela"}>
          {erro ? <p className="aviso">{erro}</p> : null}

          {aba === "projetos" || !quadro ? (
            <>
              <header className="sim-topo">
                <strong>Projetos</strong>
                <span>
                  {projetosVisiveis.length} na lista · toque para abrir em tela cheia
                </span>
              </header>
              <label className="sim-campo">
                Buscar projeto
                <input
                  value={buscaProjeto}
                  onChange={(e) => setBuscaProjeto(e.target.value)}
                  placeholder="código, cliente, pasta…"
                />
              </label>
              <div className="sim-acoes-topo">
                <button type="button" className="secundario" onClick={onAtualizar}>
                  Atualizar
                </button>
              </div>
              <div className="sim-projetos">
                {projetosVisiveis.length === 0 ? (
                  <p className="miudo">Nenhum projeto encontrado.</p>
                ) : null}
                {projetosVisiveis.map((q) => (
                  <button key={q.id} type="button" className="sim-cartao-projeto" onClick={() => abrirProjeto(q.id)}>
                    <span className="sim-cartao-barra" />
                    <span className="sim-cartao-corpo">
                      <strong>{q.codigo}</strong>
                      <em>{ondePortal(q.caminho_projeto) || q.cliente_nome || "Sem pasta"}</em>
                      <span className="sim-selo">{seloProjeto(q, rotuloRev)}</span>
                    </span>
                    <b>›</b>
                  </button>
                ))}
              </div>
            </>
          ) : null}

          {aba === "desenho" && quadro ? (
            <>
              <header className="sim-topo sim-topo-cheio">
                <button type="button" className="sim-voltar" onClick={voltarLista}>
                  ← Projetos
                </button>
                <div className="sim-topo-titulo">
                  <strong>{quadro.codigo}</strong>
                  <span>{pdfStatus || "Desenho em tela cheia"}</span>
                </div>
                <div className="sim-topo-acoes">
                  <button type="button" className="secundario" onClick={() => setAba("almox")}>
                    Almox
                  </button>
                  <button type="button" className="secundario" onClick={() => setAba("oficina")}>
                    Oficina
                  </button>
                </div>
              </header>
              {pdfUrl ? (
                <iframe title={`PDF ${quadro.codigo}`} className="sim-pdf sim-pdf-cheio" src={`${pdfUrl}#toolbar=1&navpanes=0`} />
              ) : (
                <div className="sim-pdf-vazio">
                  <p>{pdfStatus || "Sem PDF"}</p>
                </div>
              )}
            </>
          ) : null}

          {aba === "almox" && quadro ? (
            <>
              <header className="sim-topo">
                <strong>Almoxarifado</strong>
                <span>Projeto {quadro.codigo} · requisição com horário</span>
              </header>
              <label className="sim-campo">
                Pesquisar no Firebird
                <input
                  value={busca}
                  onChange={(e) => setBusca(e.target.value)}
                  placeholder="código, nome ou modelo"
                />
              </label>
              {status ? <p className="sim-status">{status}</p> : null}
              <div className="sim-lista">
                {produtos.map((p) => (
                  <button key={p.codigo} type="button" className="sim-produto" onClick={() => addProduto(p)}>
                    <strong>{p.codigo}</strong>
                    <span>{p.nome}</span>
                    <em>
                      Saldo {p.saldo} {p.unidade}
                      {p.modelo ? ` · ${p.modelo}` : ""}
                    </em>
                    <b>+</b>
                  </button>
                ))}
              </div>
              {carrinho.length > 0 ? (
                <div className="sim-carrinho">
                  <strong>Pedido ({carrinho.length})</strong>
                  {carrinho.map((i) => (
                    <div key={i.codigo} className="sim-item">
                      <div>
                        <strong>{i.codigo}</strong>
                        <span>{i.nome}</span>
                      </div>
                      <div className="sim-qtd">
                        <button type="button" onClick={() => alterarQtd(i.codigo, -1)}>
                          −
                        </button>
                        <span>
                          {i.quantidade} {i.unidade}
                        </span>
                        <button type="button" onClick={() => alterarQtd(i.codigo, 1)}>
                          +
                        </button>
                      </div>
                    </div>
                  ))}
                  <label className="sim-campo">
                    Observação
                    <textarea value={obs} onChange={(e) => setObs(e.target.value)} rows={2} />
                  </label>
                  <button type="button" className="sim-enviar" disabled={enviando} onClick={enviar}>
                    ENVIAR PARA O ESTOQUE
                  </button>
                </div>
              ) : null}
              {pedidos.length > 0 ? (
                <div className="sim-fila">
                  <strong>Fila deste projeto</strong>
                  {pedidos.slice(0, 6).map((p) => (
                    <div key={p.id} className="sim-pedido">
                      <span>{p.status}</span>
                      <span>
                        {horaBrasil(p.pedido_em)} · {p.itens?.length ?? 0} item(ns)
                      </span>
                    </div>
                  ))}
                </div>
              ) : null}
            </>
          ) : null}

          {aba === "oficina" && quadro ? (
            <>
              <header className="sim-topo">
                <strong>Oficina</strong>
                <span>{quadro.codigo}</span>
              </header>
              <div className="sim-oficina">
                <p>
                  Vigente:{" "}
                  <strong>
                    {rotuloRev(quadro.revisao_vigente_codigo)
                      ? `Rev ${rotuloRev(quadro.revisao_vigente_codigo)}`
                      : "Sem vigente"}
                  </strong>
                </p>
                <p className="miudo">
                  {leituraOk ? "Leitura confirmada (simulado neste navegador)." : "Leitura pendente."}
                </p>
                {!leituraOk ? (
                  <button type="button" onClick={() => setLeituraOk(true)}>
                    Confirmar leitura da vigente
                  </button>
                ) : (
                  <p className="ok">OK · pode seguir etapas no tablet real</p>
                )}
              </div>
            </>
          ) : null}

          {aba !== "desenho" ? (
            <nav className="sim-nav">
              {(
                [
                  ["projetos", "Projetos"],
                  ["desenho", "Desenho"],
                  ["almox", "Almox"],
                  ["oficina", "Oficina"],
                ] as const
              ).map(([id, rotulo]) => (
                <button
                  key={id}
                  type="button"
                  className={aba === id ? "ativo" : undefined}
                  onClick={() => (id === "projetos" ? (quadroId ? setAba("projetos") : voltarLista()) : irAba(id))}
                >
                  {rotulo}
                </button>
              ))}
            </nav>
          ) : null}
        </div>
      </div>
    </div>
  );
}

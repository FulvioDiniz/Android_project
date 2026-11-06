import { useEffect, useMemo, useState } from "react";

type Quadro = {
  id: string;
  codigo: string;
  cliente_nome: string;
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

type Props = {
  token: string;
  quadros: Quadro[];
  api: <T>(caminho: string, token: string, opcoes?: RequestInit) => Promise<T>;
  horaBrasil: (iso: string) => string;
  onPedidoEnviado: () => void;
};

export function SimuladorTablet({ token, quadros, api, horaBrasil, onPedidoEnviado }: Props) {
  const comPdf = useMemo(() => quadros.filter((q) => q.id), [quadros]);
  const [quadroId, setQuadroId] = useState(comPdf[0]?.id ?? "");
  const [busca, setBusca] = useState("");
  const [produtos, setProdutos] = useState<Produto[]>([]);
  const [carrinho, setCarrinho] = useState<ItemCarrinho[]>([]);
  const [obs, setObs] = useState("");
  const [status, setStatus] = useState("");
  const [erro, setErro] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [pedidos, setPedidos] = useState<PedidoResumo[]>([]);
  const quadro = comPdf.find((q) => q.id === quadroId) ?? null;

  useEffect(() => {
    if (!quadroId) return;
    api<PedidoResumo[]>(`/api/almox/pedidos?status=todos&quadro_id=${encodeURIComponent(quadroId)}`, token)
      .then(setPedidos)
      .catch(() => setPedidos([]));
  }, [quadroId, token]);

  useEffect(() => {
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
  }, [busca, token]);

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

  return (
    <div className="sim-wrap">
      <div>
        <h1>Simulador do tablet</h1>
        <p className="miudo">
          Mesmo fluxo da aba Almox do APK: escolher projeto, pesquisar peça no Firebird e requisitar.
          O pedido cai na fila do menu Almox.
        </p>
      </div>
      <div className="sim-tablet">
        <div className="sim-notch" />
        <div className="sim-tela">
          <header className="sim-topo">
            <strong>QUADROS</strong>
            <span>Requisição · Almox</span>
          </header>
          <label className="sim-campo">
            Projeto
            <select value={quadroId} onChange={(e) => setQuadroId(e.target.value)}>
              {comPdf.length === 0 ? <option value="">Sem projetos</option> : null}
              {comPdf.map((q) => (
                <option key={q.id} value={q.id}>
                  {q.codigo}
                </option>
              ))}
            </select>
          </label>
          <label className="sim-campo">
            Pesquisar no Firebird
            <input
              value={busca}
              onChange={(e) => setBusca(e.target.value)}
              placeholder="código, nome ou modelo"
            />
          </label>
          {status ? <p className="sim-status">{status}</p> : null}
          {erro ? <p className="aviso">{erro}</p> : null}
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
              <button type="button" className="sim-enviar" disabled={enviando || !quadro} onClick={enviar}>
                ENVIAR PARA O ESTOQUE
              </button>
            </div>
          ) : null}
          {pedidos.length > 0 ? (
            <div className="sim-fila">
              <strong>Fila deste projeto</strong>
              {pedidos.slice(0, 8).map((p) => (
                <div key={p.id} className="sim-pedido">
                  <span>{p.status}</span>
                  <span>
                    {horaBrasil(p.pedido_em)} · {p.itens?.length ?? 0} item(ns)
                  </span>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

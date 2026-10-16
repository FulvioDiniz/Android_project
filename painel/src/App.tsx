import { FormEvent, useEffect, useState } from "react";

type Quadro = {
  id: string;
  codigo: string;
  titulo: string;
  status: string;
  ordem_numero: string;
  cliente_nome: string;
  responsavel_nome: string | null;
  revisao_vigente_id: string | null;
};

type Sessao = {
  token: string;
  perfil: string;
  usuario: { nome: string };
};

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
  const [login, setLogin] = useState("ana.ribeiro");
  const [senha, setSenha] = useState("");
  const [erro, setErro] = useState("");
  const [quadros, setQuadros] = useState<Quadro[]>([]);
  const [numero, setNumero] = useState("PED-DEL-01890-2026-16");
  const [cliente, setCliente] = useState("");
  const [codigo, setCodigo] = useState("PED-DEL-01890-2026-16");
  const [titulo, setTitulo] = useState("");
  const [aviso, setAviso] = useState("");
  const [arquivo, setArquivo] = useState<File | null>(null);
  const [pendentes, setPendentes] = useState<{ id: string; codigo: string; quadro_codigo: string; status: string }[]>([]);
  const [erros, setErros] = useState<{ id: string; nome_original: string; motivo: string }[]>([]);

  async function carregar(token: string) {
    setQuadros(await api<Quadro[]>("/api/quadros", token));
    try {
      setPendentes(await api("/api/revisoes?status=pendente", token));
      setErros(await api("/api/erros-importacao?status=aberto", token));
    } catch {
      setPendentes([]);
      setErros([]);
    }
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
    if (corpo.status === "pendente") {
      setAviso(`Revisão ${corpo.codigo ?? ""} pendente. O ponteiro vigente não mudou.`);
    } else {
      setAviso("Nome inseguro. Foi para validação manual, sem revisão.");
    }
    setArquivo(null);
    await carregar(sessao.token);
  }

  useEffect(() => {
    if (sessao) {
      carregar(sessao.token).catch((e: Error) => setErro(e.message));
    }
  }, [sessao]);

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
  }

  async function cadastrar(evento: FormEvent) {
    evento.preventDefault();
    if (!sessao) return;
    setAviso("");
    setErro("");
    try {
      const ordem = await api<{ id: string }>("/api/ordens-producao", sessao.token, {
        method: "POST",
        body: JSON.stringify({ numero, cliente_nome: cliente }),
      });
      await api("/api/quadros", sessao.token, {
        method: "POST",
        body: JSON.stringify({
          codigo,
          titulo,
          ordem_producao_id: ordem.id,
        }),
      });
      setAviso(`Quadro ${codigo} gravado.`);
      await carregar(sessao.token);
    } catch (e) {
      setErro(e instanceof Error ? e.message : "Falha ao cadastrar");
    }
  }

  async function sair() {
    if (!sessao) return;
    await api("/api/sessao", sessao.token, { method: "DELETE" });
    setSessao(null);
    setQuadros([]);
  }

  if (!sessao) {
    return (
      <main>
        <h1>Quadros</h1>
        <p>Painel da engenharia. Montagem entra no tablet.</p>
        <form onSubmit={entrar}>
          <label>
            Login
            <input value={login} onChange={(e) => setLogin(e.target.value)} />
          </label>
          <label>
            Senha
            <input type="password" value={senha} onChange={(e) => setSenha(e.target.value)} />
          </label>
          {erro ? <p className="aviso">{erro}</p> : null}
          <button type="submit">Entrar</button>
        </form>
      </main>
    );
  }

  return (
    <main>
      <h1>Quadros em cadastro</h1>
      <p>
        {sessao.usuario.nome} · {sessao.perfil}. Ainda não há revisão vigente: o PDF entra na fase seguinte.
      </p>
      <button className="secundario" type="button" onClick={sair}>
        Sair
      </button>
      {erro ? <p className="aviso">{erro}</p> : null}
      {aviso ? <p className="ok">{aviso}</p> : null}
      <table>
        <thead>
          <tr>
            <th>Código</th>
            <th>Título</th>
            <th>Ordem</th>
            <th>Cliente</th>
            <th>Vigente</th>
          </tr>
        </thead>
        <tbody>
          {quadros.map((q) => (
            <tr key={q.id}>
              <td>{q.codigo}</td>
              <td>{q.titulo}</td>
              <td>{q.ordem_numero}</td>
              <td>{q.cliente_nome}</td>
              <td>{q.revisao_vigente_id ? "publicada" : "nenhuma"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {sessao.perfil === "engenharia" ? (
        <form className="cadastro" onSubmit={enviarPdf}>
          <h2>Enviar PDF</h2>
          <p>O arquivo entra por aqui. Chegar o PDF não publica a revisão.</p>
          <label>
            Arquivo no padrão CODIGO-REV.X.pdf
            <input
              type="file"
              accept="application/pdf,.pdf"
              onChange={(e) => setArquivo(e.target.files?.[0] ?? null)}
            />
          </label>
          <button type="submit">Enviar</button>
          {pendentes.length > 0 ? (
            <ul>
              {pendentes.map((item) => (
                <li key={item.id}>
                  {item.quadro_codigo} Rev {item.codigo} · {item.status}
                </li>
              ))}
            </ul>
          ) : null}
          {erros.length > 0 ? (
            <ul>
              {erros.map((item) => (
                <li key={item.id}>
                  {item.nome_original} · {item.motivo}
                </li>
              ))}
            </ul>
          ) : null}
        </form>
      ) : null}
      {sessao.perfil === "engenharia" ? (
        <form className="cadastro" onSubmit={cadastrar}>
          <h2>Novo quadro</h2>
          <label>
            Número da ordem
            <input value={numero} onChange={(e) => setNumero(e.target.value)} />
          </label>
          <label>
            Cliente
            <input value={cliente} onChange={(e) => setCliente(e.target.value)} />
          </label>
          <label>
            Código do quadro
            <input value={codigo} onChange={(e) => setCodigo(e.target.value)} />
          </label>
          <label>
            Título
            <input value={titulo} onChange={(e) => setTitulo(e.target.value)} />
          </label>
          <button type="submit">Cadastrar</button>
        </form>
      ) : (
        <p>Este perfil consulta. Quem cadastra é a engenharia.</p>
      )}
    </main>
  );
}

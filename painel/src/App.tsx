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
  revisao_vigente_codigo: string | null;
};

type Sessao = {
  token: string;
  perfil: string;
  usuario: { nome: string };
};

type Duvida = {
  id: string;
  quadro_codigo: string;
  texto: string;
  resposta: string | null;
  usuario: string;
  fotos: { id: string; hash_sha256: string; legenda: string }[];
};

type BancadaAndamento = {
  id: string;
  nome: string;
  quadros: {
    id: string;
    codigo: string;
    etapas: { codigo: string; nome: string; status: string }[];
    pendencias: { id: string; descricao: string; bloqueia_etapa: boolean }[];
    eventos: { acao: string; detalhe: string }[];
  }[];
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
  const [pendentes, setPendentes] = useState<{ id: string; codigo: string; quadro_codigo: string; status: string; tipo: string | null }[]>([]);
  const [aprovadas, setAprovadas] = useState<{ id: string; codigo: string; quadro_codigo: string }[]>([]);
  const [tipo, setTipo] = useState("bloqueio");
  const [motivo, setMotivo] = useState("");
  const [erros, setErros] = useState<{ id: string; nome_original: string; motivo: string }[]>([]);
  const [reconhecimento, setReconhecimento] = useState<{
    confirmados: { usuario: string; dispositivo: string; ocorrido_em: string }[];
    faltam: { dispositivo: string }[];
  } | null>(null);
  const [duvidas, setDuvidas] = useState<Duvida[]>([]);
  const [bancadas, setBancadas] = useState<BancadaAndamento[]>([]);
  const [rascunho, setRascunho] = useState<Record<string, string>>({});
  const [usuarios, setUsuarios] = useState<{ id: string; nome: string; login: string; perfil: string; ativo: number }[]>([]);
  const [dispositivos, setDispositivos] = useState<{ id: string; identificador_instalacao: string; apelido: string; bancada_id: string | null; status: string }[]>([]);
  const [auditoria, setAuditoria] = useState<{ id: number; acao: string; detalhe: string; quadro_id: string | null; ocorrido_em: string }[]>([]);
  const [filtroQuadro, setFiltroQuadro] = useState("");
  const [filtroAcao, setFiltroAcao] = useState("");
  const [novoNome, setNovoNome] = useState("");
  const [novoLogin, setNovoLogin] = useState("");
  const [novaSenha, setNovaSenha] = useState("");
  const [novoPerfil, setNovoPerfil] = useState("montagem");
  const [motivoCancelamento, setMotivoCancelamento] = useState<Record<string, string>>({});
  const [bancadaVinculo, setBancadaVinculo] = useState<Record<string, string>>({});

  async function carregar(token: string) {
    setQuadros(await api<Quadro[]>("/api/quadros", token));
    try {
      setPendentes(await api("/api/revisoes?status=pendente", token));
      setAprovadas(await api("/api/revisoes?status=aprovada", token));
      setErros(await api("/api/erros-importacao?status=aberto", token));
      setDuvidas(await api<Duvida[]>("/api/observacoes?tipo=duvida", token));
      setBancadas(await api<BancadaAndamento[]>("/api/bancadas/andamento", token));
    } catch {
        setPendentes([]);
        setAprovadas([]);
        setErros([]);
    }
    try {
      setAuditoria(await api(`/api/auditoria?quadro_id=${encodeURIComponent(filtroQuadro)}&acao=${encodeURIComponent(filtroAcao)}`, token));
    } catch {
      setAuditoria([]);
    }
    try {
      setUsuarios(await api("/api/usuarios", token));
      setDispositivos(await api("/api/dispositivos", token));
    } catch {
      setUsuarios([]);
      setDispositivos([]);
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
        {sessao.usuario.nome} · {sessao.perfil}. A vigente só muda na publicação, não no envio do PDF.
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
              <td>{q.revisao_vigente_codigo ? `Rev ${q.revisao_vigente_codigo}` : "nenhuma"}</td>
              <td>
                <button
                  type="button"
                  className="secundario"
                  onClick={() =>
                    api<{
                      confirmados: { usuario: string; dispositivo: string; ocorrido_em: string }[];
                      faltam: { dispositivo: string }[];
                    }>(`/api/quadros/${q.id}/confirmacoes`, sessao.token).then(setReconhecimento)
                  }
                >
                  Quem reconheceu
                </button>
                {sessao.perfil === "engenharia" && q.revisao_vigente_id ? (
                  <>
                    <input
                      value={motivoCancelamento[q.id] ?? ""}
                      onChange={(e) => setMotivoCancelamento({ ...motivoCancelamento, [q.id]: e.target.value })}
                      placeholder="Motivo do cancelamento"
                    />
                    <button
                      type="button"
                      className="secundario"
                      onClick={() =>
                        api(`/api/quadros/${q.id}/cancelamento`, sessao.token, {
                          method: "POST",
                          body: JSON.stringify({ motivo: motivoCancelamento[q.id] ?? "" }),
                        })
                          .then(() => {
                            setAviso("Publicação cancelada. O ponteiro voltou.");
                            return carregar(sessao.token);
                          })
                          .catch((e: Error) => setErro(e.message))
                      }
                    >
                      Cancelar vigente
                    </button>
                  </>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {reconhecimento ? (
        <div className="ok">
          <p>Reconheceram: {reconhecimento.confirmados.map((item) => `${item.usuario} (${item.dispositivo})`).join(", ") || "ninguém"}</p>
          <p>Faltam: {reconhecimento.faltam.map((item) => item.dispositivo).join(", ") || "ninguém"}</p>
        </div>
      ) : null}
      <section>
        <h2>Dúvidas dos montadores</h2>
        {duvidas.length === 0 ? <p>Nenhuma dúvida.</p> : null}
        {duvidas.map((item) => (
          <div key={item.id}>
            <p>
              {item.quadro_codigo} · {item.usuario}: {item.texto}
            </p>
            {item.fotos.map((foto) => (
              <p key={foto.id}>
                Foto {foto.legenda || "sem legenda"} · {foto.hash_sha256}
              </p>
            ))}
            {item.resposta ? (
              <p>Resposta: {item.resposta}</p>
            ) : sessao.perfil === "engenharia" ? (
              <p>
                <input
                  value={rascunho[item.id] ?? ""}
                  onChange={(e) => setRascunho({ ...rascunho, [item.id]: e.target.value })}
                  placeholder="Resposta da engenharia"
                />
                <button
                  type="button"
                  onClick={() =>
                    api(`/api/observacoes/${item.id}/resposta`, sessao.token, {
                      method: "POST",
                      body: JSON.stringify({ resposta: rascunho[item.id] ?? "" }),
                    }).then(() => carregar(sessao.token))
                  }
                >
                  Responder
                </button>
              </p>
            ) : (
              <p>Sem resposta.</p>
            )}
          </div>
        ))}
      </section>
      <section>
        <h2>Bancadas</h2>
        {bancadas.map((bancada) => (
          <div key={bancada.id}>
            <h3>{bancada.nome}</h3>
            {bancada.quadros.length === 0 ? <p>Nenhum quadro nesta bancada.</p> : null}
            {bancada.quadros.map((quadro) => (
              <div key={quadro.id}>
                <p>{quadro.codigo}</p>
                {quadro.etapas.length === 0 ? <p>Sem etapa registrada.</p> : null}
                {quadro.etapas.map((etapa) => (
                  <p key={etapa.codigo}>
                    {etapa.nome}: {etapa.status}
                  </p>
                ))}
                {quadro.pendencias.map((pendencia) => (
                  <p key={pendencia.id}>
                    {pendencia.descricao}
                    {pendencia.bloqueia_etapa ? " · bloqueia a etapa" : ""}
                    {sessao.perfil === "engenharia" || sessao.perfil === "supervisao" ? (
                      <button
                        type="button"
                        className="secundario"
                        onClick={() =>
                          api(`/api/pendencias/${pendencia.id}/resolucao`, sessao.token, {
                            method: "POST",
                            body: "{}",
                          }).then(() => carregar(sessao.token))
                        }
                      >
                        Resolver
                      </button>
                    ) : null}
                  </p>
                ))}
                {quadro.eventos.length > 0 ? (
                  <p>
                    Trilha: {quadro.eventos.map((evento) => `${evento.acao} ${evento.detalhe}`).join(" · ")}
                  </p>
                ) : null}
              </div>
            ))}
          </div>
        ))}
      </section>
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
          {pendentes.map((item) => (
            <div key={item.id}>
              <p>
                {item.quadro_codigo} Rev {item.codigo} · pendente
              </p>
              <label>
                Tipo
                <select value={tipo} onChange={(e) => setTipo(e.target.value)}>
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
                    body: JSON.stringify({ tipo }),
                  }).then(() => carregar(sessao.token))
                }
              >
                Aprovar
              </button>
              <input value={motivo} onChange={(e) => setMotivo(e.target.value)} placeholder="Motivo da rejeição" />
              <button
                type="button"
                className="secundario"
                onClick={() =>
                  api(`/api/revisoes/${item.id}/rejeicao`, sessao.token, {
                    method: "POST",
                    body: JSON.stringify({ motivo }),
                  }).then(() => carregar(sessao.token))
                }
              >
                Rejeitar
              </button>
            </div>
          ))}
          {aprovadas.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={async () => {
                const lista = await api<Quadro[]>("/api/quadros", sessao.token);
                const alvo = lista.find((q) => q.codigo === item.quadro_codigo);
                if (!alvo) return;
                await api(`/api/quadros/${alvo.id}/publicacao`, sessao.token, {
                  method: "POST",
                  body: JSON.stringify({ revisao_id: item.id }),
                });
                setAviso(`Vigente passou a ser Rev ${item.codigo}.`);
                await carregar(sessao.token);
              }}
            >
              Publicar {item.quadro_codigo} Rev {item.codigo}
            </button>
          ))}
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
        sessao.perfil === "supervisao" ? <p>Este perfil consulta. Quem cadastra é a engenharia.</p> : null
      )}
      <section>
        <h2>Auditoria</h2>
        <label>
          Quadro
          <input value={filtroQuadro} onChange={(e) => setFiltroQuadro(e.target.value)} placeholder="id do quadro" />
        </label>
        <label>
          Ação
          <input value={filtroAcao} onChange={(e) => setFiltroAcao(e.target.value)} placeholder="publicacao, cancelamento, bloqueio" />
        </label>
        <button type="button" onClick={() => carregar(sessao.token)}>Filtrar</button>
        {auditoria.length === 0 ? <p>Nenhum evento neste filtro.</p> : null}
        <ul>
          {auditoria.map((evento) => (
            <li key={evento.id}>
              {evento.acao} · {evento.detalhe} · {evento.quadro_id ?? "sem quadro"} · {evento.ocorrido_em}
            </li>
          ))}
        </ul>
      </section>
      {sessao.perfil === "admin" ? (
        <section>
          <h2>Usuários</h2>
          <form
            className="cadastro"
            onSubmit={(evento) => {
              evento.preventDefault();
              api("/api/usuarios", sessao.token, {
                method: "POST",
                body: JSON.stringify({ nome: novoNome, login: novoLogin, senha: novaSenha, perfil: novoPerfil }),
              })
                .then(() => carregar(sessao.token))
                .catch((e: Error) => setErro(e.message));
            }}
          >
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
            <p key={aparelho.id}>
              {aparelho.apelido} · {aparelho.identificador_instalacao} · {aparelho.status} · bancada {aparelho.bancada_id ?? "nenhuma"}
              <input
                value={bancadaVinculo[aparelho.id] ?? aparelho.bancada_id ?? ""}
                onChange={(e) => setBancadaVinculo({ ...bancadaVinculo, [aparelho.id]: e.target.value })}
                placeholder="id da bancada"
              />
              <button
                type="button"
                onClick={() =>
                  api(`/api/dispositivos/${aparelho.id}/vinculo`, sessao.token, {
                    method: "POST",
                    body: JSON.stringify({ bancada_id: bancadaVinculo[aparelho.id] ?? aparelho.bancada_id ?? "" }),
                  }).then(() => carregar(sessao.token))
                }
              >
                Vincular
              </button>
              {aparelho.status === "pendente" ? (
                <button
                  type="button"
                  onClick={() =>
                    api(`/api/dispositivos/${aparelho.id}/ativacao`, sessao.token, { method: "POST", body: "{}" }).then(() =>
                      carregar(sessao.token),
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
                    api(`/api/dispositivos/${aparelho.id}/bloqueio`, sessao.token, { method: "POST", body: "{}" }).then(() =>
                      carregar(sessao.token),
                    )
                  }
                >
                  Bloquear
                </button>
              ) : null}
            </p>
          ))}
        </section>
      ) : null}
    </main>
  );
}

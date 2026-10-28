import { useEffect, useRef, useState } from "react";
import { getDocument, GlobalWorkerOptions } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

GlobalWorkerOptions.workerSrc = workerUrl;

export type MarcaPdf = {
  folha: number;
  pin?: { x: number; y: number; tipo: string };
  tracos: number[][][];
};

export type ApontamentoNoPdf = MarcaPdf & {
  id: string;
  comentario: string;
  usuario: string;
  resposta: string | null;
  projeto: string;
  tipo: "erro" | "mudanca" | "desenho";
};

export function lerMarca(texto: string): { visivel: string; marca: MarcaPdf | null } {
  const corte = texto.indexOf("@@marcas@@");
  const bruto = corte >= 0 ? texto.slice(0, corte).trim() : texto.trim();
  let marca: MarcaPdf | null = null;
  if (corte >= 0) {
    try {
      const json = JSON.parse(texto.slice(corte + "@@marcas@@".length)) as MarcaPdf;
      if (json && typeof json.folha === "number") marca = json;
    } catch {
      marca = null;
    }
  }
  if (!marca) {
    const antigo = bruto.match(/Folha\s+(\d+)[\s\S]*?marca\s+(\d+)%\s*x\s+(\d+)%/i);
    if (antigo) {
      const tipo = bruto.includes("[ERRO]") ? "erro" : "mudanca";
      marca = {
        folha: Number(antigo[1]),
        pin: { x: Number(antigo[2]) / 100, y: Number(antigo[3]) / 100, tipo },
        tracos: [],
      };
    }
  }
  return { visivel: bruto, marca };
}

export function comentarioMarca(texto: string): string {
  const visivel = lerMarca(texto).visivel;
  const linhas = visivel
    .split("\n")
    .map((linha) => linha.trim())
    .filter((linha) => linha && !linha.startsWith("["));
  return linhas.join(" ") || "Sem comentário";
}

export function tipoMarca(texto: string): "erro" | "mudanca" | "desenho" {
  if (texto.startsWith("[ERRO]")) return "erro";
  if (texto.startsWith("[MUDANCA]") || texto.startsWith("[MUDANÇA]")) return "mudanca";
  return "desenho";
}

export function apontamentoNoPdf(item: {
  id: string;
  texto: string;
  usuario: string;
  resposta: string | null;
  quadro_codigo: string;
}): ApontamentoNoPdf | null {
  const marca = lerMarca(item.texto).marca;
  if (!marca) return null;
  return {
    ...marca,
    id: item.id,
    comentario: comentarioMarca(item.texto),
    usuario: item.usuario,
    resposta: item.resposta,
    projeto: item.quadro_codigo,
    tipo: tipoMarca(item.texto),
  };
}

export function VisorPdf({
  url,
  itens,
  folha,
  selecionadoId,
}: {
  url: string;
  itens: ApontamentoNoPdf[];
  folha?: number;
  selecionadoId?: string | null;
}) {
  const tela = useRef<HTMLCanvasElement>(null);
  const [pagina, setPagina] = useState(1);
  const [total, setTotal] = useState(1);
  const [falha, setFalha] = useState("");
  const [popupId, setPopupId] = useState<string | null>(null);

  useEffect(() => {
    setPagina(folha && folha > 0 ? folha : 1);
  }, [url, folha]);

  useEffect(() => {
    if (selecionadoId) setPopupId(selecionadoId);
  }, [selecionadoId]);

  useEffect(() => {
    const canvas = tela.current;
    if (!canvas) return;
    let cancelado = false;
    const tarefa = getDocument({ url });
    tarefa.promise
      .then(async (pdf) => {
        if (cancelado) return;
        setTotal(pdf.numPages);
        const indice = Math.min(Math.max(pagina, 1), pdf.numPages);
        const folha = await pdf.getPage(indice);
        if (cancelado) return;
        const vista = folha.getViewport({ scale: 1.5 });
        canvas.width = vista.width;
        canvas.height = vista.height;
        const ctx = canvas.getContext("2d");
        if (!ctx) return;
        await folha.render({ canvas, canvasContext: ctx, viewport: vista }).promise;
      })
      .catch((erro: Error) => {
        if (!cancelado) setFalha(erro.message || "PDF não abriu");
      });
    return () => {
      cancelado = true;
      tarefa.destroy();
    };
  }, [url, pagina]);

  const daFolha = itens.filter((item) => item.folha === pagina);
  const popup = itens.find((item) => item.id === popupId) ?? null;
  const rotulo = (tipo: ApontamentoNoPdf["tipo"]) =>
    tipo === "erro" ? "Erro" : tipo === "mudanca" ? "Mudança" : "Desenho";

  return (
    <div className="visor-pdf">
      <div className="visor-nav">
        <button type="button" className="secundario" disabled={pagina <= 1} onClick={() => setPagina((n) => n - 1)}>
          Folha anterior
        </button>
        <span>
          Folha {pagina} de {total}
        </span>
        <button type="button" className="secundario" disabled={pagina >= total} onClick={() => setPagina((n) => n + 1)}>
          Próxima folha
        </button>
      </div>
      {falha ? <p className="aviso">{falha}</p> : null}
      <div className="visor-folha">
        <canvas ref={tela} />
        <svg className="marcas" viewBox="0 0 1 1" preserveAspectRatio="none">
          {daFolha.flatMap((item) =>
            item.tracos.map((linha, j) => (
              <polyline
                key={`${item.id}-${j}`}
                className="clicavel"
                points={linha.map((p) => `${p[0]},${p[1]}`).join(" ")}
                fill="none"
                stroke="#e85d04"
                strokeWidth="0.012"
                strokeLinecap="round"
                strokeLinejoin="round"
                onClick={() => setPopupId(item.id)}
              />
            )),
          )}
          {daFolha.map((item) =>
            item.pin ? (
              <g key={`pin-${item.id}`} className="clicavel" onClick={() => setPopupId(item.id)}>
                <circle cx={item.pin.x} cy={item.pin.y} r="0.028" fill="transparent" />
                <circle cx={item.pin.x} cy={item.pin.y} r="0.018" fill={item.tipo === "erro" ? "#c2410c" : "#0f766e"} />
                <circle cx={item.pin.x} cy={item.pin.y} r="0.006" fill="#fff" />
              </g>
            ) : null,
          )}
        </svg>
      </div>
      {daFolha.length === 0 ? <p className="miudo">Nenhuma marcação do tablet nesta folha. Clique num ponto para ver o problema.</p> : (
        <p className="miudo">Clique no ponto laranja ou verde para abrir o problema.</p>
      )}
      {popup ? (
        <div className="popup-fundo" onClick={() => setPopupId(null)}>
          <article className="popup-problema" onClick={(evento) => evento.stopPropagation()}>
            <header>
              <span className={`tag tag-${popup.tipo}`}>{rotulo(popup.tipo)} · folha {popup.folha}</span>
              <button type="button" className="popup-fechar" onClick={() => setPopupId(null)} aria-label="Fechar">
                ×
              </button>
            </header>
            <p className="codigo-menor">{popup.projeto}</p>
            <h2>{popup.comentario}</h2>
            <p className="miudo">Enviado por {popup.usuario}</p>
            {popup.resposta ? <p className="ok">Resposta: {popup.resposta}</p> : <p className="aviso">Ainda sem resposta da engenharia.</p>}
          </article>
        </div>
      ) : null}
    </div>
  );
}

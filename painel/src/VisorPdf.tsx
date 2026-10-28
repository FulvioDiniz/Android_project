import { useEffect, useRef, useState } from "react";
import { getDocument, GlobalWorkerOptions } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

GlobalWorkerOptions.workerSrc = workerUrl;

export type MarcaPdf = {
  folha: number;
  pin?: { x: number; y: number; tipo: string };
  tracos: number[][][];
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

export function VisorPdf({ url, marcas }: { url: string; marcas: MarcaPdf[] }) {
  const tela = useRef<HTMLCanvasElement>(null);
  const [pagina, setPagina] = useState(1);
  const [total, setTotal] = useState(1);
  const [falha, setFalha] = useState("");

  useEffect(() => {
    setPagina(1);
  }, [url]);

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

  const daFolha = marcas.filter((item) => item.folha === pagina);

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
          {daFolha.flatMap((item, i) =>
            item.tracos.map((linha, j) => (
              <polyline
                key={`${i}-${j}`}
                points={linha.map((p) => `${p[0]},${p[1]}`).join(" ")}
                fill="none"
                stroke="#e85d04"
                strokeWidth="0.004"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            )),
          )}
          {daFolha.map((item, i) =>
            item.pin ? (
              <g key={`pin-${i}`}>
                <circle cx={item.pin.x} cy={item.pin.y} r="0.018" fill={item.pin.tipo === "erro" ? "#c2410c" : "#0f766e"} />
                <circle cx={item.pin.x} cy={item.pin.y} r="0.006" fill="#fff" />
              </g>
            ) : null,
          )}
        </svg>
      </div>
      {daFolha.length === 0 ? <p className="miudo">Nenhuma marcação do tablet nesta folha.</p> : null}
    </div>
  );
}

package com.fulvio.quadros

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class ModoDesenho { Ver, Lapis, Apagar, Erro, Mudanca }

data class PinoDesenho(
    val id: String,
    val pagina: Int,
    val x: Float,
    val y: Float,
    val tipo: ModoDesenho,
    val texto: String = "",
    val enviado: Boolean = false,
)

data class TracoDesenho(
    val id: String,
    val pagina: Int,
    val pontos: List<Offset>,
    val cor: Color = Laranja,
    val espessura: Float = 4f,
)

/** Distância normalizada (~3% da folha) para a borracha pegar o traço. */
private const val RAIO_APAGAR = 0.035f

private fun pontoPertoDoTraco(ponto: Offset, traco: TracoDesenho, raio: Float = RAIO_APAGAR): Boolean {
    val r2 = raio * raio
    for (p in traco.pontos) {
        val dx = p.x - ponto.x
        val dy = p.y - ponto.y
        if (dx * dx + dy * dy <= r2) return true
    }
    return false
}

private fun pinoSobToque(x: Float, y: Float, pinos: List<PinoDesenho>, raio: Float = 0.05f): PinoDesenho? {
    val r2 = raio * raio
    return pinos.minByOrNull { p ->
        val dx = p.x - x
        val dy = p.y - y
        dx * dx + dy * dy
    }?.takeIf { p ->
        val dx = p.x - x
        val dy = p.y - y
        dx * dx + dy * dy <= r2
    }
}

@Composable
fun TelaDesenho(
    quadro: QuadroLocal,
    pinos: List<PinoDesenho>,
    tracos: List<TracoDesenho>,
    aoAtualizarPinos: (List<PinoDesenho>) -> Unit,
    aoAtualizarTracos: (List<TracoDesenho>) -> Unit,
    aoEnviar: (PinoDesenho?, String, List<TracoDesenho>) -> Unit,
    aoFoto: (PinoDesenho, String) -> Unit,
    aoVoltar: () -> Unit,
    aoGarantirPdf: suspend (QuadroLocal) -> Unit,
) {
    val contexto = LocalContext.current
    var pagina by remember(quadro.id, quadro.arquivoId) { mutableIntStateOf(0) }
    var folhas by remember(quadro.id, quadro.arquivoId) { mutableIntStateOf(1) }
    var bitmap by remember(quadro.id, quadro.arquivoId) { mutableStateOf<Bitmap?>(null) }
    var falha by remember(quadro.id, quadro.arquivoId) { mutableStateOf("") }
    var modo by remember(quadro.id) { mutableStateOf(ModoDesenho.Ver) }
    var rascunho by remember(quadro.id) { mutableStateOf<PinoDesenho?>(null) }
    var texto by remember { mutableStateOf("") }
    var escala by remember(quadro.id) { mutableFloatStateOf(1f) }
    var deslocamento by remember(quadro.id) { mutableStateOf(Offset.Zero) }
    var painelAberto by remember { mutableStateOf(false) }

    fun renderizar(arquivo: File) {
        val descritor = ParcelFileDescriptor.open(arquivo, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descritor)
        folhas = renderer.pageCount.coerceAtLeast(1)
        val indice = pagina.coerceIn(0, folhas - 1)
        val folha = renderer.openPage(indice)
        val escalaBase = 2.2f
        val largura = (folha.width * escalaBase).toInt().coerceAtLeast(1)
        val altura = (folha.height * escalaBase).toInt().coerceAtLeast(1)
        val imagem = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val matriz = android.graphics.Matrix().apply { setScale(escalaBase, escalaBase) }
        folha.render(imagem, null, matriz, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        folha.close()
        renderer.close()
        descritor.close()
        val anterior = bitmap
        bitmap = imagem
        if (anterior != null && anterior !== imagem && !anterior.isRecycled) {
            anterior.recycle()
        }
    }

    DisposableEffect(quadro.id, quadro.arquivoId) {
        onDispose {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = null
        }
    }

    LaunchedEffect(quadro.id, quadro.arquivoId, quadro.arquivoEm, pagina) {
        val local = File(contexto.filesDir, "${quadro.arquivoId}.pdf")
        if (quadro.arquivoId.isBlank()) {
            val anterior = bitmap
            bitmap = null
            if (anterior != null && !anterior.isRecycled) anterior.recycle()
            falha = "Este projeto ainda não tem PDF na engenharia."
        } else {
            if (!local.exists() || local.length() == 0L) {
                falha = "Baixando o PDF…"
            }
            val erro = runCatching { aoGarantirPdf(quadro) }.exceptionOrNull()
            if (erro != null || !local.exists() || local.length() == 0L) {
                val anterior = bitmap
                bitmap = null
                if (anterior != null && !anterior.isRecycled) anterior.recycle()
                falha = erro?.message ?: "O PDF não chegou. Toque em voltar e abra de novo."
            } else {
                runCatching { renderizar(local) }
                    .onSuccess { falha = "" }
                    .onFailure { falha = it.message ?: "PDF não abriu" }
            }
        }
        escala = 1f
        deslocamento = Offset.Zero
    }

    fun entrarModo(novo: ModoDesenho) {
        modo = novo
        if (novo != ModoDesenho.Erro && novo != ModoDesenho.Mudanca) {
            rascunho = null
            texto = ""
        }
    }

    fun guardarTraco(pontos: List<Offset>) {
        if (pontos.size < 2) return
        aoAtualizarTracos(
            tracos + TracoDesenho(
                id = java.util.UUID.randomUUID().toString(),
                pagina = pagina,
                pontos = pontos,
                cor = Laranja,
            ),
        )
    }

    fun apagarTracos(ids: Set<String>) {
        if (ids.isEmpty()) return
        aoAtualizarTracos(tracos.filterNot { it.id in ids })
    }

    fun desfazerUltimoTraco() {
        val ultimo = tracos.lastOrNull { it.pagina == pagina } ?: return
        aoAtualizarTracos(tracos.filterNot { it.id == ultimo.id })
    }

    fun limparTracosFolha() {
        aoAtualizarTracos(tracos.filterNot { it.pagina == pagina })
    }

    fun removerPino(id: String) {
        if (rascunho?.id == id) {
            rascunho = null
            texto = ""
        }
        aoAtualizarPinos(pinos.filterNot { it.id == id })
    }

    fun editarPino(pino: PinoDesenho) {
        rascunho = pino.copy(enviado = false)
        texto = pino.texto
        modo = when (pino.tipo) {
            ModoDesenho.Mudanca -> ModoDesenho.Mudanca
            else -> ModoDesenho.Erro
        }
        painelAberto = true
        // Troca a marca antiga pela edição em andamento.
        aoAtualizarPinos(pinos.filterNot { it.id == pino.id })
    }

    val pinosPagina = pinos.filter { it.pagina == pagina }
    val pinosVisiveis = pinosPagina + listOfNotNull(rascunho?.takeIf { it.pagina == pagina })
    val tracosPagina = tracos.filter { it.pagina == pagina }

    LaunchedEffect(rascunho?.id) {
        if (rascunho != null) painelAberto = true
    }

    Box(modifier = Modifier.fillMaxSize().background(Fundo)) {
        AreaPdf(
            modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = if (painelAberto) 250.dp else 84.dp),
            bitmap = bitmap,
            falha = falha,
            modo = modo,
            escala = escala,
            deslocamento = deslocamento,
            pinos = pinosVisiveis,
            tracos = tracosPagina,
            aoZoom = { fator, pan ->
                escala = (escala * fator).coerceIn(1f, 6f)
                deslocamento += pan
            },
            aoToque = { x, y ->
                val sobPino = pinoSobToque(x, y, pinosVisiveis)
                when {
                    modo == ModoDesenho.Apagar && sobPino != null -> {
                        removerPino(sobPino.id)
                    }
                    (modo == ModoDesenho.Ver || modo == ModoDesenho.Erro || modo == ModoDesenho.Mudanca) &&
                        sobPino != null && sobPino.id != rascunho?.id -> {
                        // Modificar marca existente: reabre texto / posição.
                        editarPino(sobPino)
                    }
                    modo == ModoDesenho.Erro || modo == ModoDesenho.Mudanca -> {
                        rascunho = PinoDesenho(
                            id = rascunho?.id ?: java.util.UUID.randomUUID().toString(),
                            pagina = pagina,
                            x = x,
                            y = y,
                            tipo = modo,
                            texto = texto,
                        )
                        painelAberto = true
                    }
                }
            },
            aoCommitTraco = { pontos -> guardarTraco(pontos) },
            aoApagarTracos = { ids -> apagarTracos(ids) },
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pilula("Voltar", aoClicar = aoVoltar)
            Text(
                quadro.codigo,
                color = Preto,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Pilula("‹", enabled = pagina > 0) { pagina = (pagina - 1).coerceAtLeast(0) }
            Text(
                "${pagina + 1} / ${max(folhas, 1)}",
                color = Cinza,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Pilula("›", enabled = pagina + 1 < folhas) {
                if (pagina + 1 < folhas) pagina += 1
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .shadow(10.dp, Forma)
                .clip(Forma)
                .background(Branco)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Ver", modo == ModoDesenho.Ver) { entrarModo(ModoDesenho.Ver) }
                Chip("Desenhar", modo == ModoDesenho.Lapis) { entrarModo(ModoDesenho.Lapis) }
                Chip("Apagar", modo == ModoDesenho.Apagar, Cinza) { entrarModo(ModoDesenho.Apagar) }
                Chip("Erro", modo == ModoDesenho.Erro, Vermelho) { entrarModo(ModoDesenho.Erro) }
                Chip("Mudança", modo == ModoDesenho.Mudanca, Azul) { entrarModo(ModoDesenho.Mudanca) }
                Chip(if (painelAberto) "Fechar" else "Enviar", painelAberto) { painelAberto = !painelAberto }
            }
            if (tracosPagina.isNotEmpty() || modo == ModoDesenho.Apagar || modo == ModoDesenho.Lapis) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tracosPagina.isNotEmpty()) {
                        Chip("Desfazer", ativo = false, cor = Cinza) { desfazerUltimoTraco() }
                        Chip("Limpar folha", ativo = false, cor = Cinza) { limparTracosFolha() }
                    }
                    Text(
                        when (modo) {
                            ModoDesenho.Apagar -> "Passe no traço ou toque na marca"
                            ModoDesenho.Lapis -> "Desenhe · use Apagar para corrigir"
                            else -> "${tracosPagina.size} traço(s) na folha"
                        },
                        color = Cinza,
                        fontSize = 12.sp,
                    )
                }
            }
            if (painelAberto) {
                val alvo = rascunho
                if (alvo == null && tracosPagina.isEmpty()) {
                    Text(
                        "Desenhe, apague ou toque em Erro/Mudança. Dois dedos dão zoom. Toque numa marca para editar.",
                        color = Cinza,
                        fontSize = 14.sp,
                    )
                } else if (alvo == null) {
                    Text(
                        "${tracosPagina.size} traço(s) nesta folha. Envie para a engenharia ver no PDF.",
                        color = Preto,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Campo("Comentário", texto, linhas = 2, aoMudar = { texto = it })
                    Botao("Enviar marcação") {
                        val mensagem = texto.trim().ifBlank { "Marcação no desenho" }
                        aoEnviar(null, mensagem, tracosPagina)
                        texto = ""
                        modo = ModoDesenho.Ver
                        painelAberto = false
                    }
                } else {
                    Text(
                        if (alvo.tipo == ModoDesenho.Erro) "Erro nesta folha" else "Mudança nesta folha",
                        color = Preto,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Toque de novo no PDF para mover. Apagar marca remove.",
                        color = Cinza,
                        fontSize = 12.sp,
                    )
                    Campo("Texto para a engenharia", texto, linhas = 3, aoMudar = { texto = it })
                    Botao("Enviar", enabled = texto.trim().isNotBlank() || tracosPagina.isNotEmpty()) {
                        val mensagem = texto.trim().ifBlank { "Marcação no desenho" }
                        val pronto = alvo.copy(texto = mensagem, enviado = true)
                        aoAtualizarPinos(pinos + pronto)
                        aoEnviar(pronto, mensagem, tracosPagina)
                        rascunho = null
                        texto = ""
                        modo = ModoDesenho.Ver
                        painelAberto = false
                    }
                    Botao("Apagar marca", preenchido = false, cor = Vermelho) {
                        removerPino(alvo.id)
                        texto = ""
                        modo = ModoDesenho.Ver
                        painelAberto = false
                    }
                    Botao("Cancelar", preenchido = false, cor = Cinza) {
                        rascunho = null
                        texto = ""
                        modo = ModoDesenho.Ver
                        painelAberto = false
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaPdf(
    modifier: Modifier,
    bitmap: Bitmap?,
    falha: String,
    modo: ModoDesenho,
    escala: Float,
    deslocamento: Offset,
    pinos: List<PinoDesenho>,
    tracos: List<TracoDesenho>,
    aoZoom: (Float, Offset) -> Unit,
    aoToque: (Float, Float) -> Unit,
    aoCommitTraco: (List<Offset>) -> Unit,
    aoApagarTracos: (Set<String>) -> Unit = {},
) {
    val density = LocalDensity.current
    val zoomAtual = rememberUpdatedState(aoZoom)
    val toqueAtual = rememberUpdatedState(aoToque)
    val commitAtual = rememberUpdatedState(aoCommitTraco)
    val apagarAtual = rememberUpdatedState(aoApagarTracos)
    val modoAtual = rememberUpdatedState(modo)
    val tracosAtual = rememberUpdatedState(tracos)
    var vivo by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        modifier = modifier
            .shadow(12.dp, Folha)
            .clip(Folha)
            .background(Branco)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && falha.isBlank()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
                val imgH = bitmap.height.toFloat().coerceAtLeast(1f)
                val fit = min(boxW / imgW, boxH / imgH)
                val desenhoW = imgW * fit
                val desenhoH = imgH * fit
                val desenhoWDp = with(density) { desenhoW.toDp() }
                val desenhoHDp = with(density) { desenhoH.toDp() }
                Box(
                    modifier = Modifier
                        .width(desenhoWDp)
                        .height(desenhoHDp)
                        .graphicsLayer(
                            scaleX = escala,
                            scaleY = escala,
                            translationX = deslocamento.x,
                            translationY = deslocamento.y,
                        )
                        .pointerInput(desenhoW, desenhoH) {
                            fun normalizar(x: Float, y: Float) = Offset(
                                (x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                                (y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                            )
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val modoAgora = modoAtual.value
                                val buffer = mutableListOf(normalizar(down.position.x, down.position.y))
                                if (modoAgora == ModoDesenho.Lapis) vivo = buffer.toList()
                                var doisDedos = false
                                var moveu = false
                                val apagados = linkedSetOf<String>()
                                fun apagarNoPonto(ponto: Offset) {
                                    val hit = tracosAtual.value.filter { pontoPertoDoTraco(ponto, it) }.map { it.id }
                                    if (hit.isNotEmpty()) {
                                        apagados.addAll(hit)
                                        apagarAtual.value(apagados.toSet())
                                    }
                                }
                                if (modoAgora == ModoDesenho.Apagar) apagarNoPonto(buffer.first())
                                while (true) {
                                    val evento = awaitPointerEvent()
                                    val pressionados = evento.changes.filter { it.pressed }
                                    if (pressionados.size >= 2) {
                                        doisDedos = true
                                        zoomAtual.value(evento.calculateZoom(), evento.calculatePan())
                                    } else if (pressionados.size == 1 && modoAgora == ModoDesenho.Lapis && !doisDedos) {
                                        val ponto = normalizar(pressionados[0].position.x, pressionados[0].position.y)
                                        if (ponto != buffer.last()) {
                                            buffer.add(ponto)
                                            vivo = buffer.toList()
                                            moveu = true
                                        }
                                    } else if (pressionados.size == 1 && modoAgora == ModoDesenho.Apagar && !doisDedos) {
                                        val ponto = normalizar(pressionados[0].position.x, pressionados[0].position.y)
                                        buffer.add(ponto)
                                        apagarNoPonto(ponto)
                                        moveu = true
                                    } else if (pressionados.size == 1 && modoAgora == ModoDesenho.Ver) {
                                        val pan = evento.calculatePan()
                                        // Ignora tremor do toque para ainda conseguir editar a marca.
                                        if (pan.getDistance() > 6f) {
                                            zoomAtual.value(evento.calculateZoom(), pan)
                                            moveu = true
                                        }
                                    }
                                    evento.changes.forEach { it.consume() }
                                    if (pressionados.isEmpty()) break
                                }
                                vivo = emptyList()
                                if (modoAgora == ModoDesenho.Lapis && !doisDedos && buffer.size >= 2) {
                                    commitAtual.value(buffer.toList())
                                } else if (
                                    (modoAgora == ModoDesenho.Apagar || modoAgora == ModoDesenho.Ver) &&
                                    !doisDedos &&
                                    !moveu
                                ) {
                                    val ponto = buffer.first()
                                    toqueAtual.value(ponto.x, ponto.y)
                                } else if (
                                    modoAgora != ModoDesenho.Ver &&
                                    modoAgora != ModoDesenho.Lapis &&
                                    modoAgora != ModoDesenho.Apagar &&
                                    !doisDedos &&
                                    !moveu
                                ) {
                                    val ponto = buffer.first()
                                    toqueAtual.value(ponto.x, ponto.y)
                                }
                            }
                        },
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Folha do desenho",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        fun desenharTraco(pontos: List<Offset>, cor: Color, espessura: Float) {
                            if (pontos.size < 2) return
                            val path = Path()
                            path.moveTo(pontos[0].x * size.width, pontos[0].y * size.height)
                            for (i in 1 until pontos.size) {
                                path.lineTo(pontos[i].x * size.width, pontos[i].y * size.height)
                            }
                            drawPath(
                                path = path,
                                color = cor,
                                style = Stroke(
                                    width = espessura.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                        tracos.forEach { desenharTraco(it.pontos, it.cor, it.espessura) }
                        desenharTraco(vivo, Laranja, 4f)
                        pinos.forEach { pino ->
                            val cor = when (pino.tipo) {
                                ModoDesenho.Erro -> Vermelho
                                ModoDesenho.Mudanca -> Azul
                                ModoDesenho.Lapis -> Laranja
                                ModoDesenho.Apagar -> Cinza
                                ModoDesenho.Ver -> Laranja
                            }
                            val centro = Offset(size.width * pino.x, size.height * pino.y)
                            drawCircle(color = cor.copy(alpha = 0.22f), radius = 30f, center = centro)
                            drawCircle(color = cor, radius = 15f, center = centro)
                            drawCircle(color = Branco, radius = 5f, center = centro)
                        }
                    }
                }
            }
            Text(
                when (modo) {
                    ModoDesenho.Ver -> "Pinça para zoom · toque numa marca para editar"
                    ModoDesenho.Lapis -> "Arraste o dedo para desenhar em laranja"
                    ModoDesenho.Apagar -> "Passe no traço para apagar · toque na marca"
                    else -> "Toque no ponto do desenho para marcar"
                },
                color = Preto,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(FormaPequena)
                    .background(LaranjaSuave)
                    .border(1.dp, Borda, FormaPequena)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LaranjaSuave)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    falha.ifBlank { "Sem PDF" },
                    color = Preto,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Volte em Projetos e toque em Atualizar para puxar o PDF da engenharia.",
                    color = Cinza,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun PainelApontamento(
    quadro: QuadroLocal,
    modo: ModoDesenho,
    aoModo: (ModoDesenho) -> Unit,
    pagina: Int,
    folhas: Int,
    aoPagina: (Int) -> Unit,
    rascunho: PinoDesenho?,
    texto: String,
    aoTexto: (String) -> Unit,
    pinos: List<PinoDesenho>,
    tracosNaFolha: Int,
    aoLimparTracos: () -> Unit,
    aoCancelar: () -> Unit,
    aoEnviar: () -> Unit,
    aoFoto: () -> Unit,
    aoVoltar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(Forma)
            .background(Branco)
            .border(1.5.dp, Borda, Forma)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(quadro.codigo, color = Preto, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SeloTexto(
                when {
                    quadro.vigenteCodigo.trim().matches(Regex("^[A-Za-z0-9]{1,4}$")) ->
                        "Rev ${quadro.vigenteCodigo.trim().uppercase()}"
                    else -> "PDF da pasta"
                },
                quadro.arquivoId.isNotBlank(),
            )
        }
        Text("Folha ${pagina + 1} de ${max(folhas, 1)}", color = Cinza, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Botao("Anterior", preenchido = false, enabled = pagina > 0) { aoPagina(pagina - 1) } }
            Box(Modifier.weight(1f)) { Botao("Próxima", preenchido = false, enabled = pagina + 1 < folhas) { aoPagina(pagina + 1) } }
        }
        Text("Ferramentas", color = Cinza, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Ver", modo == ModoDesenho.Ver, Laranja) { aoModo(ModoDesenho.Ver) }
            Chip("Desenhar", modo == ModoDesenho.Lapis, Laranja) { aoModo(ModoDesenho.Lapis) }
            Chip("Apagar", modo == ModoDesenho.Apagar, Cinza) { aoModo(ModoDesenho.Apagar) }
            Chip("Erro", modo == ModoDesenho.Erro, Vermelho) { aoModo(ModoDesenho.Erro) }
            Chip("Mudança", modo == ModoDesenho.Mudanca, Azul) { aoModo(ModoDesenho.Mudanca) }
        }
        if (tracosNaFolha > 0 || modo == ModoDesenho.Lapis) {
            Cartao {
                Text(
                    if (tracosNaFolha > 0) "$tracosNaFolha traço(s) nesta folha" else "Desenhe com o dedo sobre o PDF",
                    color = Preto,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (tracosNaFolha > 0) {
                    Botao("Apagar desenhos desta folha", preenchido = false, cor = Cinza, aoClicar = aoLimparTracos)
                }
            }
        }
        if (rascunho != null) {
            Cartao {
                Text(
                    if (rascunho.tipo == ModoDesenho.Erro) "Apontar erro" else "Pedir mudança",
                    color = Preto,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Folha ${rascunho.pagina + 1} · ${(rascunho.x * 100).toInt()}%, ${(rascunho.y * 100).toInt()}%",
                    color = Cinza,
                    fontSize = 13.sp,
                )
                Campo("Descreva para a engenharia", texto, linhas = 4, aoMudar = aoTexto)
                Botao("Enviar à engenharia", enabled = texto.trim().isNotBlank(), aoClicar = aoEnviar)
                Botao("Enviar com foto", enabled = texto.trim().isNotBlank(), preenchido = false, aoClicar = aoFoto)
                Botao("Cancelar marca", preenchido = false, cor = Cinza, aoClicar = aoCancelar)
            }
        } else {
            Cartao {
                Text(
                    when (modo) {
                        ModoDesenho.Ver -> "Navegue no desenho"
                        ModoDesenho.Lapis -> "Modo desenho livre"
                        ModoDesenho.Apagar -> "Apague traços e marcas"
                        ModoDesenho.Erro -> "Toque no ponto do erro"
                        ModoDesenho.Mudanca -> "Toque onde precisa mudar"
                    },
                    color = Preto,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when (modo) {
                        ModoDesenho.Ver -> "Use Desenhar para rabiscar, ou Erro/Mudança para mandar à engenharia."
                        ModoDesenho.Lapis -> "O traço fica no tablet para marcar o que viu na montagem."
                        ModoDesenho.Apagar -> "Passe o dedo nos traços ou toque nas marcas para remover."
                        else -> "O apontamento sobe para a engenharia avaliar. Não altera a vigente sozinho."
                    },
                    color = Cinza,
                    fontSize = 14.sp,
                )
            }
        }
        if (pinos.isNotEmpty()) {
            Text("Marcas nesta folha", color = Cinza, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            pinos.filter { it.enviado || it.texto.isNotBlank() }.forEach { pino ->
                Cartao {
                    Text(
                        if (pino.tipo == ModoDesenho.Erro) "Erro" else "Mudança",
                        color = if (pino.tipo == ModoDesenho.Erro) Vermelho else Azul,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(pino.texto.ifBlank { "Sem texto" }, color = Preto, fontSize = 15.sp)
                }
            }
        }
        Botao("Voltar aos projetos", preenchido = false, aoClicar = aoVoltar)
    }
}

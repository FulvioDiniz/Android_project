package com.fulvio.quadros

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.gestures.TransformableState
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

enum class ModoDesenho { Ver, Lapis, Erro, Mudanca }

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

@Composable
fun TelaDesenho(
    quadro: QuadroLocal,
    pinos: List<PinoDesenho>,
    tracos: List<TracoDesenho>,
    aoAtualizarPinos: (List<PinoDesenho>) -> Unit,
    aoAtualizarTracos: (List<TracoDesenho>) -> Unit,
    aoEnviar: (PinoDesenho, String) -> Unit,
    aoFoto: (PinoDesenho, String) -> Unit,
    aoVoltar: () -> Unit,
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
    var tracoAtual by remember { mutableStateOf<List<Offset>>(emptyList()) }

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

    LaunchedEffect(quadro.id, quadro.arquivoId, pagina) {
        val local = File(contexto.filesDir, "${quadro.arquivoId}.pdf")
        if (quadro.arquivoId.isNotBlank() && local.exists() && local.length() > 0L) {
            runCatching { renderizar(local) }
                .onSuccess { falha = "" }
                .onFailure { falha = it.message ?: "PDF não abriu" }
        } else {
            val anterior = bitmap
            bitmap = null
            if (anterior != null && !anterior.isRecycled) anterior.recycle()
            falha = if (quadro.arquivoId.isBlank()) {
                "Este projeto ainda não tem PDF na engenharia."
            } else {
                "Baixando o PDF… atualize a lista se demorar."
            }
        }
        escala = 1f
        deslocamento = Offset.Zero
        tracoAtual = emptyList()
    }

    fun entrarModo(novo: ModoDesenho) {
        modo = novo
        if (novo != ModoDesenho.Ver) {
            escala = 1f
            deslocamento = Offset.Zero
        }
        if (novo != ModoDesenho.Erro && novo != ModoDesenho.Mudanca) {
            rascunho = null
            texto = ""
        }
        tracoAtual = emptyList()
    }

    fun fecharTraco() {
        val pontos = tracoAtual
        tracoAtual = emptyList()
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

    val transform = rememberTransformableState { zoom, pan, _ ->
        if (modo == ModoDesenho.Ver) {
            val nova = (escala * zoom).coerceIn(1f, 5f)
            escala = nova
            deslocamento += pan
        }
    }

    val pinosPagina = pinos.filter { it.pagina == pagina }
    val pinosVisiveis = pinosPagina + listOfNotNull(rascunho?.takeIf { it.pagina == pagina })
    val tracosPagina = tracos.filter { it.pagina == pagina }

    var painelAberto by remember { mutableStateOf(false) }
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
            transform = transform,
            pinos = pinosVisiveis,
            tracos = tracosPagina,
            tracoAtual = tracoAtual,
            aoToque = { x, y ->
                if (modo == ModoDesenho.Erro || modo == ModoDesenho.Mudanca) {
                    rascunho = PinoDesenho(
                        id = java.util.UUID.randomUUID().toString(),
                        pagina = pagina,
                        x = x,
                        y = y,
                        tipo = modo,
                    )
                    texto = ""
                    painelAberto = true
                }
            },
            aoArrastar = { ponto ->
                if (modo == ModoDesenho.Lapis) tracoAtual = tracoAtual + ponto
            },
            aoSoltar = {
                if (modo == ModoDesenho.Lapis) fecharTraco()
            },
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
                Chip("Erro", modo == ModoDesenho.Erro, Vermelho) { entrarModo(ModoDesenho.Erro) }
                Chip("Mudança", modo == ModoDesenho.Mudanca, Azul) { entrarModo(ModoDesenho.Mudanca) }
                Chip(if (painelAberto) "Fechar" else "Enviar", painelAberto) { painelAberto = !painelAberto }
            }
            if (painelAberto) {
                val alvo = rascunho
                if (alvo == null) {
                    Text(
                        "Escolha Erro ou Mudança e toque no desenho.",
                        color = Cinza,
                        fontSize = 14.sp,
                    )
                } else {
                    Text(
                        if (alvo.tipo == ModoDesenho.Erro) "Erro nesta folha" else "Mudança nesta folha",
                        color = Preto,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Campo("Texto para a engenharia", texto, linhas = 3, aoMudar = { texto = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            Botao("Enviar", enabled = texto.trim().isNotBlank()) {
                                val mensagem = texto.trim()
                                val pronto = alvo.copy(texto = mensagem, enviado = true)
                                aoAtualizarPinos(pinos + pronto)
                                aoEnviar(pronto, mensagem)
                                rascunho = null
                                texto = ""
                                modo = ModoDesenho.Ver
                                painelAberto = false
                            }
                        }
                        Box(Modifier.weight(1f)) {
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
    transform: TransformableState,
    pinos: List<PinoDesenho>,
    tracos: List<TracoDesenho>,
    tracoAtual: List<Offset>,
    aoToque: (Float, Float) -> Unit,
    aoArrastar: (Offset) -> Unit,
    aoSoltar: () -> Unit,
) {
    val density = LocalDensity.current
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
                        .then(
                            when (modo) {
                                ModoDesenho.Ver -> Modifier.transformable(state = transform)
                                ModoDesenho.Lapis -> Modifier.pointerInput(modo, desenhoW, desenhoH) {
                                    detectDragGestures(
                                        onDragEnd = { aoSoltar() },
                                        onDragCancel = { aoSoltar() },
                                    ) { change, _ ->
                                        change.consume()
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        aoArrastar(
                                            Offset(
                                                (change.position.x / w).coerceIn(0f, 1f),
                                                (change.position.y / h).coerceIn(0f, 1f),
                                            ),
                                        )
                                    }
                                }
                                ModoDesenho.Erro, ModoDesenho.Mudanca -> Modifier.pointerInput(modo, desenhoW, desenhoH) {
                                    detectTapGestures { offset ->
                                        aoToque(
                                            (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                                            (offset.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            },
                        ),
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
                        desenharTraco(tracoAtual, Laranja, 4f)
                        pinos.forEach { pino ->
                            val cor = when (pino.tipo) {
                                ModoDesenho.Erro -> Vermelho
                                ModoDesenho.Mudanca -> Azul
                                ModoDesenho.Lapis -> Laranja
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
                    ModoDesenho.Ver -> "Pinça para zoom · arraste para mover"
                    ModoDesenho.Lapis -> "Arraste o dedo para desenhar em laranja"
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
                if (quadro.vigenteCodigo.isNotBlank()) "Rev ${quadro.vigenteCodigo}" else "PDF da pasta",
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

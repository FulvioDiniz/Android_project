package com.fulvio.quadros

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

enum class ModoDesenho { Ver, Erro, Mudanca }

data class PinoDesenho(
    val id: String,
    val pagina: Int,
    val x: Float,
    val y: Float,
    val tipo: ModoDesenho,
    val texto: String = "",
    val enviado: Boolean = false,
)

@Composable
fun TelaDesenho(
    quadro: QuadroLocal,
    pinos: List<PinoDesenho>,
    aoAtualizarPinos: (List<PinoDesenho>) -> Unit,
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

    fun renderizar(arquivo: File) {
        val descritor = ParcelFileDescriptor.open(arquivo, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descritor)
        folhas = renderer.pageCount.coerceAtLeast(1)
        val indice = pagina.coerceIn(0, folhas - 1)
        val folha = renderer.openPage(indice)
        val escalaBase = 2f
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
            falha = "O PDF ainda não está neste aparelho. Atualize a lista."
        }
        escala = 1f
        deslocamento = Offset.Zero
    }

    fun entrarModo(novo: ModoDesenho) {
        modo = novo
        // Marca só com zoom 1:1 — evita pino longe do toque.
        if (novo != ModoDesenho.Ver) {
            escala = 1f
            deslocamento = Offset.Zero
        } else {
            rascunho = null
            texto = ""
        }
    }

    val transform = rememberTransformableState { zoom, pan, _ ->
        if (modo == ModoDesenho.Ver) {
            val nova = (escala * zoom).coerceIn(1f, 4f)
            escala = nova
            deslocamento += pan
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val largo = maxWidth >= 900.dp
        val pinosPagina = pinos.filter { it.pagina == pagina }
        val pinosVisiveis = pinosPagina + listOfNotNull(rascunho?.takeIf { it.pagina == pagina })

        if (largo) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AreaPdf(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bitmap = bitmap,
                    falha = falha,
                    modo = modo,
                    escala = escala,
                    deslocamento = deslocamento,
                    transform = transform,
                    pinos = pinosVisiveis,
                    aoToque = { x, y ->
                        if (modo != ModoDesenho.Ver) {
                            rascunho = PinoDesenho(
                                id = java.util.UUID.randomUUID().toString(),
                                pagina = pagina,
                                x = x,
                                y = y,
                                tipo = modo,
                            )
                            texto = ""
                        }
                    },
                )
                Box(modifier = Modifier.width(380.dp).fillMaxHeight()) {
                    PainelApontamento(
                        quadro = quadro,
                        modo = modo,
                        aoModo = { entrarModo(it) },
                        pagina = pagina,
                        folhas = folhas,
                        aoPagina = { pagina = it },
                        rascunho = rascunho,
                        texto = texto,
                        aoTexto = { texto = it },
                        pinos = pinosPagina,
                        aoCancelar = {
                            rascunho = null
                            texto = ""
                            modo = ModoDesenho.Ver
                        },
                        aoEnviar = {
                            val alvo = rascunho
                            val mensagem = texto.trim()
                            if (alvo != null && mensagem.isNotBlank()) {
                                val pronto = alvo.copy(texto = mensagem, enviado = true)
                                aoAtualizarPinos(pinos + pronto)
                                aoEnviar(pronto, mensagem)
                                rascunho = null
                                texto = ""
                                modo = ModoDesenho.Ver
                            }
                        },
                        aoFoto = {
                            val alvo = rascunho
                            val mensagem = texto.trim()
                            if (alvo != null && mensagem.isNotBlank()) {
                                val pronto = alvo.copy(texto = mensagem, enviado = true)
                                aoAtualizarPinos(pinos + pronto)
                                aoFoto(pronto, mensagem)
                                rascunho = null
                                texto = ""
                                modo = ModoDesenho.Ver
                            }
                        },
                        aoVoltar = aoVoltar,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AreaPdf(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    bitmap = bitmap,
                    falha = falha,
                    modo = modo,
                    escala = escala,
                    deslocamento = deslocamento,
                    transform = transform,
                    pinos = pinosVisiveis,
                    aoToque = { x, y ->
                        if (modo != ModoDesenho.Ver) {
                            rascunho = PinoDesenho(
                                id = java.util.UUID.randomUUID().toString(),
                                pagina = pagina,
                                x = x,
                                y = y,
                                tipo = modo,
                            )
                            texto = ""
                        }
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    PainelApontamento(
                        quadro = quadro,
                        modo = modo,
                        aoModo = { entrarModo(it) },
                        pagina = pagina,
                        folhas = folhas,
                        aoPagina = { pagina = it },
                        rascunho = rascunho,
                        texto = texto,
                        aoTexto = { texto = it },
                        pinos = pinosPagina,
                        aoCancelar = {
                            rascunho = null
                            texto = ""
                            modo = ModoDesenho.Ver
                        },
                        aoEnviar = {
                            val alvo = rascunho
                            val mensagem = texto.trim()
                            if (alvo != null && mensagem.isNotBlank()) {
                                val pronto = alvo.copy(texto = mensagem, enviado = true)
                                aoAtualizarPinos(pinos + pronto)
                                aoEnviar(pronto, mensagem)
                                rascunho = null
                                texto = ""
                                modo = ModoDesenho.Ver
                            }
                        },
                        aoFoto = {
                            val alvo = rascunho
                            val mensagem = texto.trim()
                            if (alvo != null && mensagem.isNotBlank()) {
                                val pronto = alvo.copy(texto = mensagem, enviado = true)
                                aoAtualizarPinos(pinos + pronto)
                                aoFoto(pronto, mensagem)
                                rascunho = null
                                texto = ""
                                modo = ModoDesenho.Ver
                            }
                        },
                        aoVoltar = aoVoltar,
                    )
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
    aoToque: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .clip(Forma)
            .background(Color(0xFF2A2E36))
            .border(1.dp, Borda, Forma)
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
                            if (modo == ModoDesenho.Ver) {
                                Modifier.transformable(state = transform)
                            } else {
                                Modifier.pointerInput(modo, desenhoW, desenhoH) {
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
                        pinos.forEach { pino ->
                            val cor = when (pino.tipo) {
                                ModoDesenho.Erro -> Vermelho
                                ModoDesenho.Mudanca -> Azul
                                ModoDesenho.Ver -> Amarelo
                            }
                            val centro = Offset(size.width * pino.x, size.height * pino.y)
                            drawCircle(color = cor.copy(alpha = 0.25f), radius = 28f, center = centro)
                            drawCircle(color = cor, radius = 14f, center = centro)
                            drawCircle(color = Branco, radius = 5f, center = centro)
                        }
                    }
                }
            }
            Text(
                if (modo == ModoDesenho.Ver) {
                    "Pinça para zoom · arraste para mover"
                } else {
                    "Zoom volta a 100% · toque no ponto do desenho"
                },
                color = Branco.copy(alpha = 0.85f),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(FormaPequena)
                    .background(Preto.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(falha.ifBlank { "Sem PDF" }, color = Branco, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Quando o arquivo chegar, o desenho abre aqui.", color = Branco.copy(alpha = 0.8f), fontSize = 15.sp)
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
            .border(1.dp, Borda, Forma)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(quadro.codigo, color = Preto, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SeloTexto(
                if (quadro.vigenteCodigo.isNotBlank()) "Rev ${quadro.vigenteCodigo}" else "Sem vigente",
                quadro.vigenteCodigo.isNotBlank(),
            )
        }
        Text("Folha ${pagina + 1} de ${max(folhas, 1)}", color = Cinza, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Botao("Anterior", preenchido = false, enabled = pagina > 0) { aoPagina(pagina - 1) } }
            Box(Modifier.weight(1f)) { Botao("Próxima", preenchido = false, enabled = pagina + 1 < folhas) { aoPagina(pagina + 1) } }
        }
        Text("Modo", color = Cinza, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Ver", modo == ModoDesenho.Ver, Amarelo) { aoModo(ModoDesenho.Ver) }
            Chip("Erro", modo == ModoDesenho.Erro, Vermelho) { aoModo(ModoDesenho.Erro) }
            Chip("Mudança", modo == ModoDesenho.Mudanca, Azul) { aoModo(ModoDesenho.Mudanca) }
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
                    "Folha ${rascunho.pagina + 1} · marca em ${(rascunho.x * 100).toInt()}%, ${(rascunho.y * 100).toInt()}%",
                    color = Cinza,
                    fontSize = 13.sp,
                )
                Campo("Descreva para a engenharia", texto, linhas = 4, aoMudar = aoTexto)
                Botao("Enviar à engenharia", enabled = texto.trim().isNotBlank(), cor = Preto, aoClicar = aoEnviar)
                Botao("Enviar com foto", enabled = texto.trim().isNotBlank(), preenchido = false, aoClicar = aoFoto)
                Botao("Cancelar marca", preenchido = false, cor = Cinza, aoClicar = aoCancelar)
            }
        } else {
            Cartao {
                Text(
                    when (modo) {
                        ModoDesenho.Ver -> "Navegue no desenho"
                        ModoDesenho.Erro -> "Toque onde está o erro"
                        ModoDesenho.Mudanca -> "Toque onde precisa mudar"
                    },
                    color = Preto,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "O apontamento sobe para a engenharia avaliar. Não altera a vigente sozinho.",
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(pino.texto.ifBlank { "Sem texto" }, color = Preto, fontSize = 15.sp)
                }
            }
        }
        Botao("Voltar aos projetos", preenchido = false, aoClicar = aoVoltar)
    }
}

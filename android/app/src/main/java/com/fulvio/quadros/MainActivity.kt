package com.fulvio.quadros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = banco(this).dao()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Tela(dao)
                }
            }
        }
    }
}

@Composable
private fun Tela(dao: QuadroDao) {
    val escopo = rememberCoroutineScope()
    val contexto = LocalContext.current
    var servidor by remember { mutableStateOf(dao.ler("servidor") ?: "http://192.168.0.10:8000") }
    var login by remember { mutableStateOf("carlos.mota") }
    var senha by remember { mutableStateOf("") }
    var nome by remember { mutableStateOf(dao.ler("nome") ?: "") }
    var token by remember { mutableStateOf(dao.ler("token") ?: "") }
    var erro by remember { mutableStateOf("") }
    var enlace by remember {
        mutableStateOf(if (dao.ler("token").isNullOrBlank()) "Sem enlace" else "Sincronizando")
    }
    var sync by remember { mutableStateOf(dao.ler("sync") ?: "nunca") }
    var alerta by remember { mutableStateOf(dao.ler("alerta") ?: "") }
    var aberto by remember { mutableStateOf<QuadroLocal?>(null) }
    var quadros by remember { mutableStateOf(dao.listar()) }
    var fila by remember { mutableStateOf(dao.listarFila()) }
    var versao by remember { mutableIntStateOf(0) }

    fun recarregar() {
        quadros = dao.listar()
        fila = dao.listarFila()
        sync = dao.ler("sync") ?: sync
        alerta = dao.ler("alerta") ?: ""
        versao += 1
    }

    fun ciclo(base: String, acesso: String) {
        escopo.launch {
            val pasta = contexto.filesDir
            withContext(Dispatchers.IO) { drenarFila(dao) }
            try {
                val lista = withContext(Dispatchers.IO) { baixarQuadros(base, acesso) }
                withContext(Dispatchers.IO) {
                    dao.limpar()
                    dao.salvar(lista)
                    dao.gravar(Meta("servidor", base))
                    dao.gravar(Meta("token", acesso))
                    val agora = java.time.Instant.now().toString()
                    dao.gravar(Meta("sync", agora))
                    lista.forEach { quadro ->
                        if (quadro.arquivoId.isBlank()) return@forEach
                        runCatching { copiarPdfSeFaltar(dao, base, acesso, quadro.arquivoId, pasta) }
                    }
                    val avisos = runCatching {
                        notificacoes(base, acesso, dao.ler("marco") ?: "")
                    }.getOrDefault(org.json.JSONArray())
                    if (avisos.length() > 0) {
                        dao.gravar(Meta("marco", avisos.getJSONObject(avisos.length() - 1).getString("criada_em")))
                        dao.gravar(Meta("alerta", "Revisão nova. Confira a vigente antes de seguir."))
                    }
                }
                enlace = "No ar"
                erro = ""
            } catch (falha: Exception) {
                enlace = "Sem enlace"
                erro = falha.message ?: "Sem enlace"
            }
            recarregar()
            agendarFila(contexto)
        }
    }

    fun enfileirar(item: FilaLocal) {
        dao.enfileirar(item)
        recarregar()
        agendarFila(contexto)
        if (token.isNotBlank()) ciclo(servidor, token)
    }

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            agendarFila(contexto)
            ciclo(servidor, token)
        }
    }

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quadros", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (token.isNotBlank() && enlace == "Sem enlace") {
            Text(
                "Offline. Última sync $sync. A cópia já baixada abre neste aparelho.",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
        if (token.isBlank()) {
            OutlinedTextField(servidor, { servidor = it }, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(login, { login = it }, label = { Text("Login") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(senha, { senha = it }, label = { Text("Senha") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                escopo.launch {
                    try {
                        val sessao = withContext(Dispatchers.IO) { entrar(servidor, login, senha) }
                        withContext(Dispatchers.IO) {
                            dao.gravar(Meta("nome", sessao.nome))
                            dao.gravar(Meta("servidor", servidor))
                            dao.gravar(Meta("token", sessao.token))
                        }
                        token = sessao.token
                        nome = sessao.nome
                    } catch (falha: Exception) {
                        erro = falha.message ?: "Não entrou"
                    }
                }
            }) { Text("Entrar", fontSize = 18.sp) }
        } else {
            Text("$nome · $enlace · última sync $sync", fontWeight = FontWeight.SemiBold)
            if (alerta.isNotBlank()) {
                Text(alerta, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ciclo(servidor, token) }) { Text("Atualizar") }
                Button(onClick = {
                    token = ""
                    nome = ""
                    escopo.launch(Dispatchers.IO) { dao.gravar(Meta("token", "")) }
                }) { Text("Sair") }
            }
        }
        if (erro.isNotBlank()) {
            Text(erro, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quadros, key = { it.id }) { quadro ->
                QuadroCard(
                    quadro = quadro,
                    dao = dao,
                    fila = fila,
                    versao = versao,
                    offline = enlace == "Sem enlace",
                    aoEnfileirar = { enfileirar(it) },
                    aoAbrir = { aberto = quadro },
                )
            }
        }
        aberto?.let { quadro ->
            Visualizador(quadro, servidor, token, enlace == "Sem enlace") { aberto = null }
        }
    }
}

@Composable
private fun QuadroCard(
    quadro: QuadroLocal,
    dao: QuadroDao,
    fila: List<FilaLocal>,
    versao: Int,
    offline: Boolean,
    aoEnfileirar: (FilaLocal) -> Unit,
    aoAbrir: () -> Unit,
) {
    var texto by remember(quadro.id) { mutableStateOf("") }
    val confirmada = remember(quadro.id, quadro.vigenteId, versao) { dao.ler("confirmada:${quadro.id}") }
    val confirmadaEm = remember(quadro.id, versao) { dao.ler("confirmada_em:${quadro.id}") ?: "nunca" }
    val semConfirmacao = quadro.vigenteId.isNotBlank() && confirmada != quadro.vigenteId
    val etapaAceita = remember(quadro.id, versao) { dao.ler("etapa:${quadro.id}:fiacao") == "concluida" }
    val etapas = fila.filter { it.quadroId == quadro.id && it.tipo == "etapa_concluir" && it.etapaCodigo == "fiacao" }
    val rotulo = rotuloEtapa(
        aceitaPelaApi = etapaAceita,
        pendente = etapas.any { it.estado == "na_fila" },
        ultima = etapas.lastOrNull(),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(quadro.codigo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            when {
                quadro.vigenteId.isBlank() -> "Sem vigente"
                offline -> "Cópia local Rev ${quadro.vigenteCodigo.ifBlank { "baixada" }}"
                quadro.vigenteCodigo.isBlank() -> "Vigente no servidor"
                else -> "Vigente Rev ${quadro.vigenteCodigo}"
            },
            fontWeight = FontWeight.SemiBold,
        )
        if (quadro.revisaoTipo.isNotBlank() && !offline) {
            Text("Tipo ${quadro.revisaoTipo}")
        }
        Text(quadro.titulo)
        Text("${quadro.clienteNome} · ${quadro.ordemNumero}")
        Text("Última confirmada $confirmadaEm")
        if (fila.any { it.quadroId == quadro.id && it.tipo == "confirmacao_leitura" && it.estado == "na_fila" }) {
            Text("Confirmação na fila")
        }
        if (semConfirmacao) {
            Text("Vigência não confirmada", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Text("Fiação: $rotulo", fontWeight = FontWeight.SemiBold)
        Button(onClick = aoAbrir, enabled = quadro.arquivoId.isNotBlank() || quadro.vigenteId.isNotBlank()) {
            Text(if (offline) "Abrir cópia local" else "Abrir PDF")
        }
        if (quadro.vigenteId.isNotBlank()) {
            Button(onClick = {
                aoEnfileirar(
                    novaOperacao(
                        tipo = "confirmacao_leitura",
                        quadroId = quadro.id,
                        revisaoId = quadro.vigenteId,
                    ),
                )
            }) { Text("Confirmar leitura") }
        }
        Button(onClick = {
            aoEnfileirar(
                novaOperacao(
                    tipo = "etapa_concluir",
                    quadroId = quadro.id,
                    etapaCodigo = "fiacao",
                ),
            )
        }) { Text("Concluir fiação") }
        OutlinedTextField(
            texto,
            { texto = it },
            label = { Text("Observação") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val anotacao = texto.trim()
            if (anotacao.isBlank()) return@Button
            texto = ""
            aoEnfileirar(
                novaOperacao(
                    tipo = "observacao",
                    quadroId = quadro.id,
                    texto = anotacao,
                ),
            )
        }) { Text("Registrar observação") }
        fila.filter { it.quadroId == quadro.id && it.tipo == "observacao" }.forEach { item ->
            Text("${item.texto} · ${rotuloFila(item)} · ${item.ocorridoEm}")
        }
    }
}

@Composable
private fun Visualizador(
    quadro: QuadroLocal,
    servidor: String,
    token: String,
    offline: Boolean,
    aoFechar: () -> Unit,
) {
    val contexto = LocalContext.current
    var pagina by remember { mutableStateOf(0) }
    var folhas by remember { mutableStateOf(1) }
    var zoom by remember { mutableStateOf(1f) }
    var cheia by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var falha by remember { mutableStateOf("") }
    var historicoTexto by remember { mutableStateOf("") }

    fun renderizar(arquivo: java.io.File) {
        val descritor = android.os.ParcelFileDescriptor.open(arquivo, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(descritor)
        folhas = renderer.pageCount.coerceAtLeast(1)
        val indice = pagina.coerceIn(0, folhas - 1)
        val folha = renderer.openPage(indice)
        val largura = (folha.width * zoom).toInt().coerceAtLeast(1)
        val altura = (folha.height * zoom).toInt().coerceAtLeast(1)
        val imagem = android.graphics.Bitmap.createBitmap(largura, altura, android.graphics.Bitmap.Config.ARGB_8888)
        val matriz = android.graphics.Matrix().apply { setScale(zoom, zoom) }
        folha.render(imagem, null, matriz, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        folha.close()
        renderer.close()
        descritor.close()
        bitmap = imagem
    }

    LaunchedEffect(quadro.id, quadro.arquivoId, pagina, zoom) {
        val local = java.io.File(contexto.filesDir, "${quadro.arquivoId}.pdf")
        if (quadro.arquivoId.isNotBlank() && local.exists() && local.length() > 0L) {
            runCatching { renderizar(local) }
            falha = ""
        }
        if (offline) {
            if (bitmap == null) falha = "Sem cópia local do PDF"
            return@LaunchedEffect
        }
        try {
            val info = withContext(Dispatchers.IO) { historico(servidor, token, quadro.id) }
            val lista = info.getJSONArray("revisoes")
            historicoTexto = (0 until lista.length()).joinToString(" · ") {
                val item = lista.getJSONObject(it)
                "Rev ${item.getString("codigo")} ${item.getString("status")}"
            }
            val vigente = info.optString("revisao_vigente_id")
            var arquivoId = quadro.arquivoId
            for (i in 0 until lista.length()) {
                val item = lista.getJSONObject(i)
                if (item.getString("id") == vigente) arquivoId = item.optString("arquivo_id")
            }
            if (arquivoId.isBlank()) {
                if (bitmap == null) falha = "Sem PDF vigente"
                return@LaunchedEffect
            }
            val destino = java.io.File(contexto.filesDir, "$arquivoId.pdf")
            if (!destino.exists() || destino.length() == 0L) {
                withContext(Dispatchers.IO) { baixarPdf(servidor, token, arquivoId, destino) }
            }
            renderizar(destino)
            falha = ""
        } catch (e: Exception) {
            if (bitmap == null) falha = e.message ?: "PDF não liberado"
        }
    }

    Column(modifier = Modifier.padding(if (cheia) 0.dp else 12.dp)) {
        Text(
            when {
                falha.isNotBlank() -> falha
                offline -> "Cópia local Rev ${quadro.vigenteCodigo} · folha ${pagina + 1} de $folhas · zoom ${(zoom * 100).toInt()}%"
                else -> "Rev ${quadro.vigenteCodigo} · folha ${pagina + 1} de $folhas · zoom ${(zoom * 100).toInt()}%"
            },
        )
        if (offline) {
            Text("Cópia local. Esta tela não afirma a vigente do servidor.")
        }
        Text(historicoTexto)
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Folha do projeto",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!cheia || falha.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (pagina > 0) pagina -= 1 }, enabled = pagina > 0) { Text("Folha anterior") }
                Button(onClick = { if (pagina + 1 < folhas) pagina += 1 }, enabled = pagina + 1 < folhas) { Text("Próxima") }
                Button(onClick = { zoom = if (zoom >= 2f) 1f else zoom + 0.5f }) { Text("Zoom") }
                Button(onClick = { cheia = !cheia }) { Text(if (cheia) "Sair da tela cheia" else "Tela cheia") }
                Button(onClick = aoFechar) { Text("Fechar") }
            }
        } else {
            Button(onClick = { cheia = false }) { Text("Sair da tela cheia") }
        }
    }
}

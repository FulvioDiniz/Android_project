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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
    var servidor by remember { mutableStateOf(dao.ler("servidor") ?: "http://192.168.0.10:8000") }
    var login by remember { mutableStateOf("carlos.mota") }
    var senha by remember { mutableStateOf("") }
    var nome by remember { mutableStateOf(dao.ler("nome") ?: "") }
    var token by remember { mutableStateOf(dao.ler("token") ?: "") }
    var erro by remember { mutableStateOf("") }
    var enlace by remember { mutableStateOf("Sem enlace") }
    var sync by remember { mutableStateOf(dao.ler("sync") ?: "nunca") }
    var alerta by remember { mutableStateOf("") }
    var aberto by remember { mutableStateOf<QuadroLocal?>(null) }
    var quadros by remember { mutableStateOf(dao.listar()) }

    fun sincronizar(base: String, acesso: String) {
        escopo.launch {
            try {
                val lista = withContext(Dispatchers.IO) { baixarQuadros(base, acesso) }
                withContext(Dispatchers.IO) {
                    dao.limpar()
                    dao.salvar(lista)
                    dao.gravar(Meta("servidor", base))
                    dao.gravar(Meta("token", acesso))
                }
                val agora = java.time.Instant.now().toString()
                val avisos = runCatching { notificacoes(base, acesso, dao.ler("marco") ?: "") }.getOrDefault(org.json.JSONArray())
                withContext(Dispatchers.IO) {
                    dao.gravar(Meta("sync", agora))
                    if (avisos.length() > 0) {
                        dao.gravar(Meta("marco", avisos.getJSONObject(avisos.length() - 1).getString("criada_em")))
                    }
                }
                if (avisos.length() > 0) {
                    alerta = "Revisão nova. Confira a vigente antes de seguir."
                }
                enlace = "No ar"
                sync = agora
                quadros = dao.listar()
                erro = ""
            } catch (falha: Exception) {
                enlace = "Sem enlace"
                erro = falha.message ?: "Sem enlace"
                quadros = dao.listar()
            }
        }
    }

    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quadros", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Sem revisão publicada nesta fase. O PDF ainda não entra.")
        if (token.isBlank()) {
            OutlinedTextField(servidor, { servidor = it }, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(login, { login = it }, label = { Text("Login") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(senha, { senha = it }, label = { Text("Senha") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                escopo.launch {
                    try {
                        val sessao = withContext(Dispatchers.IO) { entrar(servidor, login, senha) }
                        token = sessao.token
                        nome = sessao.nome
                        withContext(Dispatchers.IO) {
                            dao.gravar(Meta("nome", sessao.nome))
                        }
                        sincronizar(servidor, sessao.token)
                    } catch (falha: Exception) {
                        erro = falha.message ?: "Não entrou"
                    }
                }
            }) { Text("Entrar", fontSize = 18.sp) }
        } else {
            Text("$nome · $enlace · sync $sync", fontWeight = FontWeight.SemiBold)
            if (alerta.isNotBlank()) {
                Text(alerta, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sincronizar(servidor, token) }) { Text("Atualizar") }
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
            items(quadros) { quadro ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${quadro.codigo}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (quadro.vigenteCodigo.isBlank()) "Sem vigente" else "Vigente Rev ${quadro.vigenteCodigo}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(quadro.titulo)
                    Text("${quadro.clienteNome} · ${quadro.ordemNumero}")
                    Text(
                        if (quadro.vigenteCodigo.isBlank()) "Vigência não confirmada"
                        else "Vigente Rev ${quadro.vigenteCodigo}",
                    )
                    Button(onClick = { aberto = quadro }) {
                        Text(if (quadro.vigenteCodigo.isBlank()) "Sem PDF vigente" else "Abrir Rev ${quadro.vigenteCodigo}")
                    }
                }
            }
        }
        aberto?.let { quadro ->
            Visualizador(quadro, servidor, token) { aberto = null }
        }
    }
}

@Composable
private fun Visualizador(quadro: QuadroLocal, servidor: String, token: String, aoFechar: () -> Unit) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    var pagina by remember { mutableStateOf(0) }
    var folhas by remember { mutableStateOf(1) }
    var zoom by remember { mutableStateOf(1f) }
    var cheia by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var falha by remember { mutableStateOf("") }
    var historico by remember { mutableStateOf("") }

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

    androidx.compose.runtime.LaunchedEffect(quadro.id, pagina, zoom) {
        try {
            val info = withContext(Dispatchers.IO) { historico(servidor, token, quadro.id) }
            val lista = info.getJSONArray("revisoes")
            historico = (0 until lista.length()).joinToString(" · ") {
                val item = lista.getJSONObject(it)
                "Rev ${item.getString("codigo")} ${item.getString("status")}"
            }
            val vigente = info.optString("revisao_vigente_id")
            var arquivoId = ""
            for (i in 0 until lista.length()) {
                val item = lista.getJSONObject(i)
                if (item.getString("id") == vigente) arquivoId = item.optString("arquivo_id")
            }
            if (arquivoId.isBlank()) {
                falha = "Sem PDF vigente"
                return@LaunchedEffect
            }
            val destino = java.io.File(contexto.cacheDir, "$arquivoId.pdf")
            withContext(Dispatchers.IO) { baixarPdf(servidor, token, arquivoId, destino) }
            renderizar(destino)
            falha = ""
        } catch (e: Exception) {
            falha = e.message ?: "PDF não liberado"
        }
    }

    Column(modifier = Modifier.padding(if (cheia) 0.dp else 12.dp)) {
        Text(if (falha.isBlank()) "Rev ${quadro.vigenteCodigo} · folha ${pagina + 1} de $folhas · zoom ${(zoom * 100).toInt()}%" else falha)
        Text(historico)
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

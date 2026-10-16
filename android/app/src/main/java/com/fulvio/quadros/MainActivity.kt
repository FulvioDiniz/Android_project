package com.fulvio.quadros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Etapas = listOf(
    "estrutura" to "Estrutura",
    "mecanica" to "Mecânica",
    "fiacao" to "Fiação",
    "identificacao" to "Identificação",
    "teste" to "Teste",
    "expedicao" to "Expedição",
)

private enum class Aba(val rotulo: String) {
    Quadros("Quadros"),
    Pdf("PDF"),
    Diario("Diário"),
    Historico("Histórico"),
}

private val Fundo = Color(0xFFF3F1EB)
private val Branco = Color.White
private val Preto = Color(0xFF111111)
private val Cinza = Color(0xFF4A4A4A)
private val Borda = Color(0xFFE2DDD4)
private val Amarelo = Color(0xFFFFE14A)
private val Forma = RoundedCornerShape(18.dp)
private val FormaSelo = RoundedCornerShape(12.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = banco(this).dao()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Preto,
                    onPrimary = Branco,
                    background = Fundo,
                    surface = Branco,
                    onBackground = Preto,
                    onSurface = Preto,
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Fundo) {
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
    var detalhe by remember { mutableStateOf(false) }
    var aba by remember { mutableStateOf(Aba.Quadros) }
    var busca by remember { mutableStateOf("") }
    var telaCheia by remember { mutableStateOf(false) }
    var quadros by remember { mutableStateOf(dao.listar()) }
    var fila by remember { mutableStateOf(dao.listarFila()) }
    var versao by remember { mutableIntStateOf(0) }
    val offline = token.isNotBlank() && enlace == "Sem enlace"

    fun recarregar() {
        quadros = dao.listar()
        fila = dao.listarFila()
        sync = dao.ler("sync") ?: sync
        alerta = dao.ler("alerta") ?: ""
        versao += 1
        val atual = aberto
        if (atual != null) {
            aberto = quadros.find { it.id == atual.id }
            if (aberto == null) {
                detalhe = false
                aba = Aba.Quadros
            }
        }
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
                    dao.gravar(Meta("sync", Instant.now().toString()))
                    lista.forEach { quadro ->
                        if (quadro.arquivoId.isBlank()) return@forEach
                        runCatching { copiarPdfSeFaltar(dao, base, acesso, quadro.arquivoId, pasta) }
                    }
                    runCatching { baixarDiario(base, acesso) }.onSuccess { notas ->
                        dao.limparDiario()
                        if (notas.isNotEmpty()) dao.salvarDiario(notas)
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

    fun enfileirar(itens: List<FilaLocal>) {
        itens.forEach { dao.enfileirar(it) }
        recarregar()
        agendarFila(contexto)
        if (token.isNotBlank()) ciclo(servidor, token)
    }

    fun confirmar(quadro: QuadroLocal) {
        if (quadro.vigenteId.isBlank()) return
        enfileirar(
            listOf(
                novaOperacao(
                    tipo = "confirmacao_leitura",
                    quadroId = quadro.id,
                    revisaoId = quadro.vigenteId,
                ),
            ),
        )
    }

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            agendarFila(contexto)
            ciclo(servidor, token)
        }
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().background(Fundo)) {
        if (token.isBlank()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Montagem", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Quadros em produção", color = Preto, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Cartao {
                    Text("Servidor configurado", color = Cinza, fontSize = 16.sp)
                    Text(servidor, color = Preto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Campo("Servidor", servidor) { servidor = it }
                Campo("Login", login) { login = it }
                Campo("Senha", senha, senha = true) { senha = it }
                Botao("Entrar") {
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
                            erro = ""
                            enlace = "Sincronizando"
                        } catch (falha: Exception) {
                            erro = falha.message ?: "Não entrou"
                            enlace = "Sem enlace"
                        }
                    }
                }
                if (erro.isNotBlank()) {
                    Text(erro, color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    aba == Aba.Quadros && !detalhe -> ListaQuadros(
                        quadros = quadros,
                        busca = busca,
                        servidor = servidor,
                        nome = nome,
                        offline = offline,
                        enlace = enlace,
                        dao = dao,
                        versao = versao,
                        aoBusca = { busca = it },
                        aoAbrir = {
                            aberto = it
                            detalhe = true
                            aba = Aba.Quadros
                        },
                        aoAtualizar = { ciclo(servidor, token) },
                        aoSair = {
                            token = ""
                            nome = ""
                            aberto = null
                            detalhe = false
                            aba = Aba.Quadros
                            telaCheia = false
                            escopo.launch(Dispatchers.IO) { dao.gravar(Meta("token", "")) }
                        },
                    )
                    aberto == null -> Vazio(
                        titulo = if (quadros.isEmpty()) "Nenhum quadro nesta API" else "Escolha um quadro",
                        servidor = servidor,
                        detalhe = if (quadros.isEmpty()) {
                            "A lista chega quando esta API devolver um quadro."
                        } else {
                            "Toque em Quadros e abra um cartão."
                        },
                    )
                    aba == Aba.Pdf -> Visualizador(
                        aberto!!,
                        telaCheia,
                        { telaCheia = it },
                    )
                    aba == Aba.Diario -> Diario(aberto!!, dao, fila, versao) { enfileirar(it) }
                    aba == Aba.Historico -> Historico(aberto!!, servidor, token, offline, dao)
                    else -> QuadroAberto(aberto!!, dao, fila, versao, alerta, offline, enlace, sync) {
                        confirmar(aberto!!)
                    }
                }
            }
            if (!telaCheia) {
                Barra(aba) { nova ->
                    if (nova == Aba.Quadros && aba == Aba.Quadros) detalhe = false
                    aba = nova
                    telaCheia = false
                }
            }
        }
    }
}

@Composable
private fun ListaQuadros(
    quadros: List<QuadroLocal>,
    busca: String,
    servidor: String,
    nome: String,
    offline: Boolean,
    enlace: String,
    dao: QuadroDao,
    versao: Int,
    aoBusca: (String) -> Unit,
    aoAbrir: (QuadroLocal) -> Unit,
    aoAtualizar: () -> Unit,
    aoSair: () -> Unit,
) {
    val termo = busca.trim().lowercase()
    val visiveis = quadros.filter { quadro ->
        termo.isBlank() || listOf(quadro.codigo, quadro.ordemNumero, quadro.clienteNome, quadro.responsavelNome)
            .any { it.lowercase().contains(termo) }
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Quadros em produção", color = Preto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "$nome · ${if (offline) "Offline" else enlace}",
            color = Preto,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Campo("Pesquisa", busca, aoMudar = aoBusca)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { BotaoSecundario("Atualizar", aoAtualizar) }
            Box(Modifier.weight(1f)) { BotaoSecundario("Sair", aoSair) }
        }
        if (visiveis.isEmpty()) {
            Vazio(
                titulo = if (quadros.isEmpty()) "Nenhum quadro nesta API" else "Nenhum quadro com essa pesquisa",
                servidor = servidor,
                detalhe = if (quadros.isEmpty()) "A lista chega quando esta API devolver um quadro." else "Tente outro código, OP, cliente ou responsável.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                items(visiveis, key = { it.id }) { quadro ->
                    CartaoQuadro(quadro, etapaDoCartao(dao, quadro, versao)) { aoAbrir(quadro) }
                }
            }
        }
    }
}

@Composable
private fun CartaoQuadro(quadro: QuadroLocal, etapa: String, aoAbrir: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Branco)
            .border(1.dp, Borda, Forma)
            .clickable(onClick = aoAbrir)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(quadro.codigo, color = Preto, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Selo(quadro)
        Text(quadro.clienteNome.ifBlank { "Sem cliente" }, color = Preto, fontSize = 20.sp)
        Text("OP ${quadro.ordemNumero.ifBlank { "sem número" }}", color = Preto, fontSize = 20.sp)
        Text("Etapa $etapa", color = Preto, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (quadro.responsavelNome.isNotBlank()) {
            Text(quadro.responsavelNome, color = Cinza, fontSize = 18.sp)
        }
    }
}

@Composable
private fun QuadroAberto(
    quadro: QuadroLocal,
    dao: QuadroDao,
    fila: List<FilaLocal>,
    versao: Int,
    alerta: String,
    offline: Boolean,
    enlace: String,
    sync: String,
    aoConfirmar: () -> Unit,
) {
    val confirmada = remember(quadro.id, quadro.vigenteId, versao) { dao.ler("confirmada:${quadro.id}") }
    val confirmadaAgora = confirmada == quadro.vigenteId && quadro.vigenteId.isNotBlank()
    val naFila = fila.any { it.quadroId == quadro.id && it.tipo == "confirmacao_leitura" && it.estado == "na_fila" }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cartao {
            Text(quadro.codigo, color = Preto, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Selo(quadro)
            Text("Enlace: ${if (offline) "Sem enlace" else enlace}", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Última sync: ${horaLegivel(sync)}", color = Preto, fontSize = 18.sp)
            Text(
                when {
                    quadro.vigenteId.isBlank() -> "Sem vigência publicada"
                    offline && confirmadaAgora -> "Offline. Última baixada confirmada. Vigência no servidor desconhecida."
                    offline -> "Offline. Vigência não confirmada."
                    confirmadaAgora -> "Vigência confirmada"
                    else -> "Vigência não confirmada"
                },
                color = Preto,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Cartao {
            Text("Alerta de leitura", color = Preto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                alerta.ifBlank { "Nenhum alerta de revisão nova." },
                color = Preto,
                fontSize = 20.sp,
            )
            if (naFila) {
                Text("Confirmação na fila", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (quadro.vigenteId.isNotBlank() && !confirmadaAgora) {
                Botao("Confirmar leitura", aoClicar = aoConfirmar)
            } else if (quadro.vigenteId.isBlank()) {
                Text("Sem revisão vigente para confirmar.", color = Preto, fontSize = 18.sp)
            }
        }
        Cartao {
            Text(quadro.titulo, color = Preto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Cliente ${quadro.clienteNome.ifBlank { "sem cliente" }}", color = Preto, fontSize = 20.sp)
            Text("OP ${quadro.ordemNumero}", color = Preto, fontSize = 20.sp)
            Text("Responsável ${quadro.responsavelNome.ifBlank { "sem responsável" }}", color = Preto, fontSize = 20.sp)
            Text("Etapa ${etapaDoCartao(dao, quadro, versao)}", color = Preto, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (quadro.revisaoTipo.isNotBlank()) {
                Text("Tipo ${quadro.revisaoTipo}", color = Preto, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Diario(
    quadro: QuadroLocal,
    dao: QuadroDao,
    fila: List<FilaLocal>,
    versao: Int,
    aoEnfileirar: (List<FilaLocal>) -> Unit,
) {
    val contexto = LocalContext.current
    var texto by remember(quadro.id) { mutableStateOf("") }
    var bloqueia by remember(quadro.id) { mutableStateOf(true) }
    var etapaCodigo by remember(quadro.id) { mutableStateOf("fiacao") }
    val confirmada = remember(quadro.id, quadro.vigenteId, versao) { dao.ler("confirmada:${quadro.id}") }
    val trava = quadro.vigenteId.isNotBlank() &&
        confirmada != quadro.vigenteId &&
        quadro.revisaoTipo in setOf("bloqueio", "reavaliacao")
    val notas = remember(quadro.id, versao) { dao.diarioDo(quadro.id) }
    val pegarFoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val anotacao = texto.trim()
        if (uri == null || anotacao.isBlank()) return@rememberLauncherForActivityResult
        val duvida = novaOperacao(tipo = "duvida", quadroId = quadro.id, texto = anotacao)
        val destino = File(contexto.filesDir, "${duvida.operacaoId}.jpg")
        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
            destino.outputStream().use { entrada.copyTo(it) }
        } ?: return@rememberLauncherForActivityResult
        val foto = novaOperacao(
            tipo = "foto",
            quadroId = quadro.id,
            texto = anotacao,
            extra = org.json.JSONObject()
                .put("caminho", destino.absolutePath)
                .put("observacao_operacao", duvida.operacaoId)
                .toString(),
        )
        texto = ""
        aoEnfileirar(listOf(duvida, foto))
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cabecalho(quadro)
        Text("Diário", color = Preto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Campo("Texto", texto) { texto = it }
        Botao("Observação") {
            val anotacao = texto.trim()
            if (anotacao.isBlank()) return@Botao
            texto = ""
            aoEnfileirar(listOf(novaOperacao(tipo = "observacao", quadroId = quadro.id, texto = anotacao)))
        }
        Botao("Dúvida") {
            val anotacao = texto.trim()
            if (anotacao.isBlank()) return@Botao
            texto = ""
            aoEnfileirar(listOf(novaOperacao(tipo = "duvida", quadroId = quadro.id, texto = anotacao)))
        }
        Botao("Foto") { pegarFoto.launch("image/*") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Opcao("Bloqueia a etapa", bloqueia) { bloqueia = true }
            }
            Box(Modifier.weight(1f)) {
                Opcao("Não bloqueia", !bloqueia) { bloqueia = false }
            }
        }
        Botao("Pendência") {
            val anotacao = texto.trim()
            if (anotacao.isBlank()) return@Botao
            texto = ""
            aoEnfileirar(
                listOf(
                    novaOperacao(
                        tipo = "pendencia",
                        quadroId = quadro.id,
                        texto = anotacao,
                        extra = org.json.JSONObject().put("bloqueia_etapa", bloqueia).toString(),
                    ),
                ),
            )
        }
        Text("Etapa", color = Preto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (trava) {
            Cartao {
                Text(
                    "A etapa fica bloqueada até a leitura da vigente ser confirmada.",
                    color = Preto,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Etapas.forEach { (codigo, nome) ->
            Opcao("$nome · ${rotuloDaEtapa(dao, fila, quadro.id, codigo, versao)}", codigo == etapaCodigo) {
                etapaCodigo = codigo
            }
        }
        Botao("Iniciar etapa", enabled = !trava) {
            aoEnfileirar(listOf(novaOperacao(tipo = "etapa_iniciar", quadroId = quadro.id, etapaCodigo = etapaCodigo)))
        }
        Botao("Concluir etapa", enabled = !trava) {
            aoEnfileirar(listOf(novaOperacao(tipo = "etapa_concluir", quadroId = quadro.id, etapaCodigo = etapaCodigo)))
        }
        fila.filter { it.quadroId == quadro.id && it.tipo in listOf("observacao", "duvida", "foto", "pendencia") }.forEach { item ->
            val nomeItem = when (item.tipo) {
                "duvida" -> "Dúvida"
                "foto" -> "Foto"
                "pendencia" -> "Pendência"
                else -> "Observação"
            }
            Cartao {
                Text(nomeItem, color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(item.texto, color = Preto, fontSize = 18.sp)
                Text(rotuloFila(item), color = Cinza, fontSize = 16.sp)
            }
        }
        notas.forEach { nota ->
            if (nota.resposta.isNotBlank()) {
                Cartao {
                    Text("Resposta da engenharia", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(nota.resposta, color = Preto, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun Historico(quadro: QuadroLocal, servidor: String, token: String, offline: Boolean, dao: QuadroDao) {
    var linhas by remember(quadro.id) { mutableStateOf(dao.ler("historico:${quadro.id}") ?: "") }
    var falha by remember(quadro.id) { mutableStateOf("") }
    LaunchedEffect(quadro.id, offline) {
        if (offline || token.isBlank()) {
            if (linhas.isBlank()) falha = "Histórico ainda não baixado. O metadado vem do servidor quando há rede."
            return@LaunchedEffect
        }
        try {
            val info = withContext(Dispatchers.IO) { historico(servidor, token, quadro.id) }
            val lista = info.getJSONArray("revisoes")
            val texto = if (lista.length() == 0) {
                "Nenhuma revisão neste quadro."
            } else {
                (0 until lista.length()).joinToString("\n") {
                    val item = lista.getJSONObject(it)
                    val marca = if (item.getString("id") == info.optString("revisao_vigente_id")) "vigente" else item.getString("status")
                    "Rev ${item.getString("codigo")} · $marca"
                }
            }
            linhas = texto
            falha = ""
            withContext(Dispatchers.IO) { dao.gravar(Meta("historico:${quadro.id}", texto)) }
        } catch (e: Exception) {
            if (linhas.isBlank()) falha = e.message ?: "Histórico indisponível"
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cabecalho(quadro)
        Text("Histórico", color = Preto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (falha.isNotBlank()) {
            Cartao { Text(falha, color = Preto, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        }
        val itens = linhas.lineSequence().filter { it.isNotBlank() }.toList()
        if (itens.isEmpty() && falha.isBlank()) {
            Cartao { Text("Nenhuma revisão neste quadro.", color = Preto, fontSize = 20.sp) }
        }
        itens.forEach { linha ->
            val partes = linha.split(" · ")
            val rev = partes.getOrElse(0) { linha }
            val marca = partes.getOrElse(1) { "" }
            val vigente = marca == "vigente"
            Cartao {
                if (vigente) {
                    SeloTexto(rev, true)
                    Text("Vigente", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(rev, color = Preto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (marca.isNotBlank()) {
                        Text(traduzStatus(marca), color = Preto, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Visualizador(quadro: QuadroLocal, telaCheia: Boolean, aoTelaCheia: (Boolean) -> Unit) {
    val contexto = LocalContext.current
    var pagina by remember(quadro.id) { mutableStateOf(0) }
    var folhas by remember(quadro.id) { mutableStateOf(1) }
    var zoom by remember(quadro.id) { mutableStateOf(1f) }
    var bitmap by remember(quadro.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var falha by remember(quadro.id) { mutableStateOf("") }

    fun renderizar(arquivo: File) {
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
        val local = File(contexto.filesDir, "${quadro.arquivoId}.pdf")
        if (quadro.arquivoId.isNotBlank() && local.exists() && local.length() > 0L) {
            runCatching { renderizar(local) }.onFailure { falha = it.message ?: "PDF não abriu" }
            if (bitmap != null) falha = ""
            return@LaunchedEffect
        }
        bitmap = null
        falha = "O PDF ainda não está neste aparelho."
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!telaCheia) {
            Cabecalho(quadro)
            Text("PDF", color = Preto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(quadro.codigo, color = Preto, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Selo(quadro)
            }
        }
        if (bitmap != null && falha.isBlank()) {
            Text(
                "Folha ${pagina + 1} de $folhas · zoom ${(zoom * 100).toInt()}%",
                color = Preto,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Folha do projeto",
                modifier = Modifier.weight(1f).fillMaxWidth().clip(Forma).background(Branco).border(1.dp, Borda, Forma),
            )
        } else {
            Cartao(Modifier.weight(1f)) {
                Text(falha.ifBlank { "O PDF ainda não está neste aparelho." }, color = Preto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("A folha, o zoom e a tela cheia aparecem quando o arquivo já estiver no aparelho.", color = Preto, fontSize = 18.sp)
            }
        }
        if (telaCheia && bitmap != null) {
            Botao("Sair da tela cheia") { aoTelaCheia(false) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    BotaoSecundario("Anterior", { if (pagina > 0) pagina -= 1 })
                }
                Box(Modifier.weight(1f)) {
                    BotaoSecundario("Próxima", { if (pagina + 1 < folhas) pagina += 1 })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    BotaoSecundario("Zoom") { zoom = if (zoom >= 2f) 1f else zoom + 0.5f }
                }
                Box(Modifier.weight(1f)) {
                    Botao("Tela cheia", enabled = bitmap != null) { aoTelaCheia(true) }
                }
            }
        }
    }
}

@Composable
private fun Vazio(titulo: String, servidor: String, detalhe: String) {
    Cartao {
        Text(titulo, color = Preto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(detalhe, color = Preto, fontSize = 18.sp)
        Text("Servidor", color = Cinza, fontSize = 16.sp)
        Text(servidor, color = Preto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Cabecalho(quadro: QuadroLocal) {
    Cartao {
        Text(quadro.codigo, color = Preto, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Selo(quadro)
    }
}

@Composable
private fun Selo(quadro: QuadroLocal) {
    val temVigente = quadro.vigenteCodigo.isNotBlank()
    SeloTexto(if (temVigente) "Rev ${quadro.vigenteCodigo}" else "Sem vigente", temVigente)
}

@Composable
private fun SeloTexto(texto: String, vigente: Boolean) {
    Text(
        texto,
        modifier = Modifier
            .clip(FormaSelo)
            .background(if (vigente) Amarelo else Branco)
            .border(2.dp, if (vigente) Amarelo else Preto, FormaSelo)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        color = Preto,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Branco)
            .border(1.dp, Borda, Forma)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        conteudo()
    }
}

@Composable
private fun Campo(rotulo: String, valor: String, senha: Boolean = false, aoMudar: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(rotulo, color = Preto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = valor,
            onValueChange = aoMudar,
            singleLine = true,
            textStyle = TextStyle(color = Preto, fontSize = 20.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Preto),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .clip(Forma)
                .background(Branco)
                .border(1.dp, Preto, Forma)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun Botao(texto: String, enabled: Boolean = true, aoClicar: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(Forma)
            .background(if (enabled) Preto else Color(0xFFD9D6D0))
            .clickable(enabled = enabled, onClick = aoClicar),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = if (enabled) Branco else Cinza, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BotaoSecundario(texto: String, aoClicar: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(Forma)
            .background(Branco)
            .border(1.dp, Preto, Forma)
            .clickable(onClick = aoClicar),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Opcao(texto: String, selecionada: Boolean, aoClicar: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(Forma)
            .background(if (selecionada) Amarelo else Branco)
            .border(1.dp, if (selecionada) Amarelo else Borda, Forma)
            .clickable(onClick = aoClicar)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(texto, color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Barra(aba: Aba, aoEscolher: (Aba) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Branco)
            .border(1.dp, Borda),
    ) {
        Aba.entries.forEach { item ->
            val ativa = aba == item
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(76.dp)
                    .background(if (ativa) Amarelo else Branco)
                    .clickable { aoEscolher(item) },
                contentAlignment = Alignment.Center,
            ) {
                Text(item.rotulo, color = Preto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun horaLegivel(iso: String): String {
    if (iso.isBlank() || iso == "nunca") return "nunca"
    return runCatching {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Sao_Paulo"))
            .format(Instant.parse(iso))
    }.getOrDefault(iso)
}

private fun traduzStatus(marca: String): String = when (marca) {
    "publicada" -> "Publicada"
    "pendente" -> "Pendente"
    "aprovada" -> "Aprovada"
    "substituida" -> "Substituída"
    "cancelada" -> "Cancelada"
    "rejeitada" -> "Rejeitada"
    else -> marca
}

private fun etapaDoCartao(dao: QuadroDao, quadro: QuadroLocal, versao: Int): String {
    if (versao < 0) return "Sem etapa"
    var ultima = "Sem etapa"
    var andamento: String? = null
    Etapas.forEach { (codigo, nome) ->
        when (dao.ler("etapa:${quadro.id}:$codigo")) {
            "concluida" -> ultima = nome
            "em_andamento" -> andamento = nome
        }
    }
    return andamento ?: ultima
}

private fun rotuloDaEtapa(dao: QuadroDao, fila: List<FilaLocal>, quadroId: String, codigo: String, versao: Int): String {
    if (versao < 0) return "em aberto"
    val estado = dao.ler("etapa:$quadroId:$codigo")
    val movimentos = fila.filter { it.quadroId == quadroId && it.etapaCodigo == codigo && it.tipo.startsWith("etapa_") }
    return when {
        estado == "concluida" -> "concluída"
        movimentos.any { it.estado == "na_fila" } -> "na fila"
        estado == "em_andamento" -> "em andamento"
        else -> rotuloEtapa(false, false, movimentos.lastOrNull())
    }
}

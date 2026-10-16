package com.fulvio.quadros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private enum class Aba { Lista, Quadro, Pdf, Diario, Alerta, Historico }

private val Azul = Color(0xFF0B3A82)
private val FundoVigente = Color(0xFFE7EEF8)
private val FundoAlerta = Color(0xFFFFE8E6)
private val FundoOffline = Color(0xFFFFF3CD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = banco(this).dao()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Azul,
                    onPrimary = Color.White,
                    background = Color.White,
                    surface = Color.White,
                    error = Color(0xFF8C1D18),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
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
    var aba by remember { mutableStateOf(Aba.Lista) }
    var busca by remember { mutableStateOf("") }
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
            if (aberto == null) aba = Aba.Lista
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

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            agendarFila(contexto)
            ciclo(servidor, token)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Montagem", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Azul)
        if (token.isBlank()) {
            Entrada(
                servidor = servidor,
                login = login,
                senha = senha,
                erro = erro,
                aoServidor = { servidor = it },
                aoLogin = { login = it },
                aoSenha = { senha = it },
                aoEntrar = {
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
                },
            )
        } else {
            Text("$nome · ${if (offline) "Offline" else enlace}", fontWeight = FontWeight.SemiBold)
            if (erro.isNotBlank()) {
                Text(erro, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            val quadro = if (aba == Aba.Lista) null else aberto
            if (quadro != null) {
                FaixaVigente(quadro, dao, versao, offline, enlace, sync)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    aba == Aba.Lista || quadro == null -> ListaQuadros(
                        quadros = quadros,
                        busca = busca,
                        servidor = servidor,
                        dao = dao,
                        versao = versao,
                        aoBusca = { busca = it },
                        aoAbrir = {
                            aberto = it
                            aba = Aba.Quadro
                        },
                        aoAtualizar = { ciclo(servidor, token) },
                        aoSair = {
                            token = ""
                            nome = ""
                            aberto = null
                            aba = Aba.Lista
                            escopo.launch(Dispatchers.IO) { dao.gravar(Meta("token", "")) }
                        },
                    )
                    aba == Aba.Quadro -> QuadroAberto(
                        quadro = quadro,
                        dao = dao,
                        fila = fila,
                        versao = versao,
                        aoConfirmar = {
                            enfileirar(
                                listOf(
                                    novaOperacao(
                                        tipo = "confirmacao_leitura",
                                        quadroId = quadro.id,
                                        revisaoId = quadro.vigenteId,
                                    ),
                                ),
                            )
                        },
                    )
                    aba == Aba.Pdf -> Visualizador(quadro, servidor, token, offline)
                    aba == Aba.Diario -> Diario(
                        quadro = quadro,
                        dao = dao,
                        fila = fila,
                        versao = versao,
                        aoEnfileirar = { enfileirar(it) },
                    )
                    aba == Aba.Alerta -> Alerta(
                        alerta = alerta,
                        quadro = quadro,
                        fila = fila,
                        aoConfirmar = {
                            if (quadro.vigenteId.isBlank()) return@Alerta
                            enfileirar(
                                listOf(
                                    novaOperacao(
                                        tipo = "confirmacao_leitura",
                                        quadroId = quadro.id,
                                        revisaoId = quadro.vigenteId,
                                    ),
                                ),
                            )
                        },
                    )
                    aba == Aba.Historico -> Historico(quadro, servidor, token, offline, dao)
                }
            }
            if (quadro != null) {
                Barra(aba) { aba = it }
            }
        }
    }
}

@Composable
private fun Entrada(
    servidor: String,
    login: String,
    senha: String,
    erro: String,
    aoServidor: (String) -> Unit,
    aoLogin: (String) -> Unit,
    aoSenha: (String) -> Unit,
    aoEntrar: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("Quadros em produção", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Servidor configurado", fontWeight = FontWeight.SemiBold)
        Text(servidor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(servidor, aoServidor, label = { Text("Servidor") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(login, aoLogin, label = { Text("Login") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(senha, aoSenha, label = { Text("Senha") }, modifier = Modifier.fillMaxWidth())
        Botao("Entrar", aoClicar = aoEntrar)
        if (erro.isNotBlank()) {
            Text(erro, color = MaterialTheme.colorScheme.error, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ListaQuadros(
    quadros: List<QuadroLocal>,
    busca: String,
    servidor: String,
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Text("Quadros em produção", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            busca,
            aoBusca,
            label = { Text("Código, OP, cliente ou responsável") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = aoAtualizar, modifier = Modifier.heightIn(min = 56.dp)) { Text("Atualizar", fontSize = 18.sp) }
            Button(onClick = aoSair, modifier = Modifier.heightIn(min = 56.dp)) { Text("Sair", fontSize = 18.sp) }
        }
        if (visiveis.isEmpty()) {
            Column(modifier = Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (quadros.isEmpty()) "Nenhum quadro nesta API" else "Nenhum quadro com essa pesquisa",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Servidor $servidor", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
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
            .background(FundoVigente)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(quadro.codigo, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Azul)
        Text(rotuloRevisao(quadro), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(quadro.clienteNome.ifBlank { "Sem cliente" }, fontSize = 20.sp)
        Text("OP ${quadro.ordemNumero.ifBlank { "sem número" }}", fontSize = 20.sp)
        Text("Etapa $etapa", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (quadro.responsavelNome.isNotBlank()) {
            Text(quadro.responsavelNome, fontSize = 18.sp)
        }
        Botao("Abrir quadro", aoClicar = aoAbrir)
    }
}

@Composable
private fun FaixaVigente(
    quadro: QuadroLocal,
    dao: QuadroDao,
    versao: Int,
    offline: Boolean,
    enlace: String,
    sync: String,
) {
    val confirmada = remember(quadro.id, quadro.vigenteId, versao) { dao.ler("confirmada:${quadro.id}") }
    val confirmadaAgora = confirmada == quadro.vigenteId && quadro.vigenteId.isNotBlank()
    val fundo = when {
        offline -> FundoOffline
        !confirmadaAgora && quadro.vigenteId.isNotBlank() -> FundoAlerta
        else -> FundoVigente
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(fundo).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(quadro.codigo, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Azul)
        Text(rotuloRevisao(quadro), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Enlace: ${if (offline) "Sem enlace" else enlace}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Última sync: ${horaLegivel(sync)}", fontSize = 18.sp)
        Text(
            when {
                quadro.vigenteId.isBlank() -> "Sem vigência publicada"
                offline && confirmadaAgora -> "Offline. Última baixada confirmada. Vigência no servidor desconhecida."
                offline -> "Offline. Vigência não confirmada."
                confirmadaAgora -> "Vigência confirmada"
                else -> "Vigência não confirmada"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (!confirmadaAgora && quadro.vigenteId.isNotBlank()) MaterialTheme.colorScheme.error else Azul,
        )
    }
}

@Composable
private fun QuadroAberto(
    quadro: QuadroLocal,
    dao: QuadroDao,
    fila: List<FilaLocal>,
    versao: Int,
    aoConfirmar: () -> Unit,
) {
    val confirmada = remember(quadro.id, quadro.vigenteId, versao) { dao.ler("confirmada:${quadro.id}") }
    val naFila = fila.any { it.quadroId == quadro.id && it.tipo == "confirmacao_leitura" && it.estado == "na_fila" }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(quadro.titulo, fontSize = 20.sp)
        Text("Cliente ${quadro.clienteNome.ifBlank { "sem cliente" }}", fontSize = 20.sp)
        Text("OP ${quadro.ordemNumero}", fontSize = 20.sp)
        Text("Responsável ${quadro.responsavelNome.ifBlank { "sem responsável" }}", fontSize = 20.sp)
        Text("Etapa ${etapaDoCartao(dao, quadro, versao)}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (quadro.revisaoTipo.isNotBlank()) {
            Text("Tipo ${quadro.revisaoTipo}", fontSize = 18.sp)
        }
        if (naFila) {
            Text("Confirmação na fila", fontWeight = FontWeight.Bold)
        }
        if (quadro.vigenteId.isNotBlank() && confirmada != quadro.vigenteId) {
            Botao("Confirmar leitura", aoClicar = aoConfirmar)
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Diário", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            texto,
            { texto = it },
            label = { Text("Texto") },
            modifier = Modifier.fillMaxWidth(),
        )
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = bloqueia, onCheckedChange = { bloqueia = it })
            Text(if (bloqueia) "Pendência bloqueia a etapa" else "Pendência não bloqueia", fontSize = 18.sp)
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
        Text("Etapa", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (trava) {
            Text(
                "A etapa fica bloqueada até a leitura da vigente ser confirmada.",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        Etapas.forEach { (codigo, nome) ->
            val escolhida = codigo == etapaCodigo
            Button(
                onClick = { etapaCodigo = codigo },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (escolhida) Azul else Color(0xFFD6DEEA),
                    contentColor = if (escolhida) Color.White else Color.Black,
                ),
            ) {
                Text("$nome · ${rotuloDaEtapa(dao, fila, quadro.id, codigo, versao)}", fontSize = 18.sp)
            }
        }
        Botao("Iniciar etapa", enabled = !trava) {
            aoEnfileirar(listOf(novaOperacao(tipo = "etapa_iniciar", quadroId = quadro.id, etapaCodigo = etapaCodigo)))
        }
        Botao("Concluir etapa", enabled = !trava) {
            aoEnfileirar(listOf(novaOperacao(tipo = "etapa_concluir", quadroId = quadro.id, etapaCodigo = etapaCodigo)))
        }
        fila.filter { it.quadroId == quadro.id && it.tipo in listOf("observacao", "duvida", "foto", "pendencia") }.forEach { item ->
            val nome = when (item.tipo) {
                "duvida" -> "Dúvida"
                "foto" -> "Foto"
                "pendencia" -> "Pendência"
                else -> "Observação"
            }
            Text("$nome: ${item.texto} · ${rotuloFila(item)}", fontSize = 18.sp)
        }
        notas.forEach { nota ->
            if (nota.resposta.isNotBlank()) {
                Text("Resposta da engenharia: ${nota.resposta}", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Alerta(alerta: String, quadro: QuadroLocal, fila: List<FilaLocal>, aoConfirmar: () -> Unit) {
    val naFila = fila.any { it.quadroId == quadro.id && it.tipo == "confirmacao_leitura" && it.estado == "na_fila" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        Text("Alerta", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            alerta.ifBlank { "Nenhum alerta de revisão nova." },
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (alerta.isBlank()) Azul else MaterialTheme.colorScheme.error,
        )
        if (naFila) {
            Text("Confirmação na fila", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (quadro.vigenteId.isNotBlank()) {
            Botao("Confirmar leitura", aoClicar = aoConfirmar)
        } else {
            Text("Sem revisão vigente para confirmar.", fontSize = 20.sp)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Histórico", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (falha.isNotBlank()) {
            Text(falha, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        linhas.lineSequence().filter { it.isNotBlank() }.forEach { linha ->
            Text(linha, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().background(FundoVigente).padding(12.dp))
        }
    }
}

@Composable
private fun Visualizador(quadro: QuadroLocal, servidor: String, token: String, offline: Boolean) {
    val contexto = LocalContext.current
    var pagina by remember(quadro.id) { mutableStateOf(0) }
    var folhas by remember(quadro.id) { mutableStateOf(1) }
    var zoom by remember(quadro.id) { mutableStateOf(1f) }
    var cheia by remember(quadro.id) { mutableStateOf(false) }
    var bitmap by remember(quadro.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var falha by remember(quadro.id) { mutableStateOf("") }
    var arquivoLocal by remember(quadro.id) { mutableStateOf<File?>(null) }

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
        arquivoLocal = arquivo
    }

    LaunchedEffect(quadro.id, quadro.arquivoId, pagina, zoom) {
        val local = File(contexto.filesDir, "${quadro.arquivoId}.pdf")
        if (quadro.arquivoId.isNotBlank() && local.exists() && local.length() > 0L) {
            runCatching { renderizar(local) }.onFailure { falha = it.message ?: "PDF não abriu" }
            if (bitmap != null) falha = ""
            return@LaunchedEffect
        }
        bitmap = null
        arquivoLocal = null
        falha = if (offline) {
            "O PDF ainda não está neste aparelho."
        } else {
            "O PDF ainda não está neste aparelho."
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (falha.isNotBlank()) falha else "Folha ${pagina + 1} de $folhas · zoom ${(zoom * 100).toInt()}%",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (bitmap != null && arquivoLocal != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Folha do projeto",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Text("Abre a folha quando o arquivo já estiver no aparelho.", fontSize = 20.sp, modifier = Modifier.weight(1f))
        }
        if (!cheia || falha.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { if (pagina > 0) pagina -= 1 }, enabled = pagina > 0, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Folha", fontSize = 16.sp)
                }
                Button(
                    onClick = { if (pagina + 1 < folhas) pagina += 1 },
                    enabled = pagina + 1 < folhas,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("Próxima", fontSize = 16.sp) }
                Button(onClick = { zoom = if (zoom >= 2f) 1f else zoom + 0.5f }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Zoom", fontSize = 16.sp)
                }
                Button(onClick = { cheia = !cheia }, enabled = bitmap != null, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(if (cheia) "Sair" else "Tela cheia", fontSize = 16.sp)
                }
            }
        } else {
            Botao("Sair da tela cheia") { cheia = false }
        }
    }
}

@Composable
private fun Barra(aba: Aba, aoEscolher: (Aba) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AbaBotao("Quadro", aba == Aba.Quadro, Modifier.weight(1f)) { aoEscolher(Aba.Quadro) }
            AbaBotao("PDF", aba == Aba.Pdf, Modifier.weight(1f)) { aoEscolher(Aba.Pdf) }
            AbaBotao("Diário", aba == Aba.Diario, Modifier.weight(1f)) { aoEscolher(Aba.Diario) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AbaBotao("Alerta", aba == Aba.Alerta, Modifier.weight(1f)) { aoEscolher(Aba.Alerta) }
            AbaBotao("Histórico", aba == Aba.Historico, Modifier.weight(1f)) { aoEscolher(Aba.Historico) }
            AbaBotao("Lista", false, Modifier.weight(1f)) { aoEscolher(Aba.Lista) }
        }
    }
}

@Composable
private fun AbaBotao(texto: String, ativa: Boolean, modifier: Modifier, aoClicar: () -> Unit) {
    Button(
        onClick = aoClicar,
        modifier = modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (ativa) Azul else Color(0xFFD6DEEA),
            contentColor = if (ativa) Color.White else Color.Black,
        ),
    ) { Text(texto, fontSize = 16.sp) }
}

@Composable
private fun Botao(texto: String, enabled: Boolean = true, aoClicar: () -> Unit) {
    Button(
        onClick = aoClicar,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { Text(texto, fontSize = 18.sp) }
}

private fun rotuloRevisao(quadro: QuadroLocal): String =
    if (quadro.vigenteCodigo.isBlank()) "Sem vigente" else "Rev ${quadro.vigenteCodigo}"

private fun horaLegivel(iso: String): String {
    if (iso.isBlank() || iso == "nunca") return "nunca"
    return runCatching {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Sao_Paulo"))
            .format(Instant.parse(iso))
    }.getOrDefault(iso)
}

private fun etapaDoCartao(dao: QuadroDao, quadro: QuadroLocal, versao: Int): String {
    var ultima = "Sem etapa"
    var andamento: String? = null
    Etapas.forEach { (codigo, nome) ->
        when (dao.ler("etapa:${quadro.id}:$codigo")) {
            "concluida" -> ultima = nome
            "em_andamento" -> andamento = nome
        }
    }
    return andamento ?: if (ultima == "Sem etapa") ultima else ultima
}

private fun rotuloDaEtapa(dao: QuadroDao, fila: List<FilaLocal>, quadroId: String, codigo: String, versao: Int): String {
    val estado = dao.ler("etapa:$quadroId:$codigo")
    val movimentos = fila.filter { it.quadroId == quadroId && it.etapaCodigo == codigo && it.tipo.startsWith("etapa_") }
    return when {
        estado == "concluida" -> "concluída"
        movimentos.any { it.estado == "na_fila" } -> "na fila"
        estado == "em_andamento" -> "em andamento"
        else -> rotuloEtapa(false, false, movimentos.lastOrNull())
    }
}

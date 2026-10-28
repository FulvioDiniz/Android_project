package com.fulvio.quadros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Etapas = listOf(
    "estrutura" to "Estrutura",
    "mecanica" to "Mecanica",
    "fiacao" to "Fiacao",
    "identificacao" to "Identificacao",
    "teste" to "Teste",
    "expedicao" to "Expedicao",
)

private enum class Aba(val rotulo: String) {
    Projetos("Projetos"),
    Desenho("Desenho"),
    Apontar("Apontar"),
    Oficina("Oficina"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = banco(this).dao()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Laranja,
                    onPrimary = Branco,
                    secondary = Laranja,
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
    var servidor by remember { mutableStateOf(dao.ler("servidor") ?: "http://192.168.0.105:8000") }
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
    var aba by remember { mutableStateOf(Aba.Projetos) }
    var busca by remember { mutableStateOf("") }
    var quadros by remember { mutableStateOf(dao.listar()) }
    var fila by remember { mutableStateOf(dao.listarFila()) }
    var versao by remember { mutableIntStateOf(0) }
    var pinos by remember { mutableStateOf<Map<String, List<PinoDesenho>>>(emptyMap()) }
    var tracos by remember { mutableStateOf<Map<String, List<TracoDesenho>>>(emptyMap()) }
    var statusSync by remember { mutableStateOf("") }
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
            if (aberto == null) aba = Aba.Projetos
        }
    }

    fun ciclo(base: String, acesso: String) {
        escopo.launch {
            val pasta = contexto.filesDir
            statusSync = "Sincronizando projetos..."
            enlace = "Sincronizando"
            withContext(Dispatchers.IO) { drenarFila(dao) }
            try {
                val lista = withContext(Dispatchers.IO) { baixarQuadros(base, acesso) }
                withContext(Dispatchers.IO) {
                    dao.limpar()
                    dao.salvar(lista)
                    dao.gravar(Meta("servidor", base))
                    dao.gravar(Meta("token", acesso))
                    dao.gravar(Meta("sync", Instant.now().toString()))
                    // PDF sob demanda ao abrir o desenho — nao baixa os centenas de arquivos na sync.
                    val abertoId = aberto?.arquivoId.orEmpty()
                    if (abertoId.isNotBlank()) {
                        runCatching { copiarPdfSeFaltar(dao, base, acesso, abertoId, pasta) }
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
                        dao.gravar(Meta("alerta", "Revisao nova. Confira a vigente antes de seguir."))
                    }
                }
                val comPdf = lista.count { it.arquivoId.isNotBlank() }
                statusSync = "${lista.size} projeto(s) · $comPdf com PDF no servidor"
                enlace = "No ar"
                erro = ""
            } catch (falha: Exception) {
                enlace = "Sem enlace"
                erro = falha.message ?: "Sem enlace"
                statusSync = "Falha na sync"
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

    fun textoApontamento(pino: PinoDesenho, mensagem: String): String {
        val tipo = if (pino.tipo == ModoDesenho.Erro) "ERRO" else "MUDANCA"
        return "[$tipo] Folha ${pino.pagina + 1} · marca ${(pino.x * 100).toInt()}% x ${(pino.y * 100).toInt()}%\n$mensagem"
    }

    fun enviarApontamento(quadro: QuadroLocal, pino: PinoDesenho, mensagem: String, comFoto: Boolean = false, uriFoto: android.net.Uri? = null) {
        val texto = textoApontamento(pino, mensagem)
        val duvida = novaOperacao(
            tipo = "duvida",
            quadroId = quadro.id,
            revisaoId = quadro.vigenteId,
            texto = texto,
        )
        if (!comFoto || uriFoto == null) {
            enfileirar(listOf(duvida))
            return
        }
        val destino = File(contexto.filesDir, "${duvida.operacaoId}.jpg")
        contexto.contentResolver.openInputStream(uriFoto)?.use { entrada ->
            destino.outputStream().use { entrada.copyTo(it) }
        } ?: run {
            enfileirar(listOf(duvida))
            return
        }
        val foto = novaOperacao(
            tipo = "foto",
            quadroId = quadro.id,
            texto = texto,
            extra = JSONObject()
                .put("caminho", destino.absolutePath)
                .put("observacao_operacao", duvida.operacaoId)
                .toString(),
        )
        enfileirar(listOf(duvida, foto))
    }

    var pendenteFoto by remember { mutableStateOf<Pair<QuadroLocal, Pair<PinoDesenho, String>>?>(null) }
    val pegarFoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pendente = pendenteFoto
        pendenteFoto = null
        if (uri == null || pendente == null) return@rememberLauncherForActivityResult
        enviarApontamento(pendente.first, pendente.second.first, pendente.second.second, comFoto = true, uriFoto = uri)
    }

    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            agendarFila(contexto)
            ciclo(servidor, token)
            while (true) {
                delay(45_000)
                if (token.isNotBlank()) ciclo(servidor, token)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().background(Fundo)) {
        if (token.isBlank()) {
            Login(
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
                            erro = falha.message ?: "Nao entrou"
                            enlace = "Sem enlace"
                        }
                    }
                },
            )
        } else {
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    aba == Aba.Projetos || aberto == null -> ListaProjetos(
                        quadros = quadros,
                        busca = busca,
                        nome = nome,
                        offline = offline,
                        enlace = enlace,
                        statusSync = statusSync,
                        dao = dao,
                        versao = versao,
                        aoBusca = { busca = it },
                        aoAbrir = {
                            aberto = it
                            aba = Aba.Desenho
                            val alvo = it
                            if (alvo.arquivoId.isNotBlank() && token.isNotBlank()) {
                                escopo.launch {
                                    statusSync = "Baixando PDF de ${alvo.codigo}..."
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            copiarPdfSeFaltar(dao, servidor, token, alvo.arquivoId, contexto.filesDir)
                                        }
                                    }
                                    statusSync = "PDF pronto · ${alvo.codigo}"
                                    versao += 1
                                }
                            }
                        },
                        aoAtualizar = { ciclo(servidor, token) },
                        aoSair = {
                            token = ""
                            nome = ""
                            aberto = null
                            aba = Aba.Projetos
                            escopo.launch(Dispatchers.IO) { dao.gravar(Meta("token", "")) }
                        },
                    )
                    aba == Aba.Desenho -> TelaDesenho(
                        quadro = aberto!!,
                        pinos = pinos[aberto!!.id].orEmpty(),
                        tracos = tracos[aberto!!.id].orEmpty(),
                        aoAtualizarPinos = { novos ->
                            pinos = pinos + (aberto!!.id to novos)
                        },
                        aoAtualizarTracos = { novos ->
                            tracos = tracos + (aberto!!.id to novos)
                        },
                        aoEnviar = { pino, mensagem ->
                            enviarApontamento(aberto!!, pino, mensagem)
                        },
                        aoFoto = { pino, mensagem ->
                            pendenteFoto = aberto!! to (pino to mensagem)
                            pegarFoto.launch("image/*")
                        },
                        aoVoltar = {
                            aberto = null
                            aba = Aba.Projetos
                        },
                    )
                    aba == Aba.Apontar -> TelaApontamentos(
                        quadro = aberto!!,
                        dao = dao,
                        fila = fila,
                        versao = versao,
                        pinos = pinos[aberto!!.id].orEmpty(),
                        aoDesenho = { aba = Aba.Desenho },
                    )
                    else -> TelaOficina(
                        quadro = aberto!!,
                        dao = dao,
                        fila = fila,
                        versao = versao,
                        alerta = alerta,
                        offline = offline,
                        enlace = enlace,
                        sync = sync,
                        aoConfirmar = {
                            val quadro = aberto ?: return@TelaOficina
                            if (quadro.vigenteId.isBlank()) return@TelaOficina
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
                        aoEnfileirar = { enfileirar(it) },
                    )
                }
            }
            Barra(aba, aberto != null) { nova ->
                if (nova != Aba.Projetos && aberto == null) return@Barra
                if (nova == Aba.Projetos) aberto = null
                aba = nova
            }
        }
    }
}

@Composable
private fun Login(
    servidor: String,
    login: String,
    senha: String,
    erro: String,
    aoServidor: (String) -> Unit,
    aoLogin: (String) -> Unit,
    aoSenha: (String) -> Unit,
    aoEntrar: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(GradienteMarca)
            .padding(24.dp),
    ) {
        val largo = maxWidth >= 720.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = if (largo) 480.dp else maxWidth)
                .align(if (largo) Alignment.Center else Alignment.TopCenter)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("QUADROS", color = Laranja, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Montagem em producao", color = Preto, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Projetos da engenharia chegam na sync. Abra o PDF, desenhe, marque erros e mande.",
                color = Cinza,
                fontSize = 16.sp,
            )
            Cartao {
                Campo("Servidor", servidor, aoMudar = aoServidor)
                Campo("Login", login, aoMudar = aoLogin)
                Campo("Senha", senha, senha = true, aoMudar = aoSenha)
                Botao("Entrar e puxar projetos", aoClicar = aoEntrar)
                if (erro.isNotBlank()) {
                    Text(erro, color = Vermelho, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ListaProjetos(
    quadros: List<QuadroLocal>,
    busca: String,
    nome: String,
    offline: Boolean,
    enlace: String,
    statusSync: String,
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
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Projetos", color = Preto, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "$nome · ${if (offline) "Offline" else enlace}",
                    color = Cinza,
                    fontSize = 14.sp,
                )
                if (statusSync.isNotBlank()) {
                    Text(statusSync, color = Laranja, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("${quadros.size}", color = Laranja, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Campo("Buscar codigo, OP ou cliente", busca, aoMudar = aoBusca)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Botao("Atualizar projetos", aoClicar = aoAtualizar) }
            Box(Modifier.weight(1f)) { Botao("Sair", preenchido = false, cor = Cinza, aoClicar = aoSair) }
        }
        if (visiveis.isEmpty()) {
            Cartao {
                Text(
                    if (quadros.isEmpty()) "Nenhum projeto ainda" else "Nada encontrado",
                    color = Preto,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (quadros.isEmpty()) {
                        "Toque em Atualizar. Os projetos das abas de engenharia entram com o PDF mais recente."
                    } else {
                        "Tente outro termo de busca."
                    },
                    color = Cinza,
                    fontSize = 15.sp,
                )
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val colunas = if (maxWidth >= 900.dp) 3 else if (maxWidth >= 600.dp) 2 else 1
                LazyVerticalGrid(
                    columns = GridCells.Fixed(colunas),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visiveis, key = { it.id }) { quadro ->
                        CartaoProjeto(quadro, etapaDoCartao(dao, quadro, versao)) { aoAbrir(quadro) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartaoProjeto(quadro: QuadroLocal, etapa: String, aoAbrir: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Branco)
            .border(1.5.dp, Borda, Forma)
            .clickable(onClick = aoAbrir)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(FormaSelo)
                .background(Laranja),
        )
        Text(quadro.codigo, color = Preto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SeloTexto(
            when {
                quadro.vigenteCodigo.isNotBlank() -> "Rev ${quadro.vigenteCodigo}"
                quadro.arquivoId.isNotBlank() -> "PDF disponivel"
                else -> "Sem PDF"
            },
            quadro.vigenteCodigo.isNotBlank() || quadro.arquivoId.isNotBlank(),
        )
        Text(
            "${quadro.clienteNome.ifBlank { "Sem cliente" }} · OP ${quadro.ordemNumero.ifBlank { "-" }}",
            color = Cinza,
            fontSize = 14.sp,
        )
        Text("Etapa $etapa", color = Preto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Abrir desenho ->", color = Laranja, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TelaApontamentos(
    quadro: QuadroLocal,
    dao: QuadroDao,
    fila: List<FilaLocal>,
    versao: Int,
    pinos: List<PinoDesenho>,
    aoDesenho: () -> Unit,
) {
    val notas = remember(quadro.id, versao) { dao.diarioDo(quadro.id) }
    val locais = fila.filter {
        it.quadroId == quadro.id && it.tipo in listOf("observacao", "duvida", "foto", "pendencia")
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Apontamentos", color = Preto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(quadro.codigo, color = Cinza, fontSize = 15.sp)
        Botao("Marcar / desenhar no PDF", aoClicar = aoDesenho)
        if (locais.isEmpty() && notas.isEmpty() && pinos.none { it.enviado }) {
            Cartao {
                Text("Nenhum apontamento ainda", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "No desenho, use Desenhar, Erro ou Mudanca e envie para a engenharia.",
                    color = Cinza,
                    fontSize = 14.sp,
                )
            }
        }
        locais.forEach { item ->
            Cartao {
                Text(
                    when (item.tipo) {
                        "duvida" -> "Enviado a engenharia"
                        "foto" -> "Foto anexada"
                        "pendencia" -> "Pendencia"
                        else -> "Observacao"
                    },
                    color = Preto,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(item.texto, color = Preto, fontSize = 15.sp)
                Text(rotuloFila(item), color = Cinza, fontSize = 13.sp)
            }
        }
        pinos.filter { it.enviado }.forEach { pino ->
            Cartao {
                Text(
                    if (pino.tipo == ModoDesenho.Erro) "Erro · folha ${pino.pagina + 1}" else "Mudanca · folha ${pino.pagina + 1}",
                    color = if (pino.tipo == ModoDesenho.Erro) Vermelho else Azul,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(pino.texto, color = Preto, fontSize = 15.sp)
            }
        }
        notas.filter { it.resposta.isNotBlank() }.forEach { nota ->
            Cartao {
                Text("Resposta da engenharia", color = Verde, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(nota.texto, color = Cinza, fontSize = 13.sp)
                Text(nota.resposta, color = Preto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TelaOficina(
    quadro: QuadroLocal,
    dao: QuadroDao,
    fila: List<FilaLocal>,
    versao: Int,
    alerta: String,
    offline: Boolean,
    enlace: String,
    sync: String,
    aoConfirmar: () -> Unit,
    aoEnfileirar: (List<FilaLocal>) -> Unit,
) {
    var etapaCodigo by remember(quadro.id) { mutableStateOf("fiacao") }
    var texto by remember(quadro.id) { mutableStateOf("") }
    var bloqueia by remember(quadro.id) { mutableStateOf(true) }
    val confirmada = remember(quadro.id, quadro.vigenteId, versao) { dao.ler("confirmada:${quadro.id}") }
    val confirmadaAgora = confirmada == quadro.vigenteId && quadro.vigenteId.isNotBlank()
    val trava = quadro.vigenteId.isNotBlank() &&
        confirmada != quadro.vigenteId &&
        quadro.revisaoTipo in setOf("bloqueio", "reavaliacao")
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Oficina", color = Preto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Cartao {
            Text(quadro.codigo, color = Preto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            SeloTexto(
                if (quadro.vigenteCodigo.isNotBlank()) "Rev ${quadro.vigenteCodigo}" else "Sem vigente",
                quadro.vigenteCodigo.isNotBlank(),
            )
            Text("Enlace: ${if (offline) "Offline" else enlace}", color = Cinza, fontSize = 14.sp)
            Text("Sync: ${horaLegivel(sync)}", color = Cinza, fontSize = 14.sp)
            Text(
                when {
                    quadro.vigenteId.isBlank() -> "Sem vigencia publicada"
                    confirmadaAgora -> "Leitura confirmada"
                    else -> "Leitura pendente"
                },
                color = Preto,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            if (alerta.isNotBlank()) {
                Text(alerta, color = Vermelho, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (quadro.vigenteId.isNotBlank() && !confirmadaAgora) {
                Botao("Confirmar leitura da vigente", aoClicar = aoConfirmar)
            }
        }
        Cartao {
            Text("Etapas", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (trava) {
                Text("Confirme a leitura da vigente antes de avancar a etapa.", color = Vermelho, fontSize = 14.sp)
            }
            Etapas.forEach { (codigo, nome) ->
                Chip(
                    "$nome · ${rotuloDaEtapa(dao, fila, quadro.id, codigo, versao)}",
                    codigo == etapaCodigo,
                    Laranja,
                ) { etapaCodigo = codigo }
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    Botao("Iniciar", enabled = !trava, preenchido = false) {
                        aoEnfileirar(listOf(novaOperacao(tipo = "etapa_iniciar", quadroId = quadro.id, etapaCodigo = etapaCodigo)))
                    }
                }
                Box(Modifier.weight(1f)) {
                    Botao("Concluir", enabled = !trava) {
                        aoEnfileirar(listOf(novaOperacao(tipo = "etapa_concluir", quadroId = quadro.id, etapaCodigo = etapaCodigo)))
                    }
                }
            }
        }
        Cartao {
            Text("Pendencia rapida", color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Campo("Descricao", texto, linhas = 3) { texto = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Bloqueia etapa", bloqueia, Vermelho) { bloqueia = true }
                Chip("Nao bloqueia", !bloqueia, Verde) { bloqueia = false }
            }
            Botao("Abrir pendencia", enabled = texto.trim().isNotBlank(), preenchido = false) {
                val anotacao = texto.trim()
                texto = ""
                aoEnfileirar(
                    listOf(
                        novaOperacao(
                            tipo = "pendencia",
                            quadroId = quadro.id,
                            texto = anotacao,
                            extra = JSONObject().put("bloqueia_etapa", bloqueia).toString(),
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Barra(aba: Aba, temProjeto: Boolean, aoEscolher: (Aba) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Branco)
            .border(1.dp, Borda)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Aba.entries.forEach { item ->
            val ativa = aba == item
            val liberada = item == Aba.Projetos || temProjeto
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clip(FormaPequena)
                    .background(if (ativa) LaranjaSuave else Branco)
                    .then(if (ativa) Modifier.border(1.5.dp, Laranja, FormaPequena) else Modifier)
                    .clickable(enabled = liberada) { aoEscolher(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                IconeAba(item, if (liberada) Preto else Cinza)
                Text(
                    item.rotulo,
                    color = if (liberada) Preto else Cinza,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun IconeAba(aba: Aba, cor: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(20.dp).padding(bottom = 2.dp)) {
        val traco = Stroke(width = 1.7.dp.toPx())
        when (aba) {
            Aba.Projetos -> {
                val folga = 3.dp.toPx()
                val lado = (size.width - folga) / 2f
                repeat(2) { coluna ->
                    repeat(2) { linha ->
                        drawRoundRect(
                            color = cor,
                            topLeft = Offset(coluna * (lado + folga), linha * (lado + folga)),
                            size = Size(lado, lado),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                            style = traco,
                        )
                    }
                }
            }
            Aba.Desenho -> {
                drawRoundRect(color = cor, cornerRadius = CornerRadius(3.dp.toPx()), style = traco)
                listOf(0.34f, 0.5f, 0.66f).forEach { y ->
                    drawLine(cor, Offset(size.width * 0.22f, size.height * y), Offset(size.width * 0.78f, size.height * y), 1.7.dp.toPx())
                }
            }
            Aba.Apontar -> {
                drawCircle(color = cor, radius = size.minDimension / 2.2f, style = traco)
                drawLine(cor, Offset(size.width * 0.5f, size.height * 0.28f), Offset(size.width * 0.5f, size.height * 0.55f), 1.7.dp.toPx())
                drawCircle(color = cor, radius = 2.2.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.7f))
            }
            Aba.Oficina -> {
                drawRoundRect(color = cor, cornerRadius = CornerRadius(3.dp.toPx()), style = traco)
                drawLine(cor, Offset(size.width * 0.28f, 0f), Offset(size.width * 0.28f, size.height), 1.7.dp.toPx())
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
        estado == "concluida" -> "concluida"
        movimentos.any { it.estado == "na_fila" } -> "na fila"
        estado == "em_andamento" -> "em andamento"
        else -> rotuloEtapa(false, false, movimentos.lastOrNull())
    }
}

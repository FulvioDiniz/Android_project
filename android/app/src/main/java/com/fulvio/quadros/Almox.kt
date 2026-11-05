package com.fulvio.quadros

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ProdutoAlmox(
    val codigo: String,
    val nome: String,
    val saldo: Double,
    val unidade: String,
    val modelo: String,
)

data class ItemCarrinhoAlmox(
    val produto: ProdutoAlmox,
    val quantidade: Double,
)

data class PedidoAlmoxResumo(
    val id: String,
    val status: String,
    val pedidoEm: String,
    val quadroCodigo: String,
    val itens: Int,
    val observacao: String,
)

fun buscarProdutosAlmox(base: String, token: String, q: String): List<ProdutoAlmox> {
    val json = pedir(base, "/api/almox/produtos?q=${java.net.URLEncoder.encode(q, "UTF-8")}", "GET", token, null)
    val arr = json.optJSONArray("itens") ?: JSONArray()
    return List(arr.length()) { i ->
        val item = arr.getJSONObject(i)
        ProdutoAlmox(
            codigo = item.optString("codigo"),
            nome = item.optString("nome"),
            saldo = item.optDouble("saldo"),
            unidade = item.optString("unidade", "UN"),
            modelo = item.optString("modelo"),
        )
    }
}

fun criarPedidoAlmox(
    base: String,
    token: String,
    quadroId: String,
    itens: List<ItemCarrinhoAlmox>,
    observacao: String,
): JSONObject {
    val arr = JSONArray()
    itens.forEach { item ->
        arr.put(
            JSONObject()
                .put("codigo", item.produto.codigo)
                .put("nome", item.produto.nome)
                .put("unidade", item.produto.unidade)
                .put("quantidade", item.quantidade)
                .put("saldo", item.produto.saldo)
                .put("modelo", item.produto.modelo),
        )
    }
    val corpo = JSONObject()
        .put("quadro_id", quadroId)
        .put("observacao", observacao)
        .put("itens", arr)
    return pedir(base, "/api/almox/pedidos", "POST", token, corpo.toString())
}

fun listarPedidosAlmox(base: String, token: String, quadroId: String, status: String = "todos"): List<PedidoAlmoxResumo> {
    val caminho = "/api/almox/pedidos?status=${java.net.URLEncoder.encode(status, "UTF-8")}" +
        "&quadro_id=${java.net.URLEncoder.encode(quadroId, "UTF-8")}"
    val texto = pedirTexto(base, caminho, "GET", token, null)
    val arr = JSONArray(texto)
    return List(arr.length()) { i ->
        val item = arr.getJSONObject(i)
        val itens = item.optJSONArray("itens") ?: JSONArray()
        PedidoAlmoxResumo(
            id = item.optString("id"),
            status = item.optString("status"),
            pedidoEm = item.optString("pedido_em"),
            quadroCodigo = item.optString("quadro_codigo"),
            itens = itens.length(),
            observacao = item.optString("observacao"),
        )
    }
}

private fun horaPedido(iso: String): String {
    if (iso.isBlank()) return "—"
    return runCatching {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Sao_Paulo"))
            .format(Instant.parse(iso))
    }.getOrDefault(iso)
}

private fun rotuloStatusAlmox(status: String): String = when (status) {
    "fila" -> "Na fila do estoque"
    "separando" -> "Separando"
    "entregue" -> "Entregue"
    "cancelado" -> "Cancelado"
    else -> status
}

@Composable
fun TelaAlmox(
    quadro: QuadroLocal,
    servidor: String,
    token: String,
) {
    var busca by remember { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<ProdutoAlmox>>(emptyList()) }
    var carrinho by remember { mutableStateOf<List<ItemCarrinhoAlmox>>(emptyList()) }
    var observacao by remember { mutableStateOf("") }
    var pedidos by remember { mutableStateOf<List<PedidoAlmoxResumo>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    fun recarregarPedidos() {
        escopo.launch {
            runCatching {
                withContext(Dispatchers.IO) { listarPedidosAlmox(servidor, token, quadro.id, "todos") }
            }.onSuccess {
                pedidos = it
            }.onFailure {
                // silencioso na lista; busca mostra erro
            }
        }
    }

    LaunchedEffect(quadro.id, token) {
        if (token.isNotBlank()) recarregarPedidos()
    }

    LaunchedEffect(busca) {
        val termo = busca.trim()
        if (termo.length < 2) {
            resultados = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        status = "Buscando no Firebird…"
        erro = ""
        runCatching {
            withContext(Dispatchers.IO) { buscarProdutosAlmox(servidor, token, termo) }
        }.onSuccess {
            resultados = it
            status = if (it.isEmpty()) "Nada encontrado para “$termo”" else "${it.size} produto(s)"
        }.onFailure {
            resultados = emptyList()
            status = ""
            erro = it.message ?: "Falha na busca"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Almoxarifado", color = Preto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Projeto ${quadro.codigo} · peça cai na fila das meninas do estoque com horário",
            color = Cinza,
            fontSize = 13.sp,
        )
        Campo("Pesquisar no Firebird (código, nome ou modelo)", busca, aoMudar = { busca = it })
        if (status.isNotBlank()) {
            Text(status, color = Laranja, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (erro.isNotBlank()) {
            Text(erro, color = Vermelho, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(FormaPequena)
                .background(Branco)
                .border(1.dp, Borda, FormaPequena),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(resultados, key = { it.codigo }) { produto ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val ja = carrinho.find { it.produto.codigo == produto.codigo }
                            carrinho = if (ja == null) {
                                carrinho + ItemCarrinhoAlmox(produto, 1.0)
                            } else {
                                carrinho.map {
                                    if (it.produto.codigo == produto.codigo) {
                                        it.copy(quantidade = it.quantidade + 1)
                                    } else {
                                        it
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(produto.codigo, color = Laranja, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(produto.nome, color = Preto, fontSize = 14.sp, maxLines = 2)
                        Text(
                            "Saldo ${produto.saldo} ${produto.unidade}" +
                                if (produto.modelo.isNotBlank()) " · ${produto.modelo}" else "",
                            color = Cinza,
                            fontSize = 12.sp,
                        )
                    }
                    Text("+", color = Laranja, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (resultados.isEmpty() && busca.trim().length >= 2 && status.isNotBlank() && erro.isBlank()) {
                item {
                    Text(
                        "Nenhum produto na lista.",
                        color = Cinza,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (carrinho.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(FormaPequena)
                    .background(LaranjaSuave)
                    .border(1.dp, Borda, FormaPequena)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pedido (${carrinho.size})", color = Preto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                carrinho.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.produto.codigo, color = Laranja, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(item.produto.nome, color = Preto, fontSize = 13.sp, maxLines = 1)
                        }
                        Text("−", color = Preto, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier
                            .clickable {
                                carrinho = carrinho.mapNotNull {
                                    if (it.produto.codigo != item.produto.codigo) it
                                    else if (it.quantidade <= 1) null
                                    else it.copy(quantidade = it.quantidade - 1)
                                }
                            }
                            .padding(8.dp))
                        Text(
                            "${item.quantidade.toInt()} ${item.produto.unidade}",
                            color = Preto,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Text("+", color = Laranja, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier
                            .clickable {
                                carrinho = carrinho.map {
                                    if (it.produto.codigo == item.produto.codigo) {
                                        it.copy(quantidade = it.quantidade + 1)
                                    } else {
                                        it
                                    }
                                }
                            }
                            .padding(8.dp))
                    }
                }
                Campo("Observação para o almox (opcional)", observacao, linhas = 2, aoMudar = { observacao = it })
                Botao("ENVIAR PARA O ESTOQUE", enabled = !enviando && token.isNotBlank()) {
                    enviando = true
                    erro = ""
                    escopo.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                criarPedidoAlmox(servidor, token, quadro.id, carrinho, observacao.trim())
                            }
                            carrinho = emptyList()
                            observacao = ""
                            busca = ""
                            resultados = emptyList()
                            status = "Pedido enviado · entrou na fila com horário"
                            recarregarPedidos()
                        } catch (e: Exception) {
                            erro = e.message ?: "Não enviou o pedido"
                        } finally {
                            enviando = false
                        }
                    }
                }
            }
        }

        if (pedidos.isNotEmpty()) {
            Text("Fila deste projeto", color = Cinza, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pedidos.take(12).forEach { pedido ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FormaPequena)
                            .background(Branco)
                            .border(1.dp, Borda, FormaPequena)
                            .padding(10.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(rotuloStatusAlmox(pedido.status), color = Laranja, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${horaPedido(pedido.pedidoEm)} · ${pedido.itens} item(ns)",
                                color = Preto,
                                fontSize = 13.sp,
                            )
                            if (pedido.observacao.isNotBlank()) {
                                Text(pedido.observacao, color = Cinza, fontSize = 12.sp, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

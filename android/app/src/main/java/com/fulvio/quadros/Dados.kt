package com.fulvio.quadros

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

const val INSTALACAO = "tablet-fase-1"

@Entity(tableName = "quadro_local")
data class QuadroLocal(
    @PrimaryKey val id: String,
    val codigo: String,
    val titulo: String,
    val ordemNumero: String,
    val clienteNome: String,
    val responsavelNome: String,
    val vigenteCodigo: String,
    val vigenteId: String,
    val arquivoId: String,
    val revisaoTipo: String,
)

@Entity(tableName = "meta")
data class Meta(
    @PrimaryKey val chave: String,
    val valor: String,
)

@Entity(tableName = "fila")
data class FilaLocal(
    @PrimaryKey val operacaoId: String,
    val tipo: String,
    val quadroId: String,
    val revisaoId: String,
    val etapaCodigo: String,
    val texto: String,
    val ocorridoEm: String,
    val estado: String,
    val mensagem: String,
)

@Dao
interface QuadroDao {
    @Query("SELECT * FROM quadro_local ORDER BY codigo")
    fun listar(): List<QuadroLocal>

    @Query("SELECT * FROM quadro_local WHERE id = :id")
    fun um(id: String): QuadroLocal?

    @Query("DELETE FROM quadro_local")
    fun limpar()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun salvar(itens: List<QuadroLocal>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun salvarUm(item: QuadroLocal)

    @Query("SELECT valor FROM meta WHERE chave = :chave")
    fun ler(chave: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun gravar(meta: Meta)

    @Query("SELECT * FROM fila ORDER BY ocorridoEm")
    fun listarFila(): List<FilaLocal>

    @Query("SELECT * FROM fila WHERE estado = 'na_fila' ORDER BY ocorridoEm")
    fun pendentes(): List<FilaLocal>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun enfileirar(item: FilaLocal)

    @Query("UPDATE fila SET estado = :estado, mensagem = :mensagem WHERE operacaoId = :id")
    fun marcar(id: String, estado: String, mensagem: String)
}

@Database(entities = [QuadroLocal::class, Meta::class, FilaLocal::class], version = 3)
abstract class BancoLocal : RoomDatabase() {
    abstract fun dao(): QuadroDao
}

private var instancia: BancoLocal? = null

fun banco(context: Context): BancoLocal {
    val ja = instancia
    if (ja != null) return ja
    return synchronized(BancoLocal::class.java) {
        instancia ?: Room.databaseBuilder(
            context.applicationContext,
            BancoLocal::class.java,
            "quadros.db",
        )
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()
            .also { instancia = it }
    }
}

data class SessaoRemota(val token: String, val nome: String, val perfil: String)

data class RespostaApi(val codigo: Int, val corpo: String)

fun rotuloFila(item: FilaLocal): String = when (item.estado) {
    "na_fila" -> "na fila"
    "aceita" -> "aceita pela API"
    "conflito" -> "conflito"
    else -> item.mensagem.ifBlank { "recusada pela API" }
}

fun rotuloEtapa(aceitaPelaApi: Boolean, pendente: Boolean, ultima: FilaLocal?): String = when {
    aceitaPelaApi -> "concluída"
    pendente -> "na fila"
    ultima != null -> rotuloFila(ultima)
    else -> "em aberto"
}

fun novaOperacao(
    tipo: String,
    quadroId: String,
    revisaoId: String = "",
    etapaCodigo: String = "",
    texto: String = "",
): FilaLocal = FilaLocal(
    operacaoId = UUID.randomUUID().toString(),
    tipo = tipo,
    quadroId = quadroId,
    revisaoId = revisaoId,
    etapaCodigo = etapaCodigo,
    texto = texto,
    ocorridoEm = Instant.now().toString(),
    estado = "na_fila",
    mensagem = "na fila",
)

fun corpoFila(item: FilaLocal): JSONObject {
    val json = JSONObject()
        .put("operacao_id", item.operacaoId)
        .put("tipo", item.tipo)
        .put("ocorrido_em", item.ocorridoEm)
    if (item.quadroId.isNotBlank()) json.put("quadro_id", item.quadroId)
    if (item.revisaoId.isNotBlank()) json.put("revisao_id", item.revisaoId)
    if (item.texto.isNotBlank()) json.put("texto", item.texto)
    if (item.etapaCodigo.isNotBlank()) json.put("etapa_codigo", item.etapaCodigo)
    return json
}

fun entrar(base: String, login: String, senha: String): SessaoRemota {
    val corpo = JSONObject()
        .put("login", login)
        .put("senha", senha)
        .put("identificador_instalacao", INSTALACAO)
    val resposta = pedir(base, "/api/sessao", "POST", null, corpo.toString())
    return SessaoRemota(
        token = resposta.getString("token"),
        nome = resposta.getJSONObject("usuario").getString("nome"),
        perfil = resposta.getString("perfil"),
    )
}

fun baixarQuadros(base: String, token: String): List<QuadroLocal> {
    val lista = JSONArray(pedirTexto(base, "/api/quadros", "GET", token, null))
    return List(lista.length()) { i ->
        val item = lista.getJSONObject(i)
        QuadroLocal(
            id = item.getString("id"),
            codigo = item.getString("codigo"),
            titulo = item.getString("titulo"),
            ordemNumero = item.getString("ordem_numero"),
            clienteNome = item.getString("cliente_nome"),
            responsavelNome = item.optString("responsavel_nome"),
            vigenteCodigo = item.optString("revisao_vigente_codigo"),
            vigenteId = item.optString("revisao_vigente_id"),
            arquivoId = item.optString("arquivo_id"),
            revisaoTipo = item.optString("revisao_tipo"),
        )
    }
}

fun historico(base: String, token: String, quadroId: String): JSONObject =
    pedir(base, "/api/quadros/$quadroId/revisoes", "GET", token, null)

fun notificacoes(base: String, token: String, desde: String): JSONArray =
    JSONArray(pedirTexto(base, "/api/notificacoes?desde=$desde", "GET", token, null))

fun baixarPdf(base: String, token: String, arquivoId: String, destino: File) {
    var offset = if (destino.exists()) destino.length() else 0L
    var etag = ""
    var total = Long.MAX_VALUE
    while (offset < total) {
        val conexao = URL(base.trimEnd('/') + "/api/arquivos/$arquivoId").openConnection() as HttpURLConnection
        conexao.setRequestProperty("Authorization", "Bearer $token")
        conexao.setRequestProperty("Range", "bytes=$offset-")
        val codigo = conexao.responseCode
        if (codigo !in listOf(200, 206)) {
            throw IllegalStateException("Download recusado")
        }
        etag = conexao.getHeaderField("ETag").orEmpty().trim('"')
        val faixa = conexao.getHeaderField("Content-Range").orEmpty()
        if (faixa.contains("/")) {
            total = faixa.substringAfter("/").toLongOrNull() ?: total
        } else if (codigo == 200) {
            total = conexao.contentLengthLong
        }
        destino.appendBytes(conexao.inputStream.readBytes())
        offset = destino.length()
        if (codigo == 200) break
    }
    if (sha256(destino) != etag) {
        destino.delete()
        throw IllegalStateException("Hash não confere. O PDF não foi liberado.")
    }
}

fun copiarPdfSeFaltar(dao: QuadroDao, base: String, token: String, arquivoId: String, pasta: File) {
    if (arquivoId.isBlank()) return
    val destino = File(pasta, "$arquivoId.pdf")
    if (dao.ler("pdf:$arquivoId") == "ok" && destino.exists()) return
    baixarPdf(base, token, arquivoId, destino)
    dao.gravar(Meta("pdf:$arquivoId", "ok"))
}

fun drenarFila(dao: QuadroDao): Boolean {
    val base = dao.ler("servidor") ?: return false
    val token = dao.ler("token").orEmpty()
    if (token.isBlank()) return false
    val pendentes = dao.pendentes()
    if (pendentes.isEmpty()) return true
    for (item in pendentes) {
        val resposta = postarJson(base, "/api/sync/operacoes", token, corpoFila(item).toString())
        if (resposta.codigo == 0) return false
        aplicarResposta(dao, item, resposta)
    }
    dao.gravar(Meta("sync", Instant.now().toString()))
    return true
}

private fun aplicarResposta(dao: QuadroDao, item: FilaLocal, resposta: RespostaApi) {
    val json = runCatching { JSONObject(resposta.corpo) }.getOrNull()
    val ponteiro = json?.optString("revisao_vigente_id").orEmpty()
    if (ponteiro.isNotBlank() && item.quadroId.isNotBlank()) {
        aplicarVigente(dao, item.quadroId, ponteiro)
    }
    if (resposta.codigo in 200..299) {
        dao.marcar(item.operacaoId, "aceita", "aceita pela API")
        if (item.tipo == "confirmacao_leitura" && item.revisaoId.isNotBlank()) {
            dao.gravar(Meta("confirmada:${item.quadroId}", item.revisaoId))
            dao.gravar(Meta("confirmada_em:${item.quadroId}", item.ocorridoEm))
            dao.gravar(Meta("alerta", ""))
        }
        if (item.tipo == "etapa_concluir" && json?.optString("status") == "concluida") {
            dao.gravar(Meta("etapa:${item.quadroId}:${item.etapaCodigo}", "concluida"))
        }
        return
    }
    val erro = json?.optString("erro").orEmpty()
    val mensagem = json?.optString("mensagem").orEmpty().ifBlank { "Recusa ${resposta.codigo}" }
    val estado = if (erro == "conflito_operacao" || erro == "relogio_invalido") "conflito" else "recusada"
    dao.marcar(item.operacaoId, estado, mensagem)
}

fun aplicarVigente(dao: QuadroDao, quadroId: String, vigenteId: String) {
    val atual = dao.um(quadroId) ?: return
    if (atual.vigenteId == vigenteId) return
    dao.salvarUm(atual.copy(vigenteId = vigenteId, arquivoId = ""))
}

fun postarJson(base: String, caminho: String, token: String, corpo: String): RespostaApi {
    return try {
        val conexao = URL(base.trimEnd('/') + caminho).openConnection() as HttpURLConnection
        conexao.requestMethod = "POST"
        conexao.connectTimeout = 8000
        conexao.readTimeout = 8000
        conexao.setRequestProperty("Accept", "application/json")
        conexao.setRequestProperty("Authorization", "Bearer $token")
        conexao.doOutput = true
        conexao.setRequestProperty("Content-Type", "application/json")
        conexao.outputStream.use { it.write(corpo.toByteArray()) }
        val codigo = conexao.responseCode
        val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
        RespostaApi(codigo, fluxo?.bufferedReader()?.readText().orEmpty())
    } catch (falha: Exception) {
        RespostaApi(0, falha.message ?: "sem rede")
    }
}

private fun sha256(arquivo: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    arquivo.inputStream().use { entrada ->
        val bloco = ByteArray(8192)
        while (true) {
            val lido = entrada.read(bloco)
            if (lido < 0) break
            digest.update(bloco, 0, lido)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun pedir(base: String, caminho: String, metodo: String, token: String?, corpo: String?): JSONObject {
    return JSONObject(pedirTexto(base, caminho, metodo, token, corpo))
}

private fun pedirTexto(base: String, caminho: String, metodo: String, token: String?, corpo: String?): String {
    val url = URL(base.trimEnd('/') + caminho)
    val conexao = url.openConnection() as HttpURLConnection
    conexao.requestMethod = metodo
    conexao.connectTimeout = 8000
    conexao.readTimeout = 8000
    conexao.setRequestProperty("Accept", "application/json")
    if (token != null) {
        conexao.setRequestProperty("Authorization", "Bearer $token")
    }
    if (corpo != null) {
        conexao.doOutput = true
        conexao.setRequestProperty("Content-Type", "application/json")
        conexao.outputStream.use { it.write(corpo.toByteArray()) }
    }
    val codigo = conexao.responseCode
    val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
    val texto = fluxo.bufferedReader().readText()
    if (codigo !in 200..299) {
        val mensagem = runCatching { JSONObject(texto).optString("mensagem") }.getOrDefault(texto)
        throw IllegalStateException(mensagem.ifBlank { "Falha $codigo" })
    }
    return texto
}

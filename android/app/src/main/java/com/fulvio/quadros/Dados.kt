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
import java.net.HttpURLConnection
import java.net.URL

const val INSTALACAO = "tablet-fase-1"

@Entity(tableName = "quadro_local")
data class QuadroLocal(
    @PrimaryKey val id: String,
    val codigo: String,
    val titulo: String,
    val ordemNumero: String,
    val clienteNome: String,
    val responsavelNome: String,
)

@Entity(tableName = "meta")
data class Meta(
    @PrimaryKey val chave: String,
    val valor: String,
)

@Dao
interface QuadroDao {
    @Query("SELECT * FROM quadro_local ORDER BY codigo")
    fun listar(): List<QuadroLocal>

    @Query("DELETE FROM quadro_local")
    fun limpar()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun salvar(itens: List<QuadroLocal>)

    @Query("SELECT valor FROM meta WHERE chave = :chave")
    fun ler(chave: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun gravar(meta: Meta)
}

@Database(entities = [QuadroLocal::class, Meta::class], version = 1)
abstract class BancoLocal : RoomDatabase() {
    abstract fun dao(): QuadroDao
}

fun banco(context: Context): BancoLocal =
    Room.databaseBuilder(context, BancoLocal::class.java, "quadros.db")
        .allowMainThreadQueries()
        .build()

data class SessaoRemota(val token: String, val nome: String, val perfil: String)

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
        )
    }
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

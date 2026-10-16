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
                quadros = dao.listar()
                erro = ""
            } catch (falha: Exception) {
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
            Text("$nome · tablet-fase-1", fontWeight = FontWeight.SemiBold)
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
                    Text(quadro.titulo)
                    Text("${quadro.clienteNome} · ${quadro.ordemNumero}")
                }
            }
        }
    }
}

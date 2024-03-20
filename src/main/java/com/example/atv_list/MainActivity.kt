package com.example.atv_list

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val meuOnibus = onibus(42)
        val meuCaminhao = caminhau(10000f)

        Log.d("MainActivity", "Ônibus - Nome: ${meuOnibus.nome}, Fabricante: ${meuOnibus.fabricante}, Número de Lugares: ${meuOnibus.numero_lugar}")
        Log.d("MainActivity", "Caminhão - Nome: ${meuCaminhao.nome}, Fabricante: ${meuCaminhao.fabricante}, Peso: ${meuCaminhao.peso}")
    }
}

open class automovel(val nome: String, val fabricante: String)

class onibus(val numero_lugar: Int) : automovel(nome = "oi", fabricante = "teste")
class caminhau(val peso: Float) : automovel(nome = "vrum", fabricante = "gol")



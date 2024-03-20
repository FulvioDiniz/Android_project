package com.example.atv_fazenda

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //val MinhaFazenda = Fazenda("1","DoFulvio", 10000000000.0F,19.41F,47.90F)
        //Log.i("aqui",MinhaFazenda.Registro + MinhaFazenda.nome)
        val minhaFazenda = Fazenda("1","DoFulvio", 10000000000.0F,19.41F,47.90F)
        val banco = CrudMainSQlite()
        val banco2 = CrudSQLite()
        banco.insere_dadosContrutor(minhaFazenda, banco2)
    }
}


package com.example.atv_fazenda

import android.content.ContentValues
import android.util.Log

class CrudMainSQlite {

    fun insere_dadosContrutor(minhaFazenda: Fazenda, meuBanco : CrudSQLite){
        val db_insercao1 = meuBanco.writableDatabase
        val cv_valores1 = ContentValues().apply {
            put("Register",minhaFazenda.Registro)
            put("name", minhaFazenda.nome)
            put("Value", minhaFazenda.Valor)
            put("Latitude", minhaFazenda.Latitude)
            put("Longitude",minhaFazenda.longitude)
        }
        //nullColumnHack -> Sometimes you want to insert an empty row, in that case ContentValues have no content value, and you should use nullColumnHack.
        val confirmaInsercao1 = db_insercao1?.insert("TableCadastro", null, cv_valores1)
        Log.i("Teste","Inserção: "+ confirmaInsercao1)

    }

}

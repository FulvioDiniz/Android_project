package com.example.atv_fazenda

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Factory -> Used to allow returning sub-classes of Cursor when calling query.
class CrudSQLite(context: Context) : SQLiteOpenHelper(context, "BancoFazenda", null, 1)
{
    override fun onCreate(db: SQLiteDatabase)
    {
        val nomeTabela = "TableCadastro"
        val registro = "Register"
        val nome = "Name"
        val valor = "Value"
        val latitude = "Latitude"
        val longitude = "Longitude"
        val SQL_criacao =
            "CREATE TABLE ${nomeTabela} (" +
                    "${registro} INTEGER PRIMARY KEY," +
                    "${nome} TEXT," +
                    "${valor} TEXT)" +
                    "${latitude} REAL" +
                    "${longitude} REAL"
        db.execSQL(SQL_criacao)
    }
    override fun onUpgrade(db: SQLiteDatabase, versaoAntiga: Int, novaVersao: Int) {
        val SQL_exclusao = "DROP TABLE IF EXISTS Pessoa"
        db.execSQL(SQL_exclusao)
        onCreate(db)
    }
}
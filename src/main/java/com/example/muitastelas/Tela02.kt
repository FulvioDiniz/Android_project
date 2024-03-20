package com.example.muitastelas

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

class Tela02 : AppCompatActivity() {
    lateinit var bt_callFirstview : Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tela02)
        bt_callFirstview = findViewById(R.id.BT_chamarTela01)
        val it_chegouDaTela01 = intent // esse é o catalão
        val bd_trouxeDaTela01 = it_chegouDaTela01.extras // mochila do catalão
        val primeiraInformacao : String? = bd_trouxeDaTela01?.getString("1234")
        val segundaInformacao : String? = bd_trouxeDaTela01?.getString("4321")
        Toast.makeText(this,"Para a chave 01: " + primeiraInformacao,Toast.LENGTH_LONG).show()
        Toast.makeText(this,"Para a chave 02: " + segundaInformacao,Toast.LENGTH_LONG).show()
        bt_callFirstview.setOnClickListener {
            finish()
        }
    }
}
package com.example.muitastelas

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button



class Tela01 : AppCompatActivity() {
    lateinit var bt_callSecondScreen : Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tela01)
        bt_callSecondScreen = findViewById(R.id.BT_chamarTela02)
        var IwantToGoTo2 = Intent(this, Tela02 :: class.java)
        var mandar_proxima_tela = Bundle()
        mandar_proxima_tela.putString("1234","Professor")
        mandar_proxima_tela.putString("4321","Aluno")
        IwantToGoTo2.putExtras(mandar_proxima_tela)
        bt_callSecondScreen.setOnClickListener {
            startActivity(IwantToGoTo2)
        }
    }
}
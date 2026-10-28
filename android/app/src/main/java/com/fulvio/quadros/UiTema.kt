package com.fulvio.quadros

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Fundo = Color(0xFFF4F5F7)
val Branco = Color.White
val Preto = Color(0xFF1A1D23)
val Cinza = Color(0xFF5B616C)
val Borda = Color(0xFFE4E6EB)
val Amarelo = Color(0xFFE6A817)
val Vermelho = Color(0xFFC62828)
val Azul = Color(0xFF1565C0)
val Verde = Color(0xFF2E7D32)
val Forma = RoundedCornerShape(18.dp)
val FormaSelo = RoundedCornerShape(12.dp)
val FormaPequena = RoundedCornerShape(12.dp)

@Composable
fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Branco)
            .border(1.dp, Borda, Forma)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        conteudo()
    }
}

@Composable
fun Campo(
    rotulo: String,
    valor: String,
    senha: Boolean = false,
    linhas: Int = 1,
    aoMudar: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(rotulo, color = Cinza, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = valor,
            onValueChange = aoMudar,
            singleLine = linhas == 1,
            maxLines = linhas.coerceAtLeast(1),
            textStyle = TextStyle(color = Preto, fontSize = 18.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Amarelo),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (linhas > 1) 120.dp else 56.dp)
                .clip(FormaPequena)
                .background(Branco)
                .border(1.dp, Borda, FormaPequena)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
fun Botao(
    texto: String,
    enabled: Boolean = true,
    preenchido: Boolean = true,
    cor: Color = Preto,
    aoClicar: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(FormaPequena)
            .background(
                when {
                    !enabled -> Color(0xFFD9D6D0)
                    preenchido -> cor
                    else -> Branco
                },
            )
            .then(
                if (!preenchido && enabled) Modifier.border(1.dp, cor, FormaPequena) else Modifier,
            )
            .clickable(enabled = enabled, onClick = aoClicar),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = when {
                !enabled -> Cinza
                preenchido -> Branco
                else -> cor
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun Chip(texto: String, ativo: Boolean, cor: Color = Amarelo, aoClicar: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(FormaSelo)
            .background(if (ativo) cor else Branco)
            .border(1.dp, if (ativo) cor else Borda, FormaSelo)
            .clickable(onClick = aoClicar)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(texto, color = Preto, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SeloTexto(texto: String, vigente: Boolean) {
    Text(
        texto,
        modifier = Modifier
            .clip(FormaSelo)
            .background(if (vigente) Amarelo else Branco)
            .border(1.dp, if (vigente) Amarelo else Borda, FormaSelo)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = Preto,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
}

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tema fábrica: branco + laranja (sem dark). */
val Fundo = Color(0xFFFFF7F0)
val FundoFaixa = Color(0xFFFFE8D6)
val Branco = Color(0xFFFFFFFF)
val Preto = Color(0xFF1C1917)
val Cinza = Color(0xFF6B635C)
val Borda = Color(0xFFF0DCC8)
val Laranja = Color(0xFFE85D04)
val LaranjaSuave = Color(0xFFFFF1E6)
val Amarelo = Laranja
val Vermelho = Color(0xFFC2410C)
val Azul = Color(0xFF0F766E)
val Verde = Color(0xFF15803D)
val Forma = RoundedCornerShape(20.dp)
val FormaSelo = RoundedCornerShape(999.dp)
val FormaPequena = RoundedCornerShape(14.dp)
val Folha = RoundedCornerShape(6.dp)

val GradienteMarca = Brush.verticalGradient(
    colors = listOf(Color(0xFFFFFBF7), FundoFaixa),
)

@Composable
fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, Forma, ambientColor = Color(0x22E85D04), spotColor = Color(0x18E85D04))
            .clip(Forma)
            .background(Branco)
            .padding(18.dp),
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
            cursorBrush = SolidColor(Laranja),
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
    cor: Color = Laranja,
    aoClicar: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(FormaPequena)
            .background(
                when {
                    !enabled -> Color(0xFFE8DED4)
                    preenchido -> cor
                    else -> Branco
                },
            )
            .then(
                if (!preenchido && enabled) Modifier.border(1.5.dp, cor, FormaPequena) else Modifier,
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
fun Chip(texto: String, ativo: Boolean, cor: Color = Laranja, aoClicar: () -> Unit) {
    Box(
        modifier = Modifier
            .height(42.dp)
            .clip(FormaSelo)
            .background(if (ativo) cor else Branco)
            .border(1.dp, if (ativo) cor else Borda, FormaSelo)
            .clickable(onClick = aoClicar)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = if (ativo) Branco else Preto,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun Pilula(
    texto: String,
    enabled: Boolean = true,
    preenchida: Boolean = false,
    aoClicar: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(FormaSelo)
            .background(if (preenchida && enabled) Laranja else Branco)
            .border(1.dp, if (enabled) Laranja else Borda, FormaSelo)
            .clickable(enabled = enabled, onClick = aoClicar)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = when {
                !enabled -> Cinza
                preenchida -> Branco
                else -> Laranja
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SeloTexto(texto: String, vigente: Boolean) {
    Text(
        texto,
        modifier = Modifier
            .clip(FormaSelo)
            .background(if (vigente) Laranja else LaranjaSuave)
            .border(1.dp, if (vigente) Laranja else Borda, FormaSelo)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (vigente) Branco else Preto,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
}

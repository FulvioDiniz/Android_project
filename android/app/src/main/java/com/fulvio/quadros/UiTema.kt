package com.fulvio.quadros

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fábrica: laranja + branco. Claro, sem dark mode. */
val Fundo = Color(0xFFF7F7F5)
val FundoFaixa = Color(0xFFEEEEEC)
val Branco = Color(0xFFFFFFFF)
val Preto = Color(0xFF1A1A1A)
val Cinza = Color(0xFF5A5A5A)
val CinzaClaro = Color(0xFF8E8E8E)
val Borda = Color(0xFFDCDCD8)
val Laranja = Color(0xFFE85D04)
val LaranjaEscuro = Color(0xFFC44D00)
val LaranjaSuave = Color(0xFFFFF1E6)
val Amarelo = Laranja
val Vermelho = Color(0xFFB42318)
val Azul = Color(0xFF175CD3)
val Verde = Color(0xFF067647)
val Forma = RoundedCornerShape(12.dp)
val FormaSelo = RoundedCornerShape(6.dp)
val FormaPequena = RoundedCornerShape(10.dp)
val Folha = RoundedCornerShape(4.dp)
val FormaNav = RoundedCornerShape(0.dp)

val FonteMarca = FontFamily.SansSerif

@Composable
fun CabecalhoTela(
    titulo: String,
    subtitulo: String = "",
    direita: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                titulo,
                color = Preto,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FonteMarca,
                letterSpacing = (-0.3).sp,
            )
            if (subtitulo.isNotBlank()) {
                Text(
                    subtitulo,
                    color = Cinza,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        direita?.invoke()
    }
}

@Composable
fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Forma)
            .background(Branco)
            .border(1.dp, Borda, Forma)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rotulo.isNotBlank()) {
            Text(rotulo, color = Cinza, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        BasicTextField(
            value = valor,
            onValueChange = aoMudar,
            singleLine = linhas == 1,
            maxLines = linhas.coerceAtLeast(1),
            textStyle = TextStyle(
                color = Preto,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FonteMarca,
            ),
            cursorBrush = SolidColor(Laranja),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (linhas > 1) 96.dp else 52.dp)
                .clip(FormaPequena)
                .background(Branco)
                .border(1.dp, Borda, FormaPequena)
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
    val fundo by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFFE8E8E6)
            preenchido -> cor
            else -> Branco
        },
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "botaoFundo",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(FormaPequena)
            .background(fundo)
            .then(
                if (!preenchido && enabled) Modifier.border(1.5.dp, cor, FormaPequena) else Modifier,
            )
            .clickable(enabled = enabled, onClick = aoClicar),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = when {
                !enabled -> CinzaClaro
                preenchido -> Branco
                else -> cor
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun Chip(texto: String, ativo: Boolean, cor: Color = Laranja, aoClicar: () -> Unit) {
    val fundo by animateColorAsState(
        targetValue = if (ativo) cor else Branco,
        animationSpec = tween(140),
        label = "chipFundo",
    )
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(FormaSelo)
            .background(fundo)
            .border(1.dp, if (ativo) cor else Borda, FormaSelo)
            .clickable(onClick = aoClicar)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = if (ativo) Branco else Preto,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
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
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            color = when {
                !enabled -> Cinza
                preenchida -> Branco
                else -> Laranja
            },
            fontSize = 13.sp,
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
            .background(if (vigente) LaranjaSuave else FundoFaixa)
            .border(1.dp, if (vigente) Color(0xFFFFD0B0) else Borda, FormaSelo)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (vigente) LaranjaEscuro else Cinza,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun ContadorBadge(valor: String) {
    Box(
        modifier = Modifier
            .clip(FormaSelo)
            .background(Laranja)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(valor, color = Branco, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FaixaAviso(
    texto: String,
    acao: String = "OK",
    aoDispensar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FormaPequena)
            .background(LaranjaSuave)
            .border(1.dp, Color(0xFFFFD0B0), FormaPequena)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            texto,
            color = Preto,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            acao,
            color = LaranjaEscuro,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(FormaSelo)
                .clickable(onClick = aoDispensar)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun DivisorSuave(altura: Dp = 1.dp) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(altura)
            .background(Borda),
    )
}

@Composable
fun MarcaQuadros(tamanho: Int = 40) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .height(4.dp)
                .width(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Laranja),
        )
        Text(
            "QUADROS",
            color = Preto,
            fontSize = tamanho.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FonteMarca,
            letterSpacing = (-0.8).sp,
        )
    }
}

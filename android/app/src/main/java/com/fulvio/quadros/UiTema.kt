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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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

/**
 * Tema oficina moderna: pedra fria + laranja de oficina.
 * Evita cream/terracota genérico e dark mode.
 */
val Fundo = Color(0xFFF2F4F7)
val FundoFaixa = Color(0xFFE7ECF2)
val Branco = Color(0xFFFFFFFF)
val Preto = Color(0xFF12151A)
val Cinza = Color(0xFF5C6570)
val CinzaClaro = Color(0xFF8A939E)
val Borda = Color(0xFFD5DCE5)
val Laranja = Color(0xFFF05A28)
val LaranjaEscuro = Color(0xFFD9481A)
val LaranjaSuave = Color(0xFFFFEFE8)
val Amarelo = Laranja
val Vermelho = Color(0xFFC0392B)
val Azul = Color(0xFF0E7490)
val Verde = Color(0xFF0F7A45)
val Forma = RoundedCornerShape(18.dp)
val FormaSelo = RoundedCornerShape(10.dp)
val FormaPequena = RoundedCornerShape(14.dp)
val Folha = RoundedCornerShape(8.dp)
val FormaNav = RoundedCornerShape(16.dp)

val FonteMarca = FontFamily.SansSerif

val GradienteMarca = Brush.verticalGradient(
    colors = listOf(Color(0xFFF8FAFC), FundoFaixa, Color(0xFFE2E8F0)),
)

val GradienteHero = Brush.linearGradient(
    colors = listOf(Color(0xFFF05A28), Color(0xFFE07830), Color(0xFF0E7490)),
)

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
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FonteMarca,
                letterSpacing = (-0.4).sp,
            )
            if (subtitulo.isNotBlank()) {
                Text(
                    subtitulo,
                    color = Cinza,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
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
            .shadow(2.dp, Forma, ambientColor = Color(0x14000000), spotColor = Color(0x10000000))
            .clip(Forma)
            .background(Branco)
            .border(1.dp, Borda, Forma)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(rotulo, color = Cinza, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        BasicTextField(
            value = valor,
            onValueChange = aoMudar,
            singleLine = linhas == 1,
            maxLines = linhas.coerceAtLeast(1),
            textStyle = TextStyle(
                color = Preto,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FonteMarca,
            ),
            cursorBrush = SolidColor(Laranja),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (linhas > 1) 112.dp else 54.dp)
                .clip(FormaPequena)
                .background(Color(0xFFF7F9FB))
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
    val fundo by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFFE4E8EE)
            preenchido -> cor
            else -> Branco
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "botaoFundo",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
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
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
fun Chip(texto: String, ativo: Boolean, cor: Color = Laranja, aoClicar: () -> Unit) {
    val fundo by animateColorAsState(
        targetValue = if (ativo) cor else Branco,
        animationSpec = tween(160),
        label = "chipFundo",
    )
    Box(
        modifier = Modifier
            .height(40.dp)
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
            .height(38.dp)
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
            .background(if (vigente) LaranjaSuave else Color(0xFFF0F3F6))
            .border(1.dp, if (vigente) Color(0xFFFFCDB8) else Borda, FormaSelo)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (vigente) LaranjaEscuro else Cinza,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
    )
}

@Composable
fun ContadorBadge(valor: String) {
    Box(
        modifier = Modifier
            .clip(FormaSelo)
            .background(LaranjaSuave)
            .border(1.dp, Color(0xFFFFCDB8), FormaSelo)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(valor, color = LaranjaEscuro, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
            .border(1.dp, Color(0xFFFFCDB8), FormaPequena)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Laranja),
        )
        Text(
            texto,
            color = Preto,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Text(
            acao,
            color = LaranjaEscuro,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(FormaSelo)
                .clickable(onClick = aoDispensar)
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
fun MarcaQuadros(tamanho: Int = 42) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .height(4.dp)
                .width(48.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GradienteHero),
        )
        Text(
            "QUADROS",
            color = Preto,
            fontSize = tamanho.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FonteMarca,
            letterSpacing = (-1).sp,
        )
    }
}

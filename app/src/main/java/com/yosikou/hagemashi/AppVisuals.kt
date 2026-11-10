package com.yosikou.hagemashi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared hierarchy: 20dp page margins, 16dp card padding/gaps, 12dp within cards.
@Composable internal fun HagemashiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8DD5C3), secondary = Color(0xFFB4CCC4))
        else lightColorScheme(primary = Color(0xFF176B59), secondary = Color(0xFF4B635B))
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp),
        titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 28.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    ), content = content)
}

@Composable internal fun SectionCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable internal fun GenerationBadge(kind: GenerationKind) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(generationKindLabel(kind), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable internal fun AnswerMetadata(answer: SavedEncouragement) {
    Text(answer.personaName?.let { stringResource(R.string.generated_persona, it) }
        ?: stringResource(R.string.persona_not_recorded), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        GenerationBadge(answer.kind)
        Text(answerDisplayTime(answer.generatedAt, java.time.ZoneId.systemDefault()), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

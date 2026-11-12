package mmm.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Material's scale on the system font, a step smaller throughout and weighted the way the
 * reference weights it: large titles bold and tight, row titles semibold, second lines in the
 * muted ink. Each level is clearly apart from the next, so a screen reads by size before it is
 * read word by word.
 *
 * Every style breaks lines by phrase, strictly: Korean is not split inside a word and a number
 * is not torn from its unit (formatters join the two with a no-break space as well). Words are
 * never hyphenated.
 */
internal fun glassTypography(colors: GlassColors): Typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.sized(28.sp, 34.sp).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = base.headlineMedium.sized(24.sp, 30.sp).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = base.headlineSmall.sized(20.sp, 26.sp).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.sized(18.sp, 24.sp).copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.sized(15.sp, 21.sp).copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.sized(13.sp, 18.sp).copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.sized(15.sp, 21.sp),
        bodyMedium = base.bodyMedium.sized(13.5.sp, 19.sp),
        bodySmall = base.bodySmall.sized(12.sp, 17.sp).copy(color = colors.inkMuted),
        labelLarge = base.labelLarge.sized(13.sp, 18.sp).copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.sized(11.5.sp, 16.sp),
        labelSmall = base.labelSmall.sized(10.5.sp, 14.sp),
    )
}

/** Korean and English alike: break between phrases, never inside a word, never hyphenate. */
private val PhraseBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)

private fun TextStyle.sized(size: TextUnit, line: TextUnit): TextStyle =
    copy(fontSize = size, lineHeight = line, lineBreak = PhraseBreak, hyphens = Hyphens.None)

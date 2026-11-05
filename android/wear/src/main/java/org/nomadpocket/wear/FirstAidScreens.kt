package org.nomadpocket.wear

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.nomadpocket.core.FirstAidCard
import org.nomadpocket.core.FirstAidSteps

/**
 * The urgent guides, read from the same HTML the phones ship (copied into the
 * watch APK's assets at build time), so they work with no phone.
 */
object FirstAidLibrary {
    @Volatile private var cached: List<FirstAidCard>? = null

    fun cards(context: Context): List<FirstAidCard> = cached ?: FirstAidSteps.load { path ->
        runCatching { context.assets.open("Guides/$path").bufferedReader().use { it.readText() } }.getOrNull()
    }.also { cached = it }

    /** Cards grouped by their guide category, in index order. */
    fun byCategory(cards: List<FirstAidCard>): List<Pair<String, List<FirstAidCard>>> =
        cards.groupBy { it.category }.toList()
}

/** Lists are scrollable with the crown or bezel: TransformingLazyColumn handles rotary input itself. */
@Composable
fun FirstAidListScreen(cards: List<FirstAidCard>, onOpen: (String) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader { Text("First aid") } }
            if (cards.isEmpty()) {
                item { Text("The guides are missing from this build.", color = WearBrand.secondaryText) }
            }
            FirstAidLibrary.byCategory(cards).forEach { (category, group) ->
                item(key = "h-$category") {
                    Text(category, Modifier.fillMaxWidth().padding(top = 6.dp), color = WearBrand.amber,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                group.forEach { card ->
                    item(key = card.id) {
                        Button(
                            onClick = { onOpen(card.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            label = { Text(card.title, fontWeight = FontWeight.SemiBold) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FirstAidDetailScreen(card: FirstAidCard?) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            if (card == null) {
                item { Text("That guide isn't in this build.", color = WearBrand.secondaryText) }
                return@TransformingLazyColumn
            }
            item { ListHeader { Text(card.title, textAlign = TextAlign.Center) } }
            item { Text("Do this now", Modifier.fillMaxWidth(), color = WearBrand.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            card.steps.forEachIndexed { i, step ->
                item {
                    Row(
                        // Inset from the edges so long steps clear the round bezel as they scroll.
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clearAndSetSemantics { contentDescription = "Step ${i + 1}. $step" },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.size(20.dp).background(WearBrand.amber, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                        Text(step, fontSize = 14.sp)
                    }
                }
            }
            card.warnings.forEach { warning ->
                item {
                    Text(
                        warning,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .background(WearBrand.danger.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        fontSize = 12.sp,
                    )
                }
            }
            item {
                Text("The full guide, with pictures and details, is in Survive in Pocket Nomad on your phone.",
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = WearBrand.secondaryText, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

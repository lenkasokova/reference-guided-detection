/**
 * @author Bc.Lenka Sokova
 */

package cz.vut.oneshotdetector.view.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlin.math.min

data class ProgressiveGridState<T>(
    val gridState: LazyGridState,
    val visibleItems: List<T>,
    val loadedCount: Int
)

@Composable
fun <T> rememberProgressiveGridState(
    items: List<T>,
    initialCount: Int = 2,
    smallChunkSize: Int = 2,
    largeChunkSize: Int = 20,
    switchToLargeChunksAt: Int = 20,
    loadTriggerOffset: Int = 4
): ProgressiveGridState<T> {
    val gridState = rememberLazyGridState()
    var loadedCount by remember(items.size) {
        mutableIntStateOf(min(initialCount, items.size))
    }
    val visibleItems = remember(items, loadedCount) {
        items.take(loadedCount)
    }

    LaunchedEffect(gridState, items.size, loadedCount) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisibleIndex ->
                val shouldLoadMore = lastVisibleIndex >= loadedCount - loadTriggerOffset
                if (shouldLoadMore && loadedCount < items.size) {
                    val increment = if (loadedCount < switchToLargeChunksAt) {
                        smallChunkSize
                    } else {
                        largeChunkSize
                    }
                    loadedCount = min(items.size, loadedCount + increment)
                }
            }
    }

    return ProgressiveGridState(
        gridState = gridState,
        visibleItems = visibleItems,
        loadedCount = loadedCount
    )
}

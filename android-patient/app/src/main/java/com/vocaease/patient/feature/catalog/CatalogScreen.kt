package com.vocaease.patient.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vocaease.patient.ui.theme.AppBackground
import com.vocaease.patient.ui.theme.AppSurfaceVariant
import com.vocaease.patient.ui.theme.AppWhite
import com.vocaease.patient.ui.theme.BrandGreen
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize
import com.vocaease.patient.ui.theme.TextPrimary
import com.vocaease.patient.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onSearch: (String) -> Unit,
    onPatientRetry: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSongClick: (CatalogSongUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember(state.keyword) { mutableStateOf(state.keyword) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 0.dp,
            end = 20.dp,
            bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CatalogHeader(
                searchVisible = searchVisible,
                query = query,
                onQueryChange = { query = it },
                onSearchToggle = { searchVisible = !searchVisible },
                onSearch = { onSearch(query) },
            )
        }
        item {
            TreatmentProgressCard(
                progress = state.treatmentProgress,
                status = state.patientStatus,
                errorMessage = state.patientErrorMessage,
                onRetry = onPatientRetry,
            )
        }
        if (state.patientErrorMessage != null && state.treatmentProgress != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.patientErrorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onPatientRetry, modifier = Modifier.height(40.dp)) { Text("重试") }
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "热门歌曲",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text("${state.totalSongCount} 首", color = TextSecondary, fontSize = 12.sp)
            }
        }
        itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
            SongCard(
                song = song,
                coverColor = songCoverColor(index),
                enabled = state.canStartTraining,
                onClick = { onSongClick(song) },
            )
        }
        state.errorMessage?.let { message ->
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Button(onClick = onRetry, modifier = Modifier.height(MinimumTouchTargetSize)) {
                        Text("重试")
                    }
                }
            }
        }
        if (state.canLoadMore) {
            item {
                Button(
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MinimumTouchTargetSize),
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogHeader(
    searchVisible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onSearch: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "VOCAEASE",
                    color = BrandGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "今天唱什么？",
                    color = TextPrimary,
                    fontSize = 26.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onSearchToggle,
                modifier = Modifier
                    .size(40.dp)
                    .background(AppSurfaceVariant, CircleShape)
                    .testTag("search-toggle"),
            ) {
                Text("⌕", color = TextPrimary, fontSize = 22.sp)
            }
        }
        if (searchVisible) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索歌曲或歌手") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("catalog-search"),
            )
        }
    }
}

@Composable
private fun SongCard(
    song: CatalogSongUi,
    coverColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .testTag("song-card-${song.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = AppWhite,
            disabledContainerColor = AppWhite.copy(alpha = 0.62f),
        ),
        border = BorderStroke(1.dp, Color(0xFFDEE8E2)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(coverColor, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("♪", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${song.artist}  ·  ${song.durationSeconds.asDuration()}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", color = TextSecondary, fontSize = 22.sp)
        }
    }
}

private fun Int.asDuration(): String = String.format(Locale.CHINA, "%d:%02d", this / 60, this % 60)

private fun songCoverColor(index: Int): Color = listOf(
    Color(0xFFC65B86),
    Color(0xFF3D8EB5),
    Color(0xFFE67B43),
    Color(0xFF4CA87C),
)[index % 4]

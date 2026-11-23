package com.hans.aura.ui.common

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuraLoadingIndicator(label: String, modifier: Modifier = Modifier) {
    LoadingIndicator(
        modifier = modifier.semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
        color = MaterialTheme.colorScheme.primary
    )
}

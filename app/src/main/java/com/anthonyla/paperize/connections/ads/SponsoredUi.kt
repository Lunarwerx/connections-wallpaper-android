package com.anthonyla.paperize.connections.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anthonyla.paperize.R
import com.anthonyla.paperize.presentation.common.components.SectionLabel
import com.anthonyla.paperize.presentation.common.theme.AppSpacing

/** The Wallpaper page's sponsored card: picture, headline, one line, and its action. */
@Composable
fun SponsoredBanner(ad: SponsoredAd, onViewed: () -> Unit, onTapped: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(ad.campaignId) { onViewed() }
    val open = ad.ctaUrl?.let { url -> { onTapped(); runCatching { uriHandler.openUri(url) }; Unit } }
    Column(modifier) {
        SectionLabel(stringResource(R.string.sponsored))
        Surface(
            onClick = { open?.invoke() },
            enabled = open != null,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(AppSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ad.imageUrl?.let { image ->
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
                Column(Modifier.weight(1f)) {
                    ad.headline?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    ad.body?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (open != null) {
                    TextButton(onClick = open) { Text(ad.ctaLabel ?: stringResource(R.string.sponsored_learn_more)) }
                }
            }
        }
    }
}

/**
 * A pop-up from Connections: an ad, or an announcement when it has no link. Shown once per campaign.
 */
@Composable
fun SponsoredPopup(ad: SponsoredAd, onViewed: () -> Unit, onTapped: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(ad.campaignId) { onViewed() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = if (ad.headline != null) ({ Text(ad.headline) }) else null,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                ad.imageUrl?.let { image ->
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                    )
                }
                ad.body?.let { Text(it) }
            }
        },
        confirmButton = {
            val url = ad.ctaUrl
            if (url != null) {
                Button(onClick = {
                    onTapped()
                    runCatching { uriHandler.openUri(url) }
                }) { Text(ad.ctaLabel ?: stringResource(R.string.sponsored_learn_more)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = if (ad.ctaUrl != null) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }) else null
    )
}

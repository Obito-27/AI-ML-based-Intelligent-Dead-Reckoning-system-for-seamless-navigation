package com.dracarys.idr.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dracarys.idr.logging.OutageValidationRecord
import com.dracarys.idr.ui.theme.DracarysAlertRed
import com.dracarys.idr.ui.theme.DracarysBackground
import com.dracarys.idr.ui.theme.DracarysCardSurface
import com.dracarys.idr.ui.theme.DracarysGnssTeal
import com.dracarys.idr.ui.theme.DracarysPrimaryText
import com.dracarys.idr.ui.theme.DracarysSecondaryText
import com.dracarys.idr.ui.theme.DracarysTypography
import java.io.File
import java.util.Locale

/**
 * Bottom sheet displaying field-test outage validation results and export tooling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutageValidationSheet(
    records: List<OutageValidationRecord>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DracarysBackground,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Field Outage Validation",
                        style = DracarysTypography.titleMedium,
                        color = DracarysPrimaryText,
                    )
                    Text(
                        text = "${records.size} simulated outage test(s) completed",
                        style = DracarysTypography.bodySmall,
                        color = DracarysSecondaryText,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (records.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val dir = File(context.getExternalFilesDir("diagnostics") ?: context.filesDir, "validations")
                                val csv = File(dir, "outage_validation_summary.csv")
                                if (csv.exists()) {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        csv
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Export Validation CSV"))
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share validation CSV",
                                tint = DracarysGnssTeal,
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = DracarysSecondaryText,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Note: Field drift % uses DR-estimated distance as the denominator. The offline benchmark (11.22% LODO-CV) uses true ground-truth distance — metrics are not directly comparable.",
                style = DracarysTypography.bodySmall,
                color = DracarysSecondaryText.copy(alpha = 0.8f),
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No simulated outages recorded yet.\nToggle the debug button during your drive to test dead reckoning.",
                        style = DracarysTypography.bodyMedium,
                        color = DracarysSecondaryText,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(records) { record ->
                        OutageRecordCard(record = record)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun OutageRecordCard(
    record: OutageValidationRecord,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DracarysCardSurface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Outage #${record.id}",
                    style = DracarysTypography.titleMedium,
                    color = DracarysPrimaryText,
                )

                // Pass/Fail badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (record.passedTarget) DracarysGnssTeal.copy(alpha = 0.2f) else DracarysAlertRed.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (record.passedTarget) "PASS (<10%)" else "DRIFT EXCEEDED",
                        color = if (record.passedTarget) DracarysGnssTeal else DracarysAlertRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricColumn(label = "Duration", value = "${record.durationSec}s")
                MetricColumn(label = "DR Distance", value = String.format(Locale.US, "%.1f m", record.drDistanceTraveledM))
                MetricColumn(label = "GPS Gap", value = String.format(Locale.US, "%.1f m", record.displacementGapM))
                MetricColumn(
                    label = "Est. Drift",
                    value = String.format(Locale.US, "%.1f%%", record.driftPercent),
                    highlightColor = if (record.passedTarget) DracarysGnssTeal else DracarysAlertRed
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    highlightColor: Color = DracarysPrimaryText,
) {
    Column {
        Text(
            text = label,
            style = DracarysTypography.bodySmall,
            color = DracarysSecondaryText,
        )
        Text(
            text = value,
            style = DracarysTypography.displaySmall,
            color = highlightColor,
        )
    }
}

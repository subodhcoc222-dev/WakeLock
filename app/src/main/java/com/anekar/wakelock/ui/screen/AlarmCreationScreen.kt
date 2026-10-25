package com.anekar.wakelock.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anekar.wakelock.ui.theme.*

@Composable
fun WakeUpIntervalSelector(
    selectedIntervalSeconds: Int,
    onIntervalSelected: (Int) -> Unit
) {
    // -1 represents Taper Mode (30m @ 5m -> then 10m)
    val intervals = listOf(
        -1 to "⚡ Taper",
        300 to "5m",
        600 to "10m",
        900 to "15m"
    )

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "SURVEILLANCE CHECK INTERVAL",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            intervals.forEach { (seconds, label) ->
                val isSelected = selectedIntervalSeconds == seconds
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(
                            if (isSelected) DarkSurface else PureBlack,
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (isSelected) LaserGreen else SubtleBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onIntervalSelected(seconds) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) LaserGreen else TextMuted
                    )
                }
            }
        }

        if (selectedIntervalSeconds == -1) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "⚡ TAPER: First 30m checks every 5m (6 checks) ➔ Then every 10m until duration ends.",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = LaserGreen
            )
        }
    }
}

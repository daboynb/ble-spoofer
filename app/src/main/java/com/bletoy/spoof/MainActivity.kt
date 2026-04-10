package com.bletoy.spoof

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ════════════════════════════════════════════
// ACTIVITY
// ════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleManager

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ble.log(LogType.SUCCESS, "Permissions granted")
        } else {
            ble.log(LogType.ERROR, "Permissions denied — BLE advertising won't work")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(applicationContext)
        ble.loadConfigs(applicationContext)
        requestBlePermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgDeep,
                    surface = Surface,
                    primary = Purple,
                    onBackground = TextPri,
                    onSurface = TextPri,
                    error = Red
                )
            ) {
                AppRoot(ble)
            }
        }
    }

    private fun requestBlePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }
}

// ════════════════════════════════════════════
// DESIGN TOKENS
// ════════════════════════════════════════════

private val BgDeep      = Color(0xFF08080F)
private val Surface     = Color(0xFF12121E)
private val SurfaceHi   = Color(0xFF1A1A2E)
private val BorderSub   = Color(0x1FFFFFFF)

private val TextPri     = Color(0xFFE8E8F0)
private val TextSec     = Color(0xFF6B6B80)

private val Neon        = Color(0xFF00FF88)
private val Cyan        = Color(0xFF00D4FF)
private val Red         = Color(0xFFFF3355)
private val Amber       = Color(0xFFFFAA00)
private val Purple      = Color(0xFFAA66FF)

private val AppleColor  = Color(0xFFFF6B6B)
private val GoogleColor = Color(0xFF64B5F6)

// ════════════════════════════════════════════
// APP ROOT
// ════════════════════════════════════════════

@Composable
fun AppRoot(ble: BleManager) {
    Scaffold(containerColor = BgDeep) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            SpoofScreen(ble)
        }
    }
}

// ════════════════════════════════════════════
// SPOOF SCREEN
// ════════════════════════════════════════════

@Composable
fun SpoofScreen(ble: BleManager) {
    val spoofing by ble.isSpoofing.collectAsStateWithLifecycle()
    val target   by ble.spoofTarget.collectAsStateWithLifecycle()
    val spamming by ble.isSpamming.collectAsStateWithLifecycle()

    val appleActionDevices = ble.spoofDevices.filter { it.category == "apple_action" }
    val appleDevices       = ble.spoofDevices.filter { it.category == "apple" }
    val googleDevices      = ble.spoofDevices.filter { it.category == "google" }

    var appleActionExpanded by remember { mutableStateOf(false) }
    var appleExpanded       by remember { mutableStateOf(false) }
    var googleExpanded      by remember { mutableStateOf(false) }
    var infoExpanded        by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Header ──
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    "BLE Spoofer",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Purple,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "broadcast fake advertisements to nearby phones",
                    fontSize = 11.sp,
                    color = TextSec
                )
            }
        }

        // ── Active broadcast banner ──
        if (spoofing) {
            item {
                ActiveBanner(target = target, onStop = { ble.stopSpoof() })
            }
        }

        // ── Apple Action section ──
        item {
            SectionHeader(
                title       = "Apple Action",
                count       = appleActionDevices.size,
                accentColor = AppleColor,
                expanded    = appleActionExpanded,
                spamLabel   = null,
                isSpamming  = false,
                onSpam      = {},
                onToggle    = { appleActionExpanded = !appleActionExpanded }
            )
        }
        if (appleActionExpanded) {
            items(appleActionDevices.size) { i ->
                val device = appleActionDevices[i]
                DeviceRow(
                    name        = device.name,
                    isActive    = spoofing && target == device.name,
                    accentColor = AppleColor,
                    onClick     = {
                        if (spoofing && target == device.name) ble.stopSpoof()
                        else ble.startSpoof(device)
                    }
                )
            }
        }

        // ── Apple Pairing section ──
        item {
            SectionHeader(
                title       = "Apple Pairing",
                count       = appleDevices.size,
                accentColor = AppleColor,
                expanded    = appleExpanded,
                spamLabel   = if (spamming && target.startsWith("SPAM AirPods")) "STOP SPAM" else "SPAM ALL",
                isSpamming  = spamming && target.startsWith("SPAM AirPods"),
                onSpam      = {
                    if (spamming && target.startsWith("SPAM AirPods")) ble.stopSpoof()
                    else ble.startSpam("apple")
                },
                onToggle    = { appleExpanded = !appleExpanded }
            )
        }
        if (appleExpanded) {
            items(appleDevices.size) { i ->
                val device = appleDevices[i]
                DeviceRow(
                    name        = device.name,
                    isActive    = spoofing && target == device.name,
                    accentColor = AppleColor,
                    onClick     = {
                        if (spoofing && target == device.name) ble.stopSpoof()
                        else ble.startSpoof(device)
                    }
                )
            }
        }

        // ── Google Fast Pair section ──
        item {
            SectionHeader(
                title       = "Google Fast Pair",
                count       = googleDevices.size,
                accentColor = GoogleColor,
                expanded    = googleExpanded,
                spamLabel   = if (spamming && target.startsWith("SPAM Fast")) "STOP SPAM" else "SPAM ALL",
                isSpamming  = spamming && target.startsWith("SPAM Fast"),
                onSpam      = {
                    if (spamming && target.startsWith("SPAM Fast")) ble.stopSpoof()
                    else ble.startSpam("google")
                },
                onToggle    = { googleExpanded = !googleExpanded }
            )
        }
        if (googleExpanded) {
            items(googleDevices.size) { i ->
                val device = googleDevices[i]
                DeviceRow(
                    name        = device.name,
                    isActive    = spoofing && target == device.name,
                    accentColor = GoogleColor,
                    onClick     = {
                        if (spoofing && target == device.name) ble.stopSpoof()
                        else ble.startSpoof(device)
                    }
                )
            }
        }

        // ── Info card ──
        item {
            Spacer(Modifier.height(2.dp))
            InfoCard(expanded = infoExpanded, onToggle = { infoExpanded = !infoExpanded })
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ════════════════════════════════════════════
// ACTIVE BANNER
// ════════════════════════════════════════════

@Composable
private fun ActiveBanner(target: String, onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Card(
        colors   = CardDefaults.cardColors(containerColor = Purple.copy(alpha = 0.08f)),
        shape    = RoundedCornerShape(14.dp),
        border   = BorderStroke(1.dp, Purple.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Purple.copy(alpha = dotAlpha))
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "BROADCASTING",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Purple,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    target,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPri
                )
            }
            Text(
                "STOP",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Red,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Red.copy(alpha = 0.12f))
                    .clickable { onStop() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

// ════════════════════════════════════════════
// SECTION HEADER
// ════════════════════════════════════════════

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    accentColor: Color,
    expanded: Boolean,
    spamLabel: String?,
    isSpamming: Boolean,
    onSpam: () -> Unit,
    onToggle: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "arrowRot"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Left accent bar
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .background(accentColor, RoundedCornerShape(2.dp))
        )

        // Title
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPri,
            modifier = Modifier.weight(1f)
        )

        // Count pill
        Text(
            "$count",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            modifier = Modifier
                .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )

        // Spam button (only if provided)
        if (spamLabel != null) {
            Text(
                spamLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSpamming) Red else accentColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background((if (isSpamming) Red else accentColor).copy(alpha = 0.13f))
                    .clickable { onSpam() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        // Collapse arrow
        Text(
            ">",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextSec,
            modifier = Modifier.rotate(arrowRotation)
        )
    }
}

// ════════════════════════════════════════════
// DEVICE ROW
// ════════════════════════════════════════════

@Composable
private fun DeviceRow(
    name: String,
    isActive: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rowPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rowDotAlpha"
    )

    Card(
        colors   = CardDefaults.cardColors(
            containerColor = if (isActive) accentColor.copy(alpha = 0.08f) else Surface
        ),
        shape    = RoundedCornerShape(14.dp),
        border   = BorderStroke(
            1.dp,
            if (isActive) accentColor.copy(alpha = 0.45f) else BorderSub
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isActive) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = dotAlpha))
                )
            }
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) accentColor else TextPri,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ════════════════════════════════════════════
// INFO CARD
// ════════════════════════════════════════════

@Composable
private fun InfoCard(expanded: Boolean, onToggle: () -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Surface),
        shape    = RoundedCornerShape(14.dp),
        border   = BorderStroke(1.dp, BorderSub),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
    ) {
        Column(
            Modifier
                .clickable { onToggle() }
                .padding(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "HOW IT WORKS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSec,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (expanded) "hide" else "show",
                    fontSize = 10.sp,
                    color = TextSec
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Apple: Broadcasts Continuity Protocol advertisements that trigger a connect popup on nearby iPhones and iPads.",
                    fontSize = 11.sp,
                    color = TextSec,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Google: Broadcasts Fast Pair service data that triggers a device found notification on nearby Android phones.",
                    fontSize = 11.sp,
                    color = TextSec,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "BLE advertisements are unauthenticated — any device can trivially spoof any other.",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber.copy(alpha = 0.85f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

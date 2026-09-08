package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.data.ShareCredentials
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isConnected

/**
 * Collapsible "Share VPN" card in the drawer.
 *
 * Turns the phone into a proxy gateway for the laptop / another phone on the
 * same Wi-Fi or hotspot: shows the exact `ip:port` values to type into the
 * other device's proxy settings, each with a one-tap copy button.
 *
 * 1.2.9-r3 (audit F-5): the shared listeners now require a username and password
 * from any device that is not this phone, so the card shows the credential next to
 * the addresses - with the same one-tap copy - and offers a rotation. Everything
 * the user has to type into the laptop is in one place, which is the only way an
 * authenticated proxy stays a feature people actually use instead of turning off.
 */
@Composable
fun SharePanel(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val scope = rememberCoroutineScope()
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "shareArrow")
    val shareActive by ShareBridge.active.collectAsState()
    // Show the ACTUAL bound ports (fixed standard ports; null while a listener is
    // busy), so the values on screen always match what the bridge listens on.
    val socksPort by ShareBridge.socksPort.collectAsState()
    val httpPort by ShareBridge.httpPort.collectAsState()
    val proxyUser by ShareBridge.proxyUser.collectAsState()
    val proxyPassword by ShareBridge.proxyPassword.collectAsState()
    val panelContext = LocalContext.current

    // Make sure the saved credential is loaded (or minted once) before the user can
    // read it off this card. Keystore work, so never on the main thread.
    LaunchedEffect(expanded) {
        if (expanded) withContext(Dispatchers.IO) { ShareCredentials.ensure(panelContext) }
    }

    // Re-resolve the LAN IP whenever the panel opens or connectivity flips.
    val lanIp = remember(expanded, shareActive, state.isConnected) { ShareBridge.lanAddress() }

    // Self-healing: whenever the panel is composed while connected with the
    // toggle on but the bridge not running yet, (re)start it. start() is
    // asynchronous and thread-safe, so this can never block the UI.
    LaunchedEffect(state.isConnected, profile.lanShare, shareActive) {
        if (state.isConnected && profile.lanShare && !shareActive) {
            // localOnly = false is the whole point of this panel: the user has
            // turned LAN sharing on. Passed explicitly - the bridge defaults to
            // loopback (see ShareBridge.start).
            withContext(Dispatchers.IO) { ShareBridge.start(localOnly = false) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.share_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.share_toggle),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.share_toggle_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = profile.lanShare,
                            onCheckedChange = { on ->
                                onProfileChange(profile.copy(lanShare = on))
                                // Take effect immediately for the current session
                                // (the service also honours the flag on connect).
                                if (state.isConnected) {
                                    if (on) {
                                        ShareBridge.start(localOnly = false)
                                    } else {
                                        ShareBridge.stop()
                                    }
                                }
                            },
                        )
                    }

                    when {
                        !state.isConnected -> InfoText(stringResource(R.string.share_need_connect))
                        !profile.lanShare -> Unit
                        lanIp == null -> InfoText(stringResource(R.string.share_need_wifi))
                        shareActive -> {
                            InfoText(stringResource(R.string.share_howto))
                            Spacer(Modifier.height(8.dp))
                            EndpointRow(
                                label = stringResource(R.string.share_http_label),
                                value = "$lanIp:${httpPort ?: ShareBridge.HTTP_SHARE_PORT}",
                            )
                            EndpointRow(
                                label = stringResource(R.string.share_socks_label),
                                value = "$lanIp:${socksPort ?: ShareBridge.SOCKS_SHARE_PORT}",
                            )

                            // The credential the other device has to send. Shown
                            // here rather than buried in settings: it is part of
                            // the same copy-and-type job as the two addresses.
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(6.dp))
                            InfoText(stringResource(R.string.share_auth_note))
                            Spacer(Modifier.height(4.dp))
                            EndpointRow(
                                label = stringResource(R.string.share_user_label),
                                value = proxyUser,
                            )
                            EndpointRow(
                                label = stringResource(R.string.share_pass_label),
                                value = proxyPassword,
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            ShareCredentials.rotate(panelContext)
                                        }
                                        Toast.makeText(
                                            panelContext,
                                            R.string.share_pass_rotated,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.share_pass_rotate))
                            }

                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.share_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            )
                        }
                        // Bridge is starting (or failed to bind): never leave
                        // the panel blank.
                        else -> InfoText(stringResource(R.string.share_starting))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EndpointRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                // BiDi fix: ip:port must always render LTR, even in RTL locale.
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.share_copy),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

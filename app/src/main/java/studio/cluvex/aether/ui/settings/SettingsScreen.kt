package studio.cluvex.aether.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.data.AppLanguage
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.ExternalKind
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.transport.ExitRegions
import studio.cluvex.aether.ui.AboutPanel
import studio.cluvex.aether.ui.SharePanel
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.SegmentedSelector

/**
 * Every screen the settings area can show.
 *
 * A flat enum plus an explicit stack, instead of a navigation library: the graph
 * is one level deep, so a dependency and the recomposition its host graph brings
 * would buy nothing. The stack lives in [SettingsHost] and the system back
 * gesture pops it.
 */
enum class SettingsRoute {
    HOME,
    CONNECTION,
    TRANSPORT,
    DNS_ROUTING,
    UPSTREAM,
    ZERO_TRUST,
    APPS,
    SECURITY,
    ENGINE,
    APPEARANCE,
    DIAGNOSTICS,
    SHARE,
    ABOUT,
}

/**
 * The settings area.
 *
 * Only ONE page is composed at a time, and a page is a list of independently lazy
 * sections, so opening settings costs the rows on screen rather than the fifty
 * controls the old single-card panel built in one frame. See SettingsUi.kt.
 */
@Composable
fun SettingsHost(
    start: SettingsRoute,
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stack = remember {
        mutableStateListOf(SettingsRoute.HOME).also {
            if (start != SettingsRoute.HOME) it.add(start)
        }
    }
    val route = stack.last()

    val open: (SettingsRoute) -> Unit = { next -> stack.add(next) }
    val back: () -> Unit = {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            onClose()
        }
    }

    BackHandler(enabled = true) { back() }

    // Engine settings can only change while the tunnel is down: they are
    // command-line flags handed to a process that is already running.
    val editable = state is ConnectionState.Idle || state is ConnectionState.Error

    when (route) {
        SettingsRoute.HOME -> SettingsHomePage(profile, editable, open, onProfileChange, onClose, modifier)
        SettingsRoute.CONNECTION -> ConnectionPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.TRANSPORT -> TransportPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.DNS_ROUTING -> DnsRoutingPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.UPSTREAM -> UpstreamPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.ZERO_TRUST -> ZeroTrustPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.APPS -> AppsPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.SECURITY -> SecurityPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.ENGINE -> EnginePage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.APPEARANCE -> AppearancePage(back, modifier)
        SettingsRoute.DIAGNOSTICS -> PanelPage(stringResource(R.string.diag_title), back, modifier) {
            DiagnosticsPanel(startExpanded = true)
        }
        SettingsRoute.SHARE -> PanelPage(stringResource(R.string.share_title), back, modifier) {
            SharePanel(
                state = state,
                profile = profile,
                onProfileChange = onProfileChange,
                startExpanded = true,
            )
        }
        SettingsRoute.ABOUT -> PanelPage(stringResource(R.string.about_title), back, modifier) {
            AboutPanel(startExpanded = true)
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun SettingsHomePage(
    profile: ConnectionProfile,
    editable: Boolean,
    onOpen: (SettingsRoute) -> Unit,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SettingsScaffold(stringResource(R.string.settings_title), onBack, modifier) {
        if (!editable) {
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(
                        text = stringResource(R.string.settings_locked_hint),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }

        // The decisions that change what the tunnel actually IS come first, each
        // with its current value on the row, so the whole configuration can be
        // read without opening anything.
        settingsSection {
            GroupCaption(stringResource(R.string.cat_group_tunnel))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.cat_connection),
                    summary = stringResource(R.string.cat_connection_desc),
                    icon = Icons.Rounded.Layers,
                    value = backendShortLabel(profile.backend),
                    onClick = { onOpen(SettingsRoute.CONNECTION) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_transport),
                    summary = stringResource(R.string.cat_transport_desc),
                    icon = Icons.Rounded.Bolt,
                    value = noizeLabel(profile.noize),
                    onClick = { onOpen(SettingsRoute.TRANSPORT) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.cat_dns_routing),
                    summary = stringResource(R.string.cat_dns_routing_desc),
                    icon = Icons.Rounded.Dns,
                    onClick = { onOpen(SettingsRoute.DNS_ROUTING) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_upstream),
                    summary = stringResource(R.string.cat_upstream_desc),
                    icon = Icons.Rounded.Link,
                    onClick = { onOpen(SettingsRoute.UPSTREAM) },
                )
            }
        }

        settingsSection {
            GroupCaption(stringResource(R.string.cat_group_device))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.cat_apps),
                    summary = stringResource(R.string.cat_apps_desc),
                    icon = Icons.Rounded.Apps,
                    onClick = { onOpen(SettingsRoute.APPS) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_security),
                    summary = stringResource(R.string.cat_security_desc),
                    icon = Icons.Rounded.Lock,
                    onClick = { onOpen(SettingsRoute.SECURITY) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.share_title),
                    summary = stringResource(R.string.share_subtitle),
                    icon = Icons.Rounded.Wifi,
                    onClick = { onOpen(SettingsRoute.SHARE) },
                )
            }
        }

        settingsSection {
            GroupCaption(stringResource(R.string.cat_group_app))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.language_title),
                    summary = stringResource(R.string.cat_appearance_desc),
                    icon = Icons.Rounded.Language,
                    value = languageLabel(LanguagePrefs.read(context)),
                    onClick = { onOpen(SettingsRoute.APPEARANCE) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.diag_title),
                    summary = stringResource(R.string.diag_subtitle),
                    icon = Icons.Rounded.BugReport,
                    onClick = { onOpen(SettingsRoute.DIAGNOSTICS) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_engine_tuning),
                    summary = stringResource(R.string.cat_engine_desc),
                    icon = Icons.Rounded.Tune,
                    onClick = { onOpen(SettingsRoute.ENGINE) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_zerotrust),
                    summary = stringResource(R.string.cat_zerotrust_desc),
                    icon = Icons.Rounded.VpnKey,
                    onClick = { onOpen(SettingsRoute.ZERO_TRUST) },
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.about_title),
                    summary = stringResource(R.string.about_subtitle),
                    icon = Icons.Rounded.Info,
                    onClick = { onOpen(SettingsRoute.ABOUT) },
                )
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.reset_settings),
                    summary = stringResource(R.string.reset_confirm_body),
                    icon = Icons.Rounded.RestartAlt,
                    enabled = editable,
                    destructive = true,
                    onClick = { confirmReset = true },
                )
            }
        }
    }

    if (confirmReset) {
        // A confirmation, which the old inline button did not have: one tap wiped
        // every setting, including hand-typed endpoint ranges and enrolment
        // details, with no way back.
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onProfileChange(ConnectionProfile())
                    Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.reset_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- connection

@Composable
private fun ConnectionPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.cat_connection), onBack, modifier) {
    settingsSection {
        GroupCaption(stringResource(R.string.backend_label))
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.backend_label),
                icon = Icons.Rounded.Layers,
                options = TransportBackend.entries,
                selected = profile.backend,
                label = { backendShortLabel(it) },
                onSelect = { onProfileChange(profile.copy(backend = it)) },
                enabled = editable,
            )
            RowDivider()
            SettingsChoiceRow(
                title = stringResource(R.string.exit_country_label),
                icon = Icons.Rounded.Public,
                options = ExitRegions.values,
                selected = profile.exitRegion.takeIf { it in ExitRegions.values } ?: "",
                label = { ExitRegions.label(it) },
                onSelect = { onProfileChange(profile.copy(exitRegion = it)) },
                enabled = editable && profile.backend.usesExternal,
                summary = when (profile.backend.externalKind) {
                    null -> stringResource(R.string.exit_help_aether)
                    ExternalKind.PSIPHON -> stringResource(R.string.exit_help_psiphon)
                },
            )
        }
        GroupFooter(backendHelp(profile.backend))
    }

    settingsSection {
        GroupCaption(stringResource(R.string.section_core))
        SettingsGroup {
            SettingsBlock(title = stringResource(R.string.protocol)) {
                SegmentedSelector(
                    options = Protocol.entries,
                    selected = profile.protocol,
                    onSelect = { onProfileChange(profile.copy(protocol = it)) },
                    label = { protocolLabel(it) },
                    enabled = editable && profile.backend.usesAetherEngine,
                )
            }
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.scan_mode),
                icon = Icons.Rounded.Speed,
                options = ScanMode.entries,
                selected = profile.scanMode,
                label = { scanLabel(it) },
                onSelect = { onProfileChange(profile.copy(scanMode = it)) },
                enabled = editable && profile.backend.usesAetherEngine,
            )
            RowDivider(inset = false)
            SettingsBlock(title = stringResource(R.string.ip_version)) {
                SegmentedSelector(
                    options = IpVersion.entries,
                    selected = profile.ipVersion,
                    onSelect = { onProfileChange(profile.copy(ipVersion = it)) },
                    label = { ipLabel(it) },
                    enabled = editable && profile.backend.usesAetherEngine,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- transport

@Composable
private fun TransportPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_transport), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.noize_title),
                icon = Icons.Rounded.Bolt,
                options = Noize.entries,
                selected = profile.noize,
                label = { noizeLabel(it) },
                onSelect = { onProfileChange(profile.copy(noize = it)) },
                enabled = editable && profile.backend.usesAetherEngine,
            )
        }
        GroupFooter(stringResource(R.string.noize_desc))
    }

    settingsSection {
        GroupCaption(stringResource(R.string.endpoint_mode))
        SettingsGroup {
            SettingsBlock {
                SegmentedSelector(
                    options = EndpointMode.entries,
                    selected = profile.endpointMode,
                    onSelect = { onProfileChange(profile.copy(endpointMode = it)) },
                    label = { endpointLabel(it) },
                    enabled = editable && profile.backend.usesAetherEngine,
                )
            }
            if (profile.endpointMode == EndpointMode.MANUAL_PEER) {
                RowDivider(inset = false)
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = profile.manualPeer,
                        onValueChange = { onProfileChange(profile.copy(manualPeer = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.manual_peer_label)) },
                        placeholder = { Text(stringResource(R.string.manual_peer_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (profile.endpointMode == EndpointMode.MANUAL_RANGE) {
                RowDivider(inset = false)
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = profile.manualRange,
                        onValueChange = { onProfileChange(profile.copy(manualRange = it)) },
                        enabled = editable,
                        singleLine = false,
                        label = { Text(stringResource(R.string.manual_range_label)) },
                        placeholder = { Text(stringResource(R.string.manual_range_hint)) },
                        supportingText = { Text(stringResource(R.string.manual_range_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_tuning))
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.keepalive_label),
                options = ConnectionProfile.KEEPALIVE_PRESETS,
                selected = profile.keepalive,
                label = { if (it == 0) stringResource(R.string.keepalive_default) else "$it" },
                onSelect = { onProfileChange(profile.copy(keepalive = it)) },
                enabled = editable,
            )
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.mtu_label),
                options = ConnectionProfile.MTU_PRESETS,
                selected = profile.mtu,
                label = { "$it" },
                onSelect = { onProfileChange(profile.copy(mtu = it)) },
                enabled = editable,
            )
        }
        GroupFooter(stringResource(R.string.mtu_desc))
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_antidpi))
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.fragment_title),
                summary = stringResource(R.string.fragment_desc),
                checked = profile.fragment,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(fragment = it)) },
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.ech_title),
                summary = stringResource(R.string.ech_desc),
                checked = profile.ech,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(ech = it)) },
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.masque_http2),
                summary = stringResource(R.string.masque_http2_desc),
                checked = profile.masqueHttp2,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(masqueHttp2 = it)) },
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.quick_reconnect),
                summary = stringResource(R.string.quick_reconnect_desc),
                checked = profile.quickReconnect,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(quickReconnect = it)) },
            )
            if (profile.quickReconnect) {
                RowDivider(inset = false)
                SettingsSwitchRow(
                    title = stringResource(R.string.fast_endpoint_title),
                    summary = stringResource(R.string.fast_endpoint_desc),
                    checked = profile.fastEndpointOnly,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(fastEndpointOnly = it)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- dns + routing

@Composable
private fun DnsRoutingPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.cat_dns_routing), onBack, modifier) {
    settingsSection {
        GroupCaption(stringResource(R.string.dns_label))
        SettingsGroup {
            SettingsBlock(helper = stringResource(R.string.dns_help)) {
                LtrOutlinedTextField(
                    value = profile.dnsServers,
                    onValueChange = { onProfileChange(profile.copy(dnsServers = it)) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.dns_label)) },
                    placeholder = { Text(stringResource(R.string.dns_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.section_routes))
        SettingsGroup {
            SettingsBlock {
                LtrOutlinedTextField(
                    value = profile.routeBlock,
                    onValueChange = { onProfileChange(profile.copy(routeBlock = it)) },
                    enabled = editable,
                    singleLine = false,
                    label = { Text(stringResource(R.string.route_block_label)) },
                    placeholder = { Text(stringResource(R.string.route_block_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = profile.routeDirect,
                    onValueChange = { onProfileChange(profile.copy(routeDirect = it)) },
                    enabled = editable,
                    singleLine = false,
                    label = { Text(stringResource(R.string.route_direct_label)) },
                    placeholder = { Text(stringResource(R.string.route_direct_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.route_sniff_title),
                summary = stringResource(R.string.route_sniff_desc),
                checked = profile.routeSniff,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(routeSniff = it)) },
            )
            if (profile.routeSniff) {
                RowDivider(inset = false)
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = if (profile.routeSniffMs == 0) "" else profile.routeSniffMs.toString(),
                        onValueChange = {
                            onProfileChange(
                                profile.copy(
                                    routeSniffMs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
                                ),
                            )
                        },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.route_sniff_ms_label)) },
                        placeholder = { Text(stringResource(R.string.route_sniff_ms_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        GroupFooter(stringResource(R.string.routes_help))
    }
}

// ---------------------------------------------------------------- upstream

@Composable
private fun UpstreamPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_upstream), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsBlock {
                LtrOutlinedTextField(
                    value = profile.upstreamProxy,
                    onValueChange = { onProfileChange(profile.copy(upstreamProxy = it)) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.upstream_label)) },
                    placeholder = { Text(stringResource(R.string.upstream_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        GroupFooter(stringResource(R.string.upstream_help))
    }
}

// ---------------------------------------------------------------- zero trust

@Composable
private fun ZeroTrustPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_zerotrust), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.team_auth_label),
                icon = Icons.Rounded.VpnKey,
                options = TeamAuth.entries,
                selected = profile.teamAuth,
                label = { teamAuthLabel(it) },
                onSelect = { onProfileChange(profile.copy(teamAuth = it)) },
                enabled = editable,
            )
        }
        GroupFooter(stringResource(R.string.team_auth_desc))
    }

    if (profile.teamAuth != TeamAuth.OFF) {
        settingsSection {
            SettingsGroup {
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = profile.team,
                        onValueChange = { onProfileChange(profile.copy(team = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.team_label)) },
                        placeholder = { Text(stringResource(R.string.team_hint)) },
                        supportingText = { Text(stringResource(R.string.team_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (profile.teamAuth) {
                        TeamAuth.SERVICE_TOKEN -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessClientId,
                                onValueChange = { onProfileChange(profile.copy(accessClientId = it)) },
                                enabled = editable,
                                singleLine = true,
                                label = { Text(stringResource(R.string.access_id_label)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessClientSecret,
                                onValueChange = { onProfileChange(profile.copy(accessClientSecret = it)) },
                                enabled = editable,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.access_secret_label)) },
                                supportingText = { Text(stringResource(R.string.access_secret_help)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TeamAuth.EMAIL -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessEmail,
                                onValueChange = { onProfileChange(profile.copy(accessEmail = it)) },
                                enabled = editable,
                                singleLine = true,
                                label = { Text(stringResource(R.string.access_email_label)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TeamAuth.TOKEN -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessToken,
                                onValueChange = { onProfileChange(profile.copy(accessToken = it)) },
                                enabled = editable,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.access_token_label)) },
                                supportingText = { Text(stringResource(R.string.access_secret_help)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TeamAuth.OFF -> Unit
                    }
                }
                RowDivider(inset = false)
                SettingsSwitchRow(
                    title = stringResource(R.string.gateway_title),
                    summary = stringResource(R.string.gateway_desc),
                    checked = profile.gateway,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(gateway = it)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- apps

@Composable
private fun AppsPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSplitPicker by remember { mutableStateOf(false) }
    var showBlockedPicker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.share_copied)
    val socks = "127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}"
    val http = "127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}"

    SettingsScaffold(stringResource(R.string.cat_apps), onBack, modifier) {
        settingsSection {
            GroupCaption(stringResource(R.string.section_routing))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.proxy_mode_title),
                    summary = stringResource(R.string.proxy_mode_desc),
                    icon = Icons.Rounded.Link,
                    checked = profile.proxyMode,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(proxyMode = it)) },
                )
                if (profile.proxyMode) {
                    RowDivider(inset = false)
                    // Fixed, standard ports: the same value every session, so
                    // what the user copies into another app keeps working.
                    SettingsValueRow(
                        title = stringResource(R.string.proxy_socks_label),
                        value = socks,
                    ) {
                        CopyButton {
                            clipboard.setText(AnnotatedString(socks))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                    RowDivider(inset = false)
                    SettingsValueRow(
                        title = stringResource(R.string.proxy_http_label),
                        value = http,
                    ) {
                        CopyButton {
                            clipboard.setText(AnnotatedString(http))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        settingsSection {
            GroupCaption(stringResource(R.string.split_mode))
            SettingsGroup {
                SettingsBlock {
                    SegmentedSelector(
                        options = SplitMode.entries,
                        selected = profile.splitMode,
                        onSelect = { onProfileChange(profile.copy(splitMode = it)) },
                        label = { splitLabel(it) },
                        enabled = editable,
                    )
                }
                if (profile.splitMode != SplitMode.OFF) {
                    RowDivider(inset = false)
                    SettingsActionRow(
                        title = stringResource(R.string.split_select_apps, profile.splitApps.size),
                        icon = Icons.Rounded.Apps,
                        enabled = editable,
                        onClick = { showSplitPicker = true },
                    )
                }
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.blocked_select_apps, profile.blockedApps.size),
                    icon = Icons.Rounded.Block,
                    enabled = editable,
                    onClick = { showBlockedPicker = true },
                )
            }
            GroupFooter(stringResource(R.string.blocked_apps_desc))
        }
    }

    if (showSplitPicker) {
        AppPickerDialog(
            selected = profile.splitApps,
            onDismiss = { showSplitPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(splitApps = it))
                showSplitPicker = false
            },
        )
    }
    if (showBlockedPicker) {
        AppPickerDialog(
            selected = profile.blockedApps,
            onDismiss = { showBlockedPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(blockedApps = it))
                showBlockedPicker = false
            },
        )
    }
}

@Composable
private fun CopyButton(onCopy: () -> Unit) {
    IconButton(onClick = onCopy) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.share_copy),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------------------------------------------------------------- security

@Composable
private fun SecurityPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_security), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.kill_switch_title),
                summary = stringResource(R.string.kill_switch_desc),
                icon = Icons.Rounded.Lock,
                checked = profile.killSwitch,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(killSwitch = it)) },
            )
            if (profile.killSwitch) {
                RowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.strict_kill_switch_title),
                    summary = stringResource(R.string.strict_kill_switch_desc),
                    checked = profile.strictKillSwitch,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(strictKillSwitch = it)) },
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.ipv6_leak_title),
                summary = stringResource(R.string.ipv6_leak_desc),
                checked = profile.ipv6LeakProtection,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(ipv6LeakProtection = it)) },
            )
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_recovery))
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.reprovision_title),
                summary = stringResource(R.string.reprovision_desc),
                checked = profile.autoReprovision,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(autoReprovision = it)) },
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.smart_reconnect_title),
                summary = stringResource(R.string.smart_reconnect_desc),
                checked = profile.smartReconnect,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(smartReconnect = it)) },
            )
            if (profile.smartReconnect) {
                RowDivider(inset = false)
                SettingsChoiceRow(
                    title = stringResource(R.string.reconnect_limit_label),
                    options = listOf(3, 5, 10, 15, 20),
                    selected = profile.reconnectRetryLimit,
                    label = { "$it" },
                    onSelect = { onProfileChange(profile.copy(reconnectRetryLimit = it)) },
                    enabled = editable,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- engine

@Composable
private fun EnginePage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_engine_tuning), onBack, modifier) {
    if (profile.fragment) {
        settingsSection {
            GroupCaption(stringResource(R.string.fragment_title))
            SettingsGroup {
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = profile.fragmentSize,
                        onValueChange = { onProfileChange(profile.copy(fragmentSize = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.fragment_size_label)) },
                        placeholder = { Text(stringResource(R.string.fragment_size_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = profile.fragmentDelay,
                        onValueChange = { onProfileChange(profile.copy(fragmentDelay = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.fragment_delay_label)) },
                        placeholder = { Text(stringResource(R.string.fragment_delay_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_tuning))
        SettingsGroup {
            SettingsBlock {
                LtrOutlinedTextField(
                    value = profile.tlsGroups,
                    onValueChange = { onProfileChange(profile.copy(tlsGroups = it)) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.tls_groups_label)) },
                    placeholder = { Text(stringResource(R.string.tls_groups_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = if (profile.validateSecs == 0) "" else profile.validateSecs.toString(),
                    onValueChange = {
                        onProfileChange(
                            profile.copy(validateSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0),
                        )
                    },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.validate_secs_label)) },
                    placeholder = { Text(stringResource(R.string.secs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = if (profile.reconnectSecs == 0) "" else profile.reconnectSecs.toString(),
                    onValueChange = {
                        onProfileChange(
                            profile.copy(reconnectSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0),
                        )
                    },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.reconnect_secs_label)) },
                    placeholder = { Text(stringResource(R.string.secs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_data_check_title),
                summary = stringResource(R.string.no_data_check_desc),
                checked = profile.noDataCheck,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(noDataCheck = it)) },
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_profile_retry_title),
                summary = stringResource(R.string.no_profile_retry_desc),
                checked = profile.noProfileRetry,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(noProfileRetry = it)) },
            )
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.core_log_level_label),
                options = CoreLogLevel.entries,
                selected = profile.coreLogLevel,
                label = { it.name },
                onSelect = { onProfileChange(profile.copy(coreLogLevel = it)) },
                enabled = editable,
            )
        }
    }
}

// ---------------------------------------------------------------- language

@Composable
private fun AppearancePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = LanguagePrefs.read(context)

    SettingsScaffold(stringResource(R.string.language_title), onBack, modifier) {
        settingsSection {
            SettingsGroup {
                AppLanguage.entries.forEachIndexed { index, language ->
                    if (index > 0) RowDivider(inset = false)
                    SettingsRadioRow(
                        // Radio rows rather than a picker: the choice has to be
                        // readable in BOTH languages at the same time, so a user
                        // who cannot read the language the app is currently in
                        // can still find the row that says "English" or "فارسی".
                        title = languageLabel(language),
                        selected = language == current,
                        onSelect = {
                            if (language != current) {
                                LanguagePrefs.write(context, language)
                                // The locale lives in the Activity's base
                                // context, so it can only change by rebuilding
                                // the Activity. This is the same recreate() the
                                // system runs for a configuration change, so
                                // nothing is lost that a rotation would not also
                                // lose.
                                //
                                // findActivity, not `as? Activity`: LocalContext
                                // is not guaranteed to BE the activity, and this
                                // app deliberately wraps its contexts, so the
                                // plain cast could silently return null and turn
                                // the whole language switch into a no-op.
                                LanguagePrefs.findActivity(context)?.recreate()
                            }
                        },
                        icon = if (language == AppLanguage.SYSTEM) Icons.Rounded.Language else null,
                    )
                }
            }
            GroupFooter(stringResource(R.string.language_desc))
        }
    }
}

// ---------------------------------------------------------------- panel host

/** Hosts one of the existing self-contained cards on its own settings page. */
@Composable
private fun PanelPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = SettingsScaffold(title, onBack, modifier) {
    item {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

// ---------------------------------------------------------------- labels

@Composable
private fun backendShortLabel(backend: TransportBackend): String = when (backend) {
    TransportBackend.AETHER -> "Aether"
    TransportBackend.AETHER_PSIPHON -> "Aether \u2192 Psiphon"
}

@Composable
private fun backendHelp(backend: TransportBackend): String = when (backend) {
    // Localised now. Both lines were hard-coded English in the old panel, so the
    // Persian UI explained its single most important setting in English.
    TransportBackend.AETHER -> stringResource(R.string.backend_help_aether)
    TransportBackend.AETHER_PSIPHON -> stringResource(R.string.backend_help_chained)
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.PERSIAN -> stringResource(R.string.language_persian)
}

@Composable
private fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.AUTO -> stringResource(R.string.protocol_auto)
    Protocol.MASQUE -> stringResource(R.string.protocol_masque)
    Protocol.WIREGUARD -> stringResource(R.string.protocol_wireguard)
    Protocol.GOOL -> stringResource(R.string.protocol_gool)
}

@Composable
private fun scanLabel(mode: ScanMode): String = when (mode) {
    ScanMode.TURBO -> stringResource(R.string.scan_turbo)
    ScanMode.BALANCED -> stringResource(R.string.scan_balanced)
    ScanMode.THOROUGH -> stringResource(R.string.scan_thorough)
    ScanMode.STEALTH -> stringResource(R.string.scan_stealth)
    ScanMode.IRONCLAD -> stringResource(R.string.scan_ironclad)
}

@Composable
private fun ipLabel(ip: IpVersion): String = when (ip) {
    IpVersion.V4 -> stringResource(R.string.ip_v4)
    IpVersion.V6 -> stringResource(R.string.ip_v6)
    IpVersion.BOTH -> stringResource(R.string.ip_both)
}

@Composable
private fun noizeLabel(n: Noize): String = when (n) {
    Noize.OFF -> stringResource(R.string.noize_off)
    Noize.LIGHT -> stringResource(R.string.noize_light)
    Noize.FIREWALL -> stringResource(R.string.noize_firewall)
    Noize.BALANCED -> stringResource(R.string.noize_balanced)
    Noize.GFW -> stringResource(R.string.noize_gfw)
    Noize.AGGRESSIVE -> stringResource(R.string.noize_aggressive)
}

@Composable
private fun endpointLabel(m: EndpointMode): String = when (m) {
    EndpointMode.AUTO -> stringResource(R.string.endpoint_auto)
    EndpointMode.MANUAL_PEER -> stringResource(R.string.endpoint_peer)
    EndpointMode.MANUAL_RANGE -> stringResource(R.string.endpoint_range)
}

@Composable
private fun teamAuthLabel(a: TeamAuth): String = when (a) {
    TeamAuth.OFF -> stringResource(R.string.team_auth_off)
    TeamAuth.SERVICE_TOKEN -> stringResource(R.string.team_auth_service)
    TeamAuth.EMAIL -> stringResource(R.string.team_auth_email)
    TeamAuth.TOKEN -> stringResource(R.string.team_auth_token)
}

@Composable
private fun splitLabel(m: SplitMode): String = when (m) {
    SplitMode.OFF -> stringResource(R.string.split_off)
    SplitMode.INCLUDE -> stringResource(R.string.split_include)
    SplitMode.EXCLUDE -> stringResource(R.string.split_exclude)
}

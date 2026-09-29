package com.example.hawk_wallet.ui.screens.team

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.theme.HawkAmber
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkAmberSoft
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkIndigoDark
import com.example.hawk_wallet.ui.theme.HawkVioletBlue

private val HeroShape = RoundedCornerShape(28.dp)
private val CardShape = RoundedCornerShape(20.dp)

/** Levels shown in "How Team Commission Works" before "Show all levels". */
private const val COLLAPSED_LEVEL_COUNT = 3

// --- summary -------------------------------------------------------------------

/** Hero card in the Home balance card's style; every figure is the backend's. */
@Composable
internal fun TeamSummaryCard(referral: ReferralUi, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = HeroShape, spotColor = HawkIndigo, ambientColor = HawkIndigo)
            .clip(HeroShape)
            .background(Brush.linearGradient(colors = listOf(HawkIndigoDark, HawkIndigo, HawkVioletBlue)))
    ) {
        // Faint ring for depth; purely decorative (as on the Home balance card).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 60.dp, y = (-70).dp)
                .size(190.dp)
                .background(Color.White.copy(alpha = 0.06f), CircleShape)
        )
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = "Total Commission",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = referral.totalCommission,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(vertical = 12.dp)
            ) {
                HeroStat(label = "Direct Team", value = referral.directTeam, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.22f))
                )
                HeroStat(label = "Total Team", value = referral.totalTeam, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// --- invite ---------------------------------------------------------------------

/**
 * Invite & Earn with the backend's code and link. The link is shown only
 * when the backend provides one - it is never built on the device.
 */
@Composable
internal fun InviteCard(
    referral: ReferralUi,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = HawkAmberSoft),
        border = BorderStroke(1.dp, HawkAmber.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Filled.CardGiftcard, tint = HawkIndigoDark, background = HawkAmber.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Invite & Earn",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Share your code. Earn when your team's payments are confirmed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            InviteField(label = "Referral Code") {
                Text(
                    text = referral.code,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = HawkIndigoDark
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            InviteField(label = "Referral Link") {
                if (referral.link != null) {
                    Text(
                        text = referral.link,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "Your invite link isn't available yet. Share your referral code instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HawkIndigoDark.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HawkIndigoDark),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (referral.link != null) "Copy Link" else "Copy Code")
                }
                Button(
                    onClick = onShare,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HawkIndigoDark, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun InviteField(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        content()
    }
}

/** Copies the backend's link (or, without one, the code) to the clipboard. */
internal fun copyInvite(context: Context, referral: ReferralUi) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val text = referral.link ?: referral.code
    clipboard.setPrimaryClip(ClipData.newPlainText("Hawk Wallet invite", text))
}

/** Opens the system share sheet with the backend's code and, when provided, link. */
internal fun shareInvite(context: Context, referral: ReferralUi) {
    val message = buildString {
        append("Join me on Hawk Wallet! Use my referral code ")
        append(referral.code)
        append(" when you register.")
        referral.link?.let { append("\n").append(it) }
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(send, "Invite friends"))
}

// --- how it works -----------------------------------------------------------------

/**
 * The commission ladder from GET /api/team/summary/: You -> Level 1 -> ...
 * Rates, counts and totals are the backend's. Levels past the last
 * commission-eligible one stay listed (they are still team) but show
 * "No commission".
 */
@Composable
internal fun HowItWorksCard(
    levels: List<TeamLevelUi>,
    onLevelClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) levels else levels.take(COLLAPSED_LEVEL_COUNT)
    val lastEligibleLevel = levels.lastOrNull { it.commissionEligible }?.level

    SurfaceCard(modifier = modifier) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Earn when your team members' payments are confirmed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            LadderNode(
                badge = { NodeBadge(background = HawkIndigo) { Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(18.dp)) } },
                title = "You",
                subtitle = null,
                trailing = null,
                showConnector = shown.isNotEmpty(),
                onClick = null
            )
            shown.forEachIndexed { index, level ->
                LadderNode(
                    badge = {
                        NodeBadge(background = if (level.commissionEligible) HawkIndigo.copy(alpha = 0.10f) else MaterialTheme.colorScheme.outline) {
                            Text(
                                text = level.level.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (level.commissionEligible) HawkIndigo else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    title = "Level ${level.level}",
                    subtitle = levelSubtitle(level),
                    trailing = { RateChip(level) },
                    showConnector = index < shown.lastIndex,
                    onClick = if (level.memberCount > 0) ({ onLevelClick(level.level) }) else null
                )
            }

            if (levels.size > COLLAPSED_LEVEL_COUNT) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 40.dp)) {
                    Text(if (expanded) "Show fewer levels" else "Show all ${levels.size} levels")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(14.dp))

            RuleLine(
                icon = Icons.Outlined.CheckCircle,
                tint = HawkEmerald,
                text = "Commission is created only when a team member's payment is confirmed."
            )
            RuleLine(
                icon = Icons.Outlined.Info,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = "No commission is created when someone registers or joins with your code, " +
                    "or while a payment is pending, under review after UTR submission, or rejected."
            )
            if (lastEligibleLevel != null) {
                RuleLine(
                    icon = Icons.Outlined.Groups,
                    tint = HawkIndigo,
                    text = "Your team can grow beyond Level $lastEligibleLevel. Members deeper than " +
                        "Level $lastEligibleLevel still count toward your team, but their payments " +
                        "don't generate commission."
                )
            }
        }
    }
}

private fun levelSubtitle(level: TeamLevelUi): String {
    val members = if (level.memberCount == 1) "1 member" else "${level.memberCount} members"
    return if (level.commissionEligible) "$members · ${level.commissionTotal} earned" else members
}

@Composable
private fun LadderNode(
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    showConnector: Boolean,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(modifier = Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            badge()
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(HawkIndigo.copy(alpha = 0.18f))
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp, bottom = if (showConnector) 14.dp else 0.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NodeBadge(background: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** The backend's rate ("0.3%"), or "No commission" for levels past the commission depth. */
@Composable
private fun RateChip(level: TeamLevelUi) {
    val (text, color, background) = if (level.rate != null) {
        Triple(level.rate, HawkAmberDark, HawkAmberSoft)
    } else {
        Triple("No commission", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun RuleLine(icon: ImageVector, tint: Color, text: String) {
    Row(modifier = Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// --- rows & shared pieces -----------------------------------------------------------

@Composable
internal fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) { content() }
}

/** "Title ........ See All", as on the Home Transactions section. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) { Text("See All") }
        }
    }
}

@Composable
internal fun MemberRow(member: TeamMemberUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Initial in a soft indigo tile - no avatars or pictures.
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(HawkIndigo.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = member.name.trim().take(1).uppercase().ifEmpty { "•" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = HawkIndigo
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${member.mobileMasked} · Joined ${member.joined}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        LevelTag(level = member.level)
    }
}

@Composable
internal fun LevelTag(level: Int) {
    Text(
        text = "Level $level",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = HawkIndigo,
        modifier = Modifier
            .background(HawkIndigo.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** Compact commission row, in the Home transaction row's style. */
@Composable
internal fun CommissionRow(commission: CommissionUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = Icons.Outlined.Payments, tint = HawkEmerald, background = HawkEmerald.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Level ${commission.level} · ${commission.rate}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "From ${commission.sourceName} · ${commission.orderReference}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = commission.createdAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "+${commission.amount}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = HawkEmerald
        )
    }
}

@Composable
internal fun IconBadge(icon: ImageVector, tint: Color, background: Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(background, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
    }
}

/** Small in-card empty message (icon, title, text), as on the Home transactions card. */
@Composable
internal fun InlineEmpty(icon: ImageVector, title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(HawkIndigo.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = HawkIndigo)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** A new member's team: nobody has joined with their code yet. */
@Composable
internal fun EmptyTeamCard(onInviteClick: () -> Unit, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(HawkEmeraldSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Groups, contentDescription = null, tint = HawkEmerald, modifier = Modifier.size(30.dp))
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Your team starts here",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Invite friends and earn when their confirmed payments generate eligible team commissions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onInviteClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HawkIndigoDark, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text("Invite Friends", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

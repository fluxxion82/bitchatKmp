package com.bitchat.design.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_teleported
import bitchatkmp.presentation.design.generated.resources.cd_unread_message
import bitchatkmp.presentation.design.generated.resources.geohash_people_header
import bitchatkmp.presentation.design.generated.resources.nobody_around
import bitchatkmp.presentation.design.generated.resources.you_suffix
import com.bitchat.design.BASE_FONT_SIZE
import com.bitchat.domain.location.model.GeoPerson
import org.jetbrains.compose.resources.stringResource

@Composable
fun GeohashPeopleList(
    people: List<GeoPerson>,
    myPersonId: String? = null,
    myNickname: String? = null,
    hasUnreadPrivateMessages: Set<String>,
    isSelfTeleported: Boolean,
    teleportedPeople: Set<String>,
    onPersonTap: (GeoPerson) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.geohash_people_header),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (people.isEmpty()) {
            Text(
                text = stringResource(Res.string.nobody_around),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = BASE_FONT_SIZE.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            return
        }

        val orderedPeople = remember(people, myNickname) {
            people.sortedWith { a, b ->
                when {
                    myNickname != null && a.displayName == myNickname && b.displayName != myNickname -> -1
                    myNickname != null && b.displayName == myNickname && a.displayName != myNickname -> 1
                    else -> b.lastSeen.compareTo(a.lastSeen)
                }
            }
        }

        val baseNameCounts = remember(people) {
            val counts = mutableMapOf<String, Int>()
            people.forEach { person ->
                val (baseName, _) = splitSuffix(person.displayName)
                counts[baseName] = (counts[baseName] ?: 0) + 1
            }
            counts
        }

        val firstId = orderedPeople.firstOrNull()?.id
        orderedPeople.forEach { person ->
            val hasUnread = hasUnreadPrivateMessages.contains("nostr_${person.id.take(16)}")
            val isMe = myNickname != null && person.displayName == myNickname
            val isTeleported = if (isMe) isSelfTeleported else teleportedPeople.contains(person.id)

            GeohashPersonItem(
                person = person,
                isFirst = person.id == firstId,
                isMe = isMe,
                hasUnreadDM = hasUnread,
                isTeleported = isTeleported,
                showHashSuffix = (baseNameCounts[splitSuffix(person.displayName).first] ?: 0) > 1,
                colorScheme = colorScheme,
                onTap = {
                    if (!isMe) {
                        onPersonTap(person)
                    }
                }
            )
        }
    }
}

@Composable
private fun GeohashPersonItem(
    person: GeoPerson,
    isFirst: Boolean,
    isMe: Boolean,
    hasUnreadDM: Boolean,
    isTeleported: Boolean,
    showHashSuffix: Boolean,
    colorScheme: ColorScheme,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .padding(top = if (isFirst) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasUnreadDM) {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(Res.string.cd_unread_message),
                modifier = Modifier.size(12.dp),
                tint = Color(0xFFFF9500)
            )
        } else {
            val iconColor = when {
                isMe -> Color(0xFFFF9500)
                else -> colorScheme.onSurface
            }
            val icon = if (isTeleported) Icons.Outlined.Explore else Icons.Outlined.LocationOn
            Icon(
                imageVector = icon,
                contentDescription = if (isTeleported) stringResource(Res.string.cd_teleported) else null,
                modifier = Modifier.size(12.dp),
                tint = iconColor.copy(alpha = if (isTeleported) 0.6f else 1.0f)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        val (baseNameRaw, suffixRaw) = splitSuffix(person.displayName)
        val baseName = truncateNickname(baseNameRaw)
        val suffix = if (showHashSuffix) suffixRaw else ""

        val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
        val assignedColor = colorForPeer(person.id, isDark)
        val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = baseName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                ),
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor.copy(alpha = 0.6f)
                )
            }

            if (isMe) {
                Text(
                    text = stringResource(Res.string.you_suffix),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor
                )
            }
        }
    }
}

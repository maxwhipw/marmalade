package app.marmalade.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The Marmalade context menu (design-lab `new-session` round 1, Option D — the
 * one detail the maintainer liked out of that round, 2026-07-25).
 *
 * Material 3's default popup menu is a square-ish sheet with full-bleed rows.
 * This is the house style instead: a soft container, an inset so the rows float
 * inside it, and individually rounded rows that tint on emphasis. Use
 * [MarmaladeMenu] + [MarmaladeMenuItem] for every long-press / overflow menu so
 * they all read as the same object.
 *
 * The rows are hand-built rather than `DropdownMenuItem`s (maintainer, 2026-07-26):
 * that component hard-codes a 48dp minimum height, 24dp icons and 16sp labels —
 * touch-target sizing meant for a full-width menu — and next to the drawer's own
 * 14sp rows the popup read as a different, much larger app. A custom row is the
 * only way to set that density, since `DropdownMenuItem` applies its `sizeIn`
 * after any modifier we could pass. Rows stay ≥40dp tall, so they are still
 * comfortably tappable.
 *
 * Not for form dropdowns — `ExposedDropdownMenu` (settings pickers) is a text
 * field affordance, a different component with different anatomy.
 */
private val MENU_RADIUS = 16.dp
private val ITEM_RADIUS = 10.dp
private val MENU_INSET = 5.dp
private val ITEM_ICON = 18.dp
private val MENU_MIN_WIDTH = 168.dp

@Composable
fun MarmaladeMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(MENU_RADIUS),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 14.dp,
        content = content,
    )
}

/**
 * One menu row: leading icon, label, optional supporting line.
 *
 * @param emphasized the menu's primary action — gets a tinted, rounded pill so
 *   the common case is obvious without reading.
 * @param destructive renders in the error color (delete / remove / revoke).
 */
@Composable
fun MarmaladeMenuItem(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    emphasized: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val accent = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconTint = if (enabled && !destructive) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        accent
    }
    Row(
        // fillMaxWidth is safe here: DropdownMenu measures its column at
        // IntrinsicSize.Max, so the rows fill to the widest one rather than to
        // the screen.
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = MENU_MIN_WIDTH)
            .padding(horizontal = MENU_INSET, vertical = 1.dp)
            .clip(RoundedCornerShape(ITEM_RADIUS))
            .background(
                if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(ITEM_ICON),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = accent,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Separator between an action group and a destructive tail. Inset to match. */
@Composable
fun MarmaladeMenuDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = MENU_INSET, vertical = 4.dp))
}

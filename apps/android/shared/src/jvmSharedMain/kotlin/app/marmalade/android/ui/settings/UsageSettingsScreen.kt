package app.marmalade.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.android.ui.rememberMarmaladeRpc
import app.marmalade.android.utils.UsageFormatUtils

/**
 * Usage screen (daemon usage.summary, T2 #8). Read-only daily rollups:
 * window picker (7/14/30/90d), per-day rows with a token bar relative to the
 * window's busiest day, per-purpose breakdown on multi-purpose days, window
 * total. Tokens are the primary metric; the notional dollar figure renders
 * only when nonzero (provider truth — under subscription auth it is
 * API-equivalent, not a real charge).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSettingsScreen(
    onBack: () -> Unit,
    viewModel: UsageViewModel = viewModel(factory = UsageViewModel.factory(rememberMarmaladeRpc())),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()

    // Usage accrues from every device — refresh on return to the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(silent = true) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is UsageUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is UsageUiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SettingsErrorState(
                    headline = "Can't load usage",
                    rawError = state.message,
                    onRetry = { viewModel.load() },
                )
            }

            is UsageUiState.Success -> UsageContent(
                padding = padding,
                state = state,
                days = days,
                onDaysChange = viewModel::setDays,
            )
        }
    }
}

@Composable
private fun UsageContent(
    padding: PaddingValues,
    state: UsageUiState.Success,
    days: Int,
    onDaysChange: (Int) -> Unit,
) {
    val rollups = UsageFormatUtils.rollupByDay(state.summary.entries)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageViewModel.WINDOWS.forEach { w ->
                    FilterChip(
                        selected = w == days,
                        onClick = { onDaysChange(w) },
                        label = { Text("${w}d") },
                    )
                }
            }
        }
        item {
            Text(
                "Trailing $days days through ${state.summary.today}. Token counts are provider " +
                    "truth; dollar figures are notional API-equivalents, not a real charge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Budget guardrail: rendered only when the daemon has one configured
        // (usage.summary.budget != null). Gates SCHEDULED (cron) turns only.
        state.summary.budget?.let { budget ->
            item { BudgetCard(budget) }
        }

        // Subscription plan limits (Claude Code's /usage windows: 5-hour +
        // weekly utilization). One card per harness entry — a future
        // subscription harness (e.g. a Codex adapter) shows up as its own
        // card with no client change. Empty when no live session can report.
        items(state.summary.planLimits, key = { it.harness }) { plan ->
            PlanLimitsCard(plan)
        }
        if (state.summary.planLimits.isEmpty()) {
            item {
                Text(
                    "Subscription plan limits (Claude Code's 5-hour and weekly windows) " +
                        "appear here while a session is live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (rollups.isEmpty()) {
            item {
                Text(
                    "No usage recorded in this window.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else {
            items(rollups, key = { it.day }) { day ->
                DayCard(day = day, rollups = rollups)
            }
            item {
                val turns = rollups.sumOf { it.turns }
                val inTok = rollups.sumOf { it.inputTokens }
                val outTok = rollups.sumOf { it.outputTokens }
                val usd = rollups.sumOf { it.costUsd }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Window total", style = MaterialTheme.typography.titleSmall)
                    Text(
                        UsageFormatUtils.summaryLine(turns, inTok, outTok, usd),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetCard(budget: app.marmalade.android.rpc.types.UsageBudget) {
    val over = budget.over
    // Loud over-state via the error color on both the line and the bar fill.
    val accent = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                UsageFormatUtils.formatBudgetLine(budget),
                style = MaterialTheme.typography.bodyMedium,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(UsageFormatUtils.budgetFraction(budget))
                        .height(8.dp)
                        .background(accent, RoundedCornerShape(4.dp)),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The daily budget gates scheduled (cron) turns only — your own prompts are never blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanLimitsCard(plan: app.marmalade.android.rpc.types.PlanLimits) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                UsageFormatUtils.planLimitsHeader(plan),
                style = MaterialTheme.typography.titleSmall,
            )
            plan.windows.forEach { w ->
                Spacer(Modifier.height(8.dp))
                // Near-cap windows go loud (error color) at 90% — the point
                // of the card is seeing a limit coming before it bites.
                val fraction = UsageFormatUtils.planWindowFraction(w)
                val accent = if (fraction >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(UsageFormatUtils.planWindowLine(w), style = MaterialTheme.typography.bodyMedium)
                    UsageFormatUtils.resetsInText(w.resetsAt, System.currentTimeMillis())?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(accent, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: UsageFormatUtils.DayRollup, rollups: List<UsageFormatUtils.DayRollup>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(day.day, style = MaterialTheme.typography.titleSmall)
                Text(
                    UsageFormatUtils.summaryLine(day.turns, day.inputTokens, day.outputTokens, day.costUsd),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(UsageFormatUtils.dayBarFraction(day, rollups))
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }
            if (day.entries.size > 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    day.entries.joinToString("   ") {
                        "${it.purpose}: ${UsageFormatUtils.fmtTokens(it.inputTokens + it.outputTokens)} tok · ${it.turns} turn" +
                            (if (it.turns == 1) "" else "s")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

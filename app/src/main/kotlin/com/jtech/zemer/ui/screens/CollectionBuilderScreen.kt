package com.jtech.zemer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * First UI surface for the personalized full-album collection builder.
 *
 * The picker buttons intentionally route into Zemer's existing catalog search instead of creating a
 * second catalog. The next integration step persists picks and feeds album candidates into
 * CollectionRecommendationRanker, then opens selected results through the normal album/download flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionBuilderScreen(navController: NavController) {
    var preferClassics by remember { mutableStateOf(true) }
    var preferCollections by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Build My Collection") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Tell Zemer what you love. We’ll recommend complete albums, then you choose what to download.",
                style = MaterialTheme.typography.bodyLarge,
            )

            CollectionStepCard(
                number = 1,
                title = "Pick favorite artists",
                description = "Choose about 3–5 singers you really like.",
                button = "Choose Artists",
                onClick = { navController.navigate(Screens.Artists.route) },
            )
            CollectionStepCard(
                number = 2,
                title = "Pick favorite albums",
                description = "Choose about 3–5 CDs that match your taste.",
                button = "Find Albums",
                onClick = { navController.navigate(Screens.Search.route) },
            )
            CollectionStepCard(
                number = 3,
                title = "Pick favorite songs",
                description = "A few favorite songs help fine-tune the recommendations.",
                button = "Find Songs",
                onClick = { navController.navigate(Screens.Search.route) },
            )

            Text("Collection style", fontWeight = FontWeight.Bold)
            PreferenceRow(
                text = "Favor classics and established CDs",
                checked = preferClassics,
                onCheckedChange = { preferClassics = it },
            )
            PreferenceRow(
                text = "Include collection / compilation albums",
                checked = preferCollections,
                onCheckedChange = { preferCollections = it },
            )

            Button(
                onClick = { /* Wired after seed persistence/candidate adapter is added. */ },
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            ) {
                Text("Generate Recommendations")
            }
            Text(
                "Recommendation generation will unlock after your picks are connected.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CollectionStepCard(
    number: Int,
    title: String,
    description: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$number. $title", fontWeight = FontWeight.Bold)
            Text(description)
            OutlinedButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun PreferenceRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

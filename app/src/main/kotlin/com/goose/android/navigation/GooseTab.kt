package com.goose.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector

enum class GooseTab(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Default.Home),
    Health("health", "Health", Icons.Default.Favorite),
    Coach("coach", "Coach", Icons.Default.Chat),
    More("more", "More", Icons.Default.MoreHoriz),
}

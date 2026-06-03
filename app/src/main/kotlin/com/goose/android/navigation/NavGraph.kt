package com.goose.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.goose.android.ui.screens.CoachScreen
import com.goose.android.ui.screens.HealthScreen
import com.goose.android.ui.screens.HomeScreen
import com.goose.android.ui.screens.MoreScreen

@Composable
fun GooseNavGraph(
    gooseNav: GooseNavController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = gooseNav.navController,
        startDestination = GooseTab.Home.route,
        modifier = modifier,
    ) {
        composable(GooseTab.Home.route) { HomeScreen() }
        composable(GooseTab.Health.route) { HealthScreen() }
        composable(GooseTab.Coach.route) { CoachScreen() }
        composable(GooseTab.More.route) { MoreScreen() }
    }
}

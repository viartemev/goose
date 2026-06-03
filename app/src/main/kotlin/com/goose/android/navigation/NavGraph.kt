package com.goose.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.goose.android.ui.screens.CoachScreen
import com.goose.android.ui.screens.HealthScreen
import com.goose.android.ui.screens.HomeScreen
import com.goose.android.ui.screens.MoreScreen

@Composable
fun GooseNavGraph(gooseNav: GooseNavController) {
    NavHost(
        navController = gooseNav.navController,
        startDestination = GooseTab.Home.route,
    ) {
        composable(GooseTab.Home.route) { HomeScreen() }
        composable(GooseTab.Health.route) { HealthScreen() }
        composable(GooseTab.Coach.route) { CoachScreen() }
        composable(GooseTab.More.route) { MoreScreen() }
    }
}

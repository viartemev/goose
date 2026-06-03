package com.goose.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class GooseNavController(
    val navController: NavHostController,
) {
    val currentTab: GooseTab?
        @Composable get() {
            val entry = navController.currentBackStackEntryAsState().value
            return GooseTab.entries.find { tab ->
                entry?.destination?.hierarchy?.any { it.route == tab.route } == true
            }
        }

    fun navigateToTab(tab: GooseTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

@Suppress("FunctionNaming")
@Composable
fun rememberGooseNavController(navController: NavHostController = rememberNavController()): GooseNavController =
    remember(navController) { GooseNavController(navController) }

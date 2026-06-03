package com.goose.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.goose.android.navigation.GooseNavGraph
import com.goose.android.navigation.GooseTab
import com.goose.android.navigation.rememberGooseNavController
import com.goose.android.ui.theme.GooseDeviceBackground
import com.goose.android.ui.theme.GooseSurfaceBackground
import com.goose.android.ui.theme.GooseTextPrimary
import com.goose.android.ui.theme.GooseTextSecondary
import com.goose.android.ui.theme.GooseTheme

@Suppress("FunctionNaming")
@Composable
fun GooseApp() {
    GooseTheme {
        // TODO T-026: onboarding gate using GooseViewModel.onboardingComplete
        AppShell()
    }
}

@Suppress("FunctionNaming")
@Composable
private fun AppShell() {
    val gooseNav = rememberGooseNavController()
    val currentTab = gooseNav.currentTab
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GooseDeviceBackground,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                GooseBottomBar(
                    currentTab = currentTab,
                    onTabSelected = { gooseNav.navigateToTab(it) },
                )
            },
        ) { innerPadding ->
            GooseNavGraph(gooseNav = gooseNav, modifier = Modifier.padding(innerPadding))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GooseBottomBar(
    currentTab: GooseTab?,
    onTabSelected: (GooseTab) -> Unit,
) {
    NavigationBar(containerColor = GooseSurfaceBackground) {
        GooseTab.entries.forEach { tab ->
            val selected = tab == currentTab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.title) },
                label = { Text(tab.title) },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = GooseTextPrimary,
                        selectedTextColor = GooseTextPrimary,
                        indicatorColor = GooseSurfaceBackground,
                        unselectedIconColor = GooseTextSecondary,
                        unselectedTextColor = GooseTextSecondary,
                    ),
            )
        }
    }
}

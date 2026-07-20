package com.sapphire.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val FEED = "feed"
    const val SAVED = "saved"
    const val EXPLORE = "explore"
    const val SETTINGS = "settings"
    const val READER = "reader/{itemId}"
    fun reader(itemId: String) = "reader/$itemId"
}

@Composable
fun SapphireNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.FEED) {
        composable(Routes.FEED) {
            TimelineScreen(
                onOpenReader = { itemId -> navController.navigate(Routes.reader(itemId)) },
                onOpenSaved = { navController.navigate(Routes.SAVED) },
                onOpenExplore = { navController.navigate(Routes.EXPLORE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) {
            val itemId = it.arguments?.getString("itemId") ?: return@composable
            ReaderScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EXPLORE) {
            ExploreScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SAVED) {
            SavedItemsScreen(
                onBack = { navController.popBackStack() },
                onOpen = { itemId -> navController.navigate(Routes.reader(itemId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

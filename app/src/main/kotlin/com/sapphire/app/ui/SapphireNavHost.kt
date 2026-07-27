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
    const val AGENTS = "agents"
    const val AGENT_BUILDER = "agentBuilder/{jobId}"
    const val AGENT_DETAIL = "agentDetail/{jobId}"
    const val READER = "reader/{itemId}"
    fun reader(itemId: String) = "reader/$itemId"
    fun agentBuilder(jobId: String) = "agentBuilder/$jobId"
    fun agentDetail(jobId: String) = "agentDetail/$jobId"
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
                onBuildAgent = { navController.navigate(Routes.AGENTS) },
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
        composable(Routes.AGENTS) {
            AgentListScreen(
                onBack = { navController.popBackStack() },
                onNew = { navController.navigate(Routes.agentBuilder("new")) },
                onOpen = { id -> navController.navigate(Routes.agentDetail(id)) },
            )
        }
        composable(
            route = Routes.AGENT_BUILDER,
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
        ) {
            AgentBuilderScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.AGENT_DETAIL,
            arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
        ) {
            AgentDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}

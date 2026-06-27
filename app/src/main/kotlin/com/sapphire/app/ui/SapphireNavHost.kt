package com.sapphire.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val ONBOARDING = "onboarding"
    const val REVIEW = "review"
    const val FEED = "feed"
    const val SAVED = "saved"
    const val EXPLORE = "explore"
}

@Composable
fun SapphireNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.FEED) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onReviewReady = {
                    navController.navigate(Routes.REVIEW) {
                        launchSingleTop = true
                    }
                },
                onCommitted = {
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.REVIEW) {
            ReviewScreen(
                onBack = { navController.popBackStack() },
                onApproved = {
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.FEED) {
            TimelineScreen(
                onBuildFeed = { navController.navigate(Routes.ONBOARDING) },
                onOpenSaved = { navController.navigate(Routes.SAVED) },
                onOpenExplore = { navController.navigate(Routes.EXPLORE) },
            )
        }
        composable(Routes.EXPLORE) {
            ExploreScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SAVED) {
            SavedItemsScreen(onBack = { navController.popBackStack() })
        }
    }
}

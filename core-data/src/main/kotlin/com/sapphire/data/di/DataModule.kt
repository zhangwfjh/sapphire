package com.sapphire.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.room.Room
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.LlmCacheDao
import com.sapphire.data.db.OnboardingDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SourceDao
import com.sapphire.data.llm.OpenAiCompatibleLlmClient
import com.sapphire.data.onboarding.ReviewMapper
import com.sapphire.data.onboarding.ReviewMapperProvider
import com.sapphire.data.onboarding.RoomOnboardingRepository
import com.sapphire.data.feed.RoomFeedRepository
import com.sapphire.data.feed.RoomSourceFeedQuery
import com.sapphire.data.feed.SourceFeedQuery
import com.sapphire.data.db.SavedItemDao
import com.sapphire.data.db.DiscoveredFeedDao
import com.sapphire.data.save.RoomRetentionPurge
import com.sapphire.data.source.RoomSourceRepository
import com.sapphire.data.save.RoomSavedItemRepository
import com.sapphire.domain.feed.FeedRepository
import com.sapphire.domain.llm.LlmClient
import com.sapphire.data.reader.RoomReaderItemStore
import com.sapphire.data.reader.RoomReaderOpCache
import com.sapphire.domain.llm.LlmConfig
import com.sapphire.domain.util.IdGenerator
import com.sapphire.domain.reader.ReaderItemStore
import com.sapphire.domain.reader.ReaderOpCache
import com.sapphire.domain.reader.ReaderOpsUseCase
import com.sapphire.domain.onboarding.CurateTaxonomyUseCase
import com.sapphire.domain.onboarding.OnboardingRepository
import com.sapphire.domain.review.ReviewBuilder
import com.sapphire.domain.save.RetentionPurge
import com.sapphire.domain.save.SavedItemRepository
import com.sapphire.domain.util.UuidIdGenerator
import com.sapphire.domain.source.SourceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.Provides
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SapphireDatabase = Room.databaseBuilder(
        context,
        SapphireDatabase::class.java,
        "sapphire.db",
    )
        .addCallback(com.sapphire.data.db.SeedDefaultFeedsCallback())
        .fallbackToDestructiveMigration()
        .build()

    @Provides fun provideOnboardingDao(db: SapphireDatabase): OnboardingDao = db.onboardingDao()
    @Provides fun provideFeedDao(db: SapphireDatabase): FeedDao = db.feedDao()
    @Provides fun provideSourceDao(db: SapphireDatabase): SourceDao = db.sourceDao()
    @Provides fun provideLlmCacheDao(db: SapphireDatabase): LlmCacheDao = db.llmCacheDao()
    @Provides fun provideSavedItemDao(db: SapphireDatabase): SavedItemDao = db.savedItemDao()
    @Provides fun provideDiscoveredFeedDao(db: SapphireDatabase): DiscoveredFeedDao = db.discoveredFeedDao()
    @Provides fun provideArticleBodyDao(db: SapphireDatabase): com.sapphire.data.db.ArticleBodyDao = db.articleBodyDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvidersModule {

    @Provides @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton
    fun provideIdGenerator(): IdGenerator = UuidIdGenerator()

    @Provides @Singleton
    fun provideReviewMapper(provider: ReviewMapperProvider): ReviewMapper = provider.provide()

    @Provides @Singleton
    fun provideReviewBuilder(ids: IdGenerator): ReviewBuilder = ReviewBuilder(ids)

    @Provides @Singleton
    fun provideCurateTaxonomyUseCase(
        llm: LlmClient,
        reviewBuilder: ReviewBuilder,
    ): CurateTaxonomyUseCase = CurateTaxonomyUseCase(llm, reviewBuilder)
    @Provides @Singleton
    fun provideCatalogAssetParser(json: Json): com.sapphire.data.explore.CatalogAssetParser =
        com.sapphire.data.explore.CatalogAssetParser(json)

    @Provides @Singleton
    fun provideSearchFeedsUseCase(llm: LlmClient): com.sapphire.domain.explore.SearchFeedsUseCase =
        com.sapphire.domain.explore.SearchFeedsUseCase(llm)

    @Provides @Singleton
    fun provideReaderOpsUseCase(
        llm: LlmClient,
        cache: ReaderOpCache,
        items: ReaderItemStore,
        json: Json,
        config: LlmConfig,
    ): ReaderOpsUseCase = ReaderOpsUseCase(
        llm = llm,
        cache = cache,
        items = items,
        json = json,
        tier1ModelVersion = config.tier1Model,
        tier2ModelVersion = config.tier2Model,
    )
}

/**
 * Binds LLM config from the app-supplied [LlmConfigProvider]. The app module owns the
 * concrete provider (reads BuildConfig/local.properties) and contributes it via its own
 * module — this keeps core-data free of BuildConfig references.
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmBindingsModule {

    @Provides @Singleton
    fun provideLlmClient(
        config: LlmConfig,
        json: Json,
        client: OkHttpClient,
    ): LlmClient = OpenAiCompatibleLlmClient(config, json, client)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingsModule {
    @Binds
    abstract fun bindOnboardingRepository(impl: RoomOnboardingRepository): OnboardingRepository
    @Binds
    abstract fun bindFeedRepository(impl: RoomFeedRepository): FeedRepository
    @Binds
    abstract fun bindSourceFeedQuery(impl: RoomSourceFeedQuery): SourceFeedQuery
    @Binds
    abstract fun bindSourceRepository(impl: RoomSourceRepository): SourceRepository
    @Binds
    abstract fun bindReaderOpCache(impl: RoomReaderOpCache): ReaderOpCache
    @Binds
    abstract fun bindReaderItemStore(impl: RoomReaderItemStore): ReaderItemStore
    @Binds
    abstract fun bindSavedItemRepository(impl: RoomSavedItemRepository): SavedItemRepository
    @Binds
    abstract fun bindRetentionPurge(impl: RoomRetentionPurge): RetentionPurge
    @Binds
    abstract fun bindExploreCatalogRepository(impl: com.sapphire.data.explore.RoomExploreCatalogRepository): com.sapphire.domain.explore.ExploreCatalogRepository
    @Binds
    abstract fun bindDiscoveredFeedRepository(impl: com.sapphire.data.explore.RoomDiscoveredFeedRepository): com.sapphire.domain.explore.DiscoveredFeedRepository
    @Binds
    abstract fun bindFeedPreview(impl: com.sapphire.data.explore.FetcherFeedPreview): com.sapphire.domain.explore.FeedPreview
    @Binds
    abstract fun bindArticleExtractor(impl: com.sapphire.data.reader.ReadabilityArticleExtractor): com.sapphire.domain.reader.ArticleExtractor
    @Binds
    abstract fun bindArticleBodyStore(impl: com.sapphire.data.reader.RoomArticleBodyStore): com.sapphire.domain.reader.ArticleBodyStore
}

/**
 * App-supplied; resolved from BuildConfig (local.properties) in the app module.
 * Implementations are contributed via an @Module in the app; core-data never references BuildConfig.
 */
interface LlmConfigProvider {
    fun config(): LlmConfig
}

/**
 * Bridges [LlmConfigProvider] -> [LlmConfig] for Hilt. The app installs this module
 * alongside its concrete provider implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmConfigBridgeModule {
    @Provides @Singleton
    fun provideLlmConfig(provider: LlmConfigProvider): LlmConfig = provider.config()
}

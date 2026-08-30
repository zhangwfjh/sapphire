package com.sapphire.data.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.room.Room
import com.sapphire.data.db.FeedDao
import com.sapphire.data.db.LlmCacheDao
import com.sapphire.data.db.SeedDao
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.data.db.SourceDao
import com.sapphire.data.llm.OpenAiCompatibleLlmClient
import com.sapphire.data.feed.RoomFeedRepository
import com.sapphire.data.feed.RoomSourceFeedQuery
import com.sapphire.data.feed.SourceFeedQuery
import com.sapphire.data.db.SavedItemDao
import com.sapphire.data.db.DiscoveredFeedDao
import com.sapphire.data.db.AgentJobDao
import com.sapphire.data.db.AgentRunDao
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Marks the process-wide [CoroutineScope] for work that must outlive a screen. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope




@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SapphireDatabase = Room.databaseBuilder(
        context,
        SapphireDatabase::class.java,
        "sapphire.db",
    )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides fun provideSeedDao(db: SapphireDatabase): SeedDao = db.seedDao()
    @Provides fun provideFeedDao(db: SapphireDatabase): FeedDao = db.feedDao()
    @Provides fun provideSourceDao(db: SapphireDatabase): SourceDao = db.sourceDao()
    @Provides fun provideLlmCacheDao(db: SapphireDatabase): LlmCacheDao = db.llmCacheDao()
    @Provides fun provideSavedItemDao(db: SapphireDatabase): SavedItemDao = db.savedItemDao()
    @Provides fun provideDiscoveredFeedDao(db: SapphireDatabase): DiscoveredFeedDao = db.discoveredFeedDao()
    @Provides fun provideArticleBodyDao(db: SapphireDatabase): com.sapphire.data.db.ArticleBodyDao = db.articleBodyDao()
    @Provides fun provideAgentJobDao(db: SapphireDatabase): AgentJobDao = db.agentJobDao()
    @Provides fun provideAgentRunDao(db: SapphireDatabase): AgentRunDao = db.agentRunDao()

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            // Force IPv4 — apihub.agnes-ai.com resolves to IPv6 on some networks/carriers
            // but the IPv6 route is broken (connect hangs until timeout). Filtering to A
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    okhttp3.Dns.SYSTEM.lookup(hostname).filter { it is java.net.Inet4Address }
            })
            .build()
    }

    @Provides @Singleton
    fun provideIdGenerator(): IdGenerator = UuidIdGenerator()

    @Provides @Singleton
    fun provideCatalogAssetParser(json: Json): com.sapphire.data.explore.CatalogAssetParser =
        com.sapphire.data.explore.CatalogAssetParser(json)

    @Provides @Singleton
    fun provideWebSearchClient(client: OkHttpClient): com.sapphire.domain.explore.WebSearchClient =
        com.sapphire.data.explore.CompositeSearchClient(
            exa = com.sapphire.data.explore.ExaMcpSearchClient(client),
            bing = com.sapphire.data.explore.BingSearchClient(client),
            ddg = com.sapphire.data.explore.DdgSearchClient(client),
            baidu = com.sapphire.data.explore.BaiduSearchClient(client),
        )

    @Provides @Singleton
    fun provideFeedFinder(client: OkHttpClient): com.sapphire.domain.explore.FeedFinder =
        com.sapphire.data.explore.RssFinderFeedFinder(client)

    @Provides @Singleton
    fun provideFeedLinkHarvester(client: OkHttpClient): com.sapphire.domain.explore.FeedLinkHarvester =
        com.sapphire.data.explore.HttpFeedLinkHarvester(client)

    @Provides @Singleton
    fun provideSearchFeedsUseCase(
        feedFinder: com.sapphire.domain.explore.FeedFinder,
        harvester: com.sapphire.domain.explore.FeedLinkHarvester,
    ): com.sapphire.domain.explore.SearchFeedsUseCase =
        com.sapphire.domain.explore.SearchFeedsUseCase(feedFinder, harvester)


    @Provides @Singleton
    fun provideEnhanceDirectiveService(
        llm: LlmClient,
    ): com.sapphire.domain.agent.EnhanceDirectiveService =
        com.sapphire.domain.agent.EnhanceDirectiveService(llm)

    @Provides @Singleton
    fun provideAgentLoopService(
        llm: LlmClient,
        webSearch: com.sapphire.domain.explore.WebSearchClient,
        extractor: com.sapphire.domain.reader.ArticleExtractor,
        browser: com.sapphire.domain.browser.BrowserClient,
    ): com.sapphire.domain.agent.AgentLoopService =
        com.sapphire.domain.agent.AgentLoopService(llm, webSearch, extractor, browser)

    @Provides @Singleton
    fun provideBrowserConfig(
        @dagger.hilt.android.qualifiers.ApplicationContext ctx: android.content.Context,
    ): com.sapphire.domain.browser.BrowserConfig =
        com.sapphire.data.settings.SharedPrefsBrowserConfig(ctx)

    @Provides @Singleton
    fun provideBrowserClient(
        client: OkHttpClient,
        json: Json,
        config: com.sapphire.domain.browser.BrowserConfig,
    ): com.sapphire.domain.browser.BrowserClient =
        com.sapphire.data.browser.HttpBrowserClient(client, json, config)

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
    abstract fun bindAgentRepository(impl: com.sapphire.data.agent.RoomAgentRepository): com.sapphire.domain.agent.AgentRepository
    @Binds
    abstract fun bindFeedPreview(impl: com.sapphire.data.explore.FetcherFeedPreview): com.sapphire.domain.explore.FeedPreview
    @Binds
    abstract fun bindArticleExtractor(impl: com.sapphire.data.reader.ReadabilityArticleExtractor): com.sapphire.domain.reader.ArticleExtractor
    @Binds @javax.inject.Singleton
    abstract fun bindArticleBodyStore(impl: com.sapphire.data.reader.RoomArticleBodyStore): com.sapphire.domain.reader.ArticleBodyStore
    @Binds @javax.inject.Singleton
    abstract fun bindRichContentParser(impl: com.sapphire.data.reader.JsoupRichContentParser): com.sapphire.domain.reader.RichContentParser
    @Binds @javax.inject.Singleton
    abstract fun bindLlmConfigStore(impl: com.sapphire.data.settings.SharedPrefsLlmConfigStore): com.sapphire.domain.settings.LlmConfigStore
    @Binds @javax.inject.Singleton
    abstract fun bindRetentionConfigStore(impl: com.sapphire.data.settings.SharedPrefsRetentionConfigStore): com.sapphire.domain.settings.RetentionConfigStore
    @Binds @javax.inject.Singleton
    abstract fun bindThemeConfigStore(impl: com.sapphire.data.settings.SharedPrefsThemeConfigStore): com.sapphire.domain.settings.ThemeConfigStore
    @Binds @javax.inject.Singleton
    abstract fun bindUiPrefsStore(impl: com.sapphire.data.settings.SharedPrefsUiPrefsStore): com.sapphire.domain.settings.UiPrefsStore
    @Binds @javax.inject.Singleton
    abstract fun bindDataClearUseCase(impl: com.sapphire.data.settings.RoomDataClearUseCase): com.sapphire.domain.settings.DataClearUseCase
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


package com.sapphire.domain.explore

/**
 * User-visible region selector for the no-key search failover chain. [AUTO] inspects
 * `Locale.getDefault()` at call time (zh* → CHINA order, else WEST order); [WEST]/[CHINA]
 * are explicit overrides that ignore Locale.
 */
enum class SearchRegion { AUTO, WEST, CHINA }

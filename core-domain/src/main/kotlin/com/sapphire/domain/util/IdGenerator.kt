package com.sapphire.domain.util

/** Injectable id generation so mappers/repositories stay deterministic under test. */
fun interface IdGenerator {
    fun uuid(): String
}

/** Default impl used in production DI. */
class UuidIdGenerator : IdGenerator {
    override fun uuid(): String = java.util.UUID.randomUUID().toString()
}

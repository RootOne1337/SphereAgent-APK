package com.sphere.agent.service

/**
 * CommandResult - результат выполнения команды на устройстве.
 *
 * Используется как унифицированный формат для AgentService и вспомогательных исполнителей команд.
 */
data class CommandResult(
    val success: Boolean,
    val data: String? = null,
    val error: String? = null
)

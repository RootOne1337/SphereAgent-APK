"""
Команды - базовый модуль
"""

from commands.base import CommandHandler, CommandResult
from commands.ldplayer import LDPlayerCommands
from commands.shell import ShellCommands
from commands.system import SystemCommands

__all__ = [
    "CommandHandler",
    "CommandResult",
    "LDPlayerCommands",
    "ShellCommands",
    "SystemCommands"
]

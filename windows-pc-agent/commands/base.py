"""
Базовый класс для обработчиков команд
"""

import asyncio
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Dict, Any, Optional, List
from enum import Enum

logger = logging.getLogger(__name__)


@dataclass
class CommandResult:
    """Результат выполнения команды"""
    success: bool
    data: Optional[Any] = None
    error: Optional[str] = None
    duration_ms: int = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "data": self.data,
            "error": self.error,
            "duration_ms": self.duration_ms
        }


class CommandHandler(ABC):
    """Базовый класс обработчика команд"""
    
    # Список поддерживаемых команд
    SUPPORTED_COMMANDS: List[str] = []
    
    @abstractmethod
    async def execute(self, command: str, params: Dict[str, Any]) -> CommandResult:
        """
        Выполнить команду
        
        Args:
            command: Название команды
            params: Параметры команды
            
        Returns:
            CommandResult
        """
        pass
    
    def supports(self, command: str) -> bool:
        """Проверить поддерживается ли команда"""
        return command in self.SUPPORTED_COMMANDS
    
    @staticmethod
    def success(data: Any = None, duration_ms: int = 0) -> CommandResult:
        """Создать успешный результат"""
        return CommandResult(success=True, data=data, duration_ms=duration_ms)
    
    @staticmethod
    def error(message: str, duration_ms: int = 0) -> CommandResult:
        """Создать результат с ошибкой"""
        return CommandResult(success=False, error=message, duration_ms=duration_ms)


class CommandRegistry:
    """
    Реестр обработчиков команд
    
    Позволяет регистрировать handlers и маршрутизировать команды
    """
    
    def __init__(self):
        self._handlers: List[CommandHandler] = []
    
    def register(self, handler: CommandHandler):
        """Зарегистрировать обработчик"""
        self._handlers.append(handler)
        logger.debug(f"Зарегистрирован handler: {handler.__class__.__name__} ({handler.SUPPORTED_COMMANDS})")
    
    def get_handler(self, command: str) -> Optional[CommandHandler]:
        """Найти обработчик для команды"""
        for handler in self._handlers:
            if handler.supports(command):
                return handler
        return None
    
    async def execute(self, command: str, params: Dict[str, Any]) -> CommandResult:
        """Выполнить команду"""
        handler = self.get_handler(command)
        
        if not handler:
            return CommandResult(
                success=False,
                error=f"Unknown command: {command}"
            )
        
        try:
            return await handler.execute(command, params)
        except Exception as e:
            logger.exception(f"Ошибка выполнения команды {command}")
            return CommandResult(
                success=False,
                error=str(e)
            )
    
    def get_all_commands(self) -> List[str]:
        """Получить список всех поддерживаемых команд"""
        commands = []
        for handler in self._handlers:
            commands.extend(handler.SUPPORTED_COMMANDS)
        return commands

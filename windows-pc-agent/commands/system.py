"""
Системные команды
Управление агентом и получение информации о системе
"""

import asyncio
import os
import sys
import logging
from typing import Dict, Any
import time

from commands.base import CommandHandler, CommandResult
from agent.system_info import get_system_info

logger = logging.getLogger(__name__)


class SystemCommands(CommandHandler):
    """
    Обработчик системных команд
    
    - get_info - информация о системе
    - restart_agent - перезапуск агента
    - update_agent - обновление агента
    - ping - проверка связи
    """
    
    SUPPORTED_COMMANDS = [
        "get_info",
        "ping",
        "restart_agent",
        "update_agent",
        "get_config"
    ]
    
    def __init__(self, agent_version: str = "1.0.0"):
        self.agent_version = agent_version
        self._start_time = time.time()
    
    async def execute(self, command: str, params: Dict[str, Any]) -> CommandResult:
        """Выполнить системную команду"""
        start_time = time.time()
        
        try:
            if command == "get_info":
                result = await self._get_info()
            elif command == "ping":
                result = await self._ping()
            elif command == "restart_agent":
                result = await self._restart_agent()
            elif command == "update_agent":
                result = await self._update_agent(params)
            elif command == "get_config":
                result = await self._get_config()
            else:
                result = self.error(f"Unknown system command: {command}")
            
            result.duration_ms = int((time.time() - start_time) * 1000)
            return result
            
        except Exception as e:
            logger.exception(f"System command error: {command}")
            return self.error(str(e), int((time.time() - start_time) * 1000))
    
    async def _get_info(self) -> CommandResult:
        """Получить информацию о системе"""
        info = get_system_info()
        
        return self.success({
            "agent_version": self.agent_version,
            "uptime_seconds": int(time.time() - self._start_time),
            "system": info.to_dict()
        })
    
    async def _ping(self) -> CommandResult:
        """Проверка связи"""
        return self.success({
            "pong": True,
            "timestamp": int(time.time() * 1000)
        })
    
    async def _restart_agent(self) -> CommandResult:
        """Перезапуск агента"""
        logger.info("Получена команда перезапуска агента")
        
        # Планируем перезапуск через 1 секунду
        asyncio.create_task(self._delayed_restart())
        
        return self.success({
            "action": "restart_scheduled",
            "delay_seconds": 1
        })
    
    async def _delayed_restart(self):
        """Отложенный перезапуск"""
        await asyncio.sleep(1)
        
        logger.info("Перезапуск агента...")
        
        # Перезапуск через тот же Python
        python = sys.executable
        os.execv(python, [python] + sys.argv)
    
    async def _update_agent(self, params: Dict[str, Any]) -> CommandResult:
        """Обновление агента"""
        version = params.get("version", "")
        url = params.get("url", "")
        
        if not url:
            return self.error("Update URL is required")
        
        # TODO: Реализовать скачивание и установку обновления
        logger.info(f"Обновление агента до версии {version} с {url}")
        
        return self.success({
            "action": "update_started",
            "version": version,
            "url": url
        })
    
    async def _get_config(self) -> CommandResult:
        """Получить текущую конфигурацию"""
        # Возвращаем безопасные части конфига (без токена)
        return self.success({
            "agent_version": self.agent_version,
            "platform": sys.platform,
            "python_version": sys.version
        })

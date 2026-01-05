"""
SpherePC Agent - Главный модуль
Windows/Linux PC Agent для SphereADB
"""

import asyncio
import json
import logging
import os
import sys
import signal
import time
from pathlib import Path
from typing import Optional, Dict, Any

# Добавляем текущую директорию в path
sys.path.insert(0, str(Path(__file__).parent))

from agent import __version__
from agent.config import AgentConfig
from agent.connection import ConnectionManager, ConnectionState
from agent.heartbeat import HeartbeatService
from agent.system_info import get_system_info, get_network_info, get_os_info
from agent.hardware_id import get_or_create_device_id

from commands.base import CommandRegistry
from commands.ldplayer import LDPlayerCommands
from commands.shell import ShellCommands
from commands.system import SystemCommands
from commands.advanced import AdvancedCommands

# Настройка логирования
def setup_logging(config: AgentConfig):
    """Настроить логирование"""
    log_level = getattr(logging, config.logging.level.upper(), logging.INFO)
    
    # Форматтер
    formatter = logging.Formatter(
        '%(asctime)s | %(levelname)-8s | %(name)s | %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    # Root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(log_level)
    
    # Console handler
    if config.logging.console:
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setFormatter(formatter)
        root_logger.addHandler(console_handler)
    
    # File handler
    if config.logging.file:
        log_path = Path(config.logging.file)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        
        from logging.handlers import RotatingFileHandler
        file_handler = RotatingFileHandler(
            str(log_path),
            maxBytes=config.logging.max_size_mb * 1024 * 1024,
            backupCount=config.logging.backup_count,
            encoding='utf-8'
        )
        file_handler.setFormatter(formatter)
        root_logger.addHandler(file_handler)


logger = logging.getLogger("SpherePC-Agent")


class SphereAgentPC:
    """
    Главный класс PC Agent
    
    Управляет:
    - WebSocket соединением с сервером
    - Heartbeat отправкой
    - Обработкой команд
    - LDPlayer управлением
    """
    
    def __init__(self, config: AgentConfig):
        self.config = config
        self._running = False
        self._device_id = get_or_create_device_id(config.advanced.device_id)
        
        # Инициализация компонентов
        self._connection: Optional[ConnectionManager] = None
        self._heartbeat: Optional[HeartbeatService] = None
        self._command_registry = CommandRegistry()
        
        # LDPlayer команды
        self._ldplayer = LDPlayerCommands(
            ldplayer_path=config.ldplayer.path,
            auto_detect=config.ldplayer.auto_detect
        )
        
        # Регистрируем handlers
        self._setup_commands()
    
    def _setup_commands(self):
        """Настроить обработчики команд"""
        # LDPlayer
        if self.config.ldplayer.enabled:
            self._command_registry.register(self._ldplayer)
        
        # Shell
        self._command_registry.register(ShellCommands(
            allow_shell=self.config.security.allow_shell,
            whitelist=self.config.security.shell_whitelist or None,
            blacklist=self.config.security.shell_blacklist
        ))
        
        # System
        self._command_registry.register(SystemCommands(
            agent_version=__version__
        ))
        
        # Advanced (ВСЁ управление: скрипты, файлы, процессы!)
        self._command_registry.register(AdvancedCommands(
            allow_dangerous=True  # Разрешаем ВСЁ!
        ))
        
        logger.info(f"Зарегистрировано команд: {len(self._command_registry.get_all_commands())}")
    
    def _build_hello_message(self) -> Dict[str, Any]:
        """Построить hello сообщение для регистрации"""
        system_info = get_system_info()
        network_info = get_network_info()
        os_info = get_os_info()
        
        # Capabilities
        capabilities = ["shell"]
        if self.config.ldplayer.enabled and self._ldplayer.is_available():
            capabilities.append("ldplayer")
        capabilities.append("system")
        
        # LDPlayer info
        ldplayer_info = None
        if self._ldplayer.is_available():
            ldplayer_info = {
                "path": self._ldplayer.ldplayer_path,
                "available": True
            }
        
        hello_data = {
            "type": "hello",
            "pc_id": self._device_id,
            "pc_name": self.config.pc.name or network_info.hostname,
            "os_type": os_info.type,
            "os_version": os_info.name,
            "agent_version": __version__,
            "hostname": network_info.ip_address,
            "location": self.config.pc.location,
            "capabilities": capabilities,
            "hardware": {
                "cpu": system_info.cpu.name,
                "cpu_cores": system_info.cpu.cores_logical,
                "ram_total_gb": system_info.memory.total_gb,
                "ram_free_gb": system_info.memory.available_gb,
                "disk_total_gb": system_info.disk.total_gb,
                "disk_free_gb": system_info.disk.free_gb
            },
            "ldplayer": ldplayer_info,
            "network": {
                "ip_address": network_info.ip_address,
                "hostname": network_info.hostname,
                "mac_address": network_info.mac_address
            }
        }
        
        # Добавляем токен если есть (опционально)
        if self.config.token:
            hello_data["token"] = self.config.token
        
        return hello_data
    
    async def _on_connect(self):
        """Callback при успешном подключении"""
        logger.info("Подключено к серверу, отправляем hello...")
        
        # Отправляем hello
        hello = self._build_hello_message()
        success = await self._connection.send(hello)
        
        if success:
            logger.info(f"✓ Зарегистрирован как {hello['pc_name']} ({self._device_id[:8]}...)")
            
            # Запускаем heartbeat
            if self._heartbeat:
                await self._heartbeat.start()
        else:
            logger.error("Не удалось отправить hello")
    
    async def _on_disconnect(self, reason: str):
        """Callback при отключении"""
        logger.warning(f"Отключено от сервера: {reason}")
        
        # Останавливаем heartbeat
        if self._heartbeat:
            await self._heartbeat.stop()
    
    async def _on_message(self, data: Dict[str, Any]):
        """Callback при получении сообщения"""
        msg_type = data.get("type", "")
        
        if msg_type == "registered":
            # Подтверждение регистрации
            logger.info(f"✓ Регистрация подтверждена сервером")
            
        elif msg_type == "heartbeat_ack":
            # Подтверждение heartbeat
            logger.debug("Heartbeat ACK получен")
            
        elif msg_type == "command":
            # Команда от сервера
            await self._handle_command(data)
            
        elif msg_type == "ping":
            # Ping от сервера
            await self._connection.send({"type": "pong"})
            
        else:
            # Попробуем интерпретировать как команду напрямую
            if msg_type in self._command_registry.get_all_commands():
                await self._handle_command({
                    "type": "command",
                    "command": msg_type,
                    "command_id": data.get("command_id"),
                    "params": data.get("params", {})
                })
            else:
                logger.debug(f"Неизвестный тип сообщения: {msg_type}")
    
    async def _handle_command(self, data: Dict[str, Any]):
        """Обработать команду от сервера"""
        command = data.get("command", "")
        command_id = data.get("command_id", "")
        params = data.get("params", {})
        
        logger.info(f"Получена команда: {command} (id={command_id})")
        
        # Выполняем команду
        result = await self._command_registry.execute(command, params)
        
        # Отправляем результат
        response = {
            "type": "command_result",
            "command_id": command_id,
            "success": result.success,
            "data": result.data,
            "error": result.error,
            "duration_ms": result.duration_ms
        }
        
        await self._connection.send(response)
        
        if result.success:
            logger.info(f"✓ Команда {command} выполнена за {result.duration_ms}ms")
        else:
            logger.warning(f"✗ Команда {command} failed: {result.error}")
    
    async def _get_emulators_for_heartbeat(self) -> list:
        """Получить список эмуляторов для heartbeat"""
        if not self.config.ldplayer.enabled or not self._ldplayer.is_available():
            return []
        
        return await self._ldplayer.get_emulators_for_heartbeat()
    
    async def run(self):
        """Запустить агент"""
        logger.info(f"═══════════════════════════════════════════")
        logger.info(f"  SpherePC Agent v{__version__}")
        logger.info(f"═══════════════════════════════════════════")
        logger.info(f"Device ID: {self._device_id}")
        logger.info(f"Server: {self.config.server.url}")
        logger.info(f"LDPlayer: {'✓ ' + self._ldplayer.ldplayer_path if self._ldplayer.is_available() else '✗ Not found'}")
        logger.info(f"═══════════════════════════════════════════")
        
        self._running = True
        
        # Создаём connection manager с fallback поддержкой
        self._connection = ConnectionManager(
            ws_url=self.config.server.ws_url,
            token=self.config.token,
            fallback_urls=[
                url.replace("https://", "wss://").replace("http://", "ws://") + self.config.server.websocket_path
                for url in self.config.server.fallback_urls
            ],
            on_connect=self._on_connect,
            on_disconnect=self._on_disconnect,
            on_message=self._on_message,
            connect_timeout=self.config.connection.connect_timeout,
            initial_reconnect_delay=self.config.connection.initial_reconnect_delay,
            max_reconnect_delay=self.config.connection.max_reconnect_delay
        )
        
        # Создаём heartbeat service
        self._heartbeat = HeartbeatService(
            send_func=self._connection.send,
            interval=self.config.connection.heartbeat_interval,
            get_emulators_func=self._get_emulators_for_heartbeat
        )
        
        # Обработка сигналов
        def signal_handler(sig, frame):
            logger.info("Получен сигнал завершения...")
            self._running = False
            asyncio.create_task(self.shutdown())
        
        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)
        
        # Главный цикл
        try:
            await self._connection.run_forever()
        except KeyboardInterrupt:
            logger.info("Прервано пользователем")
        finally:
            await self.shutdown()
    
    async def shutdown(self):
        """Завершение работы"""
        logger.info("Завершение работы агента...")
        
        self._running = False
        
        if self._heartbeat:
            await self._heartbeat.stop()
        
        if self._connection:
            await self._connection.disconnect("Agent shutdown")
        
        logger.info("Агент остановлен")


def main():
    """Точка входа"""
    import argparse
    
    parser = argparse.ArgumentParser(description="SpherePC Agent")
    parser.add_argument("-c", "--config", help="Path to config.yaml", default=None)
    parser.add_argument("-t", "--token", help="Authorization token", default=None)
    parser.add_argument("-s", "--server", help="Server URL", default=None)
    parser.add_argument("-v", "--version", action="store_true", help="Show version")
    args = parser.parse_args()
    
    if args.version:
        print(f"SpherePC Agent v{__version__}")
        sys.exit(0)
    
    # Загружаем конфиг
    config = AgentConfig.load(args.config)
    
    # Переопределение из CLI
    if args.token:
        config.token = args.token
    if args.server:
        config.server.url = args.server
    
    # Настраиваем логирование
    setup_logging(config)
    
    # Валидация
    errors = config.validate()
    if errors:
        for error in errors:
            logger.error(f"Config error: {error}")
        logger.error("Укажите токен в config.yaml или через --token")
        sys.exit(1)
    
    # Запускаем агент
    agent = SphereAgentPC(config)
    
    try:
        asyncio.run(agent.run())
    except Exception as e:
        logger.exception(f"Fatal error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()

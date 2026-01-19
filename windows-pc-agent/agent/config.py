"""
Модуль загрузки и валидации конфигурации
"""

import os
import sys
import yaml
import logging
from pathlib import Path
from typing import Optional, Dict, Any, List
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ServerConfig:
    """Конфигурация сервера"""
    url: str = "https://adb.leetpc.com"
    websocket_path: str = "/api/v1/pc/ws"
    fallback_urls: List[str] = field(default_factory=list)
    
    @property
    def ws_url(self) -> str:
        """WebSocket URL"""
        base = self.url.replace("https://", "wss://").replace("http://", "ws://")
        return f"{base}{self.websocket_path}"


@dataclass
class PCConfig:
    """Конфигурация ПК"""
    name: str = ""
    location: str = ""


@dataclass
class LDPlayerConfig:
    """Конфигурация LDPlayer"""
    enabled: bool = True
    path: str = ""
    auto_detect: bool = True


@dataclass
class ConnectionConfig:
    """Конфигурация соединения"""
    heartbeat_interval: int = 30
    connect_timeout: int = 30
    max_reconnect_delay: int = 60
    initial_reconnect_delay: int = 1


@dataclass
class AutostartConfig:
    """Конфигурация автозагрузки"""
    enabled: bool = True
    hidden: bool = True


@dataclass
class LoggingConfig:
    """Конфигурация логирования"""
    level: str = "INFO"
    file: str = "logs/agent.log"
    max_size_mb: int = 10
    backup_count: int = 5
    console: bool = True


@dataclass
class SecurityConfig:
    """Конфигурация безопасности"""
    allow_shell: bool = True
    shell_whitelist: List[str] = field(default_factory=list)
    shell_blacklist: List[str] = field(default_factory=list)


@dataclass
class AdvancedConfig:
    """Дополнительная конфигурация"""
    device_id: str = ""
    metrics_enabled: bool = True
    metrics_interval: int = 60


@dataclass
class AgentConfig:
    """Главная конфигурация агента"""
    server: ServerConfig = field(default_factory=ServerConfig)
    token: str = ""
    pc: PCConfig = field(default_factory=PCConfig)
    ldplayer: LDPlayerConfig = field(default_factory=LDPlayerConfig)
    connection: ConnectionConfig = field(default_factory=ConnectionConfig)
    autostart: AutostartConfig = field(default_factory=AutostartConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)
    security: SecurityConfig = field(default_factory=SecurityConfig)
    advanced: AdvancedConfig = field(default_factory=AdvancedConfig)
    
    @classmethod
    def load(cls, config_path: Optional[str] = None) -> "AgentConfig":
        """
        Загрузить конфигурацию из файла
        
        Args:
            config_path: Путь к config.yaml (опционально)
            
        Returns:
            AgentConfig инстанс
        """
        # Определяем путь к конфигу
        if config_path is None:
            # Ищем config.yaml в нескольких местах
            possible_paths = [
                Path("config.yaml"),
                Path("config.yml"),
                Path(__file__).parent.parent / "config.yaml",
                Path(__file__).parent.parent / "config.yml",
                Path.home() / ".sphere-pc-agent" / "config.yaml",
            ]
            
            for p in possible_paths:
                if p.exists():
                    config_path = str(p)
                    break
        
        config = cls()
        
        if config_path and os.path.exists(config_path):
            try:
                with open(config_path, 'r', encoding='utf-8') as f:
                    data = yaml.safe_load(f) or {}
                
                config = cls._from_dict(data)
                logger.info(f"Конфигурация загружена из {config_path}")
                
            except Exception as e:
                logger.error(f"Ошибка загрузки конфига {config_path}: {e}")
        else:
            logger.warning("Конфиг не найден, используются значения по умолчанию")
        
        # Переопределение из переменных окружения
        config._load_from_env()
        
        return config
    
    @classmethod
    def _from_dict(cls, data: Dict[str, Any]) -> "AgentConfig":
        """Создать конфиг из словаря"""
        config = cls()
        
        # Server
        if "server" in data:
            s = data["server"]
            config.server = ServerConfig(
                url=s.get("url", config.server.url),
                websocket_path=s.get("websocket_path", config.server.websocket_path),
                fallback_urls=s.get("fallback_urls", [])
            )
        
        # Token
        config.token = data.get("token", "")
        
        # PC
        if "pc" in data:
            p = data["pc"]
            config.pc = PCConfig(
                name=p.get("name", ""),
                location=p.get("location", "")
            )
        
        # LDPlayer
        if "ldplayer" in data:
            l = data["ldplayer"]
            config.ldplayer = LDPlayerConfig(
                enabled=l.get("enabled", True),
                path=l.get("path", ""),
                auto_detect=l.get("auto_detect", True)
            )
        
        # Connection
        if "connection" in data:
            c = data["connection"]
            config.connection = ConnectionConfig(
                heartbeat_interval=c.get("heartbeat_interval", 30),
                connect_timeout=c.get("connect_timeout", 30),
                max_reconnect_delay=c.get("max_reconnect_delay", 60),
                initial_reconnect_delay=c.get("initial_reconnect_delay", 1)
            )
        
        # Autostart
        if "autostart" in data:
            a = data["autostart"]
            config.autostart = AutostartConfig(
                enabled=a.get("enabled", True),
                hidden=a.get("hidden", True)
            )
        
        # Logging
        if "logging" in data:
            l = data["logging"]
            config.logging = LoggingConfig(
                level=l.get("level", "INFO"),
                file=l.get("file", "logs/agent.log"),
                max_size_mb=l.get("max_size_mb", 10),
                backup_count=l.get("backup_count", 5),
                console=l.get("console", True)
            )
        
        # Security
        if "security" in data:
            s = data["security"]
            config.security = SecurityConfig(
                allow_shell=s.get("allow_shell", True),
                shell_whitelist=s.get("shell_whitelist", []),
                shell_blacklist=s.get("shell_blacklist", [])
            )
        
        # Advanced
        if "advanced" in data:
            a = data["advanced"]
            config.advanced = AdvancedConfig(
                device_id=a.get("device_id", ""),
                metrics_enabled=a.get("metrics_enabled", True),
                metrics_interval=a.get("metrics_interval", 60)
            )
        
        return config
    
    def _load_from_env(self):
        """Загрузить значения из переменных окружения"""
        # SPHERE_SERVER_URL
        if os.getenv("SPHERE_SERVER_URL"):
            self.server.url = os.getenv("SPHERE_SERVER_URL")
        
        # SPHERE_TOKEN
        if os.getenv("SPHERE_TOKEN"):
            self.token = os.getenv("SPHERE_TOKEN")
        
        # SPHERE_PC_NAME
        if os.getenv("SPHERE_PC_NAME"):
            self.pc.name = os.getenv("SPHERE_PC_NAME")
        
        # SPHERE_LDPLAYER_PATH
        if os.getenv("SPHERE_LDPLAYER_PATH"):
            self.ldplayer.path = os.getenv("SPHERE_LDPLAYER_PATH")
    
    def validate(self) -> List[str]:
        """
        Валидация конфигурации
        
        Returns:
            Список ошибок (пустой если всё ок)
        """
        errors = []
        
        # Токен опционален - если нет, будет автоматическая регистрация
        
        if not self.server.url:
            errors.append("URL сервера не указан (server.url)")
        
        return errors
    
    def save(self, config_path: str = "config.yaml"):
        """Сохранить конфигурацию в файл"""
        data = {
            "server": {
                "url": self.server.url,
                "websocket_path": self.server.websocket_path,
                "fallback_urls": self.server.fallback_urls
            },
            "token": self.token,
            "pc": {
                "name": self.pc.name,
                "location": self.pc.location
            },
            "ldplayer": {
                "enabled": self.ldplayer.enabled,
                "path": self.ldplayer.path,
                "auto_detect": self.ldplayer.auto_detect
            },
            "connection": {
                "heartbeat_interval": self.connection.heartbeat_interval,
                "connect_timeout": self.connection.connect_timeout,
                "max_reconnect_delay": self.connection.max_reconnect_delay,
                "initial_reconnect_delay": self.connection.initial_reconnect_delay
            },
            "autostart": {
                "enabled": self.autostart.enabled,
                "hidden": self.autostart.hidden
            },
            "logging": {
                "level": self.logging.level,
                "file": self.logging.file,
                "max_size_mb": self.logging.max_size_mb,
                "backup_count": self.logging.backup_count,
                "console": self.logging.console
            },
            "security": {
                "allow_shell": self.security.allow_shell,
                "shell_whitelist": self.security.shell_whitelist,
                "shell_blacklist": self.security.shell_blacklist
            },
            "advanced": {
                "device_id": self.advanced.device_id,
                "metrics_enabled": self.advanced.metrics_enabled,
                "metrics_interval": self.advanced.metrics_interval
            }
        }
        
        with open(config_path, 'w', encoding='utf-8') as f:
            yaml.dump(data, f, default_flow_style=False, allow_unicode=True)
        
        logger.info(f"Конфигурация сохранена в {config_path}")

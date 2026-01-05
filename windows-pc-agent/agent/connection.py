"""
WebSocket Connection Manager
Управление подключением к серверу с auto-reconnect
"""

import asyncio
import json
import time
import logging
from typing import Optional, Callable, Awaitable, Dict, Any
from dataclasses import dataclass, field
from enum import Enum

try:
    import websockets
    from websockets.client import WebSocketClientProtocol
    WEBSOCKETS_AVAILABLE = True
except ImportError:
    WEBSOCKETS_AVAILABLE = False

logger = logging.getLogger(__name__)


class ConnectionState(Enum):
    """Состояния соединения"""
    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    CONNECTED = "connected"
    RECONNECTING = "reconnecting"


@dataclass
class ConnectionStats:
    """Статистика соединения"""
    connected_at: Optional[float] = None
    last_message_at: Optional[float] = None
    reconnect_count: int = 0
    messages_sent: int = 0
    messages_received: int = 0
    bytes_sent: int = 0
    bytes_received: int = 0


class ConnectionManager:
    """
    Менеджер WebSocket соединения
    
    Обеспечивает:
    - Подключение к серверу
    - Автоматический reconnect с exponential backoff
    - Отправку и приём сообщений
    - Heartbeat
    """
    
    def __init__(
        self,
        ws_url: str,
        token: str = "",  # Токен опционален!
        on_message: Optional[Callable[[Dict[str, Any]], Awaitable[None]]] = None,
        on_connect: Optional[Callable[[], Awaitable[None]]] = None,
        on_disconnect: Optional[Callable[[str], Awaitable[None]]] = None,
        connect_timeout: int = 30,
        initial_reconnect_delay: float = 1.0,
        max_reconnect_delay: float = 60.0,
        fallback_urls: List[str] = None  # НОВОЕ: Fallback серверы!
    ):
        """
        Args:
            ws_url: WebSocket URL сервера
            token: Токен авторизации (опционально!)
            on_message: Callback при получении сообщения
            on_connect: Callback при успешном подключении
            on_disconnect: Callback при отключении
            connect_timeout: Таймаут подключения (сек)
            initial_reconnect_delay: Начальная задержка reconnect (сек)
            max_reconnect_delay: Максимальная задержка reconnect (сек)
            fallback_urls: Список fallback серверов для отказоустойчивости
        """
        if not WEBSOCKETS_AVAILABLE:
            raise ImportError("websockets library not installed. Run: pip install websockets")
        
        self.ws_url = ws_url
        self.token = token
        self.fallback_urls = fallback_urls or []
        self.current_url_index = 0  # Индекс текущего сервера
        self.on_message = on_message
        self.on_connect = on_connect
        self.on_disconnect = on_disconnect
        self.connect_timeout = connect_timeout
        self.initial_reconnect_delay = initial_reconnect_delay
        self.max_reconnect_delay = max_reconnect_delay
        
        self._ws: Optional[WebSocketClientProtocol] = None
        self._state = ConnectionState.DISCONNECTED
        self._should_reconnect = True
        self._reconnect_attempt = 0
        self._stats = ConnectionStats()
        self._message_queue: asyncio.Queue = asyncio.Queue()
        self._send_lock = asyncio.Lock()
        
        # Tasks
        self._receive_task: Optional[asyncio.Task] = None
        self._reconnect_task: Optional[asyncio.Task] = None
    
    @property
    def state(self) -> ConnectionState:
        return self._state
    
    @property
    def is_connected(self) -> bool:
        return self._state == ConnectionState.CONNECTED and self._ws is not None
    
    @property
    def stats(self) -> ConnectionStats:
        return self._stats
    
    def get_full_url(self) -> str:
        """Получить полный WebSocket URL (с fallback поддержкой)"""
        # Если есть fallback серверы и основной не первый раз падает
        if self.fallback_urls and self.current_url_index > 0:
            # Циклически пробуем fallback серверы
            all_urls = [self.ws_url] + self.fallback_urls
            current_url = all_urls[self.current_url_index % len(all_urls)]
            logger.info(f"Используем fallback сервер: {current_url}")
            return current_url
        
        return self.ws_url
    
    def _next_fallback_url(self):
        """Переключиться на следующий fallback сервер"""
        if not self.fallback_urls:
            return
        
        self.current_url_index += 1
        total_urls = 1 + len(self.fallback_urls)
        
        if self.current_url_index >= total_urls:
            self.current_url_index = 0  # Вернуться к основному
            logger.info("Вернулись к основному серверу")
        else:
            logger.info(f"Переключение на fallback #{self.current_url_index}/{total_urls}")
    
    async def connect(self) -> bool:
        """
        Подключиться к серверу
        
        Returns:
            True если подключение успешно
        """
        if self._state == ConnectionState.CONNECTED:
            logger.warning("Уже подключен")
            return True
        
        self._state = ConnectionState.CONNECTING
        url = self.get_full_url()
        
        try:
            logger.info(f"Подключение к {self.ws_url}...")
            
            self._ws = await asyncio.wait_for(
                websockets.connect(
                    url,
                    ping_interval=20,
                    ping_timeout=20,
                    close_timeout=10,
                    max_size=10 * 1024 * 1024,  # 10MB
                    extra_headers={
                        "User-Agent": "SpherePC-Agent/1.0.0"
                    }
                ),
                timeout=self.connect_timeout
            )
            
            self._state = ConnectionState.CONNECTED
            self._stats.connected_at = time.time()
            self._reconnect_attempt = 0
            
            logger.info(f"✓ Подключено к серверу")
            
            # Запускаем приём сообщений
            self._receive_task = asyncio.create_task(self._receive_loop())
            
            # Callback
            if self.on_connect:
                await self.on_connect()
            
            return True
            
        except asyncio.TimeoutError:
            logger.error(f"Таймаут подключения к {url}")
            self._state = ConnectionState.DISCONNECTED
            self._next_fallback_url()  # Пробуем следующий сервер
            return False
            
        except Exception as e:
            logger.error(f"Ошибка подключения: {e}")
            self._state = ConnectionState.DISCONNECTED
            self._next_fallback_url()  # Пробуем следующий сервер
            return False
    
    async def disconnect(self, reason: str = "User requested"):
        """Отключиться от сервера"""
        self._should_reconnect = False
        
        # Отменяем tasks
        if self._receive_task:
            self._receive_task.cancel()
            try:
                await self._receive_task
            except asyncio.CancelledError:
                pass
        
        if self._reconnect_task:
            self._reconnect_task.cancel()
            try:
                await self._reconnect_task
            except asyncio.CancelledError:
                pass
        
        # Закрываем WebSocket
        if self._ws:
            try:
                await self._ws.close()
            except:
                pass
            self._ws = None
        
        self._state = ConnectionState.DISCONNECTED
        
        logger.info(f"Отключено: {reason}")
        
        if self.on_disconnect:
            await self.on_disconnect(reason)
    
    async def send(self, data: Dict[str, Any]) -> bool:
        """
        Отправить JSON сообщение
        
        Args:
            data: Словарь для отправки
            
        Returns:
            True если отправлено успешно
        """
        if not self.is_connected or not self._ws:
            logger.warning("Не подключен, сообщение не отправлено")
            return False
        
        try:
            async with self._send_lock:
                message = json.dumps(data)
                await self._ws.send(message)
                
                self._stats.messages_sent += 1
                self._stats.bytes_sent += len(message)
                
                return True
                
        except Exception as e:
            logger.error(f"Ошибка отправки: {e}")
            return False
    
    async def send_bytes(self, data: bytes) -> bool:
        """Отправить бинарные данные"""
        if not self.is_connected or not self._ws:
            return False
        
        try:
            async with self._send_lock:
                await self._ws.send(data)
                self._stats.messages_sent += 1
                self._stats.bytes_sent += len(data)
                return True
        except Exception as e:
            logger.error(f"Ошибка отправки bytes: {e}")
            return False
    
    async def _receive_loop(self):
        """Цикл приёма сообщений"""
        try:
            async for message in self._ws:
                self._stats.last_message_at = time.time()
                self._stats.messages_received += 1
                
                if isinstance(message, str):
                    self._stats.bytes_received += len(message)
                    
                    try:
                        data = json.loads(message)
                        
                        if self.on_message:
                            await self.on_message(data)
                            
                    except json.JSONDecodeError as e:
                        logger.error(f"Ошибка парсинга JSON: {e}")
                        
                elif isinstance(message, bytes):
                    self._stats.bytes_received += len(message)
                    logger.debug(f"Получено {len(message)} bytes")
                    
        except websockets.exceptions.ConnectionClosed as e:
            logger.warning(f"Соединение закрыто: {e}")
            
        except Exception as e:
            logger.error(f"Ошибка в receive loop: {e}")
            
        finally:
            self._state = ConnectionState.DISCONNECTED
            
            if self._should_reconnect:
                asyncio.create_task(self._schedule_reconnect())
    
    async def _schedule_reconnect(self):
        """Запланировать переподключение с exponential backoff"""
        self._state = ConnectionState.RECONNECTING
        self._reconnect_attempt += 1
        
        # Exponential backoff: 1s, 2s, 4s, 8s, ... до max
        delay = min(
            self.initial_reconnect_delay * (2 ** (self._reconnect_attempt - 1)),
            self.max_reconnect_delay
        )
        
        logger.info(f"Переподключение через {delay:.1f}с (попытка {self._reconnect_attempt})...")
        
        self._stats.reconnect_count += 1
        
        await asyncio.sleep(delay)
        
        if self._should_reconnect:
            success = await self.connect()
            
            if not success and self._should_reconnect:
                # Пробуем ещё раз
                asyncio.create_task(self._schedule_reconnect())
    
    async def run_forever(self):
        """Запустить connection manager в бесконечном режиме"""
        self._should_reconnect = True
        
        while self._should_reconnect:
            if not self.is_connected:
                success = await self.connect()
                
                if not success:
                    await self._schedule_reconnect()
            else:
                # Ждём пока не отключимся
                await asyncio.sleep(1)

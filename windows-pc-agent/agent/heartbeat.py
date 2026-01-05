"""
Heartbeat Service
Периодическая отправка статуса на сервер
"""

import asyncio
import time
import logging
from typing import Callable, Awaitable, Optional, Dict, Any

from agent.system_info import get_quick_metrics

logger = logging.getLogger(__name__)


class HeartbeatService:
    """
    Сервис heartbeat
    
    Отправляет статус ПК на сервер каждые N секунд:
    - CPU usage
    - RAM usage
    - Статус эмуляторов
    - Timestamp
    """
    
    def __init__(
        self,
        send_func: Callable[[Dict[str, Any]], Awaitable[bool]],
        interval: int = 30,
        get_emulators_func: Optional[Callable[[], Awaitable[list]]] = None
    ):
        """
        Args:
            send_func: Функция отправки сообщения (ConnectionManager.send)
            interval: Интервал heartbeat в секундах
            get_emulators_func: Функция для получения списка эмуляторов
        """
        self.send_func = send_func
        self.interval = interval
        self.get_emulators_func = get_emulators_func
        
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self._last_heartbeat: Optional[float] = None
        self._heartbeat_count = 0
    
    @property
    def is_running(self) -> bool:
        return self._running
    
    @property
    def last_heartbeat(self) -> Optional[float]:
        return self._last_heartbeat
    
    async def start(self):
        """Запустить heartbeat service"""
        if self._running:
            logger.warning("Heartbeat уже запущен")
            return
        
        self._running = True
        self._task = asyncio.create_task(self._heartbeat_loop())
        logger.info(f"Heartbeat запущен (интервал: {self.interval}с)")
    
    async def stop(self):
        """Остановить heartbeat service"""
        self._running = False
        
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        
        logger.info("Heartbeat остановлен")
    
    async def send_heartbeat(self) -> bool:
        """Отправить heartbeat сейчас"""
        try:
            # Собираем метрики
            metrics = get_quick_metrics()
            
            # Эмуляторы
            emulators = []
            if self.get_emulators_func:
                try:
                    emulators = await self.get_emulators_func()
                except Exception as e:
                    logger.debug(f"Ошибка получения эмуляторов: {e}")
            
            # Формируем heartbeat
            heartbeat = {
                "type": "heartbeat",
                "timestamp": int(time.time() * 1000),
                "cpu_usage": metrics.get("cpu_usage", 0),
                "ram_usage": metrics.get("ram_usage", 0),
                "ram_available_gb": metrics.get("ram_available_gb", 0),
                "emulators": emulators
            }
            
            success = await self.send_func(heartbeat)
            
            if success:
                self._last_heartbeat = time.time()
                self._heartbeat_count += 1
                logger.debug(f"Heartbeat #{self._heartbeat_count} отправлен")
            
            return success
            
        except Exception as e:
            logger.error(f"Ошибка отправки heartbeat: {e}")
            return False
    
    async def _heartbeat_loop(self):
        """Цикл отправки heartbeat"""
        while self._running:
            try:
                await self.send_heartbeat()
                await asyncio.sleep(self.interval)
                
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Ошибка в heartbeat loop: {e}")
                await asyncio.sleep(5)  # Короткая пауза при ошибке

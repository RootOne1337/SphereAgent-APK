"""
Windows Service для SpherePC Agent
Позволяет запускать агент как Windows службу
"""

import sys
import os
import logging
import asyncio
from pathlib import Path

# Добавляем путь к модулям
sys.path.insert(0, str(Path(__file__).parent.parent))

logger = logging.getLogger("SpherePC-Service")

try:
    import win32serviceutil
    import win32service
    import win32event
    import servicemanager
    WIN32_AVAILABLE = True
except ImportError:
    WIN32_AVAILABLE = False
    logger.warning("pywin32 не установлен, Windows Service недоступен")


if WIN32_AVAILABLE:
    class SpherePCAgentService(win32serviceutil.ServiceFramework):
        """Windows Service для SpherePC Agent"""
        
        _svc_name_ = "SpherePCAgent"
        _svc_display_name_ = "SpherePC Agent"
        _svc_description_ = "SphereADB PC Agent - управление Remote PC и LDPlayer эмуляторами"
        
        def __init__(self, args):
            win32serviceutil.ServiceFramework.__init__(self, args)
            self.hWaitStop = win32event.CreateEvent(None, 0, 0, None)
            self._agent = None
            self._loop = None
        
        def SvcStop(self):
            """Остановка сервиса"""
            self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
            win32event.SetEvent(self.hWaitStop)
            
            # Останавливаем агент
            if self._agent and self._loop:
                asyncio.run_coroutine_threadsafe(
                    self._agent.shutdown(),
                    self._loop
                )
        
        def SvcDoRun(self):
            """Запуск сервиса"""
            servicemanager.LogMsg(
                servicemanager.EVENTLOG_INFORMATION_TYPE,
                servicemanager.PYS_SERVICE_STARTED,
                (self._svc_name_, '')
            )
            
            self.main()
        
        def main(self):
            """Главный метод сервиса"""
            from agent.config import AgentConfig
            from main import SphereAgentPC, setup_logging
            
            # Загружаем конфиг
            config_paths = [
                Path(__file__).parent.parent / "config.yaml",
                Path(os.environ.get("PROGRAMDATA", "C:\\ProgramData")) / "SpherePC-Agent" / "config.yaml",
                Path.home() / ".sphere-pc-agent" / "config.yaml"
            ]
            
            config = None
            for path in config_paths:
                if path.exists():
                    config = AgentConfig.load(str(path))
                    break
            
            if config is None:
                config = AgentConfig.load()
            
            # Настраиваем логирование в файл (сервис не имеет консоли)
            config.logging.console = False
            if not config.logging.file:
                config.logging.file = str(
                    Path(os.environ.get("PROGRAMDATA", "C:\\ProgramData")) / 
                    "SpherePC-Agent" / "logs" / "agent.log"
                )
            
            setup_logging(config)
            
            # Валидация
            errors = config.validate()
            if errors:
                for error in errors:
                    logger.error(f"Config error: {error}")
                return
            
            # Запускаем агент
            self._agent = SphereAgentPC(config)
            self._loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self._loop)
            
            try:
                self._loop.run_until_complete(self._agent.run())
            except Exception as e:
                logger.exception(f"Service error: {e}")
            finally:
                self._loop.close()


def install_service():
    """Установить Windows Service"""
    if not WIN32_AVAILABLE:
        print("Error: pywin32 не установлен. Выполните: pip install pywin32")
        return False
    
    try:
        win32serviceutil.InstallService(
            SpherePCAgentService._svc_name_,
            SpherePCAgentService._svc_name_,
            SpherePCAgentService._svc_display_name_
        )
        print(f"✓ Сервис {SpherePCAgentService._svc_display_name_} установлен")
        return True
    except Exception as e:
        print(f"✗ Ошибка установки: {e}")
        return False


def uninstall_service():
    """Удалить Windows Service"""
    if not WIN32_AVAILABLE:
        print("Error: pywin32 не установлен")
        return False
    
    try:
        win32serviceutil.RemoveService(SpherePCAgentService._svc_name_)
        print(f"✓ Сервис удалён")
        return True
    except Exception as e:
        print(f"✗ Ошибка удаления: {e}")
        return False


def start_service():
    """Запустить сервис"""
    if not WIN32_AVAILABLE:
        return False
    
    try:
        win32serviceutil.StartService(SpherePCAgentService._svc_name_)
        print(f"✓ Сервис запущен")
        return True
    except Exception as e:
        print(f"✗ Ошибка запуска: {e}")
        return False


def stop_service():
    """Остановить сервис"""
    if not WIN32_AVAILABLE:
        return False
    
    try:
        win32serviceutil.StopService(SpherePCAgentService._svc_name_)
        print(f"✓ Сервис остановлен")
        return True
    except Exception as e:
        print(f"✗ Ошибка остановки: {e}")
        return False


if __name__ == '__main__':
    if WIN32_AVAILABLE:
        win32serviceutil.HandleCommandLine(SpherePCAgentService)

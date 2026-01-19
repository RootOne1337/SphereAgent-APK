"""
Продвинутые команды для полного управления ПК
Скрипты, файлы, процессы, реестр, сеть - ВСЁ!
"""

import asyncio
import os
import sys
import logging
import subprocess
import json
import base64
from pathlib import Path
from typing import Dict, Any, List
import time

from commands.base import CommandHandler, CommandResult

logger = logging.getLogger(__name__)


class AdvancedCommands(CommandHandler):
    """
    Продвинутые команды для ПОЛНОГО управления ПК
    
    Поддерживает:
    - Выполнение скриптов (.bat, .ps1, .py)
    - Управление файлами (чтение, запись, удаление, загрузка)
    - Управление процессами (список, kill, запуск)
    - Реестр Windows (чтение, запись)
    - Сетевые команды
    - Системные команды
    """
    
    SUPPORTED_COMMANDS = [
        # Скрипты
        "exec_script", "exec_batch", "exec_powershell", "exec_python",
        
        # Файлы
        "file_read", "file_write", "file_delete", "file_list", "file_exists",
        "file_upload", "file_download", "file_move", "file_copy",
        
        # Процессы
        "process_list", "process_kill", "process_start",
        
        # Реестр (Windows)
        "registry_read", "registry_write", "registry_delete",
        
        # Сеть
        "net_ping", "net_tracert", "net_download", "net_upload",
        
        # Система
        "sys_screenshot", "sys_reboot", "sys_shutdown", "sys_env",
        "sys_services", "sys_service_control"
    ]
    
    def __init__(self, allow_dangerous: bool = False):
        """
        Args:
            allow_dangerous: Разрешить опасные команды (reboot, shutdown, registry)
        """
        self.allow_dangerous = allow_dangerous
    
    async def execute(self, command: str, params: Dict[str, Any]) -> CommandResult:
        """Выполнить команду"""
        start_time = time.time()
        
        try:
            # === СКРИПТЫ ===
            if command == "exec_script":
                result = await self._exec_script(params)
            elif command == "exec_batch":
                result = await self._exec_batch(params)
            elif command == "exec_powershell":
                result = await self._exec_powershell(params)
            elif command == "exec_python":
                result = await self._exec_python(params)
            
            # === ФАЙЛЫ ===
            elif command == "file_read":
                result = await self._file_read(params)
            elif command == "file_write":
                result = await self._file_write(params)
            elif command == "file_delete":
                result = await self._file_delete(params)
            elif command == "file_list":
                result = await self._file_list(params)
            elif command == "file_exists":
                result = await self._file_exists(params)
            elif command == "file_move":
                result = await self._file_move(params)
            elif command == "file_copy":
                result = await self._file_copy(params)
            
            # === ПРОЦЕССЫ ===
            elif command == "process_list":
                result = await self._process_list(params)
            elif command == "process_kill":
                result = await self._process_kill(params)
            elif command == "process_start":
                result = await self._process_start(params)
            
            # === РЕЕСТР ===
            elif command == "registry_read":
                result = await self._registry_read(params)
            elif command == "registry_write":
                result = await self._registry_write(params)
            
            # === СЕТЬ ===
            elif command == "net_ping":
                result = await self._net_ping(params)
            elif command == "net_download":
                result = await self._net_download(params)
            
            # === СИСТЕМА ===
            elif command == "sys_screenshot":
                result = await self._sys_screenshot(params)
            elif command == "sys_env":
                result = await self._sys_env(params)
            elif command == "sys_reboot":
                result = await self._sys_reboot(params)
            elif command == "sys_shutdown":
                result = await self._sys_shutdown(params)
            
            else:
                result = self.error(f"Unknown advanced command: {command}")
            
            result.duration_ms = int((time.time() - start_time) * 1000)
            return result
            
        except Exception as e:
            logger.exception(f"Advanced command error: {command}")
            return self.error(str(e), int((time.time() - start_time) * 1000))
    
    # === СКРИПТЫ ===
    
    async def _exec_script(self, params: Dict[str, Any]) -> CommandResult:
        """Выполнить скрипт (автоопределение типа)"""
        script = params.get("script", "")
        args = params.get("args", [])
        
        if not script:
            return self.error("Script is required")
        
        # Определяем тип скрипта
        if script.endswith(".bat") or script.endswith(".cmd"):
            return await self._exec_batch({"script": script, "args": args})
        elif script.endswith(".ps1"):
            return await self._exec_powershell({"script": script, "args": args})
        elif script.endswith(".py"):
            return await self._exec_python({"script": script, "args": args})
        else:
            # Выполняем как batch
            return await self._exec_batch({"content": script, "args": args})
    
    async def _exec_batch(self, params: Dict[str, Any]) -> CommandResult:
        """Выполнить batch скрипт"""
        script = params.get("script")  # Путь к файлу
        content = params.get("content")  # Или содержимое
        args = params.get("args", [])
        timeout = params.get("timeout", 300)
        
        if not script and not content:
            return self.error("Script path or content is required")
        
        try:
            if content:
                # Создаём временный файл
                import tempfile
                with tempfile.NamedTemporaryFile(mode='w', suffix='.bat', delete=False) as f:
                    f.write(content)
                    script = f.name
            
            cmd = [script] + args
            
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=os.path.dirname(script) if script else None
            )
            
            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=timeout
            )
            
            return self.success({
                "stdout": stdout.decode("utf-8", errors="replace"),
                "stderr": stderr.decode("utf-8", errors="replace"),
                "returncode": process.returncode
            })
            
        except asyncio.TimeoutError:
            return self.error(f"Script timeout after {timeout}s")
        except Exception as e:
            return self.error(str(e))
    
    async def _exec_powershell(self, params: Dict[str, Any]) -> CommandResult:
        """Выполнить PowerShell скрипт"""
        script = params.get("script")
        content = params.get("content")
        args = params.get("args", [])
        timeout = params.get("timeout", 300)
        
        if not script and not content:
            return self.error("Script path or content is required")
        
        try:
            if content:
                # Выполняем inline
                cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", content]
            else:
                cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script] + args
            
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=timeout
            )
            
            return self.success({
                "stdout": stdout.decode("utf-8", errors="replace"),
                "stderr": stderr.decode("utf-8", errors="replace"),
                "returncode": process.returncode
            })
            
        except Exception as e:
            return self.error(str(e))
    
    async def _exec_python(self, params: Dict[str, Any]) -> CommandResult:
        """Выполнить Python скрипт"""
        script = params.get("script")
        content = params.get("content")
        args = params.get("args", [])
        timeout = params.get("timeout", 300)
        
        try:
            if content:
                # Выполняем inline
                cmd = [sys.executable, "-c", content] + args
            else:
                cmd = [sys.executable, script] + args
            
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=timeout
            )
            
            return self.success({
                "stdout": stdout.decode("utf-8", errors="replace"),
                "stderr": stderr.decode("utf-8", errors="replace"),
                "returncode": process.returncode
            })
            
        except Exception as e:
            return self.error(str(e))
    
    # === ФАЙЛЫ ===
    
    async def _file_read(self, params: Dict[str, Any]) -> CommandResult:
        """Прочитать файл"""
        path = params.get("path", "")
        encoding = params.get("encoding", "utf-8")
        binary = params.get("binary", False)
        
        if not path:
            return self.error("Path is required")
        
        try:
            if binary:
                with open(path, 'rb') as f:
                    content = base64.b64encode(f.read()).decode('ascii')
                    return self.success({"content": content, "binary": True})
            else:
                with open(path, 'r', encoding=encoding) as f:
                    content = f.read()
                    return self.success({"content": content, "binary": False})
        except Exception as e:
            return self.error(str(e))
    
    async def _file_write(self, params: Dict[str, Any]) -> CommandResult:
        """Записать файл"""
        path = params.get("path", "")
        content = params.get("content", "")
        encoding = params.get("encoding", "utf-8")
        binary = params.get("binary", False)
        append = params.get("append", False)
        
        if not path:
            return self.error("Path is required")
        
        try:
            # Создаём директорию если нет
            os.makedirs(os.path.dirname(path), exist_ok=True)
            
            mode = 'ab' if (binary and append) else ('wb' if binary else ('a' if append else 'w'))
            
            if binary:
                content_bytes = base64.b64decode(content)
                with open(path, mode) as f:
                    f.write(content_bytes)
            else:
                with open(path, mode, encoding=encoding) as f:
                    f.write(content)
            
            return self.success({"written": True, "path": path})
        except Exception as e:
            return self.error(str(e))
    
    async def _file_delete(self, params: Dict[str, Any]) -> CommandResult:
        """Удалить файл"""
        path = params.get("path", "")
        
        if not path:
            return self.error("Path is required")
        
        try:
            if os.path.isfile(path):
                os.remove(path)
                return self.success({"deleted": True, "path": path})
            elif os.path.isdir(path):
                import shutil
                shutil.rmtree(path)
                return self.success({"deleted": True, "path": path, "was_directory": True})
            else:
                return self.error("Path not found")
        except Exception as e:
            return self.error(str(e))
    
    async def _file_list(self, params: Dict[str, Any]) -> CommandResult:
        """Список файлов в директории"""
        path = params.get("path", ".")
        recursive = params.get("recursive", False)
        
        try:
            if recursive:
                files = []
                for root, dirs, filenames in os.walk(path):
                    for name in filenames:
                        full_path = os.path.join(root, name)
                        files.append({
                            "path": full_path,
                            "name": name,
                            "size": os.path.getsize(full_path),
                            "is_dir": False
                        })
                    for name in dirs:
                        full_path = os.path.join(root, name)
                        files.append({
                            "path": full_path,
                            "name": name,
                            "size": 0,
                            "is_dir": True
                        })
            else:
                files = []
                for item in os.listdir(path):
                    full_path = os.path.join(path, item)
                    files.append({
                        "path": full_path,
                        "name": item,
                        "size": os.path.getsize(full_path) if os.path.isfile(full_path) else 0,
                        "is_dir": os.path.isdir(full_path)
                    })
            
            return self.success({"files": files, "count": len(files)})
        except Exception as e:
            return self.error(str(e))
    
    async def _file_exists(self, params: Dict[str, Any]) -> CommandResult:
        """Проверить существование файла"""
        path = params.get("path", "")
        
        return self.success({
            "exists": os.path.exists(path),
            "is_file": os.path.isfile(path),
            "is_dir": os.path.isdir(path)
        })
    
    async def _file_move(self, params: Dict[str, Any]) -> CommandResult:
        """Переместить файл"""
        src = params.get("src", "")
        dst = params.get("dst", "")
        
        if not src or not dst:
            return self.error("Source and destination are required")
        
        try:
            import shutil
            shutil.move(src, dst)
            return self.success({"moved": True, "from": src, "to": dst})
        except Exception as e:
            return self.error(str(e))
    
    async def _file_copy(self, params: Dict[str, Any]) -> CommandResult:
        """Скопировать файл"""
        src = params.get("src", "")
        dst = params.get("dst", "")
        
        if not src or not dst:
            return self.error("Source and destination are required")
        
        try:
            import shutil
            if os.path.isdir(src):
                shutil.copytree(src, dst)
            else:
                shutil.copy2(src, dst)
            return self.success({"copied": True, "from": src, "to": dst})
        except Exception as e:
            return self.error(str(e))
    
    # === ПРОЦЕССЫ ===
    
    async def _process_list(self, params: Dict[str, Any]) -> CommandResult:
        """Список процессов"""
        try:
            import psutil
            
            processes = []
            for proc in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent']):
                try:
                    processes.append({
                        "pid": proc.info['pid'],
                        "name": proc.info['name'],
                        "cpu_percent": proc.info['cpu_percent'],
                        "memory_percent": proc.info['memory_percent']
                    })
                except:
                    pass
            
            return self.success({"processes": processes, "count": len(processes)})
        except Exception as e:
            return self.error(str(e))
    
    async def _process_kill(self, params: Dict[str, Any]) -> CommandResult:
        """Убить процесс"""
        pid = params.get("pid")
        name = params.get("name")
        
        if not pid and not name:
            return self.error("PID or name is required")
        
        try:
            import psutil
            
            killed = []
            if pid:
                proc = psutil.Process(pid)
                proc.kill()
                killed.append(pid)
            elif name:
                for proc in psutil.process_iter(['pid', 'name']):
                    if proc.info['name'] == name:
                        proc.kill()
                        killed.append(proc.info['pid'])
            
            return self.success({"killed": killed, "count": len(killed)})
        except Exception as e:
            return self.error(str(e))
    
    async def _process_start(self, params: Dict[str, Any]) -> CommandResult:
        """Запустить процесс"""
        command = params.get("command", "")
        args = params.get("args", [])
        
        if not command:
            return self.error("Command is required")
        
        try:
            process = await asyncio.create_subprocess_exec(
                command, *args,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            return self.success({
                "started": True,
                "pid": process.pid,
                "command": command
            })
        except Exception as e:
            return self.error(str(e))
    
    # === РЕЕСТР (Windows) ===
    
    async def _registry_read(self, params: Dict[str, Any]) -> CommandResult:
        """Прочитать из реестра"""
        if sys.platform != "win32":
            return self.error("Registry is only available on Windows")
        
        key_path = params.get("key", "")
        value_name = params.get("value", "")
        
        if not key_path:
            return self.error("Key is required")
        
        try:
            import winreg
            
            # Парсим путь к ключу
            parts = key_path.split("\\", 1)
            root_key = getattr(winreg, parts[0])
            sub_key = parts[1] if len(parts) > 1 else ""
            
            key = winreg.OpenKey(root_key, sub_key)
            value, value_type = winreg.QueryValueEx(key, value_name)
            winreg.CloseKey(key)
            
            return self.success({
                "value": value,
                "type": value_type
            })
        except Exception as e:
            return self.error(str(e))
    
    async def _registry_write(self, params: Dict[str, Any]) -> CommandResult:
        """Записать в реестр"""
        if not self.allow_dangerous:
            return self.error("Registry write is disabled (dangerous)")
        
        if sys.platform != "win32":
            return self.error("Registry is only available on Windows")
        
        # TODO: Реализовать если нужно
        return self.error("Not implemented yet")
    
    # === СЕТЬ ===
    
    async def _net_ping(self, params: Dict[str, Any]) -> CommandResult:
        """Ping хоста"""
        host = params.get("host", "")
        count = params.get("count", 4)
        
        if not host:
            return self.error("Host is required")
        
        try:
            cmd = ["ping", "-n" if sys.platform == "win32" else "-c", str(count), host]
            
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            
            stdout, stderr = await process.communicate()
            
            return self.success({
                "output": stdout.decode("utf-8", errors="replace"),
                "success": process.returncode == 0
            })
        except Exception as e:
            return self.error(str(e))
    
    async def _net_download(self, params: Dict[str, Any]) -> CommandResult:
        """Скачать файл"""
        url = params.get("url", "")
        path = params.get("path", "")
        
        if not url:
            return self.error("URL is required")
        
        try:
            import aiohttp
            
            if not path:
                path = os.path.basename(url)
            
            async with aiohttp.ClientSession() as session:
                async with session.get(url) as response:
                    content = await response.read()
                    
                    with open(path, 'wb') as f:
                        f.write(content)
            
            return self.success({
                "downloaded": True,
                "path": path,
                "size": len(content)
            })
        except Exception as e:
            return self.error(str(e))
    
    # === СИСТЕМА ===
    
    async def _sys_screenshot(self, params: Dict[str, Any]) -> CommandResult:
        """Сделать скриншот"""
        try:
            # TODO: Реализовать с помощью PIL или pyautogui
            return self.error("Screenshot not implemented yet (install PIL/pyautogui)")
        except Exception as e:
            return self.error(str(e))
    
    async def _sys_env(self, params: Dict[str, Any]) -> CommandResult:
        """Получить переменные окружения"""
        return self.success({"env": dict(os.environ)})
    
    async def _sys_reboot(self, params: Dict[str, Any]) -> CommandResult:
        """Перезагрузить ПК"""
        if not self.allow_dangerous:
            return self.error("Reboot is disabled (dangerous)")
        
        delay = params.get("delay", 60)
        
        try:
            if sys.platform == "win32":
                os.system(f"shutdown /r /t {delay}")
            else:
                os.system(f"shutdown -r +{delay//60}")
            
            return self.success({"rebooting": True, "delay_seconds": delay})
        except Exception as e:
            return self.error(str(e))
    
    async def _sys_shutdown(self, params: Dict[str, Any]) -> CommandResult:
        """Выключить ПК"""
        if not self.allow_dangerous:
            return self.error("Shutdown is disabled (dangerous)")
        
        delay = params.get("delay", 60)
        
        try:
            if sys.platform == "win32":
                os.system(f"shutdown /s /t {delay}")
            else:
                os.system(f"shutdown -h +{delay//60}")
            
            return self.success({"shutting_down": True, "delay_seconds": delay})
        except Exception as e:
            return self.error(str(e))

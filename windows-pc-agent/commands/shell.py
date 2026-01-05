"""
Shell команды
Выполнение shell команд на ПК
"""

import asyncio
import os
import sys
import re
import logging
from typing import Dict, Any, List, Optional
import time

from commands.base import CommandHandler, CommandResult

logger = logging.getLogger(__name__)


class ShellCommands(CommandHandler):
    """
    Обработчик shell команд
    
    С учётом безопасности:
    - Whitelist разрешённых команд
    - Blacklist опасных команд
    - Таймаут выполнения
    """
    
    SUPPORTED_COMMANDS = [
        "shell",
        "shell_exec"
    ]
    
    # Дефолтный whitelist (regex)
    DEFAULT_WHITELIST = [
        r"^dir.*",
        r"^ls.*",
        r"^cd.*",
        r"^pwd.*",
        r"^echo.*",
        r"^type.*",
        r"^cat.*",
        r"^ipconfig.*",
        r"^ifconfig.*",
        r"^ping.*",
        r"^netstat.*",
        r"^tasklist.*",
        r"^ps.*",
        r"^systeminfo.*",
        r"^hostname.*",
        r"^whoami.*",
        r"^date.*",
        r"^time.*",
        r"^adb.*",  # ADB команды разрешены
    ]
    
    # Дефолтный blacklist (regex) - ВСЕГДА блокируем
    DEFAULT_BLACKLIST = [
        r".*format\s+[a-zA-Z]:.*",
        r".*del\s+/[sS].*",
        r".*rmdir\s+/[sS].*",
        r".*rm\s+-[rR][fF].*",
        r".*shutdown.*",
        r".*reboot.*",
        r".*restart.*",
        r".*:(){.*",  # Fork bomb
        r".*>\s*/dev/sd.*",
        r".*mkfs.*",
        r".*dd\s+if=.*of=/dev.*",
    ]
    
    def __init__(
        self,
        allow_shell: bool = True,
        whitelist: Optional[List[str]] = None,
        blacklist: Optional[List[str]] = None,
        default_timeout: int = 30
    ):
        """
        Args:
            allow_shell: Разрешить выполнение shell команд
            whitelist: Regex паттерны разрешённых команд (None = все)
            blacklist: Regex паттерны запрещённых команд
            default_timeout: Таймаут по умолчанию (сек)
        """
        self.allow_shell = allow_shell
        self.whitelist = whitelist  # None = без ограничений
        self.blacklist = (blacklist or []) + self.DEFAULT_BLACKLIST
        self.default_timeout = default_timeout
        
        # Компилируем regex для производительности
        self._blacklist_patterns = [re.compile(p, re.IGNORECASE) for p in self.blacklist]
        self._whitelist_patterns = None
        if self.whitelist:
            self._whitelist_patterns = [re.compile(p, re.IGNORECASE) for p in self.whitelist]
    
    def _is_allowed(self, command: str) -> tuple[bool, str]:
        """
        Проверить разрешена ли команда
        
        Returns:
            (allowed: bool, reason: str)
        """
        if not self.allow_shell:
            return False, "Shell commands are disabled"
        
        # Проверка blacklist
        for pattern in self._blacklist_patterns:
            if pattern.match(command):
                return False, f"Command blocked by security policy"
        
        # Проверка whitelist (если задан)
        if self._whitelist_patterns:
            for pattern in self._whitelist_patterns:
                if pattern.match(command):
                    return True, ""
            return False, "Command not in whitelist"
        
        return True, ""
    
    async def execute(self, command: str, params: Dict[str, Any]) -> CommandResult:
        """Выполнить shell команду"""
        start_time = time.time()
        
        cmd = params.get("command", "")
        timeout = params.get("timeout", self.default_timeout)
        
        if not cmd:
            return self.error("Command is required")
        
        # Проверка безопасности
        allowed, reason = self._is_allowed(cmd)
        if not allowed:
            logger.warning(f"Shell command blocked: {cmd} ({reason})")
            return self.error(reason)
        
        try:
            result = await self._execute_command(cmd, timeout)
            result.duration_ms = int((time.time() - start_time) * 1000)
            return result
            
        except Exception as e:
            logger.exception(f"Shell command error: {cmd}")
            return self.error(str(e), int((time.time() - start_time) * 1000))
    
    async def _execute_command(self, command: str, timeout: int) -> CommandResult:
        """Выполнить команду"""
        try:
            # Определяем shell
            if sys.platform == "win32":
                shell = True
                args = command
            else:
                shell = True
                args = command
            
            process = await asyncio.create_subprocess_shell(
                args,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                # Для Windows добавляем флаг чтобы не открывать окно
                creationflags=0x08000000 if sys.platform == "win32" else 0  # CREATE_NO_WINDOW
            )
            
            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=timeout
            )
            
            stdout_str = stdout.decode("utf-8", errors="replace").strip()
            stderr_str = stderr.decode("utf-8", errors="replace").strip()
            
            if process.returncode == 0:
                return self.success({
                    "stdout": stdout_str,
                    "stderr": stderr_str,
                    "returncode": process.returncode
                })
            else:
                return CommandResult(
                    success=False,
                    data={
                        "stdout": stdout_str,
                        "stderr": stderr_str,
                        "returncode": process.returncode
                    },
                    error=stderr_str or f"Command failed with code {process.returncode}"
                )
                
        except asyncio.TimeoutError:
            return self.error(f"Command timeout after {timeout}s")
            
        except Exception as e:
            return self.error(str(e))

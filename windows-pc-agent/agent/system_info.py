"""
Модуль сбора информации о системе
CPU, RAM, диск, сеть, ОС
"""

import os
import sys
import socket
import platform
import logging
from typing import Dict, Any, List, Optional
from dataclasses import dataclass, asdict

logger = logging.getLogger(__name__)

try:
    import psutil
    PSUTIL_AVAILABLE = True
except ImportError:
    PSUTIL_AVAILABLE = False
    logger.warning("psutil не установлен, системные метрики ограничены")


@dataclass
class CPUInfo:
    """Информация о CPU"""
    name: str = "Unknown"
    cores_physical: int = 0
    cores_logical: int = 0
    frequency_mhz: float = 0
    usage_percent: float = 0


@dataclass
class MemoryInfo:
    """Информация о памяти"""
    total_gb: float = 0
    available_gb: float = 0
    used_gb: float = 0
    usage_percent: float = 0


@dataclass
class DiskInfo:
    """Информация о диске"""
    total_gb: float = 0
    free_gb: float = 0
    used_gb: float = 0
    usage_percent: float = 0


@dataclass
class NetworkInfo:
    """Информация о сети"""
    hostname: str = ""
    ip_address: str = ""
    mac_address: str = ""


@dataclass
class OSInfo:
    """Информация об ОС"""
    type: str = ""  # windows/linux/darwin
    name: str = ""  # Windows 10 Pro
    version: str = ""  # 10.0.19045
    architecture: str = ""  # AMD64


@dataclass
class SystemInfo:
    """Полная информация о системе"""
    cpu: CPUInfo
    memory: MemoryInfo
    disk: DiskInfo
    network: NetworkInfo
    os: OSInfo
    
    def to_dict(self) -> Dict[str, Any]:
        """Преобразовать в словарь"""
        return {
            "cpu": asdict(self.cpu),
            "memory": asdict(self.memory),
            "disk": asdict(self.disk),
            "network": asdict(self.network),
            "os": asdict(self.os)
        }


def get_cpu_info() -> CPUInfo:
    """Получить информацию о CPU"""
    info = CPUInfo()
    
    try:
        if PSUTIL_AVAILABLE:
            info.cores_physical = psutil.cpu_count(logical=False) or 0
            info.cores_logical = psutil.cpu_count(logical=True) or 0
            info.usage_percent = psutil.cpu_percent(interval=0.1)
            
            try:
                freq = psutil.cpu_freq()
                if freq:
                    info.frequency_mhz = freq.current
            except:
                pass
        
        # Название CPU
        if sys.platform == "win32":
            import subprocess
            try:
                result = subprocess.run(
                    ["wmic", "cpu", "get", "name"],
                    capture_output=True, text=True, timeout=5
                )
                name = result.stdout.strip().split('\n')[-1].strip()
                if name and name != "Name":
                    info.name = name
            except:
                pass
        else:
            try:
                with open("/proc/cpuinfo", "r") as f:
                    for line in f:
                        if line.startswith("model name"):
                            info.name = line.split(":")[1].strip()
                            break
            except:
                pass
                
    except Exception as e:
        logger.error(f"Ошибка получения CPU info: {e}")
    
    return info


def get_memory_info() -> MemoryInfo:
    """Получить информацию о памяти"""
    info = MemoryInfo()
    
    try:
        if PSUTIL_AVAILABLE:
            mem = psutil.virtual_memory()
            info.total_gb = round(mem.total / (1024**3), 2)
            info.available_gb = round(mem.available / (1024**3), 2)
            info.used_gb = round(mem.used / (1024**3), 2)
            info.usage_percent = mem.percent
    except Exception as e:
        logger.error(f"Ошибка получения Memory info: {e}")
    
    return info


def get_disk_info(path: str = "/") -> DiskInfo:
    """Получить информацию о диске"""
    info = DiskInfo()
    
    try:
        if PSUTIL_AVAILABLE:
            # Для Windows используем C:
            if sys.platform == "win32":
                path = "C:\\"
            
            disk = psutil.disk_usage(path)
            info.total_gb = round(disk.total / (1024**3), 2)
            info.free_gb = round(disk.free / (1024**3), 2)
            info.used_gb = round(disk.used / (1024**3), 2)
            info.usage_percent = disk.percent
    except Exception as e:
        logger.error(f"Ошибка получения Disk info: {e}")
    
    return info


def get_network_info() -> NetworkInfo:
    """Получить информацию о сети"""
    info = NetworkInfo()
    
    try:
        info.hostname = socket.gethostname()
        
        # IP адрес
        try:
            # Создаём сокет для определения внешнего IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            info.ip_address = s.getsockname()[0]
            s.close()
        except:
            info.ip_address = socket.gethostbyname(socket.gethostname())
        
        # MAC адрес
        if PSUTIL_AVAILABLE:
            for iface, addrs in psutil.net_if_addrs().items():
                for addr in addrs:
                    if addr.family == psutil.AF_LINK:  # MAC address
                        if addr.address and addr.address != "00:00:00:00:00:00":
                            info.mac_address = addr.address
                            break
                if info.mac_address:
                    break
                    
    except Exception as e:
        logger.error(f"Ошибка получения Network info: {e}")
    
    return info


def get_os_info() -> OSInfo:
    """Получить информацию об ОС"""
    info = OSInfo()
    
    try:
        info.type = sys.platform  # win32, linux, darwin
        info.architecture = platform.machine()  # AMD64, x86_64
        
        if sys.platform == "win32":
            info.name = f"Windows {platform.release()}"
            info.version = platform.version()
            
            # Более детальная информация
            try:
                import subprocess
                result = subprocess.run(
                    ["wmic", "os", "get", "caption"],
                    capture_output=True, text=True, timeout=5
                )
                caption = result.stdout.strip().split('\n')[-1].strip()
                if caption and caption != "Caption":
                    info.name = caption
            except:
                pass
        else:
            info.name = platform.system()
            info.version = platform.release()
            
            # Для Linux пробуем получить distro
            try:
                import distro
                info.name = f"{distro.name()} {distro.version()}"
            except ImportError:
                try:
                    with open("/etc/os-release", "r") as f:
                        for line in f:
                            if line.startswith("PRETTY_NAME="):
                                info.name = line.split("=")[1].strip().strip('"')
                                break
                except:
                    pass
                    
    except Exception as e:
        logger.error(f"Ошибка получения OS info: {e}")
    
    return info


def get_system_info() -> SystemInfo:
    """Получить полную информацию о системе"""
    return SystemInfo(
        cpu=get_cpu_info(),
        memory=get_memory_info(),
        disk=get_disk_info(),
        network=get_network_info(),
        os=get_os_info()
    )


def get_quick_metrics() -> Dict[str, Any]:
    """
    Быстрые метрики для heartbeat
    Минимум информации, максимум скорости
    """
    metrics = {
        "cpu_usage": 0,
        "ram_usage": 0,
        "ram_available_gb": 0
    }
    
    try:
        if PSUTIL_AVAILABLE:
            metrics["cpu_usage"] = psutil.cpu_percent(interval=0.1)
            mem = psutil.virtual_memory()
            metrics["ram_usage"] = mem.percent
            metrics["ram_available_gb"] = round(mem.available / (1024**3), 2)
    except:
        pass
    
    return metrics

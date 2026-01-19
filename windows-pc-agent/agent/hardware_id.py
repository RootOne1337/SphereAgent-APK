"""
Модуль генерации уникального Hardware ID
Используется для идентификации ПК на сервере
"""

import os
import sys
import uuid
import hashlib
import platform
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


def get_hardware_id() -> str:
    """
    Получить уникальный Hardware ID системы
    
    Использует комбинацию:
    - Windows: MAC адрес + серийный номер BIOS
    - Linux: machine-id + MAC адрес
    
    Returns:
        Уникальный UUID на основе hardware
    """
    try:
        if sys.platform == "win32":
            return _get_windows_hardware_id()
        else:
            return _get_linux_hardware_id()
    except Exception as e:
        logger.warning(f"Не удалось получить Hardware ID: {e}, генерируем случайный")
        return _get_fallback_id()


def _get_windows_hardware_id() -> str:
    """Получить Hardware ID для Windows"""
    import subprocess
    
    components = []
    
    # MAC адрес
    try:
        mac = uuid.getnode()
        if mac != uuid.getnode():  # Проверка на случайный MAC
            mac = uuid.getnode()
        components.append(str(mac))
    except:
        pass
    
    # Серийный номер BIOS
    try:
        result = subprocess.run(
            ["wmic", "bios", "get", "serialnumber"],
            capture_output=True,
            text=True,
            timeout=5
        )
        serial = result.stdout.strip().split('\n')[-1].strip()
        if serial and serial != "SerialNumber":
            components.append(serial)
    except:
        pass
    
    # Серийный номер материнской платы
    try:
        result = subprocess.run(
            ["wmic", "baseboard", "get", "serialnumber"],
            capture_output=True,
            text=True,
            timeout=5
        )
        serial = result.stdout.strip().split('\n')[-1].strip()
        if serial and serial not in ["SerialNumber", "To be filled by O.E.M."]:
            components.append(serial)
    except:
        pass
    
    # UUID системы
    try:
        result = subprocess.run(
            ["wmic", "csproduct", "get", "uuid"],
            capture_output=True,
            text=True,
            timeout=5
        )
        sys_uuid = result.stdout.strip().split('\n')[-1].strip()
        if sys_uuid and sys_uuid != "UUID":
            components.append(sys_uuid)
    except:
        pass
    
    if not components:
        return _get_fallback_id()
    
    # Генерируем UUID из компонентов
    combined = "-".join(components)
    hash_bytes = hashlib.sha256(combined.encode()).digest()[:16]
    return str(uuid.UUID(bytes=hash_bytes))


def _get_linux_hardware_id() -> str:
    """Получить Hardware ID для Linux"""
    components = []
    
    # /etc/machine-id
    try:
        machine_id_path = Path("/etc/machine-id")
        if machine_id_path.exists():
            machine_id = machine_id_path.read_text().strip()
            if machine_id:
                components.append(machine_id)
    except:
        pass
    
    # /var/lib/dbus/machine-id (fallback)
    try:
        dbus_id_path = Path("/var/lib/dbus/machine-id")
        if dbus_id_path.exists() and not components:
            dbus_id = dbus_id_path.read_text().strip()
            if dbus_id:
                components.append(dbus_id)
    except:
        pass
    
    # MAC адрес
    try:
        mac = uuid.getnode()
        components.append(str(mac))
    except:
        pass
    
    # Product UUID (если есть права)
    try:
        uuid_path = Path("/sys/class/dmi/id/product_uuid")
        if uuid_path.exists():
            product_uuid = uuid_path.read_text().strip()
            if product_uuid:
                components.append(product_uuid)
    except:
        pass
    
    if not components:
        return _get_fallback_id()
    
    # Генерируем UUID из компонентов
    combined = "-".join(components)
    hash_bytes = hashlib.sha256(combined.encode()).digest()[:16]
    return str(uuid.UUID(bytes=hash_bytes))


def _get_fallback_id() -> str:
    """
    Fallback: генерируем и сохраняем случайный UUID
    Сохраняется в файл чтобы сохраняться между перезапусками
    """
    id_file = Path.home() / ".sphere-pc-agent" / "device_id"
    
    # Проверяем существующий ID
    if id_file.exists():
        try:
            existing_id = id_file.read_text().strip()
            if existing_id:
                return existing_id
        except:
            pass
    
    # Генерируем новый
    new_id = str(uuid.uuid4())
    
    # Сохраняем
    try:
        id_file.parent.mkdir(parents=True, exist_ok=True)
        id_file.write_text(new_id)
        logger.info(f"Сгенерирован новый Device ID: {new_id}")
    except Exception as e:
        logger.warning(f"Не удалось сохранить Device ID: {e}")
    
    return new_id


def get_or_create_device_id(config_device_id: str = "") -> str:
    """
    Получить или создать Device ID
    
    Args:
        config_device_id: ID из конфига (приоритет)
        
    Returns:
        Device ID
    """
    # Приоритет: конфиг -> hardware -> fallback
    if config_device_id:
        return config_device_id
    
    return get_hardware_id()

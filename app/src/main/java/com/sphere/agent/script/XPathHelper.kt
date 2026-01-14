package com.sphere.agent.script

import android.util.Log
import com.sphere.agent.service.CommandExecutor
import com.sphere.agent.service.CommandResult
import kotlinx.coroutines.delay
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * XPathHelper v2.2.0 - Помощник для работы с XPath и UIAutomator2
 * 
 * Позволяет:
 * - Получать UI дамп экрана через uiautomator dump
 * - Парсить XML и находить элементы по XPath
 * - Извлекать координаты элементов (bounds)
 * - Находить элементы по text, resource-id, content-desc
 * - Ждать появления элементов с таймаутом
 * 
 * Примеры XPath:
 * - //node[@text='OK']
 * - //node[@resource-id='com.app:id/button']
 * - //node[@content-desc='Settings']
 * - //node[contains(@text, 'Login')]
 * - //node[@class='android.widget.Button'][@text='Submit']
 */
class XPathHelper(private val commandExecutor: CommandExecutor) {
    
    companion object {
        private const val TAG = "XPathHelper"
        private const val DUMP_PATH = "/sdcard/sphere_ui_dump.xml"
        private const val DEFAULT_TIMEOUT = 10000L // 10 секунд
        private const val POLL_INTERVAL = 500L // 0.5 секунды
    }
    
    private val xpathFactory = XPathFactory.newInstance()
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    
    /**
     * Результат поиска элемента
     */
    data class ElementInfo(
        val found: Boolean,
        val bounds: Bounds? = null,
        val text: String? = null,
        val resourceId: String? = null,
        val className: String? = null,
        val contentDesc: String? = null,
        val clickable: Boolean = false,
        val enabled: Boolean = true
    )
    
    /**
     * Границы элемента (bounds)
     */
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    
    /**
     * Получить дамп UI иерархии
     */
    suspend fun getUiDump(): String? {
        try {
            // Делаем дамп через uiautomator
            val dumpResult = commandExecutor.shell("uiautomator dump $DUMP_PATH")
            if (!dumpResult.success) {
                Log.e(TAG, "UI dump failed: ${dumpResult.error}")
                return null
            }
            
            // Небольшая задержка чтобы файл записался
            delay(100)
            
            // Читаем содержимое
            val catResult = commandExecutor.shell("cat $DUMP_PATH")
            if (!catResult.success) {
                Log.e(TAG, "Failed to read dump: ${catResult.error}")
                return null
            }
            
            // Удаляем временный файл
            commandExecutor.shell("rm $DUMP_PATH")
            
            return catResult.data
        } catch (e: Exception) {
            Log.e(TAG, "getUiDump error", e)
            return null
        }
    }
    
    /**
     * Найти элемент по XPath
     */
    suspend fun findByXPath(xpath: String): ElementInfo {
        val xml = getUiDump() ?: return ElementInfo(found = false)
        return parseAndFind(xml, xpath)
    }
    
    /**
     * Найти элемент по XPath с таймаутом (ожидание появления)
     */
    suspend fun waitForXPath(xpath: String, timeoutMs: Long = DEFAULT_TIMEOUT): ElementInfo {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = findByXPath(xpath)
            if (result.found) {
                return result
            }
            delay(POLL_INTERVAL)
        }
        
        return ElementInfo(found = false)
    }
    
    /**
     * Найти элемент по тексту
     */
    suspend fun findByText(text: String, exact: Boolean = true): ElementInfo {
        val xpath = if (exact) {
            "//node[@text='$text']"
        } else {
            "//node[contains(@text, '$text')]"
        }
        return findByXPath(xpath)
    }
    
    /**
     * Найти элемент по resource-id
     */
    suspend fun findByResourceId(resourceId: String): ElementInfo {
        val xpath = "//node[@resource-id='$resourceId']"
        return findByXPath(xpath)
    }
    
    /**
     * Найти элемент по content-desc
     */
    suspend fun findByContentDesc(desc: String, exact: Boolean = true): ElementInfo {
        val xpath = if (exact) {
            "//node[@content-desc='$desc']"
        } else {
            "//node[contains(@content-desc, '$desc')]"
        }
        return findByXPath(xpath)
    }
    
    /**
     * Универсальный поиск (by = "text", "id", "desc", "class", "xpath")
     */
    suspend fun findElement(by: String, value: String): ElementInfo {
        return when (by.lowercase()) {
            "text" -> findByText(value)
            "text_contains" -> findByText(value, exact = false)
            "id", "resource-id" -> findByResourceId(value)
            "desc", "content-desc" -> findByContentDesc(value)
            "desc_contains" -> findByContentDesc(value, exact = false)
            "class" -> findByXPath("//node[@class='$value']")
            "xpath" -> findByXPath(value)
            else -> {
                Log.w(TAG, "Unknown find method: $by, trying as xpath")
                findByXPath(value)
            }
        }
    }
    
    /**
     * Ждать появления элемента
     */
    suspend fun waitForElement(by: String, value: String, timeoutMs: Long = DEFAULT_TIMEOUT): ElementInfo {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = findElement(by, value)
            if (result.found) {
                return result
            }
            delay(POLL_INTERVAL)
        }
        
        return ElementInfo(found = false)
    }
    
    /**
     * Тап по элементу (XPath)
     */
    suspend fun tapByXPath(xpath: String, timeoutMs: Long = DEFAULT_TIMEOUT): CommandResult {
        val element = waitForXPath(xpath, timeoutMs)
        
        if (!element.found || element.bounds == null) {
            return CommandResult(success = false, error = "Element not found: $xpath")
        }
        
        Log.i(TAG, "Tapping element at (${element.bounds.centerX}, ${element.bounds.centerY})")
        return commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
    }
    
    /**
     * Тап по элементу (универсальный)
     */
    suspend fun tapElement(by: String, value: String, timeoutMs: Long = DEFAULT_TIMEOUT): CommandResult {
        val element = waitForElement(by, value, timeoutMs)
        
        if (!element.found || element.bounds == null) {
            return CommandResult(success = false, error = "Element not found: $by=$value")
        }
        
        Log.i(TAG, "Tapping element '$value' at (${element.bounds.centerX}, ${element.bounds.centerY})")
        return commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
    }
    
    /**
     * Ввод текста в элемент (XPath)
     */
    suspend fun textByXPath(xpath: String, text: String, timeoutMs: Long = DEFAULT_TIMEOUT): CommandResult {
        val element = waitForXPath(xpath, timeoutMs)
        
        if (!element.found || element.bounds == null) {
            return CommandResult(success = false, error = "Element not found: $xpath")
        }
        
        // Сначала тапаем на элемент для фокуса
        val tapResult = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
        if (!tapResult.success) {
            return tapResult
        }
        
        // Небольшая задержка для получения фокуса
        delay(300)
        
        // Вводим текст
        return commandExecutor.inputText(text)
    }
    
    /**
     * Свайп от элемента
     */
    suspend fun swipeFromElement(
        xpath: String, 
        direction: String, // up, down, left, right
        distance: Int = 300,
        timeoutMs: Long = DEFAULT_TIMEOUT
    ): CommandResult {
        val element = waitForXPath(xpath, timeoutMs)
        
        if (!element.found || element.bounds == null) {
            return CommandResult(success = false, error = "Element not found: $xpath")
        }
        
        val cx = element.bounds.centerX
        val cy = element.bounds.centerY
        
        val (endX, endY) = when (direction.lowercase()) {
            "up" -> Pair(cx, cy - distance)
            "down" -> Pair(cx, cy + distance)
            "left" -> Pair(cx - distance, cy)
            "right" -> Pair(cx + distance, cy)
            else -> return CommandResult(success = false, error = "Invalid direction: $direction")
        }
        
        return commandExecutor.swipe(cx, cy, endX, endY, 300)
    }
    
    /**
     * Парсинг XML и поиск элемента
     */
    private fun parseAndFind(xml: String, xpathExpr: String): ElementInfo {
        try {
            // Подготовка XML - заменяем node на что-то понятное для парсера
            val cleanXml = xml
                .replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "")
                .trim()
            
            val builder = documentBuilderFactory.newDocumentBuilder()
            val doc: Document = builder.parse(InputSource(StringReader(cleanXml)))
            
            val xpath = xpathFactory.newXPath()
            val result = xpath.evaluate(xpathExpr, doc, XPathConstants.NODESET) as? NodeList
            
            if (result == null || result.length == 0) {
                Log.d(TAG, "XPath not found: $xpathExpr")
                return ElementInfo(found = false)
            }
            
            // Берём первый найденный элемент
            val node = result.item(0) as? Element ?: return ElementInfo(found = false)
            
            return parseNode(node)
            
        } catch (e: Exception) {
            Log.e(TAG, "XPath parse error: $xpathExpr", e)
            return ElementInfo(found = false)
        }
    }
    
    /**
     * Парсинг узла в ElementInfo
     */
    private fun parseNode(node: Element): ElementInfo {
        val boundsStr = node.getAttribute("bounds")
        val bounds = parseBounds(boundsStr)
        
        return ElementInfo(
            found = true,
            bounds = bounds,
            text = node.getAttribute("text").takeIf { it.isNotEmpty() },
            resourceId = node.getAttribute("resource-id").takeIf { it.isNotEmpty() },
            className = node.getAttribute("class").takeIf { it.isNotEmpty() },
            contentDesc = node.getAttribute("content-desc").takeIf { it.isNotEmpty() },
            clickable = node.getAttribute("clickable") == "true",
            enabled = node.getAttribute("enabled") != "false"
        )
    }
    
    /**
     * Парсинг bounds строки "[left,top][right,bottom]"
     */
    private fun parseBounds(boundsStr: String): Bounds? {
        if (boundsStr.isBlank()) return null
        
        try {
            // Формат: [left,top][right,bottom]
            val regex = """\[(\d+),(\d+)\]\[(\d+),(\d+)\]""".toRegex()
            val match = regex.find(boundsStr) ?: return null
            
            val (left, top, right, bottom) = match.destructured
            return Bounds(
                left = left.toInt(),
                top = top.toInt(),
                right = right.toInt(),
                bottom = bottom.toInt()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bounds: $boundsStr", e)
            return null
        }
    }
}

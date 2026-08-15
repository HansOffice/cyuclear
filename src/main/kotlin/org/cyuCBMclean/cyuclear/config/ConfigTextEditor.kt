package org.cyuCBMclean.cyuclear.config

import org.cyuCBMclean.cyuclear.Cyuclear
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ConfigTextEditor {
    fun setScalar(path: String, value: String) {
        val file = configFile()
        val lines = file.readLines(Charsets.UTF_8).toMutableList()
        val index = findPath(lines, path) ?: throw IllegalArgumentException("找不到配置项 $path")
        val line = lines[index]
        val separator = line.indexOf(':')
        val suffix = line.substring(separator + 1).substringAfter('#', "").takeIf { '#' in line.substring(separator + 1) }
        lines[index] = line.substring(0, separator + 1) + " " + value + if (suffix == null) "" else " #$suffix"
        write(file, lines)
    }

    fun setList(path: String, values: List<String>) {
        val file = configFile()
        val lines = file.readLines(Charsets.UTF_8).toMutableList()
        val index = findPath(lines, path) ?: throw IllegalArgumentException("找不到配置项 $path")
        val baseIndent = indent(lines[index])
        var end = index + 1
        while (end < lines.size) {
            val trimmed = lines[end].trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && indent(lines[end]) <= baseIndent) break
            end++
        }
        for (lineIndex in end - 1 downTo index + 1) {
            val trimmed = lines[lineIndex].trimStart()
            if (trimmed.startsWith("- ") && indent(lines[lineIndex]) > baseIndent) lines.removeAt(lineIndex)
        }
        val additions = values.map { " ".repeat(baseIndent + 2) + "- '" + it.replace("'", "''") + "'" }
        lines.addAll(index + 1, additions)
        write(file, lines)
    }

    private fun findPath(lines: List<String>, path: String): Int? {
        val parts = path.split('.')
        val stack = ArrayList<Pair<Int, String>>()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) continue
            val separator = trimmed.indexOf(':')
            if (separator <= 0) continue
            val level = indent(line)
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.lastIndex)
            val key = trimmed.substring(0, separator).trim()
            val current = stack.map { it.second } + key
            if (current == parts) return index
            stack.add(level to key)
        }
        return null
    }

    private fun indent(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) Int.MAX_VALUE else it }

    private fun write(file: File, lines: List<String>) {
        val original = file.readText(Charsets.UTF_8)
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val temp = File(file.parentFile, ".rules.yml.menu.tmp")
        temp.writeText(lines.joinToString(newline, postfix = newline), Charsets.UTF_8)
        runCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun configFile(): File = File(Cyuclear.instance.dataFolder, "rules.yml")
}

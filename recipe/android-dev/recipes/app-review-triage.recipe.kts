import com.fajarnuha.klassify.recipe.recipe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

data class CsvRow(val line: Int, val fields: List<String>)

fun readCsv(text: String): Sequence<CsvRow> = sequence {
    var index = 0
    var line = 1
    var rowLine = line
    var fields = mutableListOf<String>()
    while (index < text.length) {
        val field = StringBuilder()
        if (text[index] == '"') {
            index++
            while (true) {
                check(index < text.length) { "CSV line $rowLine: unterminated quoted field" }
                val char = text[index++]
                if (char == '"') {
                    if (text.getOrNull(index) != '"') break
                    index++
                }
                if (char == '\n' || (char == '\r' && text.getOrNull(index) != '\n')) line++
                field.append(char)
            }
        } else {
            while (index < text.length && text[index] !in ",\r\n") {
                require(text[index] != '"') { "CSV line $rowLine: unexpected quote" }
                field.append(text[index++])
            }
        }
        fields.add(field.toString())

        when (text.getOrNull(index)) {
            ',' -> {
                index++
                if (index == text.length) fields.add("")
            }
            '\r', '\n' -> {
                if (text[index++] == '\r' && text.getOrNull(index) == '\n') index++
                yield(CsvRow(rowLine, fields))
                fields = mutableListOf()
                rowLine = ++line
            }
            null -> break
            else -> error("CSV line $rowLine: expected a comma or line ending after a quoted field")
        }
    }
    if (fields.isNotEmpty()) yield(CsvRow(rowLine, fields))
}

recipe(args) {
    val path = pathOption("reviews") ?: error("Pass --reviews")
    val version = option("app-version")
    questionsFromPack()

    val rows = readCsv(Files.readString(path).removePrefix("\uFEFF")).iterator()
    require(rows.hasNext()) { "CSV is empty" }
    val header = rows.next()
    val columns = header.fields.map(String::trim)
    val required = listOf("id", "text", "rating", "appVersion")
    require(columns.distinct().size == columns.size && columns.containsAll(required)) {
        "CSV line ${header.line}: expected unique columns including ${required.joinToString()}"
    }
    val idColumn = columns.indexOf("id")
    val textColumn = columns.indexOf("text")
    val ratingColumn = columns.indexOf("rating")
    val versionColumn = columns.indexOf("appVersion")
    val platformColumn = columns.indexOf("platform")
    val seen = mutableSetOf<String>()

    while (rows.hasNext()) {
        val (line, fields) = rows.next()
        if (fields.all(String::isBlank)) continue
        require(fields.size == columns.size) { "CSV line $line: expected ${columns.size} fields" }
        val review = fields[textColumn]
        val appVersion = fields[versionColumn].trim()
        if (review.isBlank() || (version != null && version != appVersion)) continue

        val id = fields[idColumn].trim()
        require(id.isNotEmpty() && seen.add(id)) { "CSV line $line: missing or duplicate review ID" }
        val rating = fields[ratingColumn].trim().toIntOrNull()
        require(rating != null && rating in 1..5) { "CSV line $line: rating must be an integer from 1 to 5" }

        item(id, buildJsonObject {
            put("text", review)
            put("rating", rating)
            if (appVersion.isNotEmpty()) put("appVersion", appVersion)
            fields.getOrNull(platformColumn)?.trim()?.takeIf(String::isNotEmpty)?.let { put("platform", it) }
        })
    }
}

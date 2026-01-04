package com.terminox.domain.model

import org.junit.Assert.*
import org.junit.Test

class SnippetTest {

    @Test
    fun `extractVariableNames extracts single variable`() {
        val command = "echo \${MESSAGE}"
        val variables = Snippet.extractVariableNames(command)

        assertEquals(1, variables.size)
        assertEquals("MESSAGE", variables[0])
    }

    @Test
    fun `extractVariableNames extracts multiple variables`() {
        val command = "ssh \${USER}@\${HOST} -p \${PORT}"
        val variables = Snippet.extractVariableNames(command)

        assertEquals(3, variables.size)
        assertTrue(variables.contains("USER"))
        assertTrue(variables.contains("HOST"))
        assertTrue(variables.contains("PORT"))
    }

    @Test
    fun `extractVariableNames removes duplicates`() {
        val command = "echo \${VAR} \${VAR} \${VAR}"
        val variables = Snippet.extractVariableNames(command)

        assertEquals(1, variables.size)
        assertEquals("VAR", variables[0])
    }

    @Test
    fun `extractVariableNames returns empty list for no variables`() {
        val command = "echo hello world"
        val variables = Snippet.extractVariableNames(command)

        assertTrue(variables.isEmpty())
    }

    @Test
    fun `create snippet auto-detects variables`() {
        val snippet = Snippet.create(
            id = "test",
            name = "Test",
            command = "ssh \${USER}@\${HOST}"
        )

        assertEquals(2, snippet.variables.size)
        assertTrue(snippet.variables.any { it.name == "USER" })
        assertTrue(snippet.variables.any { it.name == "HOST" })
    }

    @Test
    fun `hasVariables returns true when variables exist`() {
        val snippet = Snippet.create(
            id = "test",
            name = "Test",
            command = "echo \${MESSAGE}"
        )

        assertTrue(snippet.hasVariables())
    }

    @Test
    fun `hasVariables returns false when no variables exist`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo hello",
            variables = emptyList()
        )

        assertFalse(snippet.hasVariables())
    }

    @Test
    fun `getPreview shows placeholders for variables without defaults`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo \${MESSAGE}",
            variables = listOf(
                SnippetVariable(name = "MESSAGE")
            )
        )

        val preview = snippet.getPreview()
        assertEquals("echo <MESSAGE>", preview)
    }

    @Test
    fun `getPreview shows default values when available`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "ssh \${USER}@\${HOST}",
            variables = listOf(
                SnippetVariable(name = "USER", defaultValue = "admin"),
                SnippetVariable(name = "HOST", defaultValue = "localhost")
            )
        )

        val preview = snippet.getPreview()
        assertEquals("ssh admin@localhost", preview)
    }

    @Test
    fun `substitute replaces variables with provided values`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo \${MESSAGE}",
            variables = listOf(
                SnippetVariable(name = "MESSAGE")
            )
        )

        val result = snippet.substitute(mapOf("MESSAGE" to "Hello World"))
        assertEquals("echo Hello World", result)
    }

    @Test
    fun `substitute returns null when required variable is missing`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo \${MESSAGE}",
            variables = listOf(
                SnippetVariable(name = "MESSAGE", isOptional = false)
            )
        )

        val result = snippet.substitute(emptyMap())
        assertNull(result)
    }

    @Test
    fun `substitute uses default value when variable not provided`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo \${MESSAGE}",
            variables = listOf(
                SnippetVariable(name = "MESSAGE", defaultValue = "default message")
            )
        )

        val result = snippet.substitute(emptyMap())
        assertEquals("echo default message", result)
    }

    @Test
    fun `substitute handles optional variables with no value`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo \${MESSAGE}",
            variables = listOf(
                SnippetVariable(name = "MESSAGE", isOptional = true)
            )
        )

        val result = snippet.substitute(emptyMap())
        assertEquals("echo ", result)
    }

    @Test
    fun `substitute replaces multiple variables`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "ssh \${USER}@\${HOST} -p \${PORT}",
            variables = listOf(
                SnippetVariable(name = "USER"),
                SnippetVariable(name = "HOST"),
                SnippetVariable(name = "PORT")
            )
        )

        val result = snippet.substitute(
            mapOf(
                "USER" to "admin",
                "HOST" to "example.com",
                "PORT" to "2222"
            )
        )

        assertEquals("ssh admin@example.com -p 2222", result)
    }

    @Test
    fun `SnippetVariable validate returns true for valid pattern match`() {
        val variable = SnippetVariable(
            name = "PORT",
            validationPattern = "\\d+"
        )

        assertTrue(variable.validate("22"))
        assertTrue(variable.validate("8080"))
    }

    @Test
    fun `SnippetVariable validate returns false for invalid pattern match`() {
        val variable = SnippetVariable(
            name = "PORT",
            validationPattern = "\\d+"
        )

        assertFalse(variable.validate("abc"))
        assertFalse(variable.validate("22abc"))
    }

    @Test
    fun `SnippetVariable validate returns true when no pattern specified`() {
        val variable = SnippetVariable(name = "VAR")

        assertTrue(variable.validate("anything"))
        assertTrue(variable.validate("123"))
    }

    @Test
    fun `SnippetVariable getDisplayName includes optional indicator`() {
        val optionalVar = SnippetVariable(name = "VAR", isOptional = true)
        assertEquals("VAR (optional)", optionalVar.getDisplayName())

        val requiredVar = SnippetVariable(name = "VAR", isOptional = false)
        assertEquals("VAR", requiredVar.getDisplayName())
    }

    @Test
    fun `SnippetCategory isRoot returns true when no parent`() {
        val category = SnippetCategory(id = "test", name = "Test")
        assertTrue(category.isRoot())
    }

    @Test
    fun `SnippetCategory isRoot returns false when has parent`() {
        val category = SnippetCategory(
            id = "test",
            name = "Test",
            parentId = "parent"
        )
        assertFalse(category.isRoot())
    }

    @Test
    fun `SnippetExport contains version and timestamp`() {
        val export = SnippetExport(
            snippets = emptyList(),
            categories = emptyList()
        )

        assertEquals(1, export.version)
        assertTrue(export.exportedAt > 0)
    }

    @Test
    fun `snippet with complex command and variables`() {
        val command = """
            docker run -d \
              --name \${CONTAINER_NAME} \
              -p \${PORT}:80 \
              -e ENV=\${ENVIRONMENT} \
              nginx:latest
        """.trimIndent()

        val snippet = Snippet.create(
            id = "docker-nginx",
            name = "Run Nginx Container",
            command = command
        )

        assertEquals(3, snippet.variables.size)
        assertTrue(snippet.variables.any { it.name == "CONTAINER_NAME" })
        assertTrue(snippet.variables.any { it.name == "PORT" })
        assertTrue(snippet.variables.any { it.name == "ENVIRONMENT" })

        val result = snippet.substitute(
            mapOf(
                "CONTAINER_NAME" to "my-nginx",
                "PORT" to "8080",
                "ENVIRONMENT" to "production"
            )
        )

        assertNotNull(result)
        assertTrue(result!!.contains("--name my-nginx"))
        assertTrue(result.contains("-p 8080:80"))
        assertTrue(result.contains("-e ENV=production"))
    }

    @Test
    fun `snippet tracks usage statistics`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo test",
            useCount = 5,
            lastUsedAt = 123456789L
        )

        assertEquals(5, snippet.useCount)
        assertEquals(123456789L, snippet.lastUsedAt)
    }

    @Test
    fun `snippet can be marked as favorite`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo test",
            isFavorite = true
        )

        assertTrue(snippet.isFavorite)
    }

    @Test
    fun `snippet can have tags`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo test",
            tags = listOf("docker", "deployment", "production")
        )

        assertEquals(3, snippet.tags.size)
        assertTrue(snippet.tags.contains("docker"))
    }

    @Test
    fun `snippet can have description and category`() {
        val snippet = Snippet(
            id = "test",
            name = "Test",
            command = "echo test",
            description = "A test snippet",
            categoryId = "category-1"
        )

        assertEquals("A test snippet", snippet.description)
        assertEquals("category-1", snippet.categoryId)
    }
}

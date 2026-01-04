package com.terminox.domain.model

/**
 * Represents a command snippet that can be saved and executed.
 *
 * @property id Unique identifier
 * @property name Display name for the snippet
 * @property command The command template with optional variables (${VAR})
 * @property description Optional description of what the snippet does
 * @property categoryId Optional category/folder ID for organization
 * @property variables List of variables used in the command
 * @property createdAt Timestamp when snippet was created
 * @property lastUsedAt Timestamp when snippet was last executed
 * @property useCount Number of times this snippet has been executed
 * @property isFavorite Whether this snippet is marked as favorite
 * @property tags Optional tags for searching/filtering
 */
data class Snippet(
    val id: String,
    val name: String,
    val command: String,
    val description: String? = null,
    val categoryId: String? = null,
    val variables: List<SnippetVariable> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val isFavorite: Boolean = false,
    val tags: List<String> = emptyList()
) {
    /**
     * Check if this snippet contains variables that need substitution.
     */
    fun hasVariables(): Boolean = variables.isNotEmpty()

    /**
     * Get a preview of the command with placeholder values.
     */
    fun getPreview(): String {
        var preview = command
        variables.forEach { variable ->
            val placeholder = variable.defaultValue ?: "<${variable.name}>"
            preview = preview.replace("\${${variable.name}}", placeholder)
        }
        return preview
    }

    /**
     * Substitute variables with provided values.
     * Returns null if required variables are missing.
     */
    fun substitute(values: Map<String, String>): String? {
        var result = command
        for (variable in variables) {
            val value = values[variable.name]
            if (value == null && !variable.isOptional) {
                return null // Required variable missing
            }
            val substitutionValue = value ?: variable.defaultValue ?: ""
            result = result.replace("\${${variable.name}}", substitutionValue)
        }
        return result
    }

    /**
     * Extract variable names from the command template.
     */
    companion object {
        private val VARIABLE_PATTERN = Regex("""\$\{([^}]+)\}""")

        fun extractVariableNames(command: String): List<String> {
            return VARIABLE_PATTERN.findAll(command)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        }

        /**
         * Create a snippet with auto-detected variables.
         */
        fun create(
            id: String,
            name: String,
            command: String,
            description: String? = null,
            categoryId: String? = null
        ): Snippet {
            val variableNames = extractVariableNames(command)
            val variables = variableNames.map { varName ->
                SnippetVariable(
                    name = varName,
                    description = null,
                    defaultValue = null,
                    isOptional = false
                )
            }
            return Snippet(
                id = id,
                name = name,
                command = command,
                description = description,
                categoryId = categoryId,
                variables = variables
            )
        }
    }
}

/**
 * Represents a variable that can be substituted in a snippet command.
 *
 * @property name Variable name (without ${} syntax)
 * @property description Optional description shown to user
 * @property defaultValue Optional default value if user doesn't provide one
 * @property isOptional Whether this variable is optional
 * @property validationPattern Optional regex pattern for validation
 */
data class SnippetVariable(
    val name: String,
    val description: String? = null,
    val defaultValue: String? = null,
    val isOptional: Boolean = false,
    val validationPattern: String? = null
) {
    /**
     * Validate a value against the validation pattern if present.
     */
    fun validate(value: String): Boolean {
        if (validationPattern == null) return true
        return try {
            Regex(validationPattern).matches(value)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get display name for the variable (with optional indicator).
     */
    fun getDisplayName(): String {
        return if (isOptional) "$name (optional)" else name
    }
}

/**
 * Represents a category/folder for organizing snippets.
 *
 * @property id Unique identifier
 * @property name Category name
 * @property description Optional description
 * @property icon Optional icon identifier
 * @property parentId Optional parent category ID for nested categories
 * @property order Display order
 */
data class SnippetCategory(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val parentId: String? = null,
    val order: Int = 0
) {
    /**
     * Check if this is a root category (no parent).
     */
    fun isRoot(): Boolean = parentId == null
}

/**
 * Export format for snippets.
 */
data class SnippetExport(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val snippets: List<Snippet>,
    val categories: List<SnippetCategory>
)

/**
 * Result of executing a snippet.
 */
sealed class SnippetExecutionResult {
    /** Snippet executed successfully */
    data class Success(val command: String) : SnippetExecutionResult()

    /** User cancelled variable input */
    data object Cancelled : SnippetExecutionResult()

    /** Required variables were not provided */
    data class MissingVariables(val missingVars: List<String>) : SnippetExecutionResult()

    /** Validation failed for one or more variables */
    data class ValidationFailed(val failures: Map<String, String>) : SnippetExecutionResult()
}

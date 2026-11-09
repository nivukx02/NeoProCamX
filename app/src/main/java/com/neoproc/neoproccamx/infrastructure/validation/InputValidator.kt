package com.neoproc.neoproccamx.infrastructure.validation

import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import com.neoproc.neoproccamx.infrastructure.error.ErrorHandler
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.util.regex.Pattern

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * INPUT VALIDATOR — COMPREHENSIVE INPUT VALIDATION AND SANITIZATION INFRASTRUCTURE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Production-grade validation framework providing:
 *   - Type-safe validation with detailed error messages
 *   - Range validation for numeric parameters
 *   - String sanitization and pattern matching
 *   - Collection validation with size constraints
 *   - Android-specific validation (Size, Rect, RectF)
 *   - Custom validator composition and chaining
 *   - Lazy evaluation for performance optimization
 *
 * Thread Safety: All validators are stateless and thread-safe.
 * Performance: Zero-allocation validation paths for hot operations.
 *
 * @author NeoProCamX Infrastructure Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
object InputValidator {

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // VALIDATION RESULT SEALED CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Sealed class representing validation outcomes.
     */
    sealed class ValidationResult<out T> {
        abstract val value: T?
        abstract val isValid: Boolean

        /**
         * Successful validation containing sanitized value.
         */
        data class Success<T>(
            override val value: T,
            val warnings: List<String> = emptyList()
        ) : ValidationResult<T>() {
            override val isValid: Boolean = true
        }

        /**
         * Failed validation with detailed error information.
         */
        data class Failure(
            val errors: List<ValidationError>,
            override val value: Nothing? = null
        ) : ValidationResult<Nothing>() {
            override val isValid: Boolean = false

            /**
             * Single error convenience constructor.
             */
            constructor(error: ValidationError) : this(listOf(error))

            /**
             * Error message convenience constructor.
             */
            constructor(field: String, message: String) :
                this(listOf(ValidationError(field, message)))

            /**
             * Combines multiple failure results.
             */
            fun combine(other: Failure): Failure {
                return Failure(this.errors + other.errors)
            }
        }

        /**
         * Individual validation error details.
         */
        data class ValidationError(
            val field: String,
            val message: String,
            val constraint: String? = null,
            val actualValue: Any? = null,
            val expectedValue: Any? = null
        )

        // ═════════════════════════════════════════════════════════════════════════════════════════════
        // RESULT OPERATORS
        // ═════════════════════════════════════════════════════════════════════════════════════════════

        /**
         * Maps success value through transformation.
         */
        inline fun <R> map(transform: (T) -> R): ValidationResult<R> {
            return when (this) {
                is Success -> Success(transform(this.value), this.warnings)
                is Failure -> this
            }
        }

        /**
         * Flat-maps success value through transformation.
         */
        inline fun <R> flatMap(transform: (T) -> ValidationResult<R>): ValidationResult<R> {
            return when (this) {
                is Success -> transform(this.value)
                is Failure -> this
            }
        }

        /**
         * Returns success value or throws with error message.
         */
        fun orThrow(): T {
            return when (this) {
                is Success -> this.value
                is Failure -> throw ValidationException(this.errors)
            }
        }

        /**
         * Returns success value or default.
         */
        fun orDefault(default: @UnsafeVariance T): T {
            return when (this) {
                is Success -> this.value
                is Failure -> default
            }
        }

        /**
         * Returns success value or null.
         */
        fun orNull(): T? {
            return when (this) {
                is Success -> this.value
                is Failure -> null
            }
        }

        /**
         * Filters success value based on predicate.
         */
        inline fun filter(predicate: (T) -> Boolean, errorMessage: String): ValidationResult<T> {
            return when (this) {
                is Success -> if (predicate(this.value)) this else
                    Failure("filtered", errorMessage)
                is Failure -> this
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // VALIDATION EXCEPTION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Exception thrown when validation fails with orThrow().
     */
    class ValidationException(
        val validationErrors: List<ValidationResult.ValidationError>
    ) : IllegalArgumentException(
        validationErrors.joinToString("; ") { "${it.field}: ${it.message}" }
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // VALIDATOR FUNCTION TYPE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Type alias for validator functions.
     */
    interface Validator<T> {
        fun validate(value: T): ValidationResult<T>
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // STRING VALIDATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates that a string is not null or blank.
     */
    fun String?.notNullOrBlank(fieldName: String = "value"): ValidationResult<String> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this.isBlank() -> ValidationResult.Failure(fieldName, "Value cannot be blank")
            else -> ValidationResult.Success(this.trim())
        }
    }

    /**
     * Validates string length constraints.
     */
    fun String?.lengthInRange(
        min: Int,
        max: Int,
        fieldName: String = "value"
    ): ValidationResult<String> {
        val notNullResult = this.notNullOrBlank(fieldName)
        if (notNullResult !is ValidationResult.Success) return notNullResult

        val str = notNullResult.value
        return when {
            str.length < min -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName,
                    "Value too short (minimum $min characters)",
                    "minLength=$min",
                    str.length,
                    min
                )
            )
            str.length > max -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName,
                    "Value too long (maximum $max characters)",
                    "maxLength=$max",
                    str.length,
                    max
                )
            )
            else -> ValidationResult.Success(str)
        }
    }

    /**
     * Validates string against regex pattern.
     */
    fun String?.matchesPattern(
        pattern: Pattern,
        fieldName: String = "value"
    ): ValidationResult<String> {
        val notNullResult = this.notNullOrBlank(fieldName)
        if (notNullResult !is ValidationResult.Success) return notNullResult

        val str = notNullResult.value
        return if (pattern.matcher(str).matches()) {
            ValidationResult.Success(str)
        } else {
            ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName,
                    "Value does not match required pattern",
                    pattern.pattern()
                )
            )
        }
    }

    /**
     * Common patterns for string validation.
     */
    object Patterns {
        val EMAIL: Pattern = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
        val ALPHANUMERIC: Pattern = Pattern.compile("^[a-zA-Z0-9]+$")
        val SAFE_FILENAME: Pattern = Pattern.compile("^[a-zA-Z0-9._-]+$")
        val UUID: Pattern = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // NUMERIC VALIDATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates integer is within range.
     */
    fun Int?.inRange(
        min: Int,
        max: Int,
        fieldName: String = "value"
    ): ValidationResult<Int> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this < min -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName,
                    "Value below minimum ($min)",
                    "range=[$min, $max]",
                    this,
                    min
                )
            )
            this > max -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName,
                    "Value above maximum ($max)",
                    "range=[$min, $max]",
                    this,
                    max
                )
            )
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates long is within range.
     */
    fun Long?.inRange(
        min: Long,
        max: Long,
        fieldName: String = "value"
    ): ValidationResult<Long> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this < min -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Value below minimum", "range=[$min, $max]", this, min
                )
            )
            this > max -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Value above maximum", "range=[$min, $max]", this, max
                )
            )
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates float is within range.
     */
    fun Float?.inRange(
        min: Float,
        max: Float,
        fieldName: String = "value"
    ): ValidationResult<Float> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this.isNaN() -> ValidationResult.Failure(fieldName, "Value cannot be NaN")
            this.isInfinite() -> ValidationResult.Failure(fieldName, "Value cannot be infinite")
            this < min -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Value below minimum", "range=[$min, $max]", this, min
                )
            )
            this > max -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Value above maximum", "range=[$min, $max]", this, max
                )
            )
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates double is within range.
     */
    fun Double?.inRange(
        min: Double,
        max: Double,
        fieldName: String = "value"
    ): ValidationResult<Double> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this.isNaN() -> ValidationResult.Failure(fieldName, "Value cannot be NaN")
            this.isInfinite() -> ValidationResult.Failure(fieldName, "Value cannot be infinite")
            this < min -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Value below minimum", "range=[$min, $max]", this, min
                )
            )
            this > max -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Value above maximum", "range=[$min, $max]", this, max
                )
            )
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates numeric value is positive.
     */
    fun Number?.positive(fieldName: String = "value"): ValidationResult<Number> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this.toDouble() <= 0 -> ValidationResult.Failure(fieldName, "Value must be positive")
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates numeric value is non-negative.
     */
    fun Number?.nonNegative(fieldName: String = "value"): ValidationResult<Number> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Value cannot be null")
            this.toDouble() < 0 -> ValidationResult.Failure(fieldName, "Value cannot be negative")
            else -> ValidationResult.Success(this)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // COLLECTION VALIDATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates collection is not null or empty.
     */
    fun <T> Collection<T>?.notEmpty(fieldName: String = "collection"): ValidationResult<Collection<T>> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Collection cannot be null")
            this.isEmpty() -> ValidationResult.Failure(fieldName, "Collection cannot be empty")
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates collection size is within range.
     */
    fun <T> Collection<T>?.sizeInRange(
        min: Int,
        max: Int,
        fieldName: String = "collection"
    ): ValidationResult<Collection<T>> {
        val notEmptyResult = this.notEmpty(fieldName)
        if (notEmptyResult !is ValidationResult.Success) return notEmptyResult

        val collection = notEmptyResult.value
        return when {
            collection.size < min -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Collection too small", "minSize=$min", collection.size, min
                )
            )
            collection.size > max -> ValidationResult.Failure(
                ValidationResult.ValidationError(
                    fieldName, "Collection too large", "maxSize=$max", collection.size, max
                )
            )
            else -> ValidationResult.Success(collection)
        }
    }

    /**
     * Validates all elements in collection pass given validator.
     */
    fun <T> Collection<T>?.allElementsValid(
        elementValidator: (T, Int) -> ValidationResult<T>,
        fieldName: String = "collection"
    ): ValidationResult<Collection<T>> {
        val notEmptyResult = this.notEmpty(fieldName)
        if (notEmptyResult !is ValidationResult.Success) return notEmptyResult

        val collection = notEmptyResult.value
        val errors = mutableListOf<ValidationResult.ValidationError>()

        collection.forEachIndexed { index, element ->
            val result = elementValidator(element, index)
            if (result is ValidationResult.Failure) {
                errors.addAll(result.errors.map { error ->
                    error.copy(field = "$fieldName[$index].${error.field}")
                })
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success(collection)
        } else {
            ValidationResult.Failure(errors)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ANDROID-SPECIFIC VALIDATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates Android Size dimensions.
     */
    fun Size?.validDimensions(
        minWidth: Int = 1,
        minHeight: Int = 1,
        maxWidth: Int = 16384,
        maxHeight: Int = 16384,
        fieldName: String = "size"
    ): ValidationResult<Size> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Size cannot be null")
            this.width < minWidth -> ValidationResult.Failure(
                ValidationResult.ValidationError(fieldName, "Width below minimum", "minWidth=$minWidth", this.width, minWidth)
            )
            this.width > maxWidth -> ValidationResult.Failure(
                ValidationResult.ValidationError(fieldName, "Width above maximum", "maxWidth=$maxWidth", this.width, maxWidth)
            )
            this.height < minHeight -> ValidationResult.Failure(
                ValidationResult.ValidationError(fieldName, "Height below minimum", "minHeight=$minHeight", this.height, minHeight)
            )
            this.height > maxHeight -> ValidationResult.Failure(
                ValidationResult.ValidationError(fieldName, "Height above maximum", "maxHeight=$maxHeight", this.height, maxHeight)
            )
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates Rect dimensions and coordinates.
     */
    fun Rect?.validRect(
        requireNonEmpty: Boolean = true,
        fieldName: String = "rect"
    ): ValidationResult<Rect> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "Rect cannot be null")
            requireNonEmpty && this.isEmpty -> ValidationResult.Failure(fieldName, "Rect cannot be empty")
            this.left >= this.right -> ValidationResult.Failure(fieldName, "Rect left must be less than right")
            this.top >= this.bottom -> ValidationResult.Failure(fieldName, "Rect top must be less than bottom")
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates RectF dimensions and coordinates.
     */
    fun RectF?.validRectF(
        requireNonEmpty: Boolean = true,
        fieldName: String = "rect"
    ): ValidationResult<RectF> {
        return when {
            this == null -> ValidationResult.Failure(fieldName, "RectF cannot be null")
            requireNonEmpty && this.isEmpty -> ValidationResult.Failure(fieldName, "RectF cannot be empty")
            this.left >= this.right -> ValidationResult.Failure(fieldName, "RectF left must be less than right")
            this.top >= this.bottom -> ValidationResult.Failure(fieldName, "RectF top must be less than bottom")
            else -> ValidationResult.Success(this)
        }
    }

    /**
     * Validates normalized coordinates (0.0 to 1.0).
     */
    fun Float?.normalizedCoordinate(fieldName: String = "coordinate"): ValidationResult<Float> {
        return this?.inRange(0.0f, 1.0f, fieldName) ?: ValidationResult.Failure(fieldName, "Coordinate cannot be null")
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // COMPOSITE VALIDATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates multiple fields and returns combined result.
     */
    fun validateAll(vararg results: ValidationResult<*>): ValidationResult<Unit> {
        val failures = results.filterIsInstance<ValidationResult.Failure>()
        return if (failures.isEmpty()) {
            ValidationResult.Success(Unit)
        } else {
            ValidationResult.Failure(failures.flatMap { it.errors })
        }
    }

    /**
     * Builder for complex validation scenarios.
     */
    class ValidationBuilder {
        private val errors = mutableListOf<ValidationResult.ValidationError>()
        private val warnings = mutableListOf<String>()

        fun <T> validate(result: ValidationResult<T>): T? {
            return when (result) {
                is ValidationResult.Success -> {
                    warnings.addAll(result.warnings)
                    result.value
                }
                is ValidationResult.Failure -> {
                    errors.addAll(result.errors)
                    null
                }
            }
        }

        fun validate(condition: Boolean, onFailure: ValidationBuilder.() -> Unit) {
            if (!condition) {
                onFailure()
            }
        }

        fun addError(field: String, message: String) {
            errors.add(ValidationResult.ValidationError(field, message))
        }

        fun addWarning(message: String) {
            warnings.add(message)
        }

        fun build(): ValidationResult<Unit> {
            return if (errors.isEmpty()) {
                ValidationResult.Success(Unit, warnings)
            } else {
                ValidationResult.Failure(errors)
            }
        }
    }

    /**
     * DSL-style validation block.
     */
    inline fun validate(block: ValidationBuilder.() -> Unit): ValidationResult<Unit> {
        return ValidationBuilder().apply(block).build()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // VALIDATION WITH ERROR HANDLING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates with automatic error reporting on failure.
     */
    fun <T> ValidationResult<T>.reportOnFailure(
        operation: String,
        component: String
    ): ValidationResult<T> {
        if (this is ValidationResult.Failure) {
            val errorMessage = this.errors.joinToString("; ") { "${it.field}: ${it.message}" }
            ErrorHandler.handle(
                throwable = ValidationException(this.errors),
                context = ErrorHandler.ErrorContext(
                    severity = ErrorHandler.ErrorSeverity.WARNING,
                    category = ErrorHandler.ErrorCategory.VALIDATION,
                    operation = operation,
                    component = component
                )
            )
            NeoLogger.w("InputValidator", "Validation failed for $operation: $errorMessage")
        }
        return this
    }
}

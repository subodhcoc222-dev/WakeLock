package com.anekar.wakelock.data

import java.security.MessageDigest

object WakelockSecurity {
    private const val CRYPTO_SALT = "WAKELOCK_TAMPER_RESISTANT_SALT_ANEKAR"

    fun hashPin(pin: String): String {
        val input = "$pin$CRYPTO_SALT"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(inputPin: String, storedHash: String): Boolean {
        if (storedHash.isBlank() || inputPin.isBlank()) return false
        val hashedInput = hashPin(inputPin)
        return hashedInput.equals(storedHash, ignoreCase = true)
    }
}

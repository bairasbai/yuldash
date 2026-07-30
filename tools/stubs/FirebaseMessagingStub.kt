package com.google.firebase.messaging

class FirebaseMessaging private constructor() {
    val token: Task<String> get() = Task()
    companion object {
        @JvmStatic fun getInstance(): FirebaseMessaging = FirebaseMessaging()
    }
}

// Минимальный Task из Google Play Services — нужен только addOnSuccessListener.
class Task<T> {
    fun addOnSuccessListener(listener: (T) -> Unit): Task<T> = this
    fun addOnFailureListener(listener: (Exception) -> Unit): Task<T> = this
}

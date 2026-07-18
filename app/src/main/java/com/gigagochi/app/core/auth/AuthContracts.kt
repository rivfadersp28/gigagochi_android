package com.gigagochi.app.core.auth

enum class AuthFailureKind {
    Configuration,
    Network,
    BadRequest,
    RateLimited,
    Server,
}

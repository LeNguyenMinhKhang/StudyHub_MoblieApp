package com.doan.studyhub.common

/** An expected business failure without provider text or sensitive payloads. */
class DomainException(val type: ErrorType) : Exception(type.code)

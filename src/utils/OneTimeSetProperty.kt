package com.fantamomo.slack.approver.utils

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class OneTimeSetProperty<T> : ReadWriteProperty<Any?, T> {
    private data object UninitializedValue

    private var value: Any? = UninitializedValue

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val currentValue = value
        if (currentValue === UninitializedValue) {
            throw IllegalStateException("Property ${property.name} has not been initialized")
        }
        @Suppress("UNCHECKED_CAST")
        return currentValue as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (this.value !== UninitializedValue) {
            throw IllegalStateException("Property ${property.name} has already been initialized")
        }
        this.value = value
    }
}

fun <T> oneTimeSetProperty(): OneTimeSetProperty<T> = OneTimeSetProperty()
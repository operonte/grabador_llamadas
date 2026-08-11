package com.cristianbravo.grabador_llamadas

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * No hace nada con los eventos de accesibilidad: existe únicamente porque
 * Android exime a los servicios de accesibilidad de la política que silencia
 * el micrófono de cualquier otra app mientras una app de videollamada
 * mantiene su propia sesión de audio "privacy-sensitive" (AudioSource
 * VOICE_COMMUNICATION o CAMCORDER) — ver README para el detalle de esa
 * restricción. Sin esta exención, `CallRecordingPipeline` no recibe nada del
 * micrófono durante una llamada activa, sin importar el resto del código.
 *
 * Requiere que el usuario lo habilite a mano en Ajustes > Accesibilidad; no
 * hay forma de activarlo por código. Es una vía inestable: puede variar por
 * fabricante y dejar de funcionar con una actualización de Android.
 */
class RecorderAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

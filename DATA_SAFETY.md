# Borrador para el formulario "Data safety" de Play Console

Esto no es un documento público — es una guía con las respuestas a copiar en el
formulario de Play Console (App content → Data safety) al momento de publicar.
Play Console no acepta un archivo, hay que completar el formulario web a mano con
estas respuestas.

## 1. ¿Tu app recopila o comparte alguno de los tipos de datos requeridos?

**Sí.**

## 2. Tipos de datos

- **Audio → Grabaciones de voz o sonido**: sí, se recopila.
  - ¿Se recopila o comparte? Se recopila (queda en el dispositivo). No se comparte.
  - ¿Es obligatorio o opcional? Opcional — el usuario decide cada vez que toca
    "Grabar".
  - ¿Para qué se usa? Funcionalidad de la app (grabar la llamada).
  - ¿Se procesa de forma efímera? No, se guarda de forma persistente en el
    dispositivo hasta que el usuario la borra.

- **Fotos y videos → Videos**: sí, se recopila.
  - Mismas respuestas que el punto anterior (video de la pantalla).

## 3. ¿Los datos se cifran en tránsito?

No aplica — la app no tiene permiso de Internet, no transmite datos a ningún
servidor.

## 4. ¿Puede el usuario solicitar que se borren sus datos?

No aplica un flujo de "solicitud de borrado" porque los datos nunca salen del
dispositivo del usuario: él mismo los borra directamente desde la Galería o el
gestor de archivos cuando quiera.

## 5. ¿Se comparten datos con terceros?

No.

## 6. Práctica de seguridad de datos

- Los datos se almacenan cifrados en tránsito: no aplica (no hay tránsito).
- Puedes solicitar que se borren los datos: no aplica (control total y directo del
  usuario sobre sus propios archivos).
- Cumple con la Política de Familias: no, la app no está dirigida a niños.

## URL de política de privacidad a ingresar en Play Console

Una vez publicado el repositorio, usar:

```
https://github.com/operonte/grabador_llamadas/blob/master/PRIVACY_POLICY.md
```

Si más adelante se quiere una URL más "profesional" (recomendado para la ficha de
la tienda), se puede activar GitHub Pages sobre este mismo repo sin costo y
apuntar a ese archivo.

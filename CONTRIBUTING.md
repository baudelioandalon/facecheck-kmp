# Contribuir a FaceCheck KMP

Gracias por el interés. Este documento dice cómo trabajar en el repo sin romper
las dos cosas que más caro cuestan aquí: la paridad con
[`facecheck-android`](https://github.com/baudelioandalon/facecheck-android) y la
honestidad de lo que el SDK promete.

## Antes de escribir código

- Para **bugs**, abre un issue con la versión del SDK, la plataforma, el
  `minSdk`/versión de iOS, y un caso reproducible. Un stack trace con el
  `FaceCheckErrorCode` ayuda mucho.
- Para **cambios grandes o de API pública**, abre un issue primero. Es un SDK
  contra el que otras apps compilan: un cambio de firma se paga en cada
  integración, y antes de la 1.0 se paga sin deprecación.
- **Nunca pegues llaves de API, grants, ni fotos de personas reales** en un
  issue o un PR. Si necesitas adjuntar una imagen para reproducir algo, usa un
  rostro tuyo o sintético.

## Configuración

Necesitas:

- **JDK 21**
- SDK de Android con `platforms;android-36` y `build-tools;36.1.0`
- **macOS + Xcode** solo si vas a tocar `iosMain`

```bash
git clone https://github.com/baudelioandalon/facecheck-kmp.git
cd facecheck-kmp
./gradlew build -x test
./gradlew test
```

En Linux los targets de iOS se saltan solos
(`kotlin.native.ignoreDisabledTargets=true`) y el build pasa igual. CI los
compila en un runner de macOS.

## Estructura

```
facecheck-kmp/src/
├── commonMain/     lógica pura: retos, red, modelos, config
├── commonTest/     pruebas de todo lo anterior; corren en Android y en iOS
├── androidMain/    CameraX + ML Kit
└── iosMain/        AVFoundation + Vision
samples/android-quickstart/    app mínima, se compila en CI
samples/immersive-ui/           UI canónica de cámara y configuración
docs/IOS.md                    integración en iOS
```

Regla de oro: **si algo puede vivir en `commonMain`, vive en `commonMain`.** El
código de plataforma es el que no se puede probar en el JVM y el que hay que
escribir dos veces.

`CameraHost.kt` es a propósito el único lugar con `expect`/`actual` en todo el
SDK. Concentrar ahí toda la costura entre plataformas es lo que permite que
`facecheck-android` sea una copia byte por byte del resto (ver abajo). Si te
descubres agregando un `expect` nuevo en otro archivo, para y piensa: casi
siempre se puede resolver con una interfaz normal en `commonMain` y una
implementación por plataforma.

## Estilo

- **Comentarios y KDoc en inglés.** Texto que ve el usuario final —
  `instructionEs`, `messageEs`, `hintEs`, los mensajes de `FaceCheckException` —
  en **español de México**.
- Los comentarios explican **por qué**, no qué. Si un comentario repite lo que
  la línea de abajo ya dice, sobra. Si una decisión tiene una alternativa obvia
  que se descartó, eso sí se escribe.
- `kotlin.code.style=official`, 4 espacios, línea de ~100 columnas.
- Nada de `TODO` sin un issue enlazado.

## Pruebas

Todo lo que va en `commonMain` se prueba en `commonTest`, y esas pruebas corren
en las dos plataformas. `ChallengeMachine` no tiene reloj, ni cámara, ni
dispatcher: el tiempo entra por `FaceFrame.timestampMs`, así que una sesión de
veinte segundos se prueba en microsegundos. Mantén esa propiedad — es lo que
hace que la lógica de vida sea comprobable.

```bash
./gradlew test                                   # Android + simulador de iOS
./gradlew :facecheck-kmp:testDebugUnitTest       # solo Android, más rápido
```

Un PR que cambia comportamiento necesita una prueba que falle sin el cambio.

## Lo que NO se acepta

Estas no son reglas de estilo; son la razón de ser del diseño. Un PR que haga
cualquiera de estas cosas se cierra aunque el código esté impecable:

- **Devolver un score de similitud desde `/verify`,** o exponerlo en
  `VerifyResult`. Un score junto con su umbral convierte el endpoint en un
  oráculo de distancia contra plantillas que el atacante nunca vio.
- **Poner un umbral de coincidencia en el dispositivo.** Un umbral en el cliente
  es un umbral que el cliente puede cambiar.
- **Tratar el resultado de `ChallengeMachine` como prueba de nada.** Corre en el
  proceso del atacante. Es guía y sube el piso; no es un control de seguridad.
  Ver la sección *Limitaciones* del README.
- **Presentar el anti-spoofing o la comparación contra INE como más maduros de
  lo que son.** Si un cambio mejora alguno de los dos, lo que hay que actualizar
  también es lo que el README dice sobre sus límites.
- **Agregar controles que supongan que la llave de API es secreta.** Va dentro
  del APK. Esos controles van en el backend.

## Paridad con `facecheck-android`

`libs/facecheck-kmp` es la única fuente de verdad tanto para el SDK como para
la UI inmersiva. El directorio `sdk/` de este checkout contiene un espejo
generado: `sdk/facecheck-sdk` y `sdk/immersive-ui` no se editan a mano.
Después de cualquier cambio en KMP, ejecuta
`tools/sync-kmp-to-android.sh --source libs/facecheck-kmp --target sdk` y deja
que `--check` detecte diferencias antes de compilar.

`facecheck-android` es la distribución solo-Android de este mismo SDK y su
código es una **copia byte por byte** de `commonMain` + `androidMain` +
`commonTest`, generada por un script.

Al fusionar un cambio aquí, ese repo se queda atrás hasta que alguien lo
resincroniza:

```bash
cd ../facecheck-android
tools/sync-from-kmp.sh ../facecheck-kmp
./gradlew build
```

El ejemplo Android del repo KMP y el demo físico no son dos productos
distintos: el primero consume directamente `samples/immersive-ui` y el
segundo consume su espejo generado. Si una pantalla necesita cambiar, cambia
el módulo KMP y vuelve a sincronizar.

Su CI lo detecta y se pone en rojo, pero es mejor hacerlo en el momento. Y si tu
cambio agrega o mueve un `expect`/`actual`, el script fallará a propósito: léelo
antes de pelearte con él.

Al publicar, los dos repos suben **la misma versión**. Que `1.1.1` signifique el
mismo código en los dos es todo el punto.

## Pull requests

1. Rama desde `main`.
2. `./gradlew build` y `./gradlew test` en verde localmente.
3. Un cambio por PR. Los renombres masivos van en su propio commit, separados de
   los cambios de comportamiento.
4. Actualiza `CHANGELOG.md` bajo `[No publicado]` si el cambio se nota desde
   fuera.
5. Si tocaste la API pública, revisa que el ejemplo del README y
   `samples/android-quickstart` sigan siendo verdad. El sample se compila en CI,
   así que te va a avisar.

Al abrir un PR aceptas que tu contribución se licencie bajo Apache 2.0, igual
que el resto del proyecto.

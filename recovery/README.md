\# Splatt Elite — Recuperación



Esta carpeta contiene archivos de referencia destinados a recuperar una versión funcional conocida de Splatt Elite.



\## Android



APK de referencia:



`android/splatt-elite-reference-debug.apk`



SHA-256:



`5960722F38501B70C8FB5F6862EA9072C5289CDC5674653BD08744FDC55C5C65`



El mismo hash está guardado en:



`android/SHA256SUMS`



\## Verificación en Windows



Desde la raíz del repositorio:



```powershell

(Get-FileHash recovery\\android\\splatt-elite-reference-debug.apk -Algorithm SHA256).Hash

```



El resultado debe ser exactamente:



```text

5960722F38501B70C8FB5F6862EA9072C5289CDC5674653BD08744FDC55C5C65

```



\## Estado de referencia



\* Rama de origen: `trazas-3-fases`

\* Commit que incorpora el APK de recuperación: `9168bd3`

\* Fecha de incorporación: `23/08/2026`



Este APK debe conservarse como referencia estable y no sustituirse sin crear previamente una nueva copia de recuperación verificable.




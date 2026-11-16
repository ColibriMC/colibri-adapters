# colibri-adapters

Adaptadores que hacen que plugins de Bukkit/Paper que usan clases internas del servidor funcionen en
[ColibriMC](https://github.com/ColibriMC). **No los instala el usuario:** ColibriMC los descarga solo a `libs/adapters/`
cuando detecta el plugin y los actualiza en cada arranque (siempre la ultima release).

| Adaptador | Plugin | Archivo de la release |
|---|---|---|
| `space.colibrimc.worldedit.ColibriAdapter` | WorldEdit 7.4.x (`BukkitImplAdapter`) | `ColibriWorldEditAdapter.jar` |

WorldEdit pide, ademas de la API de Bukkit, un adaptador por version de Paper hecho contra las clases internas del
servidor. ColibriMC no las tiene, asi que este adaptador habla directamente con Minestom: bloques con todas sus
propiedades, seleccion, `//set`, `//replace`, `//copy`, `//paste`, `//undo`, formas... Todavia no copia entidades ni el
contenido de cofres y carteles (NBT de bloques), no regenera chunks, arboles ni estructuras.

## Publicar una version

Se publica sola: cada push a `main` que compile bien crea una release nueva (`v1.0.<n>`) con
`ColibriWorldEditAdapter.jar` y `ColibriWorldEditAdapter.jar.sha1` (`.github/workflows/gradle.yml`). ColibriMC descarga
siempre la ultima, asi que un parche llega a los servidores al reiniciar.

Para hacerlo a mano: `./gradlew jar`, y subir `build/libs/ColibriWorldEditAdapter.jar` y un `.sha1` con solo su suma
(`sha1sum ColibriWorldEditAdapter.jar | cut -d' ' -f1 > ColibriWorldEditAdapter.jar.sha1`).

## Compilar

`./gradlew jar` (Java 25). No necesita ColibriMC: todo es `compileOnly`.

## Licencia

GPL-3.0. Se compila contra la API de WorldEdit, que es GPL-3, por eso este codigo vive aparte del servidor.

package care.primary.sphere360.stitch

import care.primary.sphere360.data.ShotMeta

/**
 * Une photo prête à être projetée dans l'équirectangulaire.
 *
 * @param matrix matrice 3x3 (ligne par ligne) du repère de référence vers le repère caméra
 * @param focal focale horizontale en pixels de l'image telle qu'elle sera relue
 * @param focalY focale verticale, égale à l'horizontale pour un objectif à pixels carrés
 * @param ppx centre optique en x, @param ppy centre optique en y
 * @param imageIndex position de l'image dans la liste chargée : le recalage réordonne les caméras
 *   selon la composante connexe qu'il retient, l'ordre des vues ne suit donc pas celui des images
 */
class ShotView(
    val shot: ShotMeta,
    val imageIndex: Int,
    val matrix: DoubleArray,
    val focal: Double,
    val ppx: Double,
    val ppy: Double,
    val focalY: Double = focal
)

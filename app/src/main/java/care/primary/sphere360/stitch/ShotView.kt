package care.primary.sphere360.stitch

import care.primary.sphere360.data.ShotMeta

/**
 * Une photo prête à être projetée dans l'équirectangulaire, avec le modèle complet de sa caméra :
 * orientation, focale, centre optique et distorsion.
 *
 * @param matrix matrice 3x3 (ligne par ligne) du repère de référence vers le repère caméra
 * @param focal focale horizontale en pixels de l'image telle qu'elle sera relue
 * @param focalY focale verticale, égale à l'horizontale pour un objectif à pixels carrés
 * @param ppx centre optique en x, @param ppy centre optique en y
 * @param distortion distorsion de l'objectif, appliquée entre la pente de rayon et le pixel
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
    val focalY: Double = focal,
    val distortion: LensDistortion = LensDistortion.NONE
) {

    /**
     * Direction du repère de référence → pixel image, distorsion comprise. `out` reçoit (x, y).
     *
     * @return false si la direction est derrière la caméra ou hors du domaine de l'objectif.
     */
    fun projectTo(dx: Double, dy: Double, dz: Double, out: DoubleArray): Boolean {
        val a = matrix
        val zc = a[6] * dx + a[7] * dy + a[8] * dz
        if (zc <= 1e-9) return false
        var xn = (a[0] * dx + a[1] * dy + a[2] * dz) / zc
        var yn = (a[3] * dx + a[4] * dy + a[5] * dz) / zc
        if (!distortion.identity) {
            if (!distortion.distort(xn, yn, out)) return false
            xn = out[0]; yn = out[1]
        }
        out[0] = focal * xn + ppx
        out[1] = focalY * yn + ppy
        return true
    }

    /** Pixel image → direction du repère de référence (non normalisée). */
    fun rayOf(px: Double, py: Double): DoubleArray {
        var xn = (px - ppx) / focal
        var yn = (py - ppy) / focalY
        if (!distortion.identity) {
            val t = DoubleArray(2)
            distortion.undistort(xn, yn, t)
            xn = t[0]; yn = t[1]
        }
        return PanoGeometry.applyTranspose(matrix, xn, yn, 1.0)
    }

    fun withMatrix(m: DoubleArray) = ShotView(shot, imageIndex, m, focal, ppx, ppy, focalY, distortion)
}

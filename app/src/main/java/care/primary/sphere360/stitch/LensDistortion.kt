package care.primary.sphere360.stitch

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Distorsion d'un objectif, modèle de Brown-Conrady : trois coefficients radiaux et deux
 * tangentiels, dans l'ordre d'OpenCV (k1, k2, p1, p2, k3).
 *
 * Le modèle relie deux pentes de rayon, pas deux positions en pixels :
 *
 *  - (x, y) = (X/Z, Y/Z) dans le repère caméra, c'est-à-dire la position qu'aurait le point sur un
 *    objectif rectilinéaire parfait, en unités de focale ;
 *  - (x', y') la position réellement observée, toujours en unités de focale.
 *
 * Écrit ainsi, le modèle ne dépend ni de la résolution de l'image ni de la focale estimée : une
 * photo réduite pour l'assemblage, ou une focale corrigée par le recalage, se projettent avec les
 * mêmes coefficients. C'est exactement la convention de `CameraCharacteristics.LENS_DISTORTION`
 * côté Android et de `cv::undistortPoints` côté OpenCV.
 *
 * Un grand angle en a besoin : à 110° de champ, le bord de l'image est à plus d'une focale de
 * l'axe optique, là où le terme en r² atteint plusieurs pourcents. Ignorer la distorsion y décale
 * les bords de chaque photo de dizaines de pixels, c'est-à-dire précisément là où les photos se
 * raccordent.
 *
 * @param maxRadius rayon idéal maximal traité comme valide, c'est-à-dire celui du coin du cadre.
 *   Au-delà, le polynôme finit par retomber et replierait l'image sur elle-même : les directions
 *   concernées sont déclarées hors champ plutôt que projetées n'importe où.
 */
class LensDistortion(
    val k1: Double,
    val k2: Double,
    val p1: Double,
    val p2: Double,
    val k3: Double,
    val maxRadius: Double = Double.MAX_VALUE
) {

    /** Objectif rectilinéaire : la projection peut sauter tout le calcul. */
    val identity: Boolean = k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0

    private val maxRadius2 = if (maxRadius >= Double.MAX_VALUE / 2) Double.MAX_VALUE else maxRadius * maxRadius

    fun withMaxRadius(r: Double) = LensDistortion(k1, k2, p1, p2, k3, r)

    fun coefficients(): DoubleArray = doubleArrayOf(k1, k2, p1, p2, k3)

    /**
     * Le même objectif, décrit dans l'image tournée d'un quart de tour dans le sens horaire.
     *
     * Le pilote de la caméra décrit la distorsion dans le repère du capteur, en paysage, alors que
     * les photos sont enregistrées redressées en portrait. Les trois coefficients radiaux ne
     * dépendent que de la distance à l'axe optique et ne bougent pas ; les deux coefficients
     * tangentiels, eux, décrivent un champ de déplacement orienté et tournent avec l'image. Une
     * rotation horaire d'un quart de tour envoie le point (x, y) sur (-y, x), ce qui échange les
     * deux coefficients et inverse le signe du premier.
     */
    fun rotatedClockwise(degrees: Int): LensDistortion {
        val turns = (((degrees / 90) % 4) + 4) % 4
        if (turns == 0 || (p1 == 0.0 && p2 == 0.0)) return this
        var a = p1
        var b = p2
        repeat(turns) {
            val t = a
            a = b
            b = -t
        }
        return LensDistortion(k1, k2, a, b, k3, maxRadius)
    }

    /**
     * Pente de rayon idéale → pente observée. `out` reçoit (x', y').
     *
     * @return false si la direction sort du domaine de validité du modèle.
     */
    fun distort(x: Double, y: Double, out: DoubleArray): Boolean {
        val r2 = x * x + y * y
        if (r2 > maxRadius2) return false
        if (identity) { out[0] = x; out[1] = y; return true }
        val radial = 1 + r2 * (k1 + r2 * (k2 + r2 * k3))
        out[0] = x * radial + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
        out[1] = y * radial + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
        return true
    }

    /** Rayon observé pour un rayon idéal donné : r (1 + k1r² + k2r⁴ + k3r⁶). */
    fun radialMap(r: Double): Double {
        val r2 = r * r
        return r * (1 + r2 * (k1 + r2 * (k2 + r2 * k3)))
    }

    /**
     * Rayon idéal dont la distorsion radiale produit le rayon observé, par bissection.
     *
     * L'itération par point fixe d'OpenCV, qui divise par le facteur radial, diverge dès que ce
     * facteur s'éloigne de 1 : sur un grand angle en barillet elle s'emballe précisément là où la
     * correction compte, au bord du cadre. La bissection, elle, converge toujours tant que la
     * fonction est croissante, ce que [usableUpTo] vérifie avant d'accepter un modèle.
     *
     * @return NaN si le polynôme n'atteint jamais ce rayon observé, c'est-à-dire si le modèle est
     *   incompatible avec le champ de vue annoncé.
     */
    fun idealRadius(observed: Double): Double {
        if (identity) return observed
        if (observed <= 0) return 0.0
        var hi = observed
        var guard = 0
        while (radialMap(hi) < observed) {
            hi *= 1.5
            if (hi > MAX_SEARCH_RADIUS || guard++ > 40) return Double.NaN
        }
        var lo = 0.0
        repeat(BISECTIONS) {
            val mid = 0.5 * (lo + hi)
            if (radialMap(mid) < observed) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /**
     * Pente observée → pente idéale.
     *
     * La partie radiale est inversée exactement par [idealRadius]. Les deux termes tangentiels,
     * eux, ne sont pas radiaux : on les traite comme une petite perturbation, retirée du point
     * observé avant chaque inversion radiale. Comme la partie radiale conserve la direction, la
     * direction du rayon idéal est exactement celle du point observé corrigé de la tangentielle,
     * et quelques passes suffisent à faire tomber l'erreur très en dessous du pixel.
     */
    fun undistort(xd: Double, yd: Double, out: DoubleArray) {
        if (identity) { out[0] = xd; out[1] = yd; return }
        var ux = xd
        var uy = yd
        var x = 0.0
        var y = 0.0
        val steps = if (p1 == 0.0 && p2 == 0.0) 1 else TANGENTIAL_STEPS
        for (i in 0 until steps) {
            val ru = kotlin.math.hypot(ux, uy)
            if (ru < 1e-15) {
                x = 0.0; y = 0.0
            } else {
                val r = idealRadius(ru)
                if (!r.isFinite()) { out[0] = xd; out[1] = yd; return }
                val k = r / ru
                x = ux * k
                y = uy * k
            }
            if (i + 1 < steps) {
                val r2 = x * x + y * y
                ux = xd - (2 * p1 * x * y + p2 * (r2 + 2 * x * x))
                uy = yd - (p1 * (r2 + 2 * y * y) + 2 * p2 * x * y)
            }
        }
        out[0] = x
        out[1] = y
    }

    /**
     * Le modèle est-il exploitable jusqu'au rayon idéal donné ?
     *
     * Trois garde-fous, parce que les coefficients viennent du pilote de la caméra et ne sont pas
     * toujours renseignés sérieusement : la fonction radiale doit rester strictement croissante
     * (sinon deux directions différentes tomberaient sur le même pixel), l'inversion doit être
     * exacte, et le rapport entre rayon observé et rayon idéal doit rester dans des proportions
     * d'objectif photo.
     */
    fun usableUpTo(radius: Double): Boolean {
        if (identity) return true
        if (!radius.isFinite() || radius <= 0) return false
        val steps = 64
        var previous = 0.0
        val out = DoubleArray(2)
        val back = DoubleArray(2)
        for (i in 1..steps) {
            val r = radius * i / steps
            if (!distort(r, 0.0, out)) return false
            val mapped = out[0]
            if (!mapped.isFinite() || mapped <= previous) return false
            previous = mapped
            undistort(out[0], out[1], back)
            if (abs(back[0] - r) > 1e-6 * radius) return false
        }
        // Un grand angle très corrigé reste plausible ; un rapport extravagant ne l'est pas.
        val ratio = previous / radius
        return ratio > 0.3 && ratio < 3.0
    }

    /**
     * Le même modèle borné au cadre, ou null s'il n'y est pas exploitable.
     *
     * Le champ de vue annoncé donne le rayon **observé** au coin du cadre : c'est la demi-largeur
     * de l'image divisée par la focale, donc une grandeur mesurée sur le capteur. Le domaine du
     * modèle, lui, s'exprime en rayon **idéal** : il faut donc inverser une fois la distorsion
     * pour savoir jusqu'où le modèle doit rester valable. Confondre les deux sous-estime le
     * domaine d'un objectif en barillet et fait rejeter des modèles corrects.
     */
    fun boundedTo(hfovDeg: Double, vfovDeg: Double): LensDistortion? {
        if (identity) return null
        val ideal = idealRadius(cornerRadius(hfovDeg, vfovDeg))
        if (!ideal.isFinite() || ideal <= 0) return null
        val limited = withMaxRadius(ideal * 1.05)
        return if (limited.usableUpTo(ideal)) limited else null
    }

    companion object {
        /**
         * Passes de correction tangentielle. Chaque passe divise l'erreur par une centaine :
         * partant d'un écart de l'ordre du centième de focale au coin d'un grand angle, cinq
         * passes la ramènent au milliardième, soit un dix-millième de pixel.
         */
        private const val TANGENTIAL_STEPS = 5

        /** Itérations de bissection : 2⁻⁴⁰ de l'intervalle, très en dessous du pixel. */
        private const val BISECTIONS = 40

        /** Au-delà, aucun objectif photo n'a de sens : le modèle est déclaré incompatible. */
        private const val MAX_SEARCH_RADIUS = 8.0

        val NONE = LensDistortion(0.0, 0.0, 0.0, 0.0, 0.0)

        /** Coefficients dans l'ordre d'OpenCV, tolérant aux tableaux vides ou nuls. */
        fun fromOpenCvOrder(c: DoubleArray?): LensDistortion {
            if (c == null || c.size < 5) return NONE
            if (c.any { !it.isFinite() }) return NONE
            if (c.all { abs(it) < 1e-6 }) return NONE
            return LensDistortion(c[0], c[1], c[2], c[3], c[4])
        }

        /**
         * Conversion depuis `CameraCharacteristics.LENS_DISTORTION`, qui range ses coefficients
         * dans l'ordre [κ1, κ2, κ3, κ4, κ5] et écrit le modèle
         *
         *   x' = x (1 + κ1 r² + κ2 r⁴ + κ3 r⁶) + κ4 (2xy) + κ5 (r² + 2x²)
         *   y' = y (1 + κ1 r² + κ2 r⁴ + κ3 r⁶) + κ5 (2xy) + κ4 (r² + 2y²)
         *
         * soit, terme à terme avec la convention d'OpenCV, k1 = κ1, k2 = κ2, k3 = κ3, p1 = κ4 et
         * p2 = κ5. L'ordre des deux coefficients tangentiels diffère donc entre les deux API.
         */
        fun fromAndroid(k: FloatArray?): LensDistortion {
            if (k == null || k.size < 5) return NONE
            return fromOpenCvOrder(doubleArrayOf(
                k[0].toDouble(), k[1].toDouble(), k[3].toDouble(), k[4].toDouble(), k[2].toDouble()
            ))
        }

        /**
         * Rayon **observé** au coin du cadre, en unités de focale : tan(champ diagonal / 2).
         * C'est la demi-diagonale de l'image divisée par la focale, donc une grandeur mesurée sur
         * l'image distordue, pas dans le repère idéal.
         */
        fun cornerRadius(hfovDeg: Double, vfovDeg: Double): Double {
            val th = kotlin.math.tan(hfovDeg * Math.PI / 360)
            val tv = kotlin.math.tan(vfovDeg * Math.PI / 360)
            return sqrt(th * th + tv * tv)
        }
    }
}

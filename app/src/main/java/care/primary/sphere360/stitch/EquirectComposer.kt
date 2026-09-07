package care.primary.sphere360.stitch

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Canevas équirectangulaire en cours de composition : BGR entrelacé + masque de couverture. */
class ComposedCanvas(val bgr: ByteArray, val mask: ByteArray, val width: Int, val height: Int, val usedShots: Int) {
    /**
     * Part de la sphère effectivement couverte par des photos, en angle solide.
     *
     * Compter les pixels du canevas donnerait une valeur trompeuse : les lignes proches des pôles
     * occupent autant de pixels que celles de l'équateur alors qu'elles représentent une portion
     * de sphère bien plus petite. Une capture normale, qui ne photographie ni le zénith ni le
     * nadir, couvre ainsi environ 96 % de la sphère mais seulement 83 % des pixels.
     */
    fun coverage(): Double {
        var covered = 0.0
        var total = 0.0
        for (row in 0 until height) {
            val w = cos(PanoGeometry.pitchOfRow(row, height))
            total += w * width
            var n = 0
            val base = row * width
            for (x in 0 until width) if (mask[base + x] != 0.toByte()) n++
            covered += w * n
        }
        return if (total <= 0) 0.0 else covered / total
    }
}

/** Fournit les images à la demande : déjà en mémoire, ou décodées à la volée. */
interface ImageSource {
    val size: Int
    fun get(index: Int): Mat?
    fun release(index: Int)
}

/**
 * Projette un ensemble de photos dans un canevas équirectangulaire à partir de leurs rotations et
 * de leur focale, quelle que soit la provenance de ces paramètres (capteurs ou recalage OpenCV).
 *
 * ## Comment les recouvrements sont traités
 *
 * Moyenner les photos qui se recouvrent produit un dédoublement translucide très visible : la
 * grille de capture prévoit 40 % de recouvrement horizontal et les rangées se chevauchent sur une
 * vingtaine de degrés, si bien qu'un pixel peut recevoir trois ou quatre photos à poids égal. Le
 * moindre écart entre elles, parallaxe due à un déplacement de l'utilisateur ou dérive de quelques
 * degrés du gyroscope, se lit alors comme une double exposition.
 *
 * La composition sépare donc chaque photo en deux échelles, dans l'esprit d'un mélange multi-bandes :
 *
 *  - les **fonds** (structures plus larges que [blurFraction] de la sphère) sont moyennés avec un
 *    poids doux sur tout le recouvrement. Les écarts d'exposition et le vignetage se diluent
 *    progressivement, sans marche à la jointure, et un dédoublement à cette échelle ne se voit pas ;
 *  - les **détails** viennent de la photo qui regarde le pixel le plus près de son centre, avec un
 *    fondu court vers la deuxième mieux centrée autour de leur frontière. Les contours restent donc
 *    nets et uniques, et la parallaxe résiduelle se lit comme un léger décalage local plutôt que
 *    comme une image fantôme.
 *
 * ## Découpage du calcul
 *
 * La bande des fonds est calculée une seule fois par photo, à un huitième de la résolution : à son
 * échelle la réduction est invisible et le coût divisé par soixante-quatre. Elle est ensuite cumulée
 * dans un canevas réduit, lui aussi global. Seuls les détails demandent la pleine résolution ; ils
 * sont traités par bandes de colonnes pour tenir dans la mémoire d'un téléphone.
 */
class EquirectComposer(
    private val progress: (StitchJobs.Stage, Int) -> Unit,
    /**
     * Douceur des raccords. La valeur par défaut est celle mesurée comme le meilleur compromis sur
     * le banc de test ; l'écran de retouche permet de l'ajuster quand une pièce s'y prête mal.
     */
    private val blend: BlendScale = BlendScale.BALANCED
) {

    companion object {
        private const val BAND_WIDTH = 512

        /** Facteur de réduction de la bande des fonds. */
        private const val LOW_SHRINK = 8

        private const val MIN_WEIGHT = 1e-3f
    }

    /**
     * Échelle de séparation des deux bandes, en fraction de la largeur du canevas.
     *
     * Tout ce qui est plus fin vient d'une seule photo, tout ce qui est plus large est moyenné.
     * Trop fin, le dédoublement reste visible sur les structures moyennes ; trop large, les écarts
     * d'exposition entre photos ne sont plus dilués et laissent une marche à la jointure. Un
     * quarante-huitième de la largeur, soit environ 85 pixels sur un canevas de 4096, s'est révélé
     * le meilleur compromis sur le banc de test.
     */
    private val blurFraction: Double get() = blend.blurFraction

    /**
     * Largeur du fondu des détails entre les deux photos les mieux centrées, exprimée en écart de
     * poids de sélection. Un basculement franc laisserait une marche visible là où la parallaxe
     * décale les deux photos ; un fondu étroit la transforme en dégradé de quelques dizaines de
     * pixels, sans ramener le dédoublement d'une moyenne large.
     */
    private val detailCrossfade: Float get() = blend.detailCrossfade

    /** Tampon de projection réutilisé : la composition est séquentielle. */
    private val scratch = DoubleArray(2)

    /** Bande basse fréquence d'une photo, en coordonnées canevas réduites et repérée sur l'empreinte. */
    private class LowMap(val values: FloatArray, val valid: FloatArray, val w: Int, val h: Int,
                         val colStart: Int, val rowStart: Int)

    fun compose(views: List<ShotView>, source: ImageSource, targetWidth: Int, progressFrom: Int, progressTo: Int): ComposedCanvas {
        if (views.isEmpty()) throw StitchException(StitchException.Kind.NEED_MORE, "aucune photo à projeter")
        val canvasW = (targetWidth / 2) * 2
        val canvasH = canvasW / 2

        var imgW = 0
        var imgH = 0
        for (v in views) {
            val m = source.get(v.imageIndex) ?: continue
            imgW = m.cols(); imgH = m.rows()
            source.release(v.imageIndex)
            break
        }
        if (imgW == 0) throw StitchException(StitchException.Kind.NEED_MORE, "aucune photo lisible")

        val prints = views.map { PanoGeometry.footprint(it, imgW, imgH, canvasW, canvasH) }

        // Images de référence remappées avec chaque photo : le poids de sélection, qui décroît
        // strictement du centre vers le bord, et un masque de validité.
        val selWeight = PanoGeometry.centreWeight(imgW, imgH)
        val selPtr = FloatPointer(selWeight.size.toLong()).apply { put(selWeight, 0, selWeight.size) }
        val selMat = Mat(imgH, imgW, opencv_core.CV_32F, selPtr)
        val onesPtr = FloatPointer((imgW * imgH).toLong()).apply {
            put(FloatArray(imgW * imgH) { 1f }, 0, imgW * imgH)
        }
        val onesMat = Mat(imgH, imgW, opencv_core.CV_32F, onesPtr)

        val trig = Trig(canvasW, canvasH)

        try {
            // ---- 1. Bande des fonds, par photo puis cumulée dans un canevas réduit ----
            val lowMaps = ArrayList<LowMap?>(views.size)
            for (i in views.indices) {
                val img = source.get(views[i].imageIndex)
                if (img == null || img.cols() != imgW || img.rows() != imgH) {
                    lowMaps.add(null)
                    if (img != null) source.release(views[i].imageIndex)
                    continue
                }
                try {
                    lowMaps.add(buildLowMap(img, onesMat, views[i], prints[i], trig, canvasW * blurFraction))
                } finally {
                    source.release(views[i].imageIndex)
                }
            }
            progress(StitchJobs.Stage.COMPOSE, progressFrom + (progressTo - progressFrom) / 6)
            val lowCanvas = blendLowBand(views, prints, lowMaps, canvasW / LOW_SHRINK, canvasH / LOW_SHRINK, trig, imgW, imgH)

            // ---- 2. Détails, à pleine résolution, par bandes de colonnes ----
            val bgr = ByteArray(canvasW * canvasH * 3)
            val mask = ByteArray(canvasW * canvasH)
            val projected = BooleanArray(views.size)
            val bands = (canvasW + BAND_WIDTH - 1) / BAND_WIDTH
            for (band in 0 until bands) {
                val b0 = band * BAND_WIDTH
                val bw = min(BAND_WIDTH, canvasW - b0)
                val n = bw * canvasH
                val high1 = FloatArray(n * 3)
                val high2 = FloatArray(n * 3)
                val sel1 = FloatArray(n)
                val sel2 = FloatArray(n)
                for (i in views.indices) {
                    val low = lowMaps[i] ?: continue
                    val fp = prints[i]
                    var c0 = -1
                    var c1 = -1
                    for (c in b0 until b0 + bw) if (fp.cols[c]) { if (c0 < 0) c0 = c; c1 = c }
                    if (c0 < 0) continue
                    val img = source.get(views[i].imageIndex) ?: continue
                    try {
                        if (img.cols() != imgW || img.rows() != imgH) continue
                        accumulateDetails(img, selMat, onesMat, views[i], low, c0, c1, fp.rowMin, fp.rowMax,
                            b0, bw, trig, high1, high2, sel1, sel2)
                        projected[i] = true
                    } finally {
                        source.release(views[i].imageIndex)
                    }
                }
                resolveBand(bgr, mask, lowCanvas, high1, high2, sel1, sel2, b0, bw, canvasW, canvasH)
                progress(StitchJobs.Stage.COMPOSE,
                    progressFrom + (progressTo - progressFrom) * (1 + 5 * (band + 1) / bands) / 6)
            }
            val used = projected.count { it }
            if (used == 0) throw StitchException(StitchException.Kind.NEED_MORE, "aucune photo projetable")
            return ComposedCanvas(bgr, mask, canvasW, canvasH, used)
        } finally {
            selMat.close(); selPtr.close(); onesMat.close(); onesPtr.close()
        }
    }

    /** Tables trigonométriques du canevas : le noyau de reprojection reste en arithmétique pure. */
    private class Trig(canvasW: Int, canvasH: Int) {
        val sinYaw = DoubleArray(canvasW)
        val cosYaw = DoubleArray(canvasW)
        val sinPitch = DoubleArray(canvasH)
        val cosPitch = DoubleArray(canvasH)
        val width = canvasW
        val height = canvasH

        init {
            for (c in 0 until canvasW) {
                val y = PanoGeometry.yawOfCol(c, canvasW); sinYaw[c] = sin(y); cosYaw[c] = cos(y)
            }
            for (r in 0 until canvasH) {
                val p = PanoGeometry.pitchOfRow(r, canvasH); sinPitch[r] = sin(p); cosPitch[r] = cos(p)
            }
        }
    }

    /**
     * Reprojette une photo à un huitième de la résolution sur toute son empreinte, puis en extrait
     * la bande basse fréquence par convolution normalisée : on floute la tuile multipliée par son
     * masque de validité et le masque seul, puis on divise. Les pixels hors champ n'entrent donc
     * pas dans la moyenne et n'assombrissent pas les bords.
     */
    private fun buildLowMap(img: Mat, onesMat: Mat, view: ShotView, fp: PanoGeometry.Footprint,
                            trig: Trig, sigma: Double): LowMap? {
        val lw = max(2, fp.colSpan / LOW_SHRINK + 1)
        val lh = max(2, (fp.rowMax - fp.rowMin + 1) / LOW_SHRINK + 1)
        val n = lw * lh
        val mx = FloatArray(n)
        val my = FloatArray(n)
        for (j in 0 until lh) {
            val row = min(trig.height - 1, fp.rowMin + j * LOW_SHRINK)
            val sp = trig.sinPitch[row]
            val cp = trig.cosPitch[row]
            for (i in 0 until lw) {
                val col = ((fp.colStart + i * LOW_SHRINK) % trig.width + trig.width) % trig.width
                project(view, trig.sinYaw[col] * cp, trig.cosYaw[col] * cp, sp, mx, my, j * lw + i)
            }
        }
        val mxPtr = FloatPointer(n.toLong()).apply { put(mx, 0, n) }
        val myPtr = FloatPointer(n.toLong()).apply { put(my, 0, n) }
        val mapX = Mat(lh, lw, opencv_core.CV_32F, mxPtr)
        val mapY = Mat(lh, lw, opencv_core.CV_32F, myPtr)
        val tilePtr = BytePointer((n * 3).toLong())
        val tile = Mat(lh, lw, opencv_core.CV_8UC3, tilePtr)
        val validPtr = FloatPointer(n.toLong())
        val validTile = Mat(lh, lw, opencv_core.CV_32F, validPtr)
        val premulPtr = FloatPointer((n * 3).toLong())
        val premul = Mat(lh, lw, opencv_core.CV_32FC3, premulPtr)
        val blurNum = Mat()
        val blurDen = Mat()
        try {
            val black = Scalar.all(0.0)
            opencv_imgproc.remap(img, tile, mapX, mapY, opencv_imgproc.INTER_AREA, opencv_core.BORDER_CONSTANT, black)
            opencv_imgproc.remap(onesMat, validTile, mapX, mapY, opencv_imgproc.INTER_LINEAR, opencv_core.BORDER_CONSTANT, black)

            val pix = ByteArray(n * 3)
            val valid = FloatArray(n)
            tilePtr.get(pix, 0, pix.size)
            validPtr.get(valid, 0, n)
            val buf = FloatArray(n * 3)
            for (i in 0 until n) {
                val v = valid[i]
                val o = i * 3
                buf[o] = (pix[o].toInt() and 0xFF) * v
                buf[o + 1] = (pix[o + 1].toInt() and 0xFF) * v
                buf[o + 2] = (pix[o + 2].toInt() and 0xFF) * v
            }
            premulPtr.put(buf, 0, buf.size)

            // Numérateur et dénominateur subissent exactement le même flou : les effets de bord se
            // compensent à la division.
            val smallSigma = max(1.0, sigma / LOW_SHRINK)
            val k = 2 * (3 * smallSigma).roundToInt() + 1
            val kernel = Size(k, k)
            opencv_imgproc.GaussianBlur(premul, blurNum, kernel, smallSigma)
            opencv_imgproc.GaussianBlur(validTile, blurDen, kernel, smallSigma)

            val num = FloatArray(n * 3)
            val den = FloatArray(n)
            FloatPointer(blurNum.data()).get(num, 0, num.size)
            FloatPointer(blurDen.data()).get(den, 0, den.size)
            val values = FloatArray(n * 3)
            for (i in 0 until n) {
                val d = den[i]
                val o = i * 3
                if (d > 1e-3f) {
                    values[o] = num[o] / d
                    values[o + 1] = num[o + 1] / d
                    values[o + 2] = num[o + 2] / d
                } else {
                    values[o] = (pix[o].toInt() and 0xFF).toFloat()
                    values[o + 1] = (pix[o + 1].toInt() and 0xFF).toFloat()
                    values[o + 2] = (pix[o + 2].toInt() and 0xFF).toFloat()
                }
            }
            return LowMap(values, valid, lw, lh, fp.colStart, fp.rowMin)
        } finally {
            tile.close(); validTile.close(); premul.close(); mapX.close(); mapY.close()
            blurNum.close(); blurDen.close()
            tilePtr.close(); validPtr.close(); premulPtr.close(); mxPtr.close(); myPtr.close()
        }
    }

    /**
     * Cumule les bandes basse fréquence dans un canevas réduit, pondérées par le centrage de chaque
     * photo. Le poids est recalculé par projection plutôt que lu dans la carte réduite : à cette
     * résolution la validité interpolée est trop grossière au ras des bords d'empreinte, et une
     * cellule laissée vide se lirait comme une tache noire dans le résultat final.
     */
    private fun blendLowBand(views: List<ShotView>, prints: List<PanoGeometry.Footprint>,
                             lowMaps: List<LowMap?>, lowW: Int, lowH: Int, trig: Trig,
                             imgW: Int, imgH: Int): FloatArray {
        val canvasW = trig.width
        val out = FloatArray(lowW * lowH * 3)
        val weight = FloatArray(lowW * lowH)
        for (i in views.indices) {
            val low = lowMaps[i] ?: continue
            val fp = prints[i]
            val view = views[i]
            for (row in 0 until lowH) {
                val canvasRow = min(trig.height - 1, row * LOW_SHRINK)
                if (canvasRow < fp.rowMin || canvasRow > fp.rowMax) continue
                val sp = trig.sinPitch[canvasRow]
                val cp = trig.cosPitch[canvasRow]
                for (col in 0 until lowW) {
                    val canvasCol = min(canvasW - 1, col * LOW_SHRINK)
                    if (!fp.cols[canvasCol]) continue
                    val sel = PanoGeometry.centreWeightAt(view, trig.sinYaw[canvasCol] * cp,
                        trig.cosYaw[canvasCol] * cp, sp, imgW, imgH, scratch)
                    val w = smooth(sel)
                    if (w <= MIN_WEIGHT) continue
                    val u = unwrappedOffset(canvasCol, fp.colStart, canvasW).toFloat() / LOW_SHRINK
                    val v = (canvasRow - low.rowStart).toFloat() / LOW_SHRINK
                    val s = sampleLow(low, u, v)
                    val o = (row * lowW + col) * 3
                    out[o] += s[0] * w
                    out[o + 1] += s[1] * w
                    out[o + 2] += s[2] * w
                    weight[row * lowW + col] += w
                }
            }
        }
        val covered = BooleanArray(lowW * lowH)
        for (i in 0 until lowW * lowH) {
            val w = weight[i]
            if (w <= MIN_WEIGHT) continue
            covered[i] = true
            val o = i * 3
            out[o] /= w; out[o + 1] /= w; out[o + 2] /= w
        }
        fillHoles(out, covered, lowW, lowH)
        return out
    }

    /**
     * Bouche les cellules restées vides du canevas réduit en propageant leurs voisines. Sans cela
     * les quelques cellules situées juste au-delà de la dernière photo, sur le bord des calottes
     * polaires, resteraient noires et l'extrapolation des pôles partirait de cette couleur.
     */
    private fun fillHoles(data: FloatArray, covered: BooleanArray, w: Int, h: Int) {
        var remaining = covered.count { !it }
        if (remaining == 0 || remaining == covered.size) return
        var guard = 0
        while (remaining > 0 && guard++ < w + h) {
            val added = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (covered[i]) continue
                var sum0 = 0f; var sum1 = 0f; var sum2 = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    val nx = ((x + dx) % w + w) % w
                    val j = ny * w + nx
                    if (!covered[j]) continue
                    sum0 += data[j * 3]; sum1 += data[j * 3 + 1]; sum2 += data[j * 3 + 2]; n++
                }
                if (n == 0) continue
                data[i * 3] = sum0 / n; data[i * 3 + 1] = sum1 / n; data[i * 3 + 2] = sum2 / n
                added.add(i)
            }
            if (added.isEmpty()) break
            for (i in added) covered[i] = true
            remaining -= added.size
        }
    }

    /**
     * Reprojette une photo à pleine résolution sur la portion de bande qu'elle couvre et retient
     * ses détails, c'est-à-dire l'écart à sa propre bande basse fréquence, là où elle figure parmi
     * les deux photos les mieux centrées.
     */
    private fun accumulateDetails(
        img: Mat, selMat: Mat, onesMat: Mat, view: ShotView, low: LowMap,
        c0: Int, c1: Int, rowMin: Int, rowMax: Int, bandStart: Int, bandWidth: Int, trig: Trig,
        high1: FloatArray, high2: FloatArray, sel1: FloatArray, sel2: FloatArray
    ) {
        val tw = c1 - c0 + 1
        val th = rowMax - rowMin + 1
        if (tw <= 0 || th <= 0) return
        val n = tw * th
        val mx = FloatArray(n)
        val my = FloatArray(n)
        for (r in 0 until th) {
            val sp = trig.sinPitch[rowMin + r]
            val cp = trig.cosPitch[rowMin + r]
            val base = r * tw
            for (c in 0 until tw) {
                val col = c0 + c
                project(view, trig.sinYaw[col] * cp, trig.cosYaw[col] * cp, sp, mx, my, base + c)
            }
        }

        val mxPtr = FloatPointer(n.toLong()).apply { put(mx, 0, n) }
        val myPtr = FloatPointer(n.toLong()).apply { put(my, 0, n) }
        val mapX = Mat(th, tw, opencv_core.CV_32F, mxPtr)
        val mapY = Mat(th, tw, opencv_core.CV_32F, myPtr)
        val tilePtr = BytePointer((n * 3).toLong())
        val tile = Mat(th, tw, opencv_core.CV_8UC3, tilePtr)
        val selPtr = FloatPointer(n.toLong())
        val selTile = Mat(th, tw, opencv_core.CV_32F, selPtr)
        val validPtr = FloatPointer(n.toLong())
        val validTile = Mat(th, tw, opencv_core.CV_32F, validPtr)
        try {
            val black = Scalar.all(0.0)
            opencv_imgproc.remap(img, tile, mapX, mapY, opencv_imgproc.INTER_LINEAR, opencv_core.BORDER_CONSTANT, black)
            opencv_imgproc.remap(selMat, selTile, mapX, mapY, opencv_imgproc.INTER_LINEAR, opencv_core.BORDER_CONSTANT, black)
            opencv_imgproc.remap(onesMat, validTile, mapX, mapY, opencv_imgproc.INTER_LINEAR, opencv_core.BORDER_CONSTANT, black)

            val pix = ByteArray(n * 3)
            val sel = FloatArray(n)
            val valid = FloatArray(n)
            tilePtr.get(pix, 0, pix.size)
            selPtr.get(sel, 0, n)
            validPtr.get(valid, 0, n)

            val canvasW = trig.width
            for (r in 0 until th) {
                val srcRow = r * tw
                val dstRow = (rowMin + r) * bandWidth + (c0 - bandStart)
                val v = (rowMin + r - low.rowStart).toFloat() / LOW_SHRINK
                for (c in 0 until tw) {
                    val s = srcRow + c
                    if (valid[s] < 0.99f || sel[s] <= MIN_WEIGHT) continue
                    val u = unwrappedOffset(c0 + c, low.colStart, canvasW).toFloat() / LOW_SHRINK
                    val base = sampleLow(low, u, v)
                    val d = dstRow + c
                    val s3 = s * 3
                    val d3 = d * 3
                    val hr = (pix[s3].toInt() and 0xFF) - base[0]
                    val hg = (pix[s3 + 1].toInt() and 0xFF) - base[1]
                    val hb = (pix[s3 + 2].toInt() and 0xFF) - base[2]
                    if (sel[s] > sel1[d]) {
                        sel2[d] = sel1[d]
                        high2[d3] = high1[d3]; high2[d3 + 1] = high1[d3 + 1]; high2[d3 + 2] = high1[d3 + 2]
                        sel1[d] = sel[s]
                        high1[d3] = hr; high1[d3 + 1] = hg; high1[d3 + 2] = hb
                    } else if (sel[s] > sel2[d]) {
                        sel2[d] = sel[s]
                        high2[d3] = hr; high2[d3 + 1] = hg; high2[d3 + 2] = hb
                    }
                }
            }
        } finally {
            tile.close(); selTile.close(); validTile.close(); mapX.close(); mapY.close()
            tilePtr.close(); selPtr.close(); validPtr.close(); mxPtr.close(); myPtr.close()
        }
    }

    /** Somme des deux bandes : fond interpolé depuis le canevas réduit, détails fondus. */
    private fun resolveBand(bgr: ByteArray, mask: ByteArray, lowCanvas: FloatArray,
                            high1: FloatArray, high2: FloatArray, sel1: FloatArray, sel2: FloatArray,
                            bandStart: Int, bandWidth: Int, canvasW: Int, canvasH: Int) {
        val lowW = canvasW / LOW_SHRINK
        val lowH = canvasH / LOW_SHRINK
        for (r in 0 until canvasH) {
            val rowOff = r * bandWidth
            val dstRow = r * canvasW + bandStart
            val fy = (r.toFloat() / LOW_SHRINK).coerceIn(0f, (lowH - 1).toFloat())
            for (x in 0 until bandWidth) {
                val p = rowOff + x
                if (sel1[p] <= MIN_WEIGHT) continue
                val fx = ((bandStart + x).toFloat() / LOW_SHRINK).coerceIn(0f, (lowW - 1).toFloat())
                val base = bilinear(lowCanvas, lowW, lowH, fx, fy)
                // Poids du fondu : moitié-moitié sur la frontière entre les deux photos les mieux
                // centrées, source unique dès que l'écart de centrage dépasse la largeur de fondu.
                val t = ((sel1[p] - sel2[p]) / detailCrossfade).coerceIn(0f, 1f)
                val w1 = if (sel2[p] <= 0f) 1f else 0.5f + 0.5f * smooth(t)
                val w2 = 1f - w1
                val o = p * 3
                val d = (dstRow + x) * 3
                bgr[d] = clamp(base[0] + high1[o] * w1 + high2[o] * w2)
                bgr[d + 1] = clamp(base[1] + high1[o + 1] * w1 + high2[o + 1] * w2)
                bgr[d + 2] = clamp(base[2] + high1[o + 2] * w1 + high2[o + 2] * w2)
                mask[dstRow + x] = 1
            }
        }
    }

    /**
     * Remplit la table de remappage pour une direction. Une direction hors champ, derrière la
     * caméra ou hors du domaine de l'objectif, reçoit une coordonnée négative : `cv::remap` la
     * traite alors comme un bord et la laisse noire, et le masque de validité l'exclut.
     */
    private fun project(view: ShotView, dx: Double, dy: Double, dz: Double,
                        mx: FloatArray, my: FloatArray, index: Int) {
        if (!view.projectTo(dx, dy, dz, scratch)) { mx[index] = -2f; my[index] = -2f; return }
        mx[index] = scratch[0].toFloat()
        my[index] = scratch[1].toFloat()
    }

    /** Écart en colonnes entre une colonne du canevas et l'origine d'une empreinte, couture résolue. */
    private fun unwrappedOffset(col: Int, colStart: Int, canvasW: Int): Int {
        var d = col - colStart
        while (d < 0) d += canvasW
        while (d >= canvasW) d -= canvasW
        return d
    }

    private fun sampleLow(low: LowMap, u: Float, v: Float): FloatArray =
        bilinear(low.values, low.w, low.h, u.coerceIn(0f, (low.w - 1).toFloat()), v.coerceIn(0f, (low.h - 1).toFloat()))

    private fun bilinear(data: FloatArray, w: Int, h: Int, x: Float, y: Float): FloatArray {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        val out = FloatArray(3)
        for (c in 0 until 3) {
            val a = data[(y0 * w + x0) * 3 + c] * (1 - fx) + data[(y0 * w + x1) * 3 + c] * fx
            val b = data[(y1 * w + x0) * 3 + c] * (1 - fx) + data[(y1 * w + x1) * 3 + c] * fx
            out[c] = a * (1 - fy) + b * fy
        }
        return out
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun clamp(v: Float): Byte = v.roundToInt().coerceIn(0, 255).toByte()
}

package care.primary.sphere360.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toolbar
import care.primary.sphere360.App
import care.primary.sphere360.R
import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.data.TourStore
import care.primary.sphere360.stitch.BlendScale
import care.primary.sphere360.stitch.StitchMode
import care.primary.sphere360.stitch.StitchOptions
import care.primary.sphere360.stitch.StitchService
import care.primary.sphere360.util.Thumbs
import care.primary.sphere360.util.dpi
import care.primary.sphere360.util.padBottomWithNavBar
import care.primary.sphere360.util.padTopWithStatusBar
import care.primary.sphere360.util.toast
import java.io.File

/**
 * Réassemblage d'une sphère existante avec d'autres réglages.
 *
 * L'assemblage conserve les photos de la capture, réduites à la résolution qu'il utilise : une
 * sphère peut donc être refaite autant de fois qu'on veut, des mois plus tard, sans rien
 * recapturer. C'est ce qui rend cet écran possible, et c'est aussi sa limite — on ne retrouvera
 * pas de détail que la capture n'a pas enregistré.
 *
 * Les trois leviers proposés sont ceux qui ont réellement changé quelque chose sur le banc de
 * test : la méthode d'alignement, la résolution de sortie, et la douceur des raccords. S'y ajoute
 * l'exclusion d'une photo, qui est le seul remède à une prise ratée au milieu d'une bonne capture.
 */
class RetouchActivity : Activity() {

    companion object {
        private const val EXTRA_SPHERE = "sphere"
        private const val COLUMNS = 4

        fun start(context: Context, sphereId: String) {
            context.startActivity(Intent(context, RetouchActivity::class.java).putExtra(EXTRA_SPHERE, sphereId))
        }
    }

    private lateinit var store: TourStore
    private lateinit var sphere: Sphere
    private lateinit var session: CaptureSessionMeta
    private lateinit var shotsContainer: LinearLayout
    private lateinit var shotsCount: TextView
    private lateinit var groupWidth: RadioGroup

    private val excluded = LinkedHashSet<String>()
    private var available: List<String> = emptyList()

    /** Boutons de résolution, indexés par largeur : les identifiants de vue sont générés. */
    private val widthButtons = LinkedHashMap<Int, RadioButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = App.store
        val sphereId = intent.getStringExtra(EXTRA_SPHERE) ?: ""
        val s = store.get(sphereId)
        val meta = if (s != null) store.loadSession(s.sessionId) else null
        if (s == null || meta == null || !store.hasSessionImages(s.sessionId)) {
            toast(getString(R.string.retouch_no_session))
            finish()
            return
        }
        sphere = s
        session = meta

        setContentView(R.layout.activity_retouch)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.padTopWithStatusBar()
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = sphere.name
        findViewById<View>(R.id.bottom_bar).padBottomWithNavBar()
        shotsContainer = findViewById(R.id.shots_container)
        shotsCount = findViewById(R.id.shots_count)
        groupWidth = findViewById(R.id.group_width)

        val dir = store.sessionDir(session.id)
        available = session.shots.map { it.file }.filter { File(dir, it).exists() }

        buildWidthChoices()
        // Les réglages du dernier assemblage servent de point de départ : on retouche par petits
        // pas, en comparant, plutôt qu'en repartant de zéro à chaque essai.
        applyOptions(StitchOptions.fromJson(session.options))
        buildShotGrid(dir)
        findViewById<Button>(R.id.btn_run).setOnClickListener { run() }
    }

    private fun buildWidthChoices() {
        for (w in StitchOptions.WIDTHS) {
            val b = RadioButton(this)
            b.id = View.generateViewId()
            b.text = getString(R.string.retouch_width, w, w / 2)
            b.setTextAppearance(R.style.Text_Body)
            groupWidth.addView(b, RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
            widthButtons[w] = b
        }
    }

    private fun selectedWidth(): Int {
        val checked = groupWidth.checkedRadioButtonId
        return widthButtons.entries.firstOrNull { it.value.id == checked }?.key ?: StitchOptions.WIDTHS[0]
    }

    private fun applyOptions(o: StitchOptions) {
        findViewById<RadioGroup>(R.id.group_align).check(
            if (o.mode == StitchMode.REFINE) R.id.align_features else R.id.align_sensors)
        val button = widthButtons[o.targetWidth] ?: widthButtons[StitchOptions.WIDTHS[0]]
        if (button != null) groupWidth.check(button.id)
        findViewById<RadioGroup>(R.id.group_blend).check(when (o.blend) {
            BlendScale.SOFT -> R.id.blend_soft
            BlendScale.CRISP -> R.id.blend_crisp
            BlendScale.BALANCED -> R.id.blend_balanced
        })
        excluded.clear()
        excluded.addAll(o.excluded.filter { it in available })
    }

    /** Grille de vignettes des photos de la session, quatre par ligne, montées à la main. */
    private fun buildShotGrid(dir: File) {
        shotsContainer.removeAllViews()
        val gap = dpi(6)
        var row: LinearLayout? = null
        for ((index, file) in available.withIndex()) {
            if (index % COLUMNS == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (index > 0) lp.topMargin = gap
                shotsContainer.addView(row, lp)
            }
            val cell = LayoutInflater.from(this).inflate(R.layout.item_shot, row, false)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index % COLUMNS > 0) lp.marginStart = gap
            row!!.addView(cell, lp)
            bindShot(cell, dir, file)
        }
        // Une ligne incomplète doit garder ses colonnes à la bonne largeur.
        val missing = (COLUMNS - available.size % COLUMNS) % COLUMNS
        for (i in 0 until missing) {
            val filler = View(this)
            val lp = LinearLayout.LayoutParams(0, 1, 1f)
            lp.marginStart = gap
            row?.addView(filler, lp)
        }
        updateCount()
    }

    private fun bindShot(cell: View, dir: File, file: String) {
        val thumb = cell.findViewById<care.primary.sphere360.util.AspectRatioImageView>(R.id.thumb)
        // Les photos sont en portrait : la vignette suit, sinon elle recadre sur un bandeau.
        thumb.ratio = if (session.camera.width in 1 until session.camera.height)
            session.camera.height.toFloat() / session.camera.width else 3f / 4f
        Thumbs.load(thumb, File(dir, file), "shot-${session.id}-$file", 240)
        cell.setOnClickListener {
            if (file in excluded) excluded.remove(file) else excluded.add(file)
            renderShotState(cell, file)
            updateCount()
        }
        renderShotState(cell, file)
    }

    private fun renderShotState(cell: View, file: String) {
        val off = file in excluded
        cell.findViewById<View>(R.id.veil).visibility = if (off) View.VISIBLE else View.GONE
        cell.findViewById<View>(R.id.state).visibility = if (off) View.VISIBLE else View.GONE
    }

    private fun updateCount() {
        shotsCount.text = getString(R.string.retouch_shots_count, available.size - excluded.size, available.size)
    }

    private fun currentOptions(): StitchOptions = StitchOptions(
        mode = if (findViewById<RadioGroup>(R.id.group_align).checkedRadioButtonId == R.id.align_features)
            StitchMode.REFINE else StitchMode.SENSORS,
        targetWidth = selectedWidth(),
        blend = when (findViewById<RadioGroup>(R.id.group_blend).checkedRadioButtonId) {
            R.id.blend_soft -> BlendScale.SOFT
            R.id.blend_crisp -> BlendScale.CRISP
            else -> BlendScale.BALANCED
        },
        excluded = LinkedHashSet(excluded)
    )

    private fun run() {
        if (available.size - excluded.size < 2) {
            toast(getString(R.string.retouch_need_two))
            return
        }
        val options = currentOptions()
        // Les réglages sont mémorisés dans la session : le prochain passage repart de là.
        session.options = options.toJson().toString()
        store.saveSession(session)
        StitchService.enqueue(this, session.id, options)
        toast(getString(R.string.retouch_started))
        finish()
    }
}

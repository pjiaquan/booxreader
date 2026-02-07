package my.hinoki.booxreader.ui.reader.nativev2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.TextPaint
import android.util.Log
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.reader.ReaderViewModel
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.ui.reader.ReaderActivity
import my.hinoki.booxreader.data.util.ChineseConverter
import my.hinoki.booxreader.databinding.FragmentNativeReaderBinding
import my.hinoki.booxreader.reader.LocatorJsonHelper
import java.net.URI
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

private const val TAG = "NativeNavigator"

class NativeNavigatorFragment : Fragment() {

    private var _binding: FragmentNativeReaderBinding? = null
    private val binding
        get() = _binding!!

    private val viewModel: ReaderViewModel by activityViewModels()

    private var publication: Publication? = null
    private var currentResourceIndex = 0
    private var currentPageInResource = 0

    private val currentResourceHref: String?
        get() = publication?.readingOrder?.getOrNull(currentResourceIndex)?.href?.toString()

    private var pager: NativeReaderPager? = null
    private var resourceText: CharSequence = ""
    private var convertToTraditionalChinese: Boolean = false

    private val _currentLocator = run {
        Log.d(TAG, "Initializing _currentLocator")
        val url =
                try {
                    Url("native://initial")
                            ?: Url("about:blank") ?: Url("/")
                                    ?: throw IllegalStateException("Could not create any URL")
                } catch (e: Exception) {
                    Log.e(TAG, "FATAL: Failed to create initial URL", e)
                    throw e
                }
        Log.d(TAG, "Url for initial locator is $url")
        MutableStateFlow<Locator>(
                Locator(
                        href = url,
                        mediaType = MediaType.BINARY,
                        locations = Locator.Locations(progression = 0.0)
                )
        )
    }
    val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private var initialLocator: Locator? = null
    private var pendingThemeColors: Triple<Int, Int, Int>? = null
    private var currentThemeColors: Pair<Int, Int>? = null // bgColor to textColor
    private var pageAnimationEnabled: Boolean = false
    private var isAnimating: Boolean = false
    private var showPageIndicator: Boolean = true

    fun setThemeColors(backgroundColor: Int, textColor: Int, buttonColor: Int) {
        Log.d(
                TAG,
                "setThemeColors: background=${Integer.toHexString(backgroundColor)}, textColor=${Integer.toHexString(textColor)}"
        )
        if (_binding != null) {
            binding.nativeReaderView.setThemeColors(backgroundColor, textColor)
            binding.nativeReaderViewSecondary.setThemeColors(backgroundColor, textColor)
            binding.root.setBackgroundColor(backgroundColor)
            binding.pageIndicator.setTextColor(textColor)

            // Theme the menu with modern design - create fresh drawable each time to fix theme
            // persistence
            val menuBg =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(buttonColor)
                        cornerRadius = 100f // Full pill shape

                        // Add a subtle border that matches the theme
                        val isDark = Color.luminance(backgroundColor) < 0.5
                        val strokeColor =
                                if (isDark) {
                                    Color.parseColor("#444444") // Night border
                                } else {
                                    Color.parseColor("#DDDDDD") // Day border
                                }
                        setStroke(2, strokeColor)
                    }
            binding.selectionMenu.background = menuBg
            binding.selectionMenu.elevation = 16f

            binding.btnCopy.setTextColor(textColor)
            binding.btnSearch.setTextColor(textColor)
            binding.btnAskAi.setTextColor(textColor)

            // Re-apply button tint for extra safety
            val btnTint =
                    android.content.res.ColorStateList.valueOf(
                            Color.TRANSPARENT
                    ) // Since they are borderless
            binding.btnCopy.backgroundTintList = btnTint
            binding.btnSearch.backgroundTintList = btnTint
            binding.btnAskAi.backgroundTintList = btnTint

            // Theme separators - use text color with very low alpha for a subtle look
            val sepColor = (textColor and 0x00FFFFFF) or 0x1A000000 // ~10% opacity
            binding.sep1.setBackgroundColor(sepColor)
            binding.sep2.setBackgroundColor(sepColor)
        } else {
            pendingThemeColors = Triple(backgroundColor, textColor, buttonColor)
        }
        currentThemeColors = backgroundColor to textColor
    }

    fun hasSelection(): Boolean = _binding?.nativeReaderView?.hasSelection() ?: false

    fun isSelectionMenuVisible(): Boolean = _binding?.selectionMenu?.visibility == View.VISIBLE

    fun isPointInSelectionMenu(x: Float, y: Float): Boolean {
        val menu = _binding?.selectionMenu ?: return false
        if (menu.visibility != View.VISIBLE) return false
        val rect = android.graphics.Rect()
        menu.getGlobalVisibleRect(rect)
        return rect.contains(x.toInt(), y.toInt())
    }

    fun clearSelection() {
        _binding?.nativeReaderView?.clearSelection()
    }

    data class Selection(val locator: Locator)

    fun currentSelection(): Selection? {
        val text = getSelectedTextFromRange() ?: return null
        val href = currentResourceHref ?: return null
        return Selection(
                Locator(
                        href = Url(href)!!,
                        mediaType = MediaType.HTML,
                        text = Locator.Text(highlight = text)
                )
        )
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView")
        _binding = FragmentNativeReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initial setup - wait for layout to get valid dimensions
        binding.nativeReaderView.post { checkDimensionsAndLoad() }

        // Apply pending theme colors if any
        pendingThemeColors?.let { (bg, text, btn) ->
            setThemeColors(bg, text, btn)
            pendingThemeColors = null
        }

        // Selection Menu Logic
        binding.nativeReaderView.setOnSelectionListener { active, x, menuY ->
            if (active) {
                showSelectionMenu(x, menuY)
            } else {
                hideSelectionMenu()
            }
        }
        binding.nativeReaderView.setOnPageEdgeHoldListener { direction ->
            handlePageEdgeHold(direction)
        }

        binding.nativeReaderView.setOnLinkClickListener { url -> handleLinkClick(url) }

        // Intercept touches on selection menu to prevent page navigation underneath
        binding.selectionMenu.setOnTouchListener { _, _ -> true }

        // Dismiss menu when clicking outside (on the background or indicator)
        binding.root.setOnClickListener { binding.nativeReaderView.clearSelection() }
        binding.pageIndicator.setOnClickListener { binding.nativeReaderView.clearSelection() }
        binding.pageIndicator.visibility = if (showPageIndicator) View.VISIBLE else View.GONE

        binding.btnCopy.setOnClickListener {
            val text = getSelectedTextFromRange()
            if (!text.isNullOrBlank()) {
                copyToClipboard(text)
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
                binding.nativeReaderView.clearSelection() // Clear after copy
                hideSelectionMenu()
            }
        }

        binding.btnSearch.setOnClickListener {
            val text = getSelectedTextFromRange()
            if (!text.isNullOrBlank()) {
                googleSearch(text)
                hideSelectionMenu()
            }
        }

        binding.btnAskAi.setOnClickListener {
            val text = getSelectedTextFromRange()
            if (!text.isNullOrBlank()) {
                val locator = buildSelectionLocator(text)
                val locatorJson = LocatorJsonHelper.toJson(locator)

                // Sanitize similar to ReaderActivity
                val sanitized =
                        text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                                .replace(Regex("\\s+"), " ")
                                .take(4000)

                if (sanitized.isNotBlank()) {
                    viewModel.postTextToServer(sanitized, locatorJson)
                    binding.nativeReaderView.clearSelection()
                    hideSelectionMenu()
                }
            }
        }
    }

    private fun showSelectionMenu(x: Float, y: Float) {
        binding.selectionMenu.visibility = View.VISIBLE
        binding.selectionMenu.alpha = 0f
        binding.selectionMenu.animate().alpha(1f).setDuration(200).start()

        binding.selectionMenu.post {
            val menuWidth = binding.selectionMenu.width
            val menuHeight = binding.selectionMenu.height

            var targetX = x - (menuWidth / 2)
            var targetY = y - menuHeight - 40 // More padding above

            // Edge safety
            val screenWidth = binding.root.width
            val margin = 20f

            if (targetX < margin) targetX = margin
            if (targetX + menuWidth > screenWidth - margin) {
                targetX = (screenWidth - menuWidth - margin)
            }

            // If too high, show below the selection
            if (targetY < margin) {
                targetY = y + 80 // show below if not enough space above
            }

            binding.selectionMenu.x = targetX
            binding.selectionMenu.y = targetY
        }
    }

    fun hideSelectionMenu() {
        binding.selectionMenu.visibility = View.GONE
        binding.selectionMenu.alpha = 0f
    }

    private fun copyToClipboard(text: String) {
        val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("BooxReader Selection", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun googleSearch(text: String) {
        val intent =
                Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}")
                )
        startActivity(intent)
    }

    private fun handleLinkClick(url: String) {
        Log.d(TAG, "handleLinkClick: $url")
        val pub = publication ?: return

        // Get current settings to check if conversion is enabled
        val settings =
                ReaderSettings.fromPrefs(
                        requireContext()
                                .getSharedPreferences(
                                        ReaderActivity.PREFS_NAME,
                                        android.content.Context.MODE_PRIVATE
                                )
                )

        // 1. Handle internal links / footnotes
        if (url.startsWith("#") || !url.contains("://")) {
            lifecycleScope.launch {
                try {
                    val href =
                            if (url.startsWith("#")) {
                                currentResourceHref?.let { "$it$url" } ?: url
                            } else {
                                url
                            }

                    // Simple heuristic: if it contains a fragment, it might be a footnote
                    if (url.contains("#")) {
                        val parts = href.split("#")
                        val resourceHref = parts[0]
                        val fragmentId = parts.getOrNull(1)

                        val link = pub.linkWithHref(Url(resourceHref)!!)
                        if (link != null) {
                            val resource = pub.get(link)
                            val html = resource?.read()?.getOrNull()?.toString(Charsets.UTF_8)

                            if (html != null && fragmentId != null) {
                                // Extract the content of the element with the ID
                                // Use a simple regex-based extraction to avoid full JSoup if
                                // possible
                                // For better results, JSoup is preferred, but let's try a simple
                                // one first
                                val pattern =
                                        Regex(
                                                "<[^>]*id=\"$fragmentId\"[^>]*>(.*?)</[^>]*>",
                                                RegexOption.DOT_MATCHES_ALL
                                        )
                                val match = pattern.find(html)
                                if (match != null) {
                                    val content = match.groupValues[1]
                                    val textColor = currentThemeColors?.second ?: Color.BLACK
                                    val imageGetter = createImageGetter(pub, resourceHref)
                                    var parsed =
                                            HtmlContentParser.parseHtml(
                                                    content,
                                                    textColor,
                                                    imageGetter
                                            )

                                    // Apply Chinese conversion if enabled
                                    if (settings.convertToTraditionalChinese) {
                                        parsed = ChineseConverter.toTraditional(parsed)
                                    }

                                    showFootnotePopup(parsed)
                                    return@launch
                                }
                            }
                        }
                    }

                    // Fallback: if not handled as popup, just jump to it
                    go(Locator(href = Url(href)!!, mediaType = MediaType.HTML))
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling internal link: $url", e)
                }
            }
        } else {
            // 2. Handle external links
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not open link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFootnotePopup(content: CharSequence) {
        val context = requireContext()
        val (popupBgColor, popupTextColor) = currentThemeColors ?: (Color.WHITE to Color.BLACK)

        // Use a slightly different background for the popup to distinguish it if it's white/black
        val isDark = Color.luminance(popupBgColor) < 0.5
        val finalPopupBg = if (isDark) Color.parseColor("#333333") else Color.parseColor("#F5F5F5")

        val padding = (16 * resources.displayMetrics.density).toInt()
        val textView =
                android.widget.TextView(context).apply {
                    text = content
                    setTextColor(popupTextColor)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(padding, padding, padding, padding)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }

        val scrollView = android.widget.ScrollView(context).apply { addView(textView) }

        val popup =
                android.widget.PopupWindow(
                                scrollView,
                                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                true
                        )
                        .apply {
                            elevation = 20f
                            setBackgroundDrawable(
                                    android.graphics.drawable.ColorDrawable(finalPopupBg)
                            )
                            animationStyle = android.R.style.Animation_Dialog
                        }

        popup.showAtLocation(binding.root, android.view.Gravity.CENTER, 0, 0)
    }

    private fun checkDimensionsAndLoad() {
        Log.d(TAG, "checkDimensionsAndLoad")
        val width =
                binding.nativeReaderView.width -
                        binding.nativeReaderView.paddingLeft -
                        binding.nativeReaderView.paddingRight
        val height =
                binding.nativeReaderView.height -
                        binding.nativeReaderView.paddingTop -
                        binding.nativeReaderView.paddingBottom
        Log.d(TAG, "Dimensions: ${width}x${height}")

        if (width > 0 && height > 0) {
            val settings =
                    ReaderSettings.fromPrefs(
                            requireContext()
                                    .getSharedPreferences(
                                            ReaderActivity.PREFS_NAME,
                                            android.content.Context.MODE_PRIVATE
                                    )
                    )
            val newTextSize = (settings.textSize.toFloat() / 100f) * 70f
            binding.nativeReaderView.setTextSize(newTextSize)

            val paint = TextPaint().apply { textSize = newTextSize }
            pager = NativeReaderPager(paint, width, height)

            // If we have an initial locator, use it
            val loc = initialLocator
            if (loc != null) {
                val pub = publication
                if (pub != null) {
                    val index =
                            pub.readingOrder.indexOfFirst {
                                it.href.toString() == loc.href.toString()
                            }
                    if (index != -1) {
                        currentResourceIndex = index
                        // We will handle intra-resource progression after paginating
                    }
                }
            }

            lifecycleScope.launch { loadCurrentResource() }
        } else {
            // Retry if not yet measured
            binding.nativeReaderView.postDelayed({ checkDimensionsAndLoad() }, 100)
        }
    }

    fun setPublication(pub: Publication, initialLocator: Locator? = null) {
        this.publication = pub
        this.initialLocator = initialLocator
        if (isResumed && pager != null) {
            lifecycleScope.launch { loadCurrentResource() }
        }
    }

    fun setFontSize(sizePercent: Int) {
        val width =
                binding.nativeReaderView.width -
                        binding.nativeReaderView.paddingLeft -
                        binding.nativeReaderView.paddingRight
        val height =
                binding.nativeReaderView.height -
                        binding.nativeReaderView.paddingTop -
                        binding.nativeReaderView.paddingBottom

        if (width > 0 && height > 0) {
            val newTextSize = (sizePercent.toFloat() / 100f) * 70f
            binding.nativeReaderView.setTextSize(newTextSize)

            val paint = TextPaint().apply { textSize = newTextSize }
            val newPager = NativeReaderPager(paint, width, height)
            pager = newPager

            lifecycleScope.launch {
                // Save current relative progression
                val oldLocator = _currentLocator.value
                val progression = oldLocator.locations.progression ?: 0.0

                newPager.paginate(resourceText)

                val pageCount = newPager.pageCount
                if (pageCount > 0) {
                    currentPageInResource =
                            (progression * pageCount).toInt().coerceIn(0, pageCount - 1)
                }
                displayCurrentPage()
            }
        }
    }

    fun setChineseConversionEnabled(enabled: Boolean) {
        convertToTraditionalChinese = enabled
        // Reload current resource with new conversion setting
        lifecycleScope.launch { loadCurrentResource() }
    }

    fun go(locator: Locator) {
        binding.nativeReaderView.clearSelection()
        val pub = publication ?: return
        val index = pub.readingOrder.indexOfFirst { it.href.toString() == locator.href.toString() }
        if (index != -1) {
            lifecycleScope.launch {
                currentResourceIndex = index
                // We need to wait for the resource to be loaded and paginated
                loadCurrentResource()

                val p = pager
                if (p != null) {
                    val progression = locator.locations.progression ?: 0.0
                    val pageCount = p.pageCount
                    if (pageCount > 0) {
                        currentPageInResource =
                                (progression * pageCount).toInt().coerceIn(0, pageCount - 1)
                        displayCurrentPage()
                    }
                }
            }
        }
    }

    private suspend fun loadCurrentResource(jumpToLastPage: Boolean = false) {
        val pub =
                publication
                        ?: run {
                            Log.e(TAG, "loadCurrentResource: publication is null")
                            return
                        }
        val link =
                pub.readingOrder.getOrNull(currentResourceIndex)
                        ?: run {
                            Log.e(
                                    TAG,
                                    "loadCurrentResource: link not found at index $currentResourceIndex"
                            )
                            return
                        }

        Log.d(TAG, "loadCurrentResource: index=$currentResourceIndex, link=${link.href}")
        binding.progressBar.visibility = View.VISIBLE

        // Get current settings to check if conversion is enabled
        val settings =
                ReaderSettings.fromPrefs(
                        requireContext()
                                .getSharedPreferences(
                                        ReaderActivity.PREFS_NAME,
                                        android.content.Context.MODE_PRIVATE
                                )
                )
        convertToTraditionalChinese = settings.convertToTraditionalChinese

        val imageGetter = createImageGetter(pub, link.href.toString())
        resourceText =
                withContext(Dispatchers.IO) {
                    val resource = pub.get(link)
                    val html = resource?.read()?.getOrNull()?.toString(Charsets.UTF_8) ?: ""
                    val textColor = currentThemeColors?.second ?: Color.BLACK
                    var parsed = HtmlContentParser.parseHtml(html, textColor, imageGetter)

                    // Apply Chinese conversion if enabled
                    parsed = applyChineseConversion(parsed)

                    parsed
                }

        if (resourceText.isEmpty() && currentResourceIndex < pub.readingOrder.size - 1) {
            // Skip empty resource
            currentResourceIndex++
            loadCurrentResource()
            return
        }

        pager?.paginate(resourceText)

        val p = pager
        // If resource is empty or too small for a page, and it's not the last one
        if ((p?.pageCount ?: 0) == 0 && currentResourceIndex < pub.readingOrder.size - 1) {
            currentResourceIndex++
            currentPageInResource = 0
            loadCurrentResource()
        } else {
            val loc = initialLocator
            if (loc != null && loc.href.toString() == link.href.toString()) {
                val progression = loc.locations.progression ?: 0.0
                val pageCount = p?.pageCount ?: 1
                currentPageInResource = (progression * pageCount).toInt().coerceIn(0, pageCount - 1)
                initialLocator = null // Clear after use
            } else if (jumpToLastPage) {
                currentPageInResource = (p?.pageCount ?: 1) - 1
            }
            displayCurrentPage()
        }

        binding.progressBar.visibility = View.GONE
    }

    private fun displayCurrentPage() {
        val p = pager ?: return
        if (p.pageCount == 0) {
            binding.nativeReaderView.setPageRange(0, 0)
            binding.nativeReaderView.setContent("No content in this chapter", resetSelection = true)
            updatePageIndicator()
            return
        }

        if (currentPageInResource >= p.pageCount) {
            currentPageInResource = 0
            if (currentResourceIndex < (publication?.readingOrder?.size ?: 0) - 1) {
                lifecycleScope.launch {
                    currentResourceIndex++
                    loadCurrentResource()
                }
            }
            return
        }

        val pageRange = p.getPageRange(currentPageInResource) ?: return
        val text = p.getPageText(currentPageInResource)
        binding.nativeReaderView.setPageRange(pageRange.startOffset, pageRange.endOffset)
        val preserveSelection = binding.nativeReaderView.hasSelection()
        // `resourceText` is already converted once in `loadCurrentResource()`.
        // Re-converting page slices can drop spans (e.g., image/link spans).
        binding.nativeReaderView.setContent(text, resetSelection = !preserveSelection)
        updatePageIndicator()
        updateLocator()
    }

    private fun applyChineseConversion(text: CharSequence): CharSequence {
        if (!convertToTraditionalChinese || text.isEmpty()) return text
        return ChineseConverter.toTraditional(text)
    }

    private fun createImageGetter(pub: Publication, baseHref: String): Html.ImageGetter {
        val maxWidth = imageMaxWidthPx()
        return Html.ImageGetter { source ->
            loadImageDrawable(
                    publication = pub,
                    baseHref = baseHref,
                    source = source,
                    maxWidth = maxWidth
            )
        }
    }

    private fun imageMaxWidthPx(): Int {
        val fallback = (resources.displayMetrics.widthPixels * 0.8f).toInt().coerceAtLeast(240)
        if (_binding == null) return fallback
        val width =
                binding.nativeReaderView.width -
                        binding.nativeReaderView.paddingLeft -
                        binding.nativeReaderView.paddingRight
        if (width <= 0) return fallback
        return (width * 0.92f).toInt().coerceAtLeast(240)
    }

    private fun loadImageDrawable(
            publication: Publication,
            baseHref: String,
            source: String?,
            maxWidth: Int
    ): Drawable {
        val fallback = placeholderImageDrawable(maxWidth)
        val rawSource = source?.trim().orEmpty()
        if (rawSource.isEmpty()) return fallback

        val imageBytes =
                when {
                    rawSource.startsWith("data:image", ignoreCase = true) -> {
                        decodeDataUriImage(rawSource)
                    }
                    rawSource.startsWith("http://", ignoreCase = true) ||
                            rawSource.startsWith("https://", ignoreCase = true) -> {
                        null
                    }
                    else -> {
                        val resolvedHref = resolveResourceHref(baseHref, rawSource) ?: return fallback
                        val link = findPublicationLink(publication, resolvedHref) ?: return fallback
                        runBlocking { publication.get(link)?.read()?.getOrNull() }
                    }
                } ?: return fallback

        val bitmap = decodeImageBitmap(imageBytes, maxWidth) ?: return fallback
        return BitmapDrawable(resources, bitmap).apply {
            setBounds(0, 0, bitmap.width, bitmap.height)
        }
    }

    private fun resolveResourceHref(baseHref: String, source: String): String? {
        val normalizedSource = source.substringBefore('#').trim()
        if (normalizedSource.isEmpty()) return null
        return runCatching {
                    URI(baseHref.substringBefore('#')).resolve(normalizedSource).toString()
                }
                .getOrElse {
                    val baseDir = baseHref.substringBeforeLast('/', "")
                    if (baseDir.isBlank()) {
                        normalizedSource.removePrefix("./")
                    } else {
                        "$baseDir/${normalizedSource.removePrefix("./")}"
                    }
                }
    }

    private fun findPublicationLink(
            publication: Publication,
            resolvedHref: String
    ): org.readium.r2.shared.publication.Link? {
        val normalized = resolvedHref.substringBefore('?').substringBefore('#')
        val candidates = linkedSetOf<String>()

        fun addCandidate(value: String) {
            if (value.isNotBlank()) candidates.add(value)
        }

        addCandidate(normalized)
        addCandidate(normalized.removePrefix("./"))
        addCandidate(normalized.removePrefix("/"))
        val decoded = Uri.decode(normalized)
        addCandidate(decoded)
        addCandidate(decoded.removePrefix("./"))
        addCandidate(decoded.removePrefix("/"))

        for (candidate in candidates) {
            val url = Url(candidate) ?: continue
            publication.linkWithHref(url)?.let { return it }
        }
        return null
    }

    private fun decodeDataUriImage(source: String): ByteArray? {
        val commaIndex = source.indexOf(',')
        if (commaIndex <= 0) return null
        val metadata = source.substring(0, commaIndex)
        if (!metadata.contains(";base64", ignoreCase = true)) return null
        val payload = source.substring(commaIndex + 1)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    private fun decodeImageBitmap(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val safeMaxWidth = maxWidth.coerceAtLeast(1)
        val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions =
                BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds.outWidth, safeMaxWidth)
                }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        if (decoded.width <= safeMaxWidth) return decoded

        val scaledHeight =
                (decoded.height * (safeMaxWidth.toFloat() / decoded.width.toFloat()))
                        .toInt()
                        .coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, safeMaxWidth, scaledHeight, true)
        if (scaled !== decoded) {
            decoded.recycle()
        }
        return scaled
    }

    private fun calculateInSampleSize(sourceWidth: Int, maxWidth: Int): Int {
        var sampleSize = 1
        while (sourceWidth / sampleSize > maxWidth * 2) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun placeholderImageDrawable(maxWidth: Int): Drawable {
        val width = (maxWidth * 0.6f).toInt().coerceAtLeast(120)
        val height = (width * 0.56f).toInt().coerceAtLeast(68)
        return ColorDrawable(Color.argb(30, 128, 128, 128)).apply {
            setBounds(0, 0, width, height)
        }
    }

    private fun updateLocator() {
        try {
            val pub = publication ?: return
            val link = pub.readingOrder.getOrNull(currentResourceIndex) ?: return
            val p = pager ?: return

            val pageCount = p.pageCount
            val progression =
                    if (pageCount > 0) currentPageInResource.toDouble() / pageCount else 0.0

            // Simplified total progression
            val totalResources = pub.readingOrder.size.toDouble()
            val totalProgression = (currentResourceIndex + progression) / totalResources

            Log.d(
                    TAG,
                    "updateLocator: index=$currentResourceIndex, prog=$progression, total=$totalProgression"
            )

            val locator =
                    Locator(
                            href = Url(link.href.toString())!!,
                            mediaType = link.mediaType ?: MediaType.BINARY,
                            title = link.title,
                            locations =
                                    Locator.Locations(
                                            progression = progression,
                                            totalProgression = totalProgression
                                    )
                    )
            _currentLocator.value = locator
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateLocator", e)
        }
    }

    private fun updatePageIndicator() {
        val p = pager ?: return
        val resourceTitle =
                publication?.readingOrder?.getOrNull(currentResourceIndex)?.title
                        ?: "Chapter ${currentResourceIndex + 1}"
        val pageInfo = "${currentPageInResource + 1} / ${p.pageCount}"
        binding.pageIndicator.text = "$resourceTitle | $pageInfo"
    }

    fun setPageAnimationEnabled(enabled: Boolean) {
        pageAnimationEnabled = enabled
    }

    fun setPageIndicatorVisible(enabled: Boolean) {
        showPageIndicator = enabled
        if (_binding != null) {
            binding.pageIndicator.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    fun goForward(clearSelection: Boolean = true) {
        if (isAnimating) return
        if (clearSelection) {
            binding.nativeReaderView.clearSelection()
        }
        val p = pager ?: return
        if (currentPageInResource < p.pageCount - 1) {
            // Same resource, next page
            if (pageAnimationEnabled) {
                animatePageForward(sameResource = true)
            } else {
                currentPageInResource++
                displayCurrentPage()
            }
        } else if (currentResourceIndex < (publication?.readingOrder?.size ?: 0) - 1) {
            // Next resource
            if (pageAnimationEnabled) {
                animatePageForward(sameResource = false)
            } else {
                lifecycleScope.launch {
                    currentResourceIndex++
                    currentPageInResource = 0
                    loadCurrentResource()
                }
            }
        }
    }

    fun goBackward(clearSelection: Boolean = true) {
        if (isAnimating) return
        if (clearSelection) {
            binding.nativeReaderView.clearSelection()
        }
        if (currentPageInResource > 0) {
            // Same resource, previous page
            if (pageAnimationEnabled) {
                animatePageBackward(sameResource = true)
            } else {
                currentPageInResource--
                displayCurrentPage()
            }
        } else if (currentResourceIndex > 0) {
            // Previous resource
            if (pageAnimationEnabled) {
                animatePageBackward(sameResource = false)
            } else {
                lifecycleScope.launch {
                    currentResourceIndex--
                    loadCurrentResource(jumpToLastPage = true)
                }
            }
        }
    }

    private fun animatePageForward(sameResource: Boolean) {
        isAnimating = true
        val p = pager ?: return

        // Prepare secondary view with next page content
        val nextText =
                if (sameResource) {
                    if (currentPageInResource < p.pageCount - 1) {
                        p.getPageText(currentPageInResource + 1)
                    } else null
                } else null

        if (sameResource && nextText != null) {
            // Simple slide animation within same resource
            val nextRange = p.getPageRange(currentPageInResource + 1)
            if (nextRange != null) {
                binding.nativeReaderViewSecondary.setPageRange(
                        nextRange.startOffset,
                        nextRange.endOffset
                )
            }
            binding.nativeReaderViewSecondary.setContent(nextText, resetSelection = true)
            binding.nativeReaderViewSecondary.visibility = View.VISIBLE
            binding.nativeReaderViewSecondary.translationX = binding.root.width.toFloat()

            // Animate
            binding.nativeReaderViewSecondary.animate().translationX(0f).setDuration(300).start()
            binding.nativeReaderView
                    .animate()
                    .translationX(-binding.root.width.toFloat())
                    .setDuration(300)
                    .withEndAction {
                        currentPageInResource++
                        binding.nativeReaderView.translationX = 0f
                        binding.nativeReaderViewSecondary.visibility = View.GONE
                        displayCurrentPage()
                        isAnimating = false
                    }
                    .start()
        } else {
            // Cross-resource navigation
            lifecycleScope.launch {
                val savedIndex = currentResourceIndex
                currentResourceIndex++
                currentPageInResource = 0
                loadCurrentResource()

                // Fade transition for resource change
                binding.nativeReaderView.alpha = 0f
                binding.nativeReaderView.animate().alpha(1f).setDuration(250).start()
                isAnimating = false
            }
        }
    }

    private fun animatePageBackward(sameResource: Boolean) {
        isAnimating = true
        val p = pager ?: return

        if (sameResource && currentPageInResource > 0) {
            // Simple slide animation within same resource
            val prevText = p.getPageText(currentPageInResource - 1)
            val prevRange = p.getPageRange(currentPageInResource - 1)
            if (prevRange != null) {
                binding.nativeReaderViewSecondary.setPageRange(
                        prevRange.startOffset,
                        prevRange.endOffset
                )
            }
            binding.nativeReaderViewSecondary.setContent(prevText, resetSelection = true)
            binding.nativeReaderViewSecondary.visibility = View.VISIBLE
            binding.nativeReaderViewSecondary.translationX = -binding.root.width.toFloat()

            // Animate
            binding.nativeReaderViewSecondary.animate().translationX(0f).setDuration(300).start()
            binding.nativeReaderView
                    .animate()
                    .translationX(binding.root.width.toFloat())
                    .setDuration(300)
                    .withEndAction {
                        currentPageInResource--
                        binding.nativeReaderView.translationX = 0f
                        binding.nativeReaderViewSecondary.visibility = View.GONE
                        displayCurrentPage()
                        isAnimating = false
                    }
                    .start()
        } else {
            // Cross-resource navigation
            lifecycleScope.launch {
                val savedIndex = currentResourceIndex
                currentResourceIndex--
                loadCurrentResource(jumpToLastPage = true)

                // Fade transition for resource change
                binding.nativeReaderView.alpha = 0f
                binding.nativeReaderView.animate().alpha(1f).setDuration(250).start()
                isAnimating = false
            }
        }
    }

    private fun handlePageEdgeHold(direction: Int) {
        if (isAnimating) return
        val p = pager ?: return
        if (direction > 0) {
            if (currentPageInResource < p.pageCount - 1) {
                currentPageInResource++
                displayCurrentPage()
                binding.nativeReaderView.nudgeSelectionAfterPageTurn(direction)
            }
        } else if (direction < 0) {
            if (currentPageInResource > 0) {
                currentPageInResource--
                displayCurrentPage()
                binding.nativeReaderView.nudgeSelectionAfterPageTurn(direction)
            }
        }
    }

    private fun getSelectedTextFromRange(): String? {
        val view = _binding?.nativeReaderView ?: return null
        val range = view.getSelectionRange() ?: return null
        if (resourceText.isEmpty()) return null
        val start = range.start.coerceIn(0, resourceText.length)
        val end = range.end.coerceIn(start, resourceText.length)
        if (start == end) return null
        return resourceText.subSequence(start, end).toString()
    }

    private fun buildSelectionLocator(text: String): Locator? {
        val href = currentResourceHref ?: return null
        return Locator(
                href = Url(href)!!,
                mediaType = MediaType.HTML,
                text = Locator.Text(highlight = text)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

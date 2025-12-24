package my.hinoki.booxreader.data.ui.reader.nativev2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.util.Log
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
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.reader.ReaderViewModel
import my.hinoki.booxreader.databinding.FragmentNativeReaderBinding
import my.hinoki.booxreader.reader.LocatorJsonHelper
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

    private var pager: NativeReaderPager? = null
    private var resourceText: CharSequence = ""

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

        // Navigation Zones: Left 20% = Prev, Right 20% = Next, Middle = Toggle Menu (Next for now)
        binding.nativeReaderView.setOnTouchTapListener { x, y ->
            val width = binding.nativeReaderView.width
            val leftZone = width * 0.2f
            val rightZone = width * 0.8f

            when {
                x < leftZone -> goBackward()
                x > rightZone -> goForward()
                else -> goForward() // Middle tap also goes forward for now
            }
        }

        // Selection Menu Logic
        binding.nativeReaderView.setOnSelectionListener { active, x, y ->
            if (active) {
                showSelectionMenu(x, y)
            } else {
                hideSelectionMenu()
            }
        }

        binding.btnCopy.setOnClickListener {
            val text = binding.nativeReaderView.getSelectedText()
            if (text != null) {
                copyToClipboard(text)
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
                binding.nativeReaderView.clearSelection() // Clear after copy
                hideSelectionMenu()
            }
        }

        binding.btnSearch.setOnClickListener {
            val text = binding.nativeReaderView.getSelectedText()
            if (text != null) {
                googleSearch(text)
                hideSelectionMenu()
            }
        }

        binding.btnAskAi.setOnClickListener {
            val text = binding.nativeReaderView.getSelectedText()
            if (!text.isNullOrBlank()) {
                val locator = binding.nativeReaderView.getSelectionLocator()
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

    private fun hideSelectionMenu() {
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
            val paint = TextPaint().apply { textSize = 98f }
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

    fun go(locator: Locator, animated: Boolean = false) {
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

        resourceText =
                withContext(Dispatchers.IO) {
                    val resource = pub.get(link)
                    val html = resource?.read()?.getOrNull()?.toString(Charsets.UTF_8) ?: ""
                    parseHtml(html)
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
            binding.nativeReaderView.setContent("No content in this chapter")
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

        val text = p.getPageText(currentPageInResource)
        binding.nativeReaderView.setContent(text)
        updatePageIndicator()
        updateLocator()
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

    fun goForward() {
        val p = pager ?: return
        if (currentPageInResource < p.pageCount - 1) {
            currentPageInResource++
            displayCurrentPage()
        } else if (currentResourceIndex < (publication?.readingOrder?.size ?: 0) - 1) {
            lifecycleScope.launch {
                currentResourceIndex++
                currentPageInResource = 0
                loadCurrentResource()
            }
        }
    }

    fun goBackward() {
        if (currentPageInResource > 0) {
            currentPageInResource--
            displayCurrentPage()
        } else if (currentResourceIndex > 0) {
            lifecycleScope.launch {
                currentResourceIndex--
                loadCurrentResource(jumpToLastPage = true)
            }
        }
    }

    private fun parseHtml(html: String): CharSequence {
        // Clean up some basic mess before parsing
        val cleaned =
                html.replace(Regex("<script.*?>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                        .replace(Regex("<style.*?>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(cleaned, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION") android.text.Html.fromHtml(cleaned)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

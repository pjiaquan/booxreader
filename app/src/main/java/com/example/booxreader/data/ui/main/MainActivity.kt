package com.example.booxreader.data.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.booxreader.data.repo.BookRepository
import com.example.booxreader.data.ui.reader.ReaderActivity
import com.example.booxreader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bookRepository by lazy { BookRepository(applicationContext) }
    private val recentAdapter by lazy { RecentBooksAdapter(emptyList()) { openBook(Uri.parse(it.fileUri)) } }

    private val pickEpub =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                takePersistable(it)
                openBook(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenEpub.setOnClickListener {
            pickEpub.launch(arrayOf("application/epub+zip"))
        }

        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, com.example.booxreader.data.auth.UserProfileActivity::class.java))
        }

        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter

        loadRecentBooks()
    }

    override fun onResume() {
        super.onResume()
        loadRecentBooks()
    }

    private fun loadRecentBooks() {
        lifecycleScope.launch {
            val recent = bookRepository.getRecent(10)
            if (recent.isEmpty()) {
                binding.tvEmptyState.visibility = android.view.View.VISIBLE
                binding.recyclerRecent.visibility = android.view.View.GONE
            } else {
                binding.tvEmptyState.visibility = android.view.View.GONE
                binding.recyclerRecent.visibility = android.view.View.VISIBLE
                recentAdapter.submit(recent)
            }
        }
    }

    private fun takePersistable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun openBook(uri: Uri) {
        try {
            ReaderActivity.open(this, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟檔案: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

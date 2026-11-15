package top.cyclops.roomseed.demo

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cyclops.roomseed.demo.dictionary.Dictionary
import top.cyclops.roomseed.demo.room3.Library

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(Dispatchers.Main)
    private lateinit var dictionaryQuery: EditText
    private lateinit var bookQuery: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val margin = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
        }
        dictionaryQuery = addSearch(
            content, R.string.dictionary, R.string.dictionary_hint, R.string.no_entries,
            savedInstanceState?.getString("dictionary"),
        ) { query ->
            withContext(Dispatchers.IO) {
                Dictionary(applicationContext).use { dictionary ->
                    dictionary.search(query).map { "${it.word} — ${it.definition}" }
                }
            }
        }
        bookQuery = addSearch(
            content, R.string.library, R.string.library_hint, R.string.no_books,
            savedInstanceState?.getString("library"), topPadding = margin,
        ) { query ->
            Library(applicationContext).use { library ->
                library.search(query).map { "${it.title} — ${it.author}" }
            }
        }
        setContentView(ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(
                    safe.left + margin, safe.top + margin, safe.right + margin, safe.bottom + margin,
                )
                insets
            }
        })
        content.requestFocus()
    }

    private fun addSearch(
        content: LinearLayout,
        title: Int,
        hint: Int,
        emptyMessage: Int,
        initialQuery: String?,
        topPadding: Int = 0,
        search: suspend (String) -> List<String>,
    ): EditText {
        val label = TextView(this).apply {
            textSize = 22f
            setText(title)
            setPadding(0, topPadding, 0, 0)
        }
        val input = EditText(this).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setHint(hint)
            setText(initialQuery)
        }
        val button = Button(this).apply { setText(R.string.search) }
        val results = TextView(this).apply {
            textSize = 17f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        var job: Job? = null
        fun load() {
            job?.cancel()
            val query = input.text.toString()
            results.setText(R.string.loading)
            job = activityScope.launch {
                results.text = try {
                    val matches = search(query)
                    if (matches.isEmpty()) getString(emptyMessage) else matches.joinToString("\n\n")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e("RoomSeedDemo", "Could not search the prepackaged database", error)
                    getString(R.string.search_error)
                }
            }
        }
        fun submit() {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
            load()
        }
        button.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            submit()
            true
        }
        content.addView(label)
        content.addView(LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button)
        })
        content.addView(results)
        load()
        return input
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("dictionary", dictionaryQuery.text.toString())
        outState.putString("library", bookQuery.text.toString())
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}

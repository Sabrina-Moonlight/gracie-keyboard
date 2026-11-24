package org.futo.inputmethod.latin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.settings.BadWordMode
import org.futo.inputmethod.latin.uix.settings.shouldBlockWord
import java.util.Locale

class SuggestionBlacklist(val settings: Settings, val context: Context, val lifecycleScope: LifecycleCoroutineScope) {
    private var userBlacklistedWords: Set<String> = setOf()

    private val mode get() = BadWordMode(
        language = settings.current.mLocale.language.lowercase(),
        blockSlurs = settings.current.mBlockSlurs,
        blockOffensive = settings.current.mBlockPotentiallyOffensive
    )

    fun init() {
        lifecycleScope.launch {
            context.getSettingFlow(SUGGESTION_BLACKLIST).collect { value ->
                userBlacklistedWords = value
            }
        }
    }

    companion object {
        @JvmStatic
        suspend fun addToBlacklistSetting(context: Context, word: String) {
            context.dataStore.edit {
                it[SUGGESTION_BLACKLIST.key] = (it[SUGGESTION_BLACKLIST.key] ?: SUGGESTION_BLACKLIST.default) + word
            }
        }

        @JvmStatic
        fun getCapitalVariants(word: String, locale: Locale = Locale.ROOT): List<String> = listOf(
            word,
            word.lowercase(locale),
            word.uppercase(locale),
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        ).distinct()
    }

    fun isWordOk(word: String): Boolean {
        if(word in userBlacklistedWords) return false
        if(shouldBlockWord(mode, word)) return false
        return true
    }

    // Short all-caps words like "UE", "SA", "IE" are almost never what the person wants.
    private val allowedAcronyms = setOf(
        "OK", "TV", "PC", "ID", "DJ", "CD", "WC", "UK", "USA", "DVD", "USB", "PDF", "GPS",
        "SMS", "CEO", "DNI", "IVA", "ONU", "FBI", "ADN", "VIP", "SOS"
    )

    private fun isJunkAcronym(info: SuggestedWordInfo, typedIsAllCaps: Boolean): Boolean {
        if(typedIsAllCaps) return false
        if(info.isKindOf(SuggestedWordInfo.KIND_TYPED)
            || info.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION)) return false
        if(info.mSourceDict?.mDictType == Dictionary.TYPE_USER) return false

        val w = info.mWord
        if(w.length < 2 || w.length > 3) return false
        if(!w.all { it.isLetter() && it.isUpperCase() }) return false
        return w !in allowedAcronyms
    }

    fun isSuggestedWordOk(word: SuggestedWordInfo): Boolean {
        return word.isKindOf(SuggestedWordInfo.KIND_TYPED) || isWordOk(word.mWord)
    }

    fun filterBlacklistedSuggestions(suggestions: SuggestedWords): SuggestedWords {
        val typedWord = when(suggestions.mInputStyle) {
            SuggestedWords.INPUT_STYLE_UPDATE_BATCH,
            SuggestedWords.INPUT_STYLE_TAIL_BATCH -> null

            else -> suggestions.mTypedWordInfo
        }

        val typedText = suggestions.mTypedWordInfo?.mWord ?: ""
        val typedIsAllCaps = typedText.length >= 2 && typedText.all { it.isUpperCase() }

        val filter: (SuggestedWordInfo) -> Boolean = { it ->
            (isSuggestedWordOk(it) && !isJunkAcronym(it, typedIsAllCaps)) || (it == typedWord)
        }

        val shouldStillAutocorrect = suggestions.mWillAutoCorrect
                && (suggestions.size() > SuggestedWords.INDEX_OF_AUTO_CORRECTION)
                && filter(suggestions.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION))

        val filtered = suggestions.mSuggestedWordInfoList.filter(filter)

        return SuggestedWords(
            ArrayList(filtered),
            suggestions.mRawSuggestions?.filter {
                it == suggestions.mTypedWordInfo || isWordOk(it.mWord)
            }?.let { ArrayList(it) },
            suggestions.mTypedWordInfo,
            suggestions.mTypedWordValid,
            shouldStillAutocorrect,
            suggestions.mIsObsoleteSuggestions,
            suggestions.mInputStyle,
            suggestions.mSequenceNumber,
            suggestions.mHighlightedCandidate
        )
    }
}
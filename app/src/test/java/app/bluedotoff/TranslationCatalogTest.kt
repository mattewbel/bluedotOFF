package app.bluedotoff

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 1.1.2: every language catalog covers every sentence, keeps placeholders and protected names, and has no Korean left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class TranslationCatalogTest {
    private fun catalog(name:String):Map<String,String> {
        val file=File("src/main/assets/i18n/$name.json")
        assertTrue("missing catalog $name",file.exists())
        val j=JSONObject(file.readText(Charsets.UTF_8))
        return j.keys().asSequence().associateWith{j.getString(it)}
    }
    private val placeholder=Regex("\\{[0-9]+\\}")
    private val protectedNames=listOf("bluedotOFF","USB","Wi-Fi")
    @Test fun everyLanguageHasEveryEnglishSentence(){
        val en=catalog("en")
        for(name in UiText.LANGUAGES-"en"){
            val other=catalog(name)
            assertEquals("$name: missing keys",emptySet<String>(),en.keys-other.keys)
            assertEquals("$name: extra keys",emptySet<String>(),other.keys-en.keys)
        }
    }
    @Test fun placeholdersNamesAndScriptAreKept(){
        val en=catalog("en")
        for(name in UiText.LANGUAGES-"en"){
            for((key,value) in catalog(name)){
                val english=en[key]?:continue
                assertEquals("$name placeholders: $key",placeholder.findAll(english).map{it.value}.sorted().toList(),placeholder.findAll(value).map{it.value}.sorted().toList())
                assertFalse("$name has Korean left: $key -> $value",value.any{it in '가'..'힣'})
                assertTrue("$name empty: $key",value.isNotBlank())
                // German Samsung menus say WLAN for Wi-Fi.
                for(word in protectedNames)if(english.contains(word))assertTrue("$name lost '$word': $key -> $value",value.contains(word)||name=="de"&&word=="Wi-Fi"&&value.contains("WLAN"))
            }
        }
    }
}

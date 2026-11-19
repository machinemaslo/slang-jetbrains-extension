package slanglsp

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

object SlangBundle {
    private const val BUNDLE = "messages.SlangBundle"
    private val INSTANCE = DynamicBundle(SlangBundle::class.java, BUNDLE)

    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): @Nls String {
        return INSTANCE.getMessage(key, *params)
    }
}

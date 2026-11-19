package slanglsp.server

import org.eclipse.lsp4j.ConfigurationParams

internal object ServerConfiguration {
    fun select(settings: MutableMap<String?, Any?>, params: ConfigurationParams): MutableList<Any?> {
        val values: MutableList<Any?> = ArrayList()

        for (item in params.items) {
            val section = item.section
            values.add(if (section == null || section.isEmpty()) settings else settings[section])
        }

        return values
    }
}

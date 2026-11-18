package slanglsp.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import slanglsp.SlangBundle.message
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

internal class FormatterPanel @JvmOverloads constructor(
    settings: CodeStyleSettings,
    bundledExecutable: String? = BundledClangFormat.executable(),
) : SettingsPreviewPanel(settings, "general") {
    private val controls = JPanel(GridBagLayout())
    private val nativeFormatter = JRadioButton(message("settings.formatter.native"))
    private val clang = JRadioButton(message("settings.formatter.clang"))
    private val readConfig = JCheckBox(message("settings.formatter.read.config"))
    private val external = JCheckBox(message("settings.formatter.external"))
    private val executable = JBTextField()
    private val browse = JButton(message("settings.formatter.browse"))
    private val bundledAvailable = bundledExecutable != null

    init {
        controls.border = JBUI.Borders.empty(12, 16)
        ButtonGroup().apply {
            add(nativeFormatter)
            add(clang)
        }
        nativeFormatter.isSelected = true

        row(TitledSeparator(message("settings.formatter.engine")), 0, 0)
        row(nativeFormatter, 0, 12)
        row(description("settings.formatter.native.help"), 26, 4)
        row(readConfig, 26, 10)
        row(clang, 0, 8)
        row(description(if (bundledAvailable) "settings.formatter.clang.bundled.help"
            else "settings.formatter.clang.external.help"), 26, 4)
        if (bundledAvailable) {
            row(external, 26, 10)
        }

        executable.accessibleContext.accessibleName = message("settings.clang.format.executable")
        browse.addActionListener { chooseExecutable() }
        row(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(executable, BorderLayout.CENTER)
            add(browse, BorderLayout.EAST)
        }, 26, 10)

        row(TitledSeparator(message("settings.formatter.export")), 0, 16)
        val export = JButton(message("settings.formatter.export.button"))
        export.addActionListener { exportStyle() }
        row(JPanel(BorderLayout()).apply { add(export, BorderLayout.WEST) }, 0, 10)
        controls.add(JPanel(), GridBagConstraints().apply {
            gridy = controls.componentCount
            weighty = 1.0
        })
        installOptions(controls)

        val examples = mapOf(
            nativeFormatter to "float shade(float intensity) { return intensity * 2; }",
            clang to "float shade(float exposure) { return clamp(exposure, 0, 1); }",
            readConfig to "namespace Lighting { float exposure; }",
            external to "struct Material { float roughness; };",
        )
        for ((option, example) in examples) {
            val explanation = when (option) {
                readConfig -> message("settings.formatter.read.config.preview")
                external -> message("settings.formatter.clang.external.help")
                else -> null
            }
            option.addActionListener {
                updateEnabled()
                showExample(example, explanation)
            }
            watchPreview(option, explanation) { example }
        }
        watchPreview(executable) { "float illuminate(float exposure) { return exposure; }" }
        watchPreview(browse) { "float shadow(float intensity) { return 1 - intensity; }" }
        watchPreview(export) { "void shade() { if (enabled) { update(); } }" }
        updateEnabled()
    }

    private fun chooseExecutable() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(message("settings.clang.format.executable"))
        val path = executable.text.trim()
        val initial = if (SlangCodeStyleSettings.isAbsoluteExecutablePath(path)) {
            LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))
        } else {
            null
        }
        FileChooser.chooseFile(descriptor, null, controls, initial) { executable.text = it.presentableUrl }
    }

    private fun exportStyle() {
        val title = message("settings.formatter.export")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor(title, ""), controls)
            .save(".clang-format") ?: return
        try {
            Files.writeString(target.file.toPath(), ClangFormatStyle.export(modelSettings ?: settings))
            LocalFileSystem.getInstance().refreshIoFiles(listOf(target.file), true, false, null)
        } catch (failure: IOException) {
            Messages.showErrorDialog(controls, message("settings.formatter.export.failed", failure.message.orEmpty()), title)
        }
    }

    private fun description(key: String) =
        JBLabel(message(key), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).setAllowAutoWrapping(true)

    private fun row(component: JComponent, indent: Int, top: Int) {
        controls.add(component, GridBagConstraints().apply {
            gridx = 0
            gridy = controls.componentCount
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(top, indent, 0, 0)
        })
    }

    private fun updateEnabled() {
        readConfig.isEnabled = nativeFormatter.isSelected
        external.isEnabled = clang.isSelected
        val useExternal = clang.isSelected && (!bundledAvailable || external.isSelected)
        executable.isEnabled = useExternal
        browse.isEnabled = useExternal
    }

    @Throws(ConfigurationException::class)
    override fun apply(settings: CodeStyleSettings) {
        val path = executable.text.trim()
        if (clang.isSelected && (!bundledAvailable || external.isSelected)
            && !SlangCodeStyleSettings.isAbsoluteExecutablePath(path)
        ) {
            throw ConfigurationException(message("settings.formatter.path.required"))
        }
        val state = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        state.USE_CLANG_FORMAT = clang.isSelected
        state.READ_CLANG_FORMAT = readConfig.isSelected
        state.USE_CUSTOM_CLANG_FORMAT = external.isSelected
        state.CLANG_FORMAT_PATH = path
    }

    override fun resetImpl(settings: CodeStyleSettings) {
        val state = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        clang.isSelected = state.USE_CLANG_FORMAT
        nativeFormatter.isSelected = !state.USE_CLANG_FORMAT
        readConfig.isSelected = state.READ_CLANG_FORMAT
        external.isSelected = state.USE_CUSTOM_CLANG_FORMAT
        executable.text = state.CLANG_FORMAT_PATH
        updateEnabled()
        clearExample()
    }

    override fun isModified(settings: CodeStyleSettings): Boolean {
        val state = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        return state.USE_CLANG_FORMAT != clang.isSelected || state.READ_CLANG_FORMAT != readConfig.isSelected
            || state.USE_CUSTOM_CLANG_FORMAT != external.isSelected || state.CLANG_FORMAT_PATH != executable.text.trim()
    }

    override fun getTabTitle() = message("settings.general")
}

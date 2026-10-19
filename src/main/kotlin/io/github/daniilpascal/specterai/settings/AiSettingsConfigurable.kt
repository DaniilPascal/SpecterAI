package io.github.daniilpascal.specterai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.daniilpascal.specterai.ai.AiModels
import io.github.daniilpascal.specterai.highlight.AiCommandSyntaxHighlighter
import io.github.daniilpascal.specterai.optim.FunctionOptimInlayController
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AiSettingsConfigurable : Configurable {

  private var panel: JPanel? = null
  private val enabledCheck = JBCheckBox("Enable Specter.AI / S.AI(\"…\").start(speed);")
  private val apiKeyField = JBPasswordField()
  private val clearKeyButton = JButton("Clear key")
  private val baseUrlField = JBTextField()
  private val modelPresetCombo = JComboBox<String>()
  private val modelField = JBTextField()
  private val typingDelaySpinner = JSpinner(
    SpinnerNumberModel(AiSettings.DEFAULT_TYPING_DELAY_MS, AiSettings.MIN_TYPING_DELAY_MS, AiSettings.MAX_TYPING_DELAY_MS, 1),
  )
  private val maxBlocksSpinner = JSpinner(SpinnerNumberModel(AiSettings.DEFAULT_MAX_BLOCKS, 1, 4, 1))
  private val fallbackCheck = JBCheckBox("If no API key / AI error — use local templates")
  private val diffPreviewCheck = JBCheckBox("Preview diff before swarm (Accept / Reject · Esc = reject)")
  private val lastSwarmLabel = JBLabel("—")

  private val optimEnabledCheck = JBCheckBox("Show Optim % next to non-empty functions")
  private val optimShowNameCheck = JBCheckBox("Include function name in badge (foo · Optim 72%)")
  private val optimShowFactorsCheck = JBCheckBox("Show score factors (+flat −nested-loops …)")
  private val optimRefreshSpinner = JSpinner(
    SpinnerNumberModel(
      AiSettings.DEFAULT_OPTIM_REFRESH_MS,
      AiSettings.MIN_OPTIM_REFRESH_MS,
      AiSettings.MAX_OPTIM_REFRESH_MS,
      10,
    ),
  )

  private var syncingModelUi = false

  override fun getDisplayName(): String = "SpecterAI"

  override fun createComponent(): JComponent {
    clearKeyButton.addActionListener {
      ApplicationManager.getApplication().executeOnPooledThread {
        AiSettings.getInstance().clearApiKey()
        ApplicationManager.getApplication().invokeLater {
          apiKeyField.text = ""
          apiKeyField.emptyText.text = "вставьте ключ"
        }
      }
    }

    val keyRow = JPanel().apply {
      layout = BorderLayout(8, 0)
      add(apiKeyField, BorderLayout.CENTER)
      add(clearKeyButton, BorderLayout.EAST)
    }

    val presetLabels = AiModels.ALL.map { it.label } + AiModels.CUSTOM_LABEL
    modelPresetCombo.model = DefaultComboBoxModel(presetLabels.toTypedArray())
    modelPresetCombo.addActionListener {
      if (syncingModelUi) return@addActionListener
      val selected = modelPresetCombo.selectedItem as? String ?: return@addActionListener
      if (selected == AiModels.CUSTOM_LABEL) return@addActionListener
      val preset = AiModels.ALL.firstOrNull { it.label == selected } ?: return@addActionListener
      syncingModelUi = true
      modelField.text = preset.id
      syncingModelUi = false
    }
    modelField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = syncPresetFromField()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = syncPresetFromField()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = syncPresetFromField()
    })

    val modelRow = JPanel(BorderLayout(8, 0)).apply {
      add(modelPresetCombo, BorderLayout.WEST)
      add(modelField, BorderLayout.CENTER)
    }

    val hint = JBLabel(
      "<html><body style='width:420px;color:gray'>" +
        "Команда: <b>Specter.AI(\"запрос\").start(14);</b><br>" +
        "Коротко: <b>S.AI(\"запрос\").start(14);</b><br>" +
        "По умолчанию: <b>Power · Claude Sonnet 5.5</b>. Max / Gemini / DeepSeek — в пресетах.<br>" +
        "start(1..100) — скорость печати курсоров (1 медленно … 100 быстро).<br>" +
        "OpenRouter / любой OpenAI-compatible endpoint + API key.<br>" +
        "Язык ответа = язык текущего файла. Optim % — клик Explain / Fix." +
        "</body></html>",
    ).apply { border = JBUI.Borders.emptyBottom(8) }

    val optimHeader = JBLabel("<html><b>Function Optim %</b></html>")
      .apply { border = JBUI.Borders.emptyTop(12) }

    val form = FormBuilder.createFormBuilder()
      .addComponent(hint)
      .addComponent(enabledCheck)
      .addLabeledComponent(JBLabel("API Key:"), keyRow, 1, false)
      .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, 1, false)
      .addLabeledComponent(JBLabel("Model:"), modelRow, 1, false)
      .addLabeledComponent(JBLabel("Typing delay (ms):"), typingDelaySpinner, 1, false)
      .addLabeledComponent(JBLabel("Max swarm blocks:"), maxBlocksSpinner, 1, false)
      .addComponent(fallbackCheck)
      .addComponent(diffPreviewCheck)
      .addLabeledComponent(JBLabel("Last swarm:"), lastSwarmLabel, 1, false)
      .addComponent(optimHeader)
      .addComponent(optimEnabledCheck)
      .addComponent(optimShowNameCheck)
      .addComponent(optimShowFactorsCheck)
      .addLabeledComponent(JBLabel("Optim live refresh (ms):"), optimRefreshSpinner, 1, false)
      .addComponentFillVertically(JPanel(), 0)
      .panel
    panel = form
    return form
  }

  private fun syncPresetFromField() {
    if (syncingModelUi) return
    syncingModelUi = true
    val preset = AiModels.findById(modelField.text.trim())
    modelPresetCombo.selectedItem = preset?.label ?: AiModels.CUSTOM_LABEL
    syncingModelUi = false
  }

  override fun isModified(): Boolean {
    val settings = AiSettings.getInstance()
    val keyTyped = String(apiKeyField.password).isNotEmpty()
    return keyTyped ||
      enabledCheck.isSelected != settings.enabled ||
      baseUrlField.text.trim() != settings.baseUrl ||
      modelField.text.trim() != settings.model ||
      (typingDelaySpinner.value as Int) != settings.typingDelayMs ||
      (maxBlocksSpinner.value as Int) != settings.maxBlocks ||
      fallbackCheck.isSelected != settings.fallbackToTemplates ||
      diffPreviewCheck.isSelected != settings.diffPreviewEnabled ||
      optimEnabledCheck.isSelected != settings.optimHintsEnabled ||
      optimShowNameCheck.isSelected != settings.optimShowFunctionName ||
      optimShowFactorsCheck.isSelected != settings.optimShowFactors ||
      (optimRefreshSpinner.value as Int) != settings.optimRefreshDelayMs
  }

  override fun apply() {
    val settings = AiSettings.getInstance()
    val key = String(apiKeyField.password)
    settings.enabled = enabledCheck.isSelected
    settings.baseUrl = baseUrlField.text
    settings.model = modelField.text
    settings.typingDelayMs = typingDelaySpinner.value as Int
    settings.maxBlocks = maxBlocksSpinner.value as Int
    settings.fallbackToTemplates = fallbackCheck.isSelected
    settings.diffPreviewEnabled = diffPreviewCheck.isSelected
    settings.optimHintsEnabled = optimEnabledCheck.isSelected
    settings.optimShowFunctionName = optimShowNameCheck.isSelected
    settings.optimShowFactors = optimShowFactorsCheck.isSelected
    settings.optimRefreshDelayMs = optimRefreshSpinner.value as Int
    if (key.isNotEmpty()) {
      settings.setApiKey(key)
    }
    apiKeyField.text = ""
    apiKeyField.emptyText.text = "•••••••• (сохранён) / вставьте новый"
    FunctionOptimInlayController.refreshAllEditors()
    AiCommandSyntaxHighlighter.refreshAllEditors()
  }

  override fun reset() {
    val settings = AiSettings.getInstance()
    enabledCheck.isSelected = settings.enabled
    apiKeyField.text = ""
    apiKeyField.emptyText.text = if (settings.hasApiKey()) "•••••••• (сохранён)" else "вставьте ключ"
    baseUrlField.text = settings.baseUrl
    syncingModelUi = true
    modelField.text = settings.model
    modelPresetCombo.selectedItem =
      AiModels.findById(settings.model)?.label ?: AiModels.CUSTOM_LABEL
    syncingModelUi = false
    typingDelaySpinner.value = settings.typingDelayMs
    maxBlocksSpinner.value = settings.maxBlocks
    fallbackCheck.isSelected = settings.fallbackToTemplates
    diffPreviewCheck.isSelected = settings.diffPreviewEnabled
    lastSwarmLabel.text = settings.lastSwarmSummary()
    optimEnabledCheck.isSelected = settings.optimHintsEnabled
    optimShowNameCheck.isSelected = settings.optimShowFunctionName
    optimShowFactorsCheck.isSelected = settings.optimShowFactors
    optimRefreshSpinner.value = settings.optimRefreshDelayMs

    ApplicationManager.getApplication().executeOnPooledThread {
      settings.refreshApiKeyCache()
      ApplicationManager.getApplication().invokeLater {
        if (String(apiKeyField.password).isNotEmpty()) return@invokeLater
        apiKeyField.emptyText.text =
          if (settings.hasApiKey()) "•••••••• (сохранён)" else "вставьте ключ"
      }
    }
  }

  override fun disposeUIResources() {
    panel = null
  }
}

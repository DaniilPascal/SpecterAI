package io.github.daniilpascal.specterai.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder


class ShowSpecterHelpAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    HelpDialog(e.project).show()
  }

  private class HelpDialog(project: Project?) : DialogWrapper(project, true) {

    init {
      title = "SpecterAI — Help"
      setOKButtonText("Close")
      init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
      val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4, 4, 12, 4)
        alignmentX = Component.LEFT_ALIGNMENT

        add(hero())
        add(Box.createVerticalStrut(JBUI.scale(14)))
        add(section("Commands", "Type in the editor, finish with semicolon"))
        add(codeBlock(
          """Specter.AI("your prompt").start(14);
S.AI("your prompt").start(14);
Specter.AI.CEO();""",
        ))
        add(hintRow(
          "start(N)",
          "Typing speed 1…100 (1 slow → 100 fast). Omit N to use Settings delay.",
        ))
        add(hintRow(
          "CEO",
          "Specter.AI.CEO(); — swarm-types // CEO SpecterAI Daniil_Pascal (Даниил Машичев) at EOF.",
        ))
        add(hintRow(
          "DSL colors",
          "Keyword · soft green AI/prompt · white .start(); / .CEO();",
        ))

        add(Box.createVerticalStrut(JBUI.scale(16)))
        add(section("AI output tags", "Where generated blocks land"))
        add(tagTable(
          "### near" to "Near the caret",
          "### imports" to "Import / include / package area",
          "### class" to "Members inside the enclosing type",
          "### file" to "Append at end of current file",
          "### file:FooTest.kt" to "Create or open another relative file",
        ))
        add(hintRow(
          "Language",
          "Matches the current file — Java, Kotlin, Python, C/C++, JS/TS, Go, …",
        ))

        add(Box.createVerticalStrut(JBUI.scale(16)))
        add(section("While busy", "Queue, cancel, progress"))
        add(bulletList(
          "A second .start(); is queued (up to 8)",
          "Esc or SpecterAI → Cancel AI — stop job & clear queue",
          "LARGE / XLARGE — live progress in the status bar",
          "Diff preview — Accept / Reject (Esc = reject, no edits)",
        ))

        add(Box.createVerticalStrut(JBUI.scale(16)))
        add(section("Optim %", "Live quality badges on functions"))
        add(bulletList(
          "Kotlin, Java, Python, JS/TS, Go, C/C++",
          "Click a badge → Explain / Fix with AI",
          "Toggle: SpecterAI → Optim %",
        ))

        add(Box.createVerticalStrut(JBUI.scale(16)))
        add(section("Menu & Settings", "Where everything lives"))
        add(tagTable(
          "SpecterAI → AI Swarm" to "Enable / disable commands",
          "SpecterAI → Optim %" to "Show / hide badges",
          "SpecterAI → Cancel AI" to "Stop current job",
          "SpecterAI → Help" to "This window",
          "Settings → Tools → SpecterAI" to "API key, model, delays, preview…",
        ))

        add(Box.createVerticalStrut(JBUI.scale(16)))
        add(footer())
      }

      
      val wrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        background = UIUtil.getPanelBackground()
        add(content, BorderLayout.NORTH)
      }

      return JBScrollPane(wrapper).apply {
        border = JBUI.Borders.customLine(DIVIDER, 1)
        preferredSize = Dimension(JBUI.scale(580), JBUI.scale(520))
        verticalScrollBar.unitIncrement = JBUI.scale(16)
      }
    }

    private fun hero(): JComponent = JPanel(BorderLayout()).apply {
      isOpaque = true
      background = HERO_BG
      border = CompoundBorder(
        JBUI.Borders.customLine(ACCENT, 0, 0, 0, 3),
        JBUI.Borders.empty(16, 18),
      )
      maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(92))
      alignmentX = Component.LEFT_ALIGNMENT

      add(
        JBLabel("SpecterAI").apply {
          font = JBFont.h2().asBold()
          foreground = UIUtil.getLabelForeground()
        },
        BorderLayout.NORTH,
      )
      add(
        JBLabel(
          "<html><body style='width:480px;margin-top:6px;color:${hex(MUTED)}'>" +
            "Ghost cursors swarm-type AI-generated code into the current file " +
            "(and optional companion files)." +
            "</body></html>",
        ).apply {
          border = JBUI.Borders.emptyTop(6)
        },
        BorderLayout.CENTER,
      )
    }

    private fun section(title: String, subtitle: String): JComponent =
      JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(48))
        border = JBUI.Borders.empty(0, 6, 8, 6)

        add(
          JBLabel(title).apply {
            font = JBFont.medium().asBold()
            foreground = UIUtil.getLabelForeground()
          },
          BorderLayout.NORTH,
        )
        add(
          JBLabel(subtitle).apply {
            font = JBFont.small()
            foreground = MUTED
            border = JBUI.Borders.emptyTop(2)
          },
          BorderLayout.SOUTH,
        )
      }

    private fun codeBlock(text: String): JComponent =
      JPanel(BorderLayout()).apply {
        isOpaque = true
        background = CODE_BG
        alignmentX = Component.LEFT_ALIGNMENT
        border = CompoundBorder(
          JBUI.Borders.customLine(CODE_BORDER, 1),
          JBUI.Borders.empty(10, 12),
        )
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(78))

        add(
          JBLabel(
            "<html><pre style='margin:0;font-family:monospace;font-size:12px;" +
              "color:${hex(UIUtil.getLabelForeground())}'>${text.escapeHtml()}</pre></html>",
          ),
          BorderLayout.CENTER,
        )
      }

    private fun hintRow(label: String, body: String): JComponent =
      JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(6, 8, 2, 8)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))

        add(
          JBLabel(label).apply {
            font = JBFont.small().asBold()
            foreground = ACCENT
            verticalAlignment = SwingConstants.TOP
          },
          BorderLayout.WEST,
        )
        add(
          JBLabel(
            "<html><body style='width:400px;color:${hex(MUTED)}'>$body</body></html>",
          ).apply {
            font = JBFont.small()
          },
          BorderLayout.CENTER,
        )
      }

    private fun tagTable(vararg rows: Pair<String, String>): JComponent =
      JPanel(GridBagLayout()).apply {
        isOpaque = true
        background = CARD_BG
        alignmentX = Component.LEFT_ALIGNMENT
        border = CompoundBorder(
          JBUI.Borders.customLine(DIVIDER, 1),
          JBUI.Borders.empty(4, 0),
        )

        rows.forEachIndexed { index, (key, value) ->
          val y = index
          val stripe = if (index % 2 == 0) CARD_BG else CARD_STRIPE
          val keyCell = cell(key, bold = true, mono = true, bg = stripe, accent = true)
          val valCell = cell(value, bold = false, mono = false, bg = stripe, accent = false)

          val gbcKey = GridBagConstraints().apply {
            gridx = 0
            gridy = y
            weightx = 0.42
            fill = GridBagConstraints.BOTH
            anchor = GridBagConstraints.WEST
          }
          val gbcVal = GridBagConstraints().apply {
            gridx = 1
            gridy = y
            weightx = 0.58
            fill = GridBagConstraints.BOTH
            anchor = GridBagConstraints.WEST
          }
          add(keyCell, gbcKey)
          add(valCell, gbcVal)
        }
      }

    private fun cell(
      text: String,
      bold: Boolean,
      mono: Boolean,
      bg: Color,
      accent: Boolean,
    ): JComponent = JBLabel(text).apply {
      isOpaque = true
      background = bg
      foreground = if (accent) ACCENT else UIUtil.getLabelForeground()
      font = when {
        mono && bold -> JBFont.small().asBold()
        mono -> JBFont.small()
        bold -> JBFont.regular().asBold()
        else -> JBFont.small()
      }
      border = JBUI.Borders.empty(7, 12)
    }

    private fun bulletList(vararg items: String): JComponent =
      JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = CARD_BG
        alignmentX = Component.LEFT_ALIGNMENT
        border = CompoundBorder(
          JBUI.Borders.customLine(DIVIDER, 1),
          JBUI.Borders.empty(8, 12),
        )
        items.forEach { item ->
          add(
            JBLabel(
              "<html><body style='width:480px'>" +
                "<span style='color:${hex(ACCENT)}'>●</span>&nbsp;&nbsp;$item" +
                "</body></html>",
            ).apply {
              border = JBUI.Borders.empty(3, 0)
              alignmentX = Component.LEFT_ALIGNMENT
            },
          )
        }
      }

    private fun footer(): JComponent =
      JBLabel(
        "<html><body style='width:500px;color:${hex(MUTED)};text-align:center'>" +
          "Works in IDEA · Android Studio · PyCharm · CLion · WebStorm · GoLand · RustRover<br>" +
          "JetBrains platform <b>2025.1+</b>" +
          "</body></html>",
      ).apply {
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(8, 6, 4, 6)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(48))
      }

    private fun String.escapeHtml(): String =
      replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")

    private fun hex(color: Color): String =
      String.format("#%02x%02x%02x", color.red, color.green, color.blue)
  }

  companion object {
    private val ACCENT = JBColor(Color(70, 130, 95), Color(140, 175, 150))
    private val MUTED = JBColor(Color(110, 110, 110), Color(160, 160, 160))
    private val HERO_BG = JBColor(Color(245, 248, 246), Color(45, 52, 48))
    private val CARD_BG = JBColor(Color(250, 250, 250), Color(49, 51, 53))
    private val CARD_STRIPE = JBColor(Color(242, 244, 242), Color(55, 58, 56))
    private val CODE_BG = JBColor(Color(248, 249, 248), Color(40, 44, 42))
    private val CODE_BORDER = JBColor(Color(210, 220, 212), Color(70, 80, 74))
    private val DIVIDER = JBColor(Color(220, 220, 220), Color(70, 70, 70))
  }
}

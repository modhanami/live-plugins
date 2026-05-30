import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.TableView
import com.intellij.ui.TableSpeedSearch
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.event.DocumentListener
import javax.swing.event.DocumentEvent
import java.awt.BorderLayout
import java.awt.FlowLayout
import liveplugin.show
import liveplugin.registerAction

// Configuration Constants
private val PREFIX_KEY = "sql.custom.comment.marker.prefix"
private val LANGS_KEY = "sql.custom.comment.marker.languages"
private val DEFAULT_PREFIX = "-- "
private val DEFAULT_LANGS = "MySQL,MariaDB"

private val ACTION_ID = "SQL Custom Comment Marker"
private val ACTION_KEYSTROKE = "ctrl alt shift C"
private val ACTION_GROUP = "ToolsMenu"

// Class representing our custom commenter that delegates block comments
class CustomCommenter(
    val delegate: Commenter?,
    private val lineCommentPrefixOverride: String
) : Commenter {
    override fun getLineCommentPrefix() = lineCommentPrefixOverride
    override fun getBlockCommentPrefix() = delegate?.blockCommentPrefix
    override fun getBlockCommentSuffix() = delegate?.blockCommentSuffix
    override fun getCommentedBlockCommentPrefix() = delegate?.commentedBlockCommentPrefix
    override fun getCommentedBlockCommentSuffix() = delegate?.commentedBlockCommentSuffix
}

// Unwraps custom commenters recursively to get the original IDE commenter
fun getTrueOriginalCommenter(lang: Language): Commenter? {
    var commenter = LanguageCommenters.INSTANCE.forLanguage(lang)
    while (commenter is CustomCommenter) {
        commenter = commenter.delegate
    }
    return commenter
}

// Track active registrations to allow clearing them on configuration updates
val activeDisposables = mutableListOf<Disposable>()

fun clearExistingOverrides() {
    activeDisposables.forEach { Disposer.dispose(it) }
    activeDisposables.clear()
}

fun applyOverrides(langIds: List<String>, prefix: String, showNotification: Boolean = false) {
    clearExistingOverrides()
    val modified = mutableListOf<String>()

    langIds.forEach { langId ->
        val lang = Language.findLanguageByID(langId)
        if (lang != null) {
            val original = getTrueOriginalCommenter(lang)
            if (original?.lineCommentPrefix != prefix) {
                val custom = CustomCommenter(original, prefix)
                LanguageCommenters.INSTANCE.addExplicitExtension(lang, custom)
                
                val disposable = Disposable {
                    LanguageCommenters.INSTANCE.removeExplicitExtension(lang, custom)
                }
                Disposer.register(pluginDisposable, disposable)
                activeDisposables.add(disposable)
            }
            modified.add(lang.displayName)
        }
    }
    
    if (showNotification && modified.isNotEmpty()) {
        show("$ACTION_ID set to '$prefix' for: ${modified.joinToString(", ")}")
    }
}

// Data class representing a dialect item in the list
class DialectItem(
    val langId: String,
    val displayName: String,
    val defaultPrefix: String,
    var isEnabled: Boolean
)

// Load all dialects and their enabled state
fun loadConfiguration(): List<DialectItem> {
    val properties = PropertiesComponent.getInstance()
    val currentLangsStr = properties.getValue(LANGS_KEY, DEFAULT_LANGS)
    val currentLangs = currentLangsStr.split(",").map { it.trim() }.toSet()

    val sqlLanguages = Language.getRegisteredLanguages()
        .filter { it.isKindOf("SQL") }
        .sortedBy { it.displayName }

    return sqlLanguages.map { lang ->
        val originalCommenter = getTrueOriginalCommenter(lang)
        val defaultPrefix = originalCommenter?.lineCommentPrefix ?: "None"
        val isEnabled = lang.id in currentLangs
        DialectItem(lang.id, lang.displayName, defaultPrefix, isEnabled)
    }
}

// Register the settings dialog action under the Tools menu
val configureAction = object : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val properties = PropertiesComponent.getInstance()
        
        val currentPrefix = properties.getValue(PREFIX_KEY, DEFAULT_PREFIX)
        val items = loadConfiguration()

        // 1. Build UI Panel
        val mainPanel = JPanel(BorderLayout(0, 10))

        // Top configuration panel (Prefix + Filter Checkbox)
        val topPanel = JPanel(BorderLayout(0, 5))
        
        val prefixPanel = JPanel(BorderLayout(10, 0))
        prefixPanel.add(JBLabel("Line comment prefix:"), BorderLayout.WEST)
        val prefixField = JBTextField(currentPrefix)
        prefixPanel.add(prefixField, BorderLayout.CENTER)
        topPanel.add(prefixPanel, BorderLayout.NORTH)
        
        val hideSameCheckbox = JBCheckBox("Hide dialects that already default to the custom prefix", true)
        topPanel.add(hideSameCheckbox, BorderLayout.SOUTH)
        
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Table view columns
        val enabledColumn = object : ColumnInfo<DialectItem, Boolean>("Enabled") {
            override fun valueOf(item: DialectItem) = item.isEnabled
            override fun getColumnClass() = java.lang.Boolean::class.java
            override fun isCellEditable(item: DialectItem) = true
            override fun setValue(item: DialectItem, value: Boolean) {
                item.isEnabled = value
            }
        }

        val dialectColumn = object : ColumnInfo<DialectItem, String>("Dialect") {
            override fun valueOf(item: DialectItem) = item.displayName
        }

        val defaultColumn = object : ColumnInfo<DialectItem, String>("Default Prefix") {
            override fun valueOf(item: DialectItem) = item.defaultPrefix
        }

        val columns = arrayOf(enabledColumn, dialectColumn, defaultColumn)
        val tableModel = ListTableModel(columns, mutableListOf<DialectItem>())
        val tableView = TableView(tableModel)

        tableView.columnModel.getColumn(0).preferredWidth = 60
        tableView.columnModel.getColumn(1).preferredWidth = 240
        tableView.columnModel.getColumn(2).preferredWidth = 100

        // Filter logic
        fun updateFilter() {
            val prefix = prefixField.text
            val hideSame = hideSameCheckbox.isSelected
            val filtered = if (hideSame) {
                items.filter { it.defaultPrefix != prefix || it.isEnabled }
            } else {
                items
            }
            tableModel.items = filtered
            tableModel.fireTableDataChanged()
        }

        // Attach listeners
        hideSameCheckbox.addActionListener { updateFilter() }
        prefixField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateFilter()
            override fun removeUpdate(e: DocumentEvent?) = updateFilter()
            override fun changedUpdate(e: DocumentEvent?) = updateFilter()
        })

        // Run initial filter to populate table
        updateFilter()

        // Enable SpeedSearch on the table
        TableSpeedSearch(tableView)

        val scrollPane = JBScrollPane(tableView)
        scrollPane.preferredSize = java.awt.Dimension(400, 250)
        
        val listPanel = JPanel(BorderLayout(0, 5))
        listPanel.add(scrollPane, BorderLayout.CENTER)

        // Bulk selection buttons
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        val selectAllBtn = JButton("Select All")
        selectAllBtn.addActionListener {
            // Toggle selected/displayed items in the table
            tableModel.items.forEach { it.isEnabled = true }
            tableModel.fireTableDataChanged()
        }
        val clearAllBtn = JButton("Clear All")
        clearAllBtn.addActionListener {
            tableModel.items.forEach { it.isEnabled = false }
            tableModel.fireTableDataChanged()
        }
        actionPanel.add(selectAllBtn)
        actionPanel.add(clearAllBtn)
        listPanel.add(actionPanel, BorderLayout.SOUTH)

        mainPanel.add(listPanel, BorderLayout.CENTER)

        val builder = DialogBuilder(project)
        builder.setTitle("SQL Custom Comment Marker Settings")
        builder.setCenterPanel(mainPanel)
        builder.addOkAction()
        builder.addCancelAction()

        if (builder.show() == DialogWrapper.OK_EXIT_CODE) {
            // Commit active table edits
            if (tableView.isEditing) {
                tableView.cellEditor.stopCellEditing()
            }

            val newPrefix = prefixField.text
            if (newPrefix.isEmpty()) {
                Messages.showErrorDialog(project, "Comment prefix cannot be empty!", "Invalid Input")
                return
            }
            
            val selectedLangs = items.filter { it.isEnabled }.map { it.langId }
            val newLangsStr = selectedLangs.joinToString(",")
            
            properties.setValue(PREFIX_KEY, newPrefix)
            properties.setValue(LANGS_KEY, newLangsStr)
            
            applyOverrides(selectedLangs, newPrefix, showNotification = true)
        }
    }
}

registerAction(
    id = ACTION_ID,
    keyStroke = ACTION_KEYSTROKE,
    actionGroupId = ACTION_GROUP,
    action = configureAction
)

// Initial load
val properties = PropertiesComponent.getInstance()
val initialPrefix = properties.getValue(PREFIX_KEY, DEFAULT_PREFIX)
val initialLangsStr = properties.getValue(LANGS_KEY, DEFAULT_LANGS)
val initialLangs = initialLangsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

applyOverrides(initialLangs, initialPrefix)

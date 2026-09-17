package slanglsp;

import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;

import java.awt.GridBagConstraints;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Vector;

public class SlangConfigurableGUI {
    private SlangPersistentStateConfig mConfig;
    private Project mProject;
    private JCheckBox useLocalVcpkgSlangd;

    private JCheckBox enableInlayHintsForDeducedTypes;
    private JCheckBox enableInlayHintsForParameterNames;
    private JCheckBox enableSearchingSubDirectoriesOfWorkspace;
    private JTextField explicitSlangdLocation;
    private JPanel root;
    private JButton additionalIncludePathsButton;
    private JButton predefinedMacrosButton;
    private JPanel additionalIncludePathsContainer;
    private JPanel predefinedMacrosContainer;
    private JComboBox enableCommitCharactersInAutoCompletion;
    private JLabel seperatorLabel1;
    private JLabel seperatorLabel0;

    JPanel getRootPanel()
    {
        return root;
    }

    Vector<JTextField> convertStringListIntoTextFieldVector(java.util.List<String> stringList)
    {
        Vector<JTextField> textFieldList = new Vector<>();
        textFieldList.setSize(stringList.size());
        for(int i = 0; i < stringList.size(); i++)
        {
            textFieldList.set(i, new JTextField(stringList.get(i)));
        }
        return textFieldList;
    }

    Vector<String> getStringListFromPanelOwnedTextFields(JPanel panel)
    {
        Vector<String> stringList = new Vector<>();
        for(var obj : panel.getComponents())
        {
            if(!(obj instanceof JTextField))
                continue;
            stringList.add(((JTextField)obj).getText());
        }
        return stringList;
    }

    boolean addedDefaultListeners = false;
    public void createUI(Project project)
    {
        mProject = project;
        mConfig = SlangPersistentStateConfig.getInstance(project);
        setGUIStateWithState(mConfig.getState());
        addDefaultListeners();
    }

    SlangPersistentStateConfig.State deriveStateFromGUI()
    {
        SlangPersistentStateConfig.State state = new SlangPersistentStateConfig.State();
        state.additionalIncludePaths = getStringListFromPanelOwnedTextFields(additionalIncludePathsContainer);
        state.predefinedMacros = getStringListFromPanelOwnedTextFields(predefinedMacrosContainer);

        state.explicitSlangdLocation = explicitSlangdLocation.getText();
        state.useLocalVcpkgSlangd = useLocalVcpkgSlangd.isSelected();

        state.enableCommitCharactersInAutoCompletion = (String)enableCommitCharactersInAutoCompletion.getSelectedItem();

        state.enableInlayHintsForDeducedTypes = enableInlayHintsForDeducedTypes.isSelected();
        state.enableInlayHintsForParameterNames = enableInlayHintsForParameterNames.isSelected();
        state.enableSearchingSubDirectoriesOfWorkspace = enableSearchingSubDirectoriesOfWorkspace.isSelected();

        return state;
    }

    void setGUIStateWithState(SlangPersistentStateConfig.State state)
    {
        setPanelContentToListOfObjects(additionalIncludePathsContainer, convertStringListIntoTextFieldVector(state.additionalIncludePaths));
        setPanelContentToListOfObjects(predefinedMacrosContainer, convertStringListIntoTextFieldVector(state.predefinedMacros));

        explicitSlangdLocation.setText(state.explicitSlangdLocation);
        useLocalVcpkgSlangd.setSelected(state.useLocalVcpkgSlangd);

        enableCommitCharactersInAutoCompletion.setSelectedItem(state.enableCommitCharactersInAutoCompletion);

        enableInlayHintsForDeducedTypes.setSelected(state.enableInlayHintsForDeducedTypes);
        enableInlayHintsForParameterNames.setSelected(state.enableInlayHintsForParameterNames);
        enableSearchingSubDirectoriesOfWorkspace.setSelected(state.enableSearchingSubDirectoriesOfWorkspace);

        root.revalidate();
        root.repaint();
        root.updateUI();
    }

    void addTextFieldToPanel(JPanel panel, JTextField field)
    {
        GridBagConstraints fieldConstraint = new GridBagConstraints();
        fieldConstraint.gridy = panel.getComponentCount();
        fieldConstraint.gridx = 0;
        fieldConstraint.weightx = 0;
        fieldConstraint.weighty = 1;
        fieldConstraint.anchor = GridBagConstraints.NORTHWEST;
        panel.add(field, fieldConstraint);

        GridBagConstraints buttonConstraint = new GridBagConstraints();
        buttonConstraint.gridy = fieldConstraint.gridy;
        buttonConstraint.gridx = 1;
        buttonConstraint.weightx = 1;
        buttonConstraint.weighty = 1;
        buttonConstraint.anchor = GridBagConstraints.NORTHEAST;
        JButton deleteButton = new JButton("-");
        deleteButton.setBackground(new JBColor(new Color(0, 120, 229), new Color(0, 120, 229)));
        deleteButton.addActionListener(new ActionListenerDeleteObjWhenClicked(panel, deleteButton, field));
        panel.add(deleteButton, buttonConstraint);

        panel.revalidate();
        panel.repaint();
    }

    void setPanelContentToListOfObjects(JPanel panel, Vector<JTextField> objects)
    {
        panel.removeAll();
        for(var i : objects)
        {
            addTextFieldToPanel(panel, i);
        }
    }

    public void apply()
    {
        var next = deriveStateFromGUI();
        var previous = mConfig.getState();
        boolean executableChanged = next.useLocalVcpkgSlangd != previous.useLocalVcpkgSlangd
                || !next.explicitSlangdLocation.equals(previous.explicitSlangdLocation);
        mConfig.setState(next);
        if (executableChanged) {
            mProject.getService(VcpkgSlangdService.class).invalidate();
            var manager = com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(mProject);
            if (manager.getServerStatus("slanglsp.SlangLanguageServer") == com.redhat.devtools.lsp4ij.ServerStatus.started) {
                manager.start("slanglsp.SlangLanguageServer",
                        new com.redhat.devtools.lsp4ij.LanguageServerManager.StartOptions().setForceStart(true));
            }
        }
        for (var client : SlangLanguageClient.maybeAliveClients)
            if (client.project == mProject) client.triggerChangeConfiguration();
    }

    public void reset()
    {
        setGUIStateWithState(mConfig.getState());
    }

    public boolean isModified()
    {
        return !mConfig.getState().equals(deriveStateFromGUI());
    }

    class ActionListenerAddWhenClicked implements ActionListener
    {
        JPanel toModify;
        ActionListenerAddWhenClicked(JPanel toModify)
        {
            this.toModify = toModify;
        }

        public void actionPerformed(ActionEvent e)
        {
            addTextFieldToPanel(toModify, new JTextField("..."));
        }
    }

    class ActionListenerDeleteObjWhenClicked implements ActionListener
    {
        JPanel parentPanel;
        JButton listeningObject;
        JTextField pairedObject;
        ActionListenerDeleteObjWhenClicked(JPanel parentPanel, JButton listeningObject, JTextField pairedObject)
        {
            this.parentPanel = parentPanel;
            this.listeningObject = listeningObject;
            this.pairedObject = pairedObject;
        }

        public void actionPerformed(ActionEvent e)
        {
            GridBagConstraints removedLayout = (GridBagConstraints)listeningObject.getLayout();
            parentPanel.remove(listeningObject);
            parentPanel.remove(pairedObject);
            for(var i : parentPanel.getComponents())
            {
                if(!(i instanceof JComponent))
                    continue;

                JComponent jcomponent = (JComponent)i;

                if(!(jcomponent.getLayout() instanceof GridBagConstraints))
                    continue;

                GridBagConstraints layoutToModify = (GridBagConstraints) jcomponent.getLayout();
                if(layoutToModify.gridy > removedLayout.gridy)
                {
                    layoutToModify.gridy -= 1;
                }
            }

            parentPanel.revalidate();
            parentPanel.repaint();
            parentPanel.updateUI();
        }
    }

    private void addDefaultListeners()
    {
        if(addedDefaultListeners)
            return;

        addedDefaultListeners = true;
        additionalIncludePathsButton.addActionListener(new ActionListenerAddWhenClicked(additionalIncludePathsContainer));
        predefinedMacrosButton.addActionListener(new ActionListenerAddWhenClicked(predefinedMacrosContainer));
    }
}

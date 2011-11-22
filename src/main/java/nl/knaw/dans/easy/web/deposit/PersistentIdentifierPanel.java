package nl.knaw.dans.easy.web.deposit;

import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;

import nl.knaw.dans.easy.domain.model.emd.EasyMetadata;
import nl.knaw.dans.easy.web.deposit.repeater.AbstractCustomPanel;

public class PersistentIdentifierPanel extends AbstractCustomPanel
{
    
    private static final long serialVersionUID = -2435808589132843377L;
    
    public static final String PI_URL = "http://www.persistent-identifier.nl?identifier=";
    
    private final String pid;

    public PersistentIdentifierPanel(String id, IModel<EasyMetadata> model)
    {
        super(id, model);
        setOutputMarkupId(true);
        EasyMetadata easyMetadata = (EasyMetadata) model.getObject();
        pid = easyMetadata.getEmdIdentifier().getPersistentIdentifier();
    }
    
    public PersistentIdentifierPanel(String id, String pid)
    {
        super(id);
        setOutputMarkupId(true);
        this.pid = pid;
    }

    @Override
    protected Panel getCustomComponentPanel()
    {
        return new CustomPanel();
    }
    
    class CustomPanel extends Panel
    {

        private static final long serialVersionUID = 1312199834066009539L;

        public CustomPanel()
        {
            super(CUSTOM_PANEL_ID);
            ExternalLink link = new ExternalLink("pid", PI_URL + pid, pid);
            add(link);
            setVisible(pid != null);
        }
    }

}
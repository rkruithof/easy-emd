package nl.knaw.dans.easy.web;

import nl.knaw.dans.easy.web.editabletexts.EasyEditablePanel;
import nl.knaw.dans.easy.web.main.AbstractEasyNavPage;
import nl.knaw.dans.easy.web.statistics.StatisticsEvent;
import nl.knaw.dans.easy.web.statistics.StatisticsLogger;

/**
 * Easy web application home page.
 */
public class HomePage extends AbstractEasyNavPage
{
    public static final String EDITABLE_HOMEPAGE_TEMPLATE = "/pages/HomePage.template";

    public HomePage()
    {
        addCommonFeedbackPanel();
        add(new EasyEditablePanel("editablePanel", EDITABLE_HOMEPAGE_TEMPLATE));
        StatisticsLogger.getInstance().logEvent(StatisticsEvent.START_PAGE_VISIT);
    }

}

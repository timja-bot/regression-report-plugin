package jp.skypencil.jenkins.regression;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import hudson.model.Descriptor.FormException;
import hudson.model.FreeStyleProject;

import java.io.IOException;
import java.util.List;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;

public class RegressionReportConfigTest {
    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private static final String NAME_CHECKBOX = "jp-skypencil-jenkins-regression-RegressionReportNotifier";
    private static final String JOB_NAME = "Test";

    @Test
    @Ignore
    public void testEnabledIsTrue() throws FormException, IOException,
            SAXException {
        testEnabled(true);
    }

    @Test
    @Ignore
    public void testEnabledIsFalse() throws FormException, IOException,
            SAXException {
        testEnabled(false);
    }

    private void testEnabled(boolean isEnabled) throws FormException,
            IOException, SAXException {
        FreeStyleProject project = jenkins.createFreeStyleProject(JOB_NAME);

        final WebClient client = new WebClient();
        try {
            HtmlPage configPage = client.getPage("job/" + JOB_NAME
                    + "/configure");
            HtmlForm form = configPage.getFormByName("config");
            form.getInputByName(NAME_CHECKBOX).setChecked(isEnabled);
            last(form.getElementsByTagName("button")).click();

            configPage = client.getPage("job/" + JOB_NAME + "/configure");
            form = configPage.getFormByName("config");
            HtmlInput checkbox = form.getInputByName(NAME_CHECKBOX);
            assertThat(checkbox.isChecked(), is(isEnabled));
        } finally {
            try {
                project.delete();
            } catch (InterruptedException ignore) {
                ignore.printStackTrace();
            }
            client.close();
        }
    }

    private HtmlButton last(List<HtmlElement> htmlElementsByTagName) {
        HtmlElement lastElement = htmlElementsByTagName.get(htmlElementsByTagName.size() - 1);
        return (HtmlButton) lastElement;
    }
}

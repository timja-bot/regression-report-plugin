package jp.skypencil.jenkins.regression;

import static com.google.common.collect.Iterables.transform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.kohsuke.stapler.DataBoundConstructor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.User;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Mailer;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TestResult;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * @version 1.0
 * @author eller86 (Kengo TODA)
 */
public final class RegressionReportNotifier extends Notifier {
    static interface MailSender {
        void send(MimeMessage message) throws MessagingException;
    }

    private static final int MAX_RESULTS_PER_MAIL = 20;
    private final String recipients;
    private final boolean sendToCulprits;
    private final boolean attachLog;
    private MailSender mailSender = new RegressionReportNotifier.MailSender() {
        @Override
        public void send(MimeMessage message) throws MessagingException {
            Transport.send(message);
        }
    };

    public RegressionReportNotifier(String recipients, boolean sendToCulprits) {
        this.recipients = recipients;
        this.sendToCulprits = sendToCulprits;
        this.attachLog = false;
    }

    @DataBoundConstructor
    public RegressionReportNotifier(String recipients, boolean sendToCulprits, boolean attachLog) {
        this.recipients = recipients;
        this.sendToCulprits = sendToCulprits;
        this.attachLog = attachLog;
    }

    @VisibleForTesting
    void setMailSender(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public String getRecipients() {
        return recipients;
    }

    public boolean getSendToCulprits() {
        return sendToCulprits;
    }

    public boolean getAttachLog() {
        return attachLog;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        if (build.getResult() == Result.SUCCESS) {
            logger.println("regression reporter doesn't run because build is success.");
            return true;
        }

        AbstractTestResultAction<?> testResultAction = build
                .getAction(AbstractTestResultAction.class);
        if (testResultAction == null) {
            // maybe compile error occurred
            logger.println("regression reporter doesn't run because test doesn\'t run.");
            return true;
        }

        logger.println("regression reporter starts now...");
        List<CaseResult> regressionedTests = listRegressions(testResultAction);

        writeToConsole(regressionedTests, listener);
        try {
            mailReport(regressionedTests, recipients, listener, build);
        } catch (MessagingException e) {
            e.printStackTrace(listener.error("failed to send mails."));
        }

        logger.println("regression reporter ends.");
        return true;
    }

    private List<CaseResult> listRegressions(
            AbstractTestResultAction<?> testResultAction) {
        List<? extends TestResult> failedTest = testResultAction.getFailedTests();
        Iterable<? extends TestResult> filtered = Iterables.filter(failedTest, new RegressionPredicate());
        List<CaseResult> regressionedTests =
                Lists.newArrayList(Iterables.transform(filtered, new TestResultToCaseResult()));
        return regressionedTests;
    }

    private void writeToConsole(List<CaseResult> regressions,
            BuildListener listener) {
        if (regressions.isEmpty()) {
            return;
        }

        PrintStream oStream = listener.getLogger();
        // TODO link to test result page
        for (CaseResult result : regressions) {
            // listener.hyperlink(url, text)
            oStream.printf("[REGRESSION]%s - description: %s%n",
                    result.getFullName(), result.getErrorDetails());
        }
    }

    private void mailReport(List<CaseResult> regressions, String recipients,
            BuildListener listener, AbstractBuild<?, ?> build)
            throws MessagingException, IOException {
        if (regressions.isEmpty()) {
            return;
        }

        // TODO link to test result page
        StringBuilder builder = new StringBuilder();
        String rootUrl = "";
        Session session = null;
        InternetAddress adminAddress = null;
        Jenkins jenkins = Jenkins.getInstance();
        JenkinsLocationConfiguration configuration = JenkinsLocationConfiguration.get();
        if (jenkins != null && configuration != null) {
            rootUrl = jenkins.getRootUrl();
            session = Mailer.descriptor().createSession();
            adminAddress = new InternetAddress(configuration.getAdminAddress());
        }
        builder.append(Util.encode(rootUrl));
        builder.append(Util.encode(build.getUrl()));
        builder.append("\n\n");
        builder.append(regressions.size() + " regressions found.");
        builder.append("\n");
        for (int i = 0, max = Math
                .min(regressions.size(), MAX_RESULTS_PER_MAIL); i < max; ++i) { // to
                                                                                // save
                                                                                // heap
                                                                                // to
                                                                                // avoid
                                                                                // OOME.
            CaseResult result = regressions.get(i);
            builder.append("  ");
            builder.append(result.getFullName());
            builder.append("\n");
        }
        if (regressions.size() > MAX_RESULTS_PER_MAIL) {
            builder.append("  ...");
            builder.append("\n");
        }
        List<Address> recipentList = parse(recipients, listener);
        if (sendToCulprits) {
            recipentList.addAll(loadAddrOfCulprits(build, listener));
        }

        MimeMessage message = new MimeMessage(session);
        message.setSubject(Messages.RegressionReportNotifier_MailSubject());
        message.setRecipients(RecipientType.TO,
                recipentList.toArray(new Address[recipentList.size()]));
        message.setContent("", "text/plain");
        message.setFrom(adminAddress);
        message.setText(builder.toString());
        message.setSentDate(new Date());

        if (attachLog) {
            attachLogFile(build, message, builder.toString());
        } 

        mailSender.send(message);
    }

    private Set<Address> loadAddrOfCulprits(AbstractBuild<?, ?> build,
            BuildListener listener) {
        Set<User> authorSet = Sets.newHashSet(transform(build.getChangeSet(),
                new ChangeSetToAuthor()));
        Set<Address> addressSet = Sets.newHashSet(transform(authorSet,
                new UserToAddr(listener.getLogger())));
        return addressSet;
    }

    private List<Address> parse(String recipients, BuildListener listener) {
        List<Address> list = Lists.newArrayList();
        StringTokenizer tokens = new StringTokenizer(recipients);
        while (tokens.hasMoreTokens()) {
            String address = tokens.nextToken();
            try {
                list.add(new InternetAddress(address));
            } catch (AddressException e) {
                e.printStackTrace(listener.error(e.getMessage()));
            }
        }

        return list;
    }

    private void attachLogFile(AbstractBuild<?, ?> build, MimeMessage message, String content)
            throws MessagingException, IOException {

        Multipart multipart = new MimeMultipart();

        BodyPart bodyText = new MimeBodyPart();
        bodyText.setText(content);
        multipart.addBodyPart(bodyText);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = build.getLogText().writeLogTo(0, out);
        assert written >= 0;

        BodyPart emailAttachment = new MimeBodyPart();
        DataSource source = new ByteArrayDataSource(out.toByteArray(), "text/plain");
        emailAttachment.setDataHandler(new DataHandler(source));
        emailAttachment.setFileName("buildLog.txt");
        multipart.addBodyPart(emailAttachment);

        message.setContent(multipart);
    }

    @Extension
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Publisher> {
        @Override
        public boolean isApplicable(
                @SuppressWarnings("rawtypes") Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.RegressionReportNotifier_DisplayName();
        }
    }
}

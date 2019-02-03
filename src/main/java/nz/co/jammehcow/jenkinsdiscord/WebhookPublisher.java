package nz.co.jammehcow.jenkinsdiscord;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import jenkins.model.JenkinsLocationConfiguration;
import nz.co.jammehcow.jenkinsdiscord.exception.WebhookException;
import nz.co.jammehcow.jenkinsdiscord.util.EmbedDescription;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.Map;

/**
 * Author: jammehcow.
 * Date: 22/04/17.
 */

public class WebhookPublisher extends Notifier {
    private final String webhookURL;
    private final String branchName;
    private final String statusTitle;
    private final String thumbnailURL;
    private final boolean sendOnStateChange;
    private boolean enableUrlLinking;
    private final boolean enableArtifactList;
    private final boolean enableFooterInfo;
    private static final String NAME = "Discord Notifier";
    private static final String VERSION = "1.4.4";

    @DataBoundConstructor
    public WebhookPublisher(String webhookURL, String thumbnailURL, boolean sendOnStateChange, String statusTitle, String branchName, boolean enableUrlLinking, boolean enableArtifactList, boolean enableFooterInfo) {
        this.webhookURL = webhookURL;
        this.thumbnailURL = thumbnailURL;
        this.sendOnStateChange = sendOnStateChange;
        this.enableUrlLinking = enableUrlLinking;
        this.enableArtifactList = enableArtifactList;
        this.enableFooterInfo = enableFooterInfo;
        this.branchName = branchName;
        this.statusTitle = statusTitle;
    }

    public String getWebhookURL() { return this.webhookURL; }
    public String getBranchName() { return this.branchName; }
    public String getStatusTitle() { return this.statusTitle; }

    public String getThumbnailURL() {
        return this.thumbnailURL;
    }
    public boolean isSendOnStateChange() { return this.sendOnStateChange; }
    public boolean isEnableUrlLinking() { return this.enableUrlLinking; }
    public boolean isEnableArtifactList() { return this.enableArtifactList; }
    public boolean isEnableFooterInfo() { return this.enableFooterInfo; }

    @Override
    public boolean needsToRunAfterFinalized() { return true; }

    //TODO clean this function
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        final EnvVars env = build.getEnvironment(listener);
        // The global configuration, used to fetch the instance url
        JenkinsLocationConfiguration globalConfig = JenkinsLocationConfiguration.get();
        if (globalConfig == null) {
            listener.getLogger().println("[Discord Notifier] JenkinsLocationConfiguration is null!");
            return true;
        }
        if (build.getResult() == null) {
            listener.getLogger().println("[Discord Notifier] build.getResult() is null!");
            return true;
        }

        // Create a new webhook payload
        DiscordWebhook wh = new DiscordWebhook(env.expand(this.webhookURL));

        if (this.webhookURL.isEmpty()) {
            // Stop the plugin from continuing when the webhook URL isn't set. Shouldn't happen due to form validation
            listener.getLogger().println("The Discord webhook is not set!");
            return true;
        }

        if (this.enableUrlLinking && (globalConfig.getUrl() == null || globalConfig.getUrl().isEmpty())) {
            // Disable linking when the instance URL isn't set
            listener.getLogger().println("Your Jenkins URL is not set (or is set to localhost)! Disabling linking.");
            this.enableUrlLinking = false;
        }

        if (this.sendOnStateChange) {
            if (build.getPreviousBuild() != null && build.getResult().equals(build.getPreviousBuild().getResult())) {
                // Stops the webhook payload being created if the status is the same as the previous
                return true;
            }
        }

        DiscordWebhook.StatusColor statusColor = DiscordWebhook.StatusColor.GREEN;
        Result buildresult = build.getResult();
        if (!buildresult.isCompleteBuild()) return true;
        if (buildresult.isBetterOrEqualTo(Result.SUCCESS)) statusColor = DiscordWebhook.StatusColor.GREEN;
        if (buildresult.isWorseThan(Result.SUCCESS)) statusColor = DiscordWebhook.StatusColor.YELLOW;
        if (buildresult.isWorseThan(Result.UNSTABLE)) statusColor = DiscordWebhook.StatusColor.RED;

        AbstractProject project = build.getProject();
        StringBuilder combinationString = new StringBuilder();
        if (this.statusTitle != null && !this.statusTitle.isEmpty()) {
            wh.setTitle(env.expand(this.statusTitle));
        } else {
            wh.setTitle(project.getDisplayName() + " #" + build.getId());
        }

        //Check if MatrixConfiguration
        if (project instanceof MatrixConfiguration) {
            wh.setTitle(project.getParent().getDisplayName() + " #" + build.getId());
            combinationString.append("**Configuration matrix:**\n");
            for (Map.Entry e : ((MatrixConfiguration) project).getCombination().entrySet())
                combinationString.append(" - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }

        String branchNameString ="";
        if (branchName != null && !branchName.isEmpty()) {
            branchNameString = "**Branch:** "+env.expand(branchName)+"\n";
        }

        String descriptionPrefix;
        // Adds links to the description and title if enableUrlLinking is enabled
        if (this.enableUrlLinking) {
            String url = globalConfig.getUrl() + build.getUrl();
            descriptionPrefix = branchNameString
                    + "**Build:** "
                    + getMarkdownHyperlink(build.getId(), url)
                    + "\n**Status:** "
                    + getMarkdownHyperlink(build.getResult().toString().toLowerCase(), url) + "\n";
            wh.setURL(url);
        } else {
            descriptionPrefix = branchNameString
                    + "**Build:** "
                    + build.getId()
                    + "\n**Status:** "
                    + build.getResult().toString().toLowerCase() + "\n";
        }
        descriptionPrefix += combinationString;

        wh.setThumbnail(thumbnailURL);
        wh.setDescription(new EmbedDescription(build, globalConfig, descriptionPrefix, this.enableArtifactList).toString());
        wh.setStatus(statusColor);

        if (this.enableFooterInfo) wh.setFooter("Jenkins v" + build.getHudsonVersion() + ", " + getDescriptor().getDisplayName() + " v" + getDescriptor().getVersion());

        try {
            listener.getLogger().println("Sending notification to Discord.");
            wh.send();
        } catch (WebhookException e) {
            e.printStackTrace(listener.getLogger());
        }

        return true;
    }


    public BuildStepMonitor getRequiredMonitorService() { return BuildStepMonitor.NONE; }


    @Override
    public DescriptorImpl getDescriptor() { return (DescriptorImpl) super.getDescriptor(); }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public boolean isApplicable(Class<? extends AbstractProject> aClass) { return true; }

        public FormValidation doCheckWebhookURL(@QueryParameter String value) {
            if (!value.matches("https://(canary\\.|ptb\\.|)discordapp\\.com/api/webhooks/\\d{18}/(\\w|-|_)*(/?)"))
                return FormValidation.error("Please enter a valid Discord webhook URL.");
            return FormValidation.ok();
        }

        public String getDisplayName() { return NAME; }

        public String getVersion() { return VERSION; }
    }
    
    private static String getMarkdownHyperlink(String content, String url) {
        url = url.replaceAll("\\)", "\\\\\\)");
        return "[" + content + "](" + url + ")";
    }
}

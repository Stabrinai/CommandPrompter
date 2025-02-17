package com.cyr1en.commandprompter.prompt;

import com.cyr1en.commandprompter.CommandPrompter;
import com.cyr1en.commandprompter.PluginLogger;
import com.cyr1en.commandprompter.api.Dispatcher;
import com.cyr1en.commandprompter.api.prompt.Prompt;
import com.cyr1en.commandprompter.util.MMUtil;
import com.cyr1en.kiso.utils.SRegex;
import fr.euphyllia.energie.model.SchedulerType;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class PromptQueue extends LinkedList<Prompt> {

    private String command;
    private final LinkedList<String> completed;
    private final String escapedRegex;

    private final boolean isOp;
    private final boolean isConsoleDelegate;
    private String permissionAttachmentKey;

    private final List<PostCommandMeta> postCommandMetas;

    private final PluginLogger logger;

    public PromptQueue(String command, boolean isOp, boolean isDelegate,
                       String escapedRegex) {
        super();
        this.command = command;
        this.escapedRegex = escapedRegex;
        this.completed = new LinkedList<>();
        this.isOp = isOp;
        this.isConsoleDelegate = isDelegate;
        this.permissionAttachmentKey = "";
        this.postCommandMetas = new LinkedList<>();
        logger = CommandPrompter.getInstance().getPluginLogger();
    }

    public void addCompleted(String s) {
        completed.add(s);
    }

    public boolean isOp() {
        return isOp;
    }

    public String getPermissionAttachmentKey() {
        return permissionAttachmentKey;
    }

    public void setPermissionAttachmentKey(String key) {
        this.permissionAttachmentKey = key;
    }

    public boolean isConsoleDelegate() {
        return isConsoleDelegate;
    }

    public String getCompleteCommand() {
        command = command.formatted(completed);
        LinkedList<String> completedClone = new LinkedList<>(this.completed);

        // get all prompts that we have to replace in the command
        var sRegex = new SRegex();
        var prompts = sRegex.find(Pattern.compile(escapedRegex), command).getResultsList();
        prompts = MMUtil.filterOutMiniMessageTags(prompts);

        for (String prompt : prompts) {
            if (completedClone.isEmpty())
                break;
            command = command.replace(prompt, completedClone.pollFirst());
        }
        return "/" + command;
    }

    public void addPCM(PostCommandMeta pcm) {
        postCommandMetas.add(pcm);
    }

    public boolean containsPCM() {
        return postCommandMetas != null && !postCommandMetas.isEmpty();
    }

    public List<PostCommandMeta> getPostCommandMetas() {
        return postCommandMetas;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public void dispatch(CommandPrompter plugin, Player sender) {
        if (isConsoleDelegate()) {
            logger.debug("Dispatching as console");
            Dispatcher.dispatchConsole(getCompleteCommand());
        } else if (!permissionAttachmentKey.isBlank()) {
            Dispatcher.dispatchWithAttachment(plugin, sender, getCompleteCommand(),
                    plugin.getConfiguration().permissionAttachmentTicks(),
                    plugin.getConfiguration().getPermissionAttachment(permissionAttachmentKey));
        } else
            Dispatcher.dispatchCommand(plugin, sender, getCompleteCommand());

        if (!postCommandMetas.isEmpty())
            postCommandMetas.forEach(pcm -> {
                if (pcm.isOnCancel())
                    return;

                if (pcm.delayTicks() > 0)
                    plugin.getScheduler().runDelayed(SchedulerType.SYNC, task -> execPCM(pcm, sender),
                            pcm.delayTicks());
                else
                    execPCM(pcm, sender);
            });
    }

    void execPCM(PostCommandMeta postCommandMeta, Player sender) {
        logger.debug("Executing PCM: " + postCommandMeta.command());

        var completedClone = new LinkedList<>(completed);
        var i18N = CommandPrompter.getInstance().getI18N();
        var command = postCommandMeta.makeAsCommand(completedClone, index -> {
            var message = i18N.getFormattedProperty("PCMOutOfBounds", index);
            CommandPrompter.getInstance().getMessenger().sendMessage(sender, message);
        });
        logger.debug("After parse: " + command);

        if (isConsoleDelegate()) {
            logger.debug("Dispatching PostCommand as console");
            Dispatcher.dispatchConsole(command);
        } else {
            logger.debug("Dispatching PostCommand as player");
            Dispatcher.dispatchCommand(CommandPrompter.getInstance(), sender, command);
        }

    }

    /**
     * @param promptIndex This will hold the index of the prompt answers to be
     *                    injected in this post command.
     */
    public record PostCommandMeta(String command, int[] promptIndex, int delayTicks, boolean isOnCancel) {
        @Override
        public String toString() {
            return "PostCommandMeta{" + "command='" + command + '\'' + ", promptIndex=" + Arrays.toString(promptIndex)
                    + ", delayTicks=" + delayTicks + ", isOnCancel=" + isOnCancel + '}';
        }

        public String makeAsCommand(LinkedList<String> promptAnswers) {
            return makeAsCommand(promptAnswers, index -> {
            });
        }

        public String makeAsCommand(LinkedList<String> promptAnswers, Consumer<String> onOutOfBounds) {
            if (promptAnswers == null || promptAnswers.isEmpty())
                return command;
            var command = this.command;
            var promptIndex = this.promptIndex;
            for (int index : promptIndex) {
                if (index >= promptAnswers.size() || index < 0) {
                    onOutOfBounds.accept(String.valueOf(index));
                    continue;
                }
                command = command.replaceFirst("p:" + index, promptAnswers.get(index));
            }
            return command;
        }
    }

}

package che.glucosemonitorbe.assistant.chat;

import che.glucosemonitorbe.sick.SickModeService;
import che.glucosemonitorbe.sick.SickModeStatusDTO;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * "I'm sick" / "I'm better" from chat: proposes starting or ending sick mode as a {@code sickMode} card.
 * It only reads the status; the mode changes when the user taps the card, which calls the sick-mode REST
 * resource. Nothing here calls a {@link SickModeService} command.
 */
@Component
@RequiredArgsConstructor
public class SickModeFlow {

    public static final String INTENT_SICK_MODE = "sick_mode";
    public static final String INTENT_SICK_MODE_PROPOSAL = "sick_mode_proposal";

    private final ChatHistoryService history;
    private final SickModeService sickModeService;

    public void propose(UUID userId, ChatMessage userMessage, IntentClassifier.SickCommand cmd, String language,
                        Consumer<AssistantEvent> sink) {
        long start = System.currentTimeMillis();
        boolean ru = "ru".equals(language);
        boolean starting = cmd.action() == IntentClassifier.SickCommand.Action.START;
        userMessage.setIntent(INTENT_SICK_MODE);
        history.save(userMessage);

        SickModeStatusDTO current = sickModeService.status(userId);
        String action = starting ? "START" : "END";
        String text = text(starting, current.active(), ru);
        ObjectNode payload = history.newPayload()
                .put("userMessageId", userMessage.getId().toString())
                .put("language", language)
                .put("action", action);
        if (cmd.severity() != null) payload.put("severity", cmd.severity().name());
        ChatMessage reply = history.appendAssistant(userMessage, text, INTENT_SICK_MODE_PROPOSAL, payload, null,
                System.currentTimeMillis() - start);
        sink.accept(new AssistantEvent.SickMode(reply.getId(), action, cmd.severity(), current));
        sink.accept(new AssistantEvent.Done(userMessage.getId(), reply.getId(), INTENT_SICK_MODE_PROPOSAL));
    }

    private static String text(boolean starting, boolean active, boolean ru) {
        if (starting) {
            if (active) {
                return ru ? "Режим болезни уже включён. Нажмите на карточку, чтобы изменить тяжесть."
                        : "Sick mode is already on. Tap the card to change the severity.";
            }
            return ru ? "Выздоравливайте! Включить режим болезни? Нажмите на карточку."
                    : "Sorry you are unwell. Turn on sick mode? Tap the card.";
        }
        if (!active) {
            return ru ? "Режим болезни сейчас не включён."
                    : "Sick mode is not on right now.";
        }
        return ru ? "Рад, что вам лучше! Выключить режим болезни? Нажмите на карточку."
                : "Glad you are better! Turn off sick mode? Tap the card.";
    }
}

package che.glucosemonitorbe.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import che.glucosemonitorbe.sick.SickModeService;
import che.glucosemonitorbe.sick.SickModeStatusDTO;
import che.glucosemonitorbe.sick.SickSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SickModeFlowTest {

    private final UUID user = UUID.randomUUID();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final SickModeService sickModeService = mock(SickModeService.class);
    private final List<AssistantEvent> events = new ArrayList<>();
    private SickModeFlow flow;

    @BeforeEach
    void setUp() {
        when(messages.save(any())).thenAnswer(i -> {
            ChatMessage m = i.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        flow = new SickModeFlow(new ChatHistoryService(messages, objectMapper), sickModeService);
    }

    private ChatMessage userMessage() {
        ChatMessage m = new ChatMessage();
        m.setId(UUID.randomUUID());
        m.setUserId(user);
        m.setRole(ChatMessage.Role.USER);
        m.setText("I'm sick");
        m.setCreatedAt(Instant.now());
        return m;
    }

    private void propose(IntentClassifier.SickCommand.Action action, SickSeverity severity, String lang) {
        flow.propose(user, userMessage(), new IntentClassifier.SickCommand(action, severity), lang, events::add);
    }

    @Test
    void theFlowProposesAndNeverSwitchesTheMode() {
        SickModeStatusDTO inactive = SickModeStatusDTO.inactive();
        when(sickModeService.status(user)).thenReturn(inactive);

        propose(IntentClassifier.SickCommand.Action.START, SickSeverity.MODERATE, "en");

        verify(sickModeService).status(user);
        verifyNoMoreInteractions(sickModeService);
        assertEquals(List.of(AssistantEvent.SickMode.class, AssistantEvent.Done.class),
                events.stream().map(Object::getClass).toList());
        AssistantEvent.SickMode card = (AssistantEvent.SickMode) events.get(0);
        AssistantEvent.Done done = (AssistantEvent.Done) events.get(1);
        assertEquals("START", card.action());
        assertEquals(SickSeverity.MODERATE, card.severity());
        assertSame(inactive, card.current());
        assertEquals(done.assistantMessageId(), card.messageId());
        assertEquals(SickModeFlow.INTENT_SICK_MODE_PROPOSAL, done.intent());
    }

    @Test
    void sayingBetterWhileNotSickStillEmitsTheCardAndSaysSo() {
        when(sickModeService.status(user)).thenReturn(SickModeStatusDTO.inactive());
        propose(IntentClassifier.SickCommand.Action.END, null, "en");
        assertEquals("END", ((AssistantEvent.SickMode) events.get(0)).action());

        ArgumentCaptorHolder.assertReplyContains(messages, "not on");
    }

    @Test
    void russianReplyIsUsedForRussian() {
        when(sickModeService.status(user)).thenReturn(SickModeStatusDTO.inactive());
        propose(IntentClassifier.SickCommand.Action.START, null, "ru");
        ArgumentCaptorHolder.assertReplyContains(messages, "режим болезни");
    }

    @Test
    void sickModeEventSerialisesWithItsTypeName() throws Exception {
        String wire = objectMapper.writeValueAsString(new AssistantEvent.SickMode(UUID.randomUUID(), "START",
                SickSeverity.MILD, SickModeStatusDTO.inactive()));
        assertTrue(wire.startsWith("{\"type\":\"sickMode\""), wire);
        assertTrue(wire.contains("\"action\":\"START\""), wire);
        assertTrue(wire.contains("\"severity\":\"MILD\""), wire);
    }

    /** Reads back the saved assistant reply text. */
    private static final class ArgumentCaptorHolder {
        static void assertReplyContains(ChatMessageRepository repo, String fragment) {
            var captor = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
            verify(repo, atLeastOnce()).save(captor.capture());
            String reply = captor.getAllValues().stream()
                    .filter(m -> m.getRole() == ChatMessage.Role.ASSISTANT).map(ChatMessage::getText)
                    .reduce((a, b) -> b).orElseThrow();
            assertTrue(reply.toLowerCase().contains(fragment.toLowerCase()), reply);
        }
    }
}
